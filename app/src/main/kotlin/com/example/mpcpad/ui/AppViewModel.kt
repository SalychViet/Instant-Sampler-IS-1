package com.example.mpcpad.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mpcpad.audio.AudioEngine
import com.example.mpcpad.audio.DefaultKit
import com.example.mpcpad.audio.PadParam
import com.example.mpcpad.audio.PlayMode
import com.example.mpcpad.capture.CaptureSource
import com.example.mpcpad.sample.PadLibrary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** PAD — пады играют звуки; SEQ — каждый пад = паттерн (а в режиме STEP — шаг 1–16 выбранного звука). */
enum class Mode { PAD, SEQ }

/** Параметры пада, отражаемые в UI (источник правды о звуке — нативный движок). */
data class PadUi(
    val volume: Float = 1f,
    val pan: Float = 0f,
    val pitch: Float = 0f,
    val start: Float = 0f,
    val end: Float = 1f,
    val mode: Int = PlayMode.ONE_SHOT,
    val reverse: Boolean = false,
    val choke: Int = 0,
    val releaseMs: Float = 15f,
    val attackMs: Float = 0f,
    /** LOOP: длина петли по темпу (индекс в [LOOP_DIVS]), -1 — свободная (END вручную). */
    val loopDiv: Int = -1,
)

/** Длины петли в долях (четвертях): 1/32 … 1/4. T — триоль. */
val LOOP_DIVS = listOf(
    "1/32" to 0.125f, "1/16T" to 1f / 6f, "1/16" to 0.25f, "1/8T" to 1f / 3f,
    "1/8" to 0.5f, "1/4T" to 2f / 3f, "1/4" to 1f,
)

/** «A1»…«D16» по глобальному номеру пада 0..63. */
fun padName(global: Int): String = "${'A' + global / 16}${global % 16 + 1}"

/** «A01»…«D16» — имя паттерна. */
fun patternName(global: Int): String = "${'A' + global / 16}${"%02d".format(global % 16 + 1)}"

const val STEPS_PER_BAR = 16
const val STEP_TICKS = 24            // 1/16 при 96 PPQ
const val MAX_UI_BARS = 4

/**
 * Общее состояние экрана устройства. Живёт, пока жива активити (переживает смену режимов).
 * Быстро меняющиеся значения (позиция секвенсора) читаются только в фазе рисования — см. [seqPos].
 */
class AppViewModel : ViewModel() {
    // ---- режимы ----
    var mode by mutableStateOf(Mode.PAD)
    var stepView by mutableStateOf(false)          // только в SEQ
    var stepBar by mutableIntStateOf(0)            // страница шагов: такт 0..3
    var editorOpen by mutableStateOf(false)
    var setupOpen by mutableStateOf(false)
    var bank by mutableIntStateOf(0)
    /** Выбранный «звук» (пад 0..63): его параметры правят ручки, его шаги видны в STEP. */
    var selected by mutableIntStateOf(0)
    /** Следующее касание пада выбирает звук (в STEP, где пады — шаги). */
    var soundPick by mutableStateOf(false)
    /** Следующее касание пада стирает: в PAD — ноты этого звука, в SEQ — этот паттерн. */
    var eraseLatch by mutableStateOf(false)
    var sampleSource by mutableStateOf(CaptureSource.MIC)

    /** CHOKE удерживается: касание пада включает/выключает у него глушение (группа 1). */
    var chokeHold by mutableStateOf(false)
    /** SEQ: паттерн, скопированный долгим нажатием (-1 — нет); касание другого пада вставляет копию. */
    var copySource by mutableIntStateOf(-1)
    /** STEP: удерживаемый шаг (-1 — нет) и его параметры с учётом P-LOCK — их показывают ручки. */
    var heldStep by mutableIntStateOf(-1)
    var heldStepUi by mutableStateOf<PadUi?>(null)
    var heldStepEdited = false

    val pads = mutableStateListOf<PadUi>().apply {
        repeat(64) { add(PadUi(mode = DefaultKit.initialMode(it), choke = if (it in DefaultKit.CHOKE_PADS) 1 else 0)) }
    }

    // ---- состояние секвенсора (опрашивается покадрово) ----
    var seqPos by mutableIntStateOf(0)                 // ЧИТАТЬ ТОЛЬКО В DRAW / derivedStateOf
    var seqPlaying by mutableStateOf(false)
    var seqRecording by mutableStateOf(false)
    var seqCountIn by mutableStateOf(false)
    var seqPattern by mutableIntStateOf(0)
    var seqQueued by mutableIntStateOf(-1)
    var seqLinkWaiting by mutableStateOf(false)
    var linkPeers by mutableIntStateOf(0)
    var patternBars by mutableIntStateOf(1)
    /** Число нот в каждом паттерне (для подсветки непустых). */
    var patternCounts by mutableStateOf(IntArray(64))
    /** Биты шагов текущего паттерна для выбранного звука: бит i = шаг i (тик i*24). До 4 тактов = 64 бита. */
    var stepMask by mutableLongStateOf(0L)

    // ---- «табло»: временные показания ручек и сообщения ----
    var readout by mutableStateOf<String?>(null)
    private var readoutJob: Job? = null

    fun flash(text: String, ms: Long = 1400) {
        readout = text
        readoutJob?.cancel()
        readoutJob = viewModelScope.launch {
            delay(ms)
            readout = null
        }
    }

    /** Длинное сообщение поверх экрана (ошибки записи/импорта). */
    var toast by mutableStateOf<String?>(null)
    private var toastJob: Job? = null

    fun showToast(text: String, ms: Long = 3500) {
        toast = text
        toastJob?.cancel()
        toastJob = viewModelScope.launch {
            delay(ms)
            toast = null
        }
    }

    // ---- tap tempo ----
    private val taps = ArrayDeque<Long>()
    /** Возвращает BPM, если набралось ≥ 2 касаний подряд, иначе null. */
    fun tapTempo(nowMs: Long): Float? {
        if (taps.isNotEmpty() && nowMs - taps.last() > 2000) taps.clear()
        taps.addLast(nowMs)
        while (taps.size > 5) taps.removeFirst()
        if (taps.size < 2) return null
        val avg = (taps.last() - taps.first()).toFloat() / (taps.size - 1)
        return (60_000f / avg).coerceIn(30f, 300f)
    }

    fun update(pad: Int, f: (PadUi) -> PadUi) {
        pads[pad] = f(pads[pad])
    }

    /** После назначения нового семпла границы воспроизведения снова на весь семпл. */
    fun resetRegion(pad: Int) {
        AudioEngine.setParam(pad, PadParam.START, 0f)
        AudioEngine.setParam(pad, PadParam.END, 1f)
        update(pad) { it.copy(start = 0f, end = 1f) }
    }

    /**
     * T-HEAD / T-TAIL для уже загруженных семплов: без порчи файла сдвигают START/END к началу/концу звука.
     * Трогаем только пады, где граница не менялась вручную.
     */
    fun applyTrim(head: Boolean, on: Boolean) {
        for (p in 0 until 64) {
            val info = PadLibrary.infos[p] ?: continue
            val ui = pads[p]
            if (head) {
                val target = info.soundStart.coerceIn(0f, ui.end - 0.01f)
                if (on && ui.start == 0f && target > 0f) setParam(p, PadParam.START, target) { it.copy(start = target) }
                if (!on && ui.start != 0f && ui.start == target) setParam(p, PadParam.START, 0f) { it.copy(start = 0f) }
            } else {
                val target = info.soundEnd.coerceIn(ui.start + 0.01f, 1f)
                if (on && ui.end == 1f && target < 1f) setParam(p, PadParam.END, target) { it.copy(end = target) }
                if (!on && ui.end != 1f && ui.end == target) setParam(p, PadParam.END, 1f) { it.copy(end = 1f) }
            }
        }
    }

    /** ONE SHOT → GATE → LOOP по кругу для пада. */
    fun cyclePlayMode(pad: Int): Int {
        val next = (pads[pad].mode + 1) % 3
        setParam(pad, PadParam.MODE, next.toFloat()) { it.copy(mode = next) }
        if (next != PlayMode.LOOP && pads[pad].loopDiv >= 0) {
            // уходим из LOOP — петля по темпу больше не нужна, END снова весь семпл
            setParam(pad, PadParam.END, 1f) { it.copy(end = 1f, loopDiv = -1) }
        }
        return next
    }

    /** Петля по темпу: END = START + длина ноты при текущем BPM. div = -1 — свободная петля. */
    fun setLoopDiv(pad: Int, div: Int, bpm: Float) {
        update(pad) { it.copy(loopDiv = div) }
        applyLoopDiv(pad, bpm)
    }

    fun applyLoopDiv(pad: Int, bpm: Float) {
        val ui = pads[pad]
        val info = PadLibrary.infos[pad] ?: return
        if (ui.loopDiv < 0 || info.durationMs <= 0) return
        val beats = LOOP_DIVS[ui.loopDiv.coerceIn(0, LOOP_DIVS.size - 1)].second
        val ms = beats * 60_000f / bpm
        val end = (ui.start + ms / info.durationMs).coerceIn(ui.start + 0.001f, 1f)
        setParam(pad, PadParam.END, end) { it.copy(end = end) }
    }

    /** Темп изменился — подогнать все петли, привязанные к темпу. */
    fun resyncLoops(bpm: Float) {
        for (p in 0 until 64) if (pads[p].mode == PlayMode.LOOP && pads[p].loopDiv >= 0) applyLoopDiv(p, bpm)
    }

    fun setParam(pad: Int, id: Int, value: Float, ui: (PadUi) -> PadUi) {
        update(pad, ui)
        AudioEngine.setParam(pad, id, value)
        // Сдвинули START у петли по темпу — END едет следом, длина петли сохраняется.
        if (id == PadParam.START && pads[pad].mode == PlayMode.LOOP && pads[pad].loopDiv >= 0) {
            applyLoopDiv(pad, com.example.mpcpad.Settings.bpm)
        }
    }
}
