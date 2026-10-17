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

    val pads = mutableStateListOf<PadUi>().apply {
        repeat(64) { add(PadUi(mode = DefaultKit.initialMode(it))) }
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

    fun setParam(pad: Int, id: Int, value: Float, ui: (PadUi) -> PadUi) {
        update(pad, ui)
        AudioEngine.setParam(pad, id, value)
    }
}
