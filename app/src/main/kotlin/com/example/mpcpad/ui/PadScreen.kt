// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad.ui

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import android.annotation.SuppressLint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mpcpad.R
import com.example.mpcpad.Settings
import com.example.mpcpad.audio.AudioEngine
import com.example.mpcpad.audio.Kaoss
import com.example.mpcpad.audio.LiveRecorder
import com.example.mpcpad.audio.DefaultKit
import com.example.mpcpad.audio.FxAutomation
import com.example.mpcpad.audio.SampleOps
import com.example.mpcpad.audio.PadParam
import com.example.mpcpad.audio.PlayMode
import com.example.mpcpad.audio.StepLocks
import com.example.mpcpad.capture.CaptureController
import com.example.mpcpad.capture.CaptureSource
import com.example.mpcpad.capture.PadCapture
import com.example.mpcpad.sample.PadLibrary
import com.example.mpcpad.sample.PadSampler
import com.example.mpcpad.sample.PreLowCut
import com.example.mpcpad.sample.PadStore
import com.example.mpcpad.sample.SampleClip
import com.example.mpcpad.sample.SampleViewModel
import com.example.mpcpad.sample.SamplerEvent
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun norm(v: Float, lo: Float, hi: Float) = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
private fun denorm(n: Float, lo: Float, hi: Float) = lo + n * (hi - lo)

private val CHROME_BG = Color(0xFF0E0F11)

/** Корневой экран: «железо» IS-1 — LCD, ручки, кнопки секвенсора, пады, фейдеры и логотипы. */
@Composable
fun MpcRoot(actions: SampleActions) {
    val app: AppViewModel = viewModel()
    val sampleVm: SampleViewModel = viewModel()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    /** Таймеры долгого нажатия (SEQ: копия паттерна). */
    val longPress = remember { arrayOfNulls<Job>(64) }
    /** STEP: шаг был поставлен этим же нажатием (тогда отпускание его не снимает). */
    val stepAdded = remember { BooleanArray(64) }

    val pressedMask = remember { mutableLongStateOf(0L) }
    val engineMask = remember { mutableLongStateOf(0L) }
    val peak = remember { mutableFloatStateOf(0f) }
    val recLevel = remember { mutableFloatStateOf(0f) }
    val holdPad = remember { mutableIntStateOf(-1) }
    val loopFrame = remember { mutableLongStateOf(0L) }
    /** Как был нажат каждый пад: 0 — никак, 1 — нота, 2 — запись семпла, 3 — удержание шага. */
    val route = remember { IntArray(64) }
    /** ISL-1: удержание пада-петли уже открыло её части (тогда отпускание не включает/выключает петлю). */
    val partsHeld = remember { BooleanArray(64) }
    val cap by PadCapture.state.collectAsState()
    val lifeState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val foreground = lifeState.isAtLeast(Lifecycle.State.STARTED)
    val inputRetries = remember { IntArray(1) }

    fun armSample() {
        if (app.srcSelf) app.selfArmed = true else actions.armPadCapture()
    }

    fun sampleOff() {
        app.selfArmed = false
        if (PadCapture.isBusy) actions.disarmPadCapture()
    }

    /** Смена источника: включённый SAMPLE открывается заново уже с нового входа (лупер — сам, см. ниже). */
    fun changeSource(change: () -> Unit) {
        val sampling = !app.looperMode && (PadCapture.isBusy || app.selfArmed)
        change()
        if (!sampling) return
        sampleOff()
        scope.launch {
            // ждём, пока старый вход закроется (до 2 с)
            var tries = 0
            while (PadCapture.isBusy && tries++ < 40) delay(50)
            if (!app.looperMode) armSample()
        }
    }

    // Лупер всегда держит вход открытым: вход мог закрыться после сворачивания, смены источника или
    // быстрого выкл/вкл, пока прошлая запись ещё завершалась. Ошибку не повторяем без конца.
    LaunchedEffect(app.looperMode, app.srcSelf, app.sampleSource, cap.armed, cap.arming, foreground) {
        if (cap.armed) inputRetries[0] = 0
        if (!app.looperMode || !foreground || app.srcSelf) return@LaunchedEffect
        if (cap.armed && cap.source != app.sampleSource) {
            actions.disarmPadCapture()
        } else if (!cap.armed && !cap.arming && !PadCapture.isBusy && inputRetries[0] < 2) {
            inputRetries[0]++
            actions.armPadCapture()
        }
    }

    var eraseHold: Job? = null
    var eraseHeld = false
    var captureHold: Job? = null
    var captureHeld = false

    // ---- RESAMPLE ----
    var resampleHold: Job? = null
    var resampleHeld = false
    fun finishResample() {
        val n = AudioEngine.resampleStop()
        app.resampling = false
        val data = AudioEngine.resampleRead()
        if (n > 0 && data.isNotEmpty()) {
            PreLowCut.apply(data, AudioEngine.sampleRate(), 2)
            app.resampleClip = SampleClip(data, 2, AudioEngine.sampleRate())
            app.flash(String.format("RESAMPLE %.1f S · HOLD = COPY", n.toFloat() / AudioEngine.sampleRate()), 3000)
        } else {
            app.flash("RESAMPLE: EMPTY", 1100)
        }
    }

    // ---- опрос движка раз в кадр (значения читаются только при рисовании) ----
    LaunchedEffect(Unit) {
        var p = 0f
        var r = 0f
        var lastSlow = 0L
        while (true) {
            withFrameNanos { now ->
                engineMask.longValue = AudioEngine.activeMask()
                p = max(AudioEngine.masterPeak(), p * 0.92f)
                peak.floatValue = p
                r = max(max(CaptureController.level, PadCapture.level), r * 0.85f)
                recLevel.floatValue = r
                holdPad.intValue = PadCapture.holdingPad
                // ISL-1: круги на падах-петлях крутятся — перерисовываем, пока что-то играет или пишется.
                if (app.loopsPlaying.isNotEmpty() || app.loopsWaiting.isNotEmpty() || app.loopRecPad >= 0) {
                    loopFrame.longValue = now
                }

                val st = AudioEngine.seqState()
                app.seqPos = st.position
                app.seqPlaying = st.playing
                app.seqRecording = st.recording
                app.seqCountIn = st.countIn
                app.seqPattern = st.pattern
                app.seqQueued = st.queued
                app.checkSeqDuck()
                app.seqLinkWaiting = st.linkWaiting
                app.songIndex = st.songIndex
                if (app.resampling && !AudioEngine.resampling()) finishResample()   // 30 с набралось
                if (app.resampling && AudioEngine.resampleFrames() >= 30 * AudioEngine.sampleRate()) finishResample()

                if (now - lastSlow > 120_000_000L) {
                    lastSlow = now
                    if (Settings.linkEnabled) {
                        app.linkPeers = AudioEngine.linkPeers()
                        // Темп задаёт сессия Link: показываем его на LCD и фейдере TEMPO.
                        val t = AudioEngine.linkTempo()
                        if (kotlin.math.abs(t - Settings.bpm) > 0.05f) Settings.bpm = t
                    } else if (app.linkPeers != 0) {
                        app.linkPeers = 0
                    }
                    val counts = AudioEngine.seqPatternCounts()
                    if (!counts.contentEquals(app.patternCounts)) app.patternCounts = counts
                    app.patternBars = AudioEngine.seqPatternBars(st.pattern)
                    app.updateLcdLoopBars()
                    if (!app.songRec) {
                        val s = AudioEngine.songList()
                        if (s != app.song) app.song = s
                        val bars = s.map { AudioEngine.seqPatternBars(it) }
                        if (bars != app.songBars) app.songBars = bars
                    }
                    val notes = AudioEngine.seqReadPattern(st.pattern)
                    if (notes != null) {
                        var m = 0L
                        for (n in notes) if (n.pad == app.selected) {
                            val step = n.tick / STEP_TICKS
                            if (step in 0..63) m = m or (1L shl step)
                        }
                        app.stepMask = m
                    }
                }
            }
        }
    }

    // ---- сообщения от записи и импорта ----
    LaunchedEffect(Unit) {
        PadSampler.events.collect { e ->
            when (e) {
                is SamplerEvent.Assigned -> {
                    app.resetPadForNewSound(e.pad)
                    app.resetRegion(e.pad)
                    if (Settings.trimStart) app.trimPad(e.pad, head = true, on = true)
                    if (Settings.trimEnd) app.trimPad(e.pad, head = false, on = true)
                    if (app.mode == Mode.PAD) app.selected = e.pad
                    val trim = e.headTrimMs + e.tailTrimMs
                    app.flash(
                        "${e.name} → ${padName(e.pad)}" + (if (trim > 0) "  TRIM ${trim}MS" else ""),
                        2500,
                    )
                    if (e.capped) app.showToast("Запись/файл длиннее лимита — обрезано")
                    // Семпл на паде готов — SAMPLE выключается (если кнопку не держат для следующих падов).
                    if (e.name.startsWith("REC_") && !app.looperMode && !app.sampleHeld &&
                        PadCapture.holdingPad < 0 && app.selfHoldPad < 0
                    ) sampleOff()
                }
                is SamplerEvent.Rejected -> {
                    if (e.pad == app.loopRecPad) {
                        app.loopRecPad = -1
                        app.loopClosing = false
                    }
                    app.showToast(e.reason, 4500)
                }
                is SamplerEvent.LoopRecorded -> {
                    if (e.capped) app.showToast("LOOP: MAX 30 S", 2500)
                    // Запись оборвалась на 30 с — конец петли там, где кончился звук.
                    val end = if (e.capped) {
                        app.loopRecStartNanos + (e.clip.frames - e.prerollFrames) * 1_000_000_000L / e.clip.sampleRate
                    } else app.loopRecEndNanos
                    app.finishLoop(e.pad, e.clip, e.prerollFrames, app.loopRecStartNanos, end)
                }
            }
        }
    }
    LaunchedEffect(cap.message) {
        cap.message?.let {
            app.showToast(it, 4500)
            PadCapture.clearMessage()
        }
    }

    fun flash(t: String) = app.flash(t, 1100)

    /** Конец записи цепочки паттернов: пустая цепочка выключает режим SONG. */
    fun finishSongRec() {
        if (!app.songRec) return
        app.songRec = false
        val draft = app.songDraft
        AudioEngine.songEnable(draft.isNotEmpty())
        app.song = draft
        app.flash(if (draft.isEmpty()) "SONG OFF" else "SONG: ${draft.size} PATTERNS · PLAY", 2500)
    }

    // ---- KAOSS ----
    /** Что сейчас управляет эффектом (для экрана): X, Y, касание (1/0). */
    val kShown = remember { mutableStateListOf(0.5f, 0.5f, 0f) }
    val kaossLast = remember { IntArray(Kaoss.BANKS.size) }
    val motionPressAt = remember { LongArray(4) }
    val motionHold = remember { arrayOfNulls<Job>(4) }

    /** A–D в KAOSS: удержание — запись движения пальца, короткое нажатие — включить/выключить петлю. */
    fun motionButton(b: Int, down: Boolean) {
        val label = 'A' + b
        if (down) {
            motionPressAt[b] = System.nanoTime()
            motionHold[b] = scope.launch {
                delay(350)
                app.motionPlaying = -1
                app.recordBuf.clear()
                app.motionStartNanos = System.nanoTime()
                app.motionRecording = b
                app.flash("REC MOTION $label: MOVE ON SCREEN", 2000)
            }
            return
        }
        if (motionPressAt[b] == 0L) return
        motionPressAt[b] = 0L
        motionHold[b]?.cancel()
        if (app.motionRecording == b) {
            val len = (System.nanoTime() - app.motionStartNanos) / 1_000_000
            val pts = ArrayList(app.recordBuf)
            app.motionRecording = -1
            if (len > 200 && pts.any { it.touch }) {
                app.motions[b] = Motion(pts, len)
                app.motionStartNanos = System.nanoTime()
                app.motionPlaying = b
                flash("MOTION $label ▶")
            } else {
                flash("MOTION $label: NOTHING RECORDED")
            }
        } else {
            when {
                app.motions[b] == null -> app.flash("MOTION $label EMPTY: HOLD $label + MOVE", 2000)
                app.motionPlaying == b -> { app.motionPlaying = -1; flash("MOTION $label ■") }
                else -> {
                    app.motionStartNanos = System.nanoTime()
                    app.motionPlaying = b
                    flash("MOTION $label ▶")
                }
            }
        }
    }

    /** Закончить запись автоматизации: точки по тикам Song, отсортированные. */
    fun finishFxRec(buf: List<IntArray>, fx: List<FloatArray>, len: Int) {
        app.fxRecording = false
        if (buf.isEmpty()) {
            flash("FX REC: NOTHING")
            return
        }
        val order = buf.indices.sortedBy { buf[it][0] }
        app.fxAuto = FxAutomation(
            ticks = IntArray(order.size) { buf[order[it]][0] },
            program = IntArray(order.size) { buf[order[it]][1] },
            x = FloatArray(order.size) { fx[order[it]][0] },
            y = FloatArray(order.size) { fx[order[it]][1] },
            touch = BooleanArray(order.size) { buf[order[it]][2] == 1 },
            lengthTicks = len,
        )
        app.flash("FX AUTOMATION ▶ (ERASE IN KAOSS = DELETE)", 2500)
    }

    // KAOSS: каждый кадр — палец, запись эффектов, движение MOTION → эффект.
    // Работает и вне экрана KAOSS: записанная автоматизация играет вместе с секвенсором.
    LaunchedEffect(Unit) {
        var sentX = -1f
        var sentY = -1f
        var sentT = false
        var sentProg = -1
        var sentMix = -1f
        val recTicks = ArrayList<IntArray>()     // tick, program, touch
        val recXY = ArrayList<FloatArray>()
        var recLast = -1
        var recElapsed = 0
        var wasRecording = false
        while (true) {
            withFrameNanos { now ->
                var prog = app.kaossProgram
                var x = app.kx
                var y = app.ky
                var t = app.kaossMode && app.kTouch
                if (app.kaossMode) {
                    val rec = app.motionRecording
                    val play = app.motionPlaying
                    if (rec >= 0) {
                        app.recordBuf.add(MotionPoint((now - app.motionStartNanos) / 1_000_000, x, y, t))
                    } else if (!t && play >= 0) {
                        val m = app.motions[play]
                        if (m != null && m.lengthMs > 0) {
                            val ms = ((now - app.motionStartNanos) / 1_000_000).mod(m.lengthMs)
                            val pts = m.points
                            var lo = 0
                            var hi = pts.size - 1
                            while (lo < hi) {
                                val mid = (lo + hi + 1) / 2
                                if (pts[mid].t <= ms) lo = mid else hi = mid - 1
                            }
                            val pt = pts[lo]
                            x = pt.x; y = pt.y; t = pt.touch
                        }
                    }
                }

                // ---- запись автоматизации: ровно одна длина Song ----
                if (app.fxRecording && !wasRecording) {
                    recTicks.clear(); recXY.clear(); recLast = -1; recElapsed = 0
                }
                wasRecording = app.fxRecording
                if (app.fxRecording && app.seqPlaying) {
                    val len = app.songLengthTicks()
                    val tick = app.songTick().mod(len)
                    if (recLast >= 0) recElapsed += (tick - recLast).mod(len)
                    recLast = tick
                    recTicks.add(intArrayOf(tick, prog, if (t) 1 else 0))
                    recXY.add(floatArrayOf(x, y))
                    app.fxRecProgress = (recElapsed.toFloat() / len).coerceIn(0f, 1f)
                    if (recElapsed >= len) finishFxRec(ArrayList(recTicks), ArrayList(recXY), len)
                } else if (!t && app.seqPlaying && !app.fxRecording) {
                    // ---- воспроизведение автоматизации (палец её перекрывает) ----
                    val a = app.fxAuto
                    if (a != null) {
                        val i = a.indexAt(app.songTick())
                        if (i >= 0) {
                            prog = a.program[i]; x = a.x[i]; y = a.y[i]; t = a.touch[i]
                        }
                    }
                }

                kShown[0] = x; kShown[1] = y; kShown[2] = if (t) 1f else 0f
                // Без касания автоматизация не звучит — на экране выбранная программа, а не записанная.
                app.shownProgram = if (t) prog else app.kaossProgram
                val mix = app.kaossMix.getOrElse(prog) { 1f }
                if (x != sentX || y != sentY || t != sentT || prog != sentProg || mix != sentMix) {
                    AudioEngine.kaoss(prog, x, y, t, mix)
                    sentX = x; sentY = y; sentT = t; sentProg = prog; sentMix = mix
                }
            }
        }
    }

    // Петли, привязанные к темпу, подстраиваются под новый BPM.
    LaunchedEffect(Settings.bpm) { app.resyncLoops(Settings.bpm) }

    // ---- обработка падов ----
    /** Параметры шага с учётом P-LOCK: значения пада, поверх — закреплённые за шагом. */
    fun stepUi(step: Int): PadUi {
        var u = app.pads[app.selected]
        val from = step * STEP_TICKS
        val note = AudioEngine.seqReadPattern(app.seqPattern)
            ?.firstOrNull { it.pad == app.selected && it.tick >= from && it.tick < from + STEP_TICKS }
        if (note != null) {
            for ((id, v) in StepLocks.decode(note.locks)) {
                u = when (id) {
                    PadParam.VOLUME -> u.copy(volume = v)
                    PadParam.PAN -> u.copy(pan = v)
                    PadParam.PITCH -> u.copy(pitch = v)
                    PadParam.START -> u.copy(start = v)
                    PadParam.END -> u.copy(end = v)
                    PadParam.ATTACK_MS -> u.copy(attackMs = v)
                    PadParam.RELEASE_MS -> u.copy(releaseMs = v)
                    else -> u
                }
            }
        }
        return u
    }

    /** Семпл пада как SampleClip: с диска или (для стартового кита) заново синтезированный. */
    fun padClip(pad: Int): SampleClip? {
        if (PadLibrary.infos[pad] == null) return null
        return PadStore.readWav(context, pad)?.let { SampleOps.parseWav(it) } ?: if (pad < 16) DefaultKit.clipFor(pad) else null
    }

    /** UNDO для изменения семпла на паде: вернуть прежний звук и параметры (или снова очистить). */
    fun undoPadSample(pad: Int, label: String) {
        val had = PadLibrary.infos[pad] != null
        val clip = if (had) padClip(pad) else null
        val name = PadLibrary.infos[pad]?.name ?: "SAMPLE"
        val prev = app.pads[pad]
        app.pushUndo(label) {
            if (clip != null) {
                PadSampler.assignToPad(pad, clip, name)
            } else if (!had) {
                PadStore.delete(context, pad)
                app.forgetLoop(pad)
            }
            app.applyPadUi(pad, prev)
        }
    }

    /** Вставка из буфера: копия пада — только на пустой пад, ресемпл — на любой. */
    fun tryPaste(global: Int): Boolean {
        val cb = app.clipboard ?: return false
        if (!app.pasteArmed) return false
        val free = PadLibrary.infos[global] == null
        if (global == cb.sourcePad || (!cb.fromResample && !free)) {
            app.pasteArmed = false
            flash(if (global == cb.sourcePad) "PASTE CANCELLED" else "PAD BUSY: PASTE CANCELLED")
            return global == cb.sourcePad
        }
        undoPadSample(global, "PASTE ${padName(global)}")
        if (cb.fromResample) {
            PadSampler.importClip(global, cb.clip, cb.name, false)   // с автообрезкой тишины
        } else {
            PadSampler.assignToPad(global, cb.clip.copy(), cb.name)
            // Копия самостоятельна: и в группу CHOKE оригинала не входит.
            cb.ui?.let { app.applyPadUi(global, it.copy(choke = 0)) }
            app.adoptParts(global, cb)
        }
        app.pasteArmed = false
        app.selected = global
        app.flash("PASTED → ${padName(global)}", 1500)
        return true
    }

    /** Пад с петлёй: короткое касание — вкл/выкл (на отпускании), удержание — части петли, она играет дальше. */
    fun loopPadDown(global: Int) {
        route[global] = 5
        partsHeld[global] = false
        longPress[global] = scope.launch {
            delay(1500)
            partsHeld[global] = true
            app.openLoopParts(global)
            app.flash("LOOP ${padName(global)} · TAP PARTS TO MUTE", 2500)
        }
    }

    /**
     * ISL-1 LOOPER: касание пустого пада — запись петли, второе касание — конец записи (петля сразу играет).
     * Пад, на котором петля уже есть, касанием включается/выключается — случайно не перезапишется
     * (перезаписать: сначала ERASE).
     */
    val resampleLoopStart = remember { arrayOfNulls<Job>(1) }

    fun looperTap(global: Int) {
        val rec = app.loopRecPad
        val hasLoop = app.pads[global].mode == PlayMode.LOOP && PadLibrary.infos[global] != null
        val fromMix = app.srcSelf
        when {
            rec < 0 && !hasLoop && fromMix -> {
                // Ресемплинг в петлю: с первой доли следующего такта (если сетка идёт), с выхода приложения.
                app.loopStop(global)
                val now = System.nanoTime()
                app.adoptSequencerGrid()
                val start = if (app.loopGridRunning) app.nextBar(now) else now
                app.loopRecPad = global
                app.loopRecFromEngine = true
                app.loopRecIsolated = app.looperIndividual
                app.loopRecStartNanos = start
                resampleLoopStart[0] = scope.launch {
                    delay(((start - now) / 1_000_000).coerceAtLeast(0))
                    AudioEngine.resampleStart(isolated = app.loopRecIsolated)
                }
                app.flash(
                    if (start > now) "RESAMPLE LOOP ${padName(global)} · STARTS ON THE BAR · TAP = CANCEL"
                    else "● RESAMPLE LOOP ${padName(global)} · TAP AGAIN TO CLOSE",
                    30_000,
                )
            }
            rec == global && app.loopRecFromEngine -> {
                if (app.loopClosing) return
                val now = System.nanoTime()
                if (now < app.loopRecStartNanos) {
                    // Запись ещё не началась — касание её отменяет, а не ждёт лишний такт.
                    resampleLoopStart[0]?.cancel()
                    app.loopRecPad = -1
                    app.loopRecFromEngine = false
                    flash("RESAMPLE LOOP CANCELLED")
                    return
                }
                // Закрываем сразу на пройденной черте; следующую ждём, только если до неё меньше четверти такта.
                val end = maxOf(app.loopRecEndFor(now, waitFrom = 0.75), app.loopRecStartNanos + 1)
                app.loopRecEndNanos = end
                app.loopClosing = true
                val pad = global
                scope.launch {
                    delay(((end - System.nanoTime()) / 1_000_000 + 10).coerceAtLeast(0))
                    AudioEngine.resampleStop()
                    val data = AudioEngine.resampleRead()
                    val isolated = app.loopRecIsolated
                    if (data.isEmpty() || (isolated && data.all { it == 0f })) {
                        app.loopRecPad = -1
                        app.loopClosing = false
                        app.loopRecFromEngine = false
                        flash(if (isolated) "iLOOPER: NOTHING PLAYED" else "RESAMPLE: EMPTY")
                    } else {
                        PreLowCut.apply(data, AudioEngine.sampleRate(), 2)
                        app.finishLoop(
                            pad, SampleClip(data, 2, AudioEngine.sampleRate()), 0, app.loopRecStartNanos, end,
                            fromEngine = true, isolated = isolated,
                        )
                    }
                }
                app.flash("● RESAMPLE LOOP ${padName(global)} · CLOSING ON THE BAR", 4000)
            }
            rec < 0 && hasLoop -> {
                loopPadDown(global)
            }
            !PadCapture.isArmed -> flash(if (PadCapture.isBusy) "LOOPER: PREPARING INPUT…" else "LOOPER: NO INPUT · PRESS LOOPER AGAIN")
            rec < 0 -> {
                app.loopStop(global)
                // Воспроизведение уже идёт — петля не задаёт темп, а встаёт в его такты.
                app.adoptSequencerGrid()
                PadSampler.loopPads.add(global)
                app.loopRecPad = global
                app.loopRecStartNanos = System.nanoTime()
                PadCapture.begin(global, loop = true)
                app.flash("● LOOP ${padName(global)} · TAP AGAIN TO CLOSE", 30_000)
            }
            rec == global -> {
                if (app.loopClosing) return
                val now = System.nanoTime()
                val end = app.loopRecEndFor(now)
                app.loopRecEndNanos = end
                // По сетке пишем ещё на задержку выхода: столько же звука отрежется с начала.
                val wait = end + (if (app.loopGridRunning) app.loopCompNanos() else 0L) - now
                if (wait > 0) {
                    // Нажали чуть раньше черты такта — пишем до неё, потом петля играет.
                    app.loopClosing = true
                    app.flash("● LOOP ${padName(global)} · CLOSING ON THE BAR", 4000)
                    scope.launch {
                        delay(wait / 1_000_000 + 20)   // +20 мс: последний чанк записи точно дошёл
                        PadCapture.end()
                    }
                } else {
                    PadCapture.end()
                    flash("LOOP ${padName(global)} …")
                }
            }
            else -> flash("RECORDING ${padName(rec)} · TAP IT TO CLOSE")
        }
    }

    /** Копия звука пада в буфер: следующее касание пустого пада вставит её. */
    suspend fun copyPad(global: Int) {
        val clip = withContext(Dispatchers.Default) { padClip(global) } ?: return
        app.clipboard = SampleClipboard(
            clip, PadLibrary.infos[global]?.name ?: "SAMPLE", app.pads[global], false, global,
            original = app.loopOriginal(global)?.copy(),
            parts = app.loopParts[global] ?: 0b1111,
            bounds = app.partBounds[global],
        )
        app.pasteArmed = true
        app.flash("COPY ${padName(global)} → TOUCH EMPTY PAD", 4000)
    }

    /** Удалить звук пада (one-shot или петлю) без подтверждения — при удерживаемом ERASE. */
    fun quickErase(global: Int): Boolean {
        if (PadLibrary.infos[global] == null || global == app.loopRecPad) return false
        app.loopStop(global)
        app.forgetLoop(global)
        undoPadSample(global, "DELETE ${padName(global)}")
        PadStore.delete(context, global)
        app.applyPadUi(global, PadUi())   // и в движке: иначе новый звук пада заиграет со старым PITCH
        eraseHeld = true   // отпускание ERASE после удаления не включает «нажми пад — удалится»
        return true
    }

    /** Палец проехал по падам: с MUTE — ряд или столбец, с удерживаемым ERASE — удаляется только пройденный пад. */
    fun padSlide(from: Int, to: Int) {
        if (app.mode == Mode.PAD && app.muteHolding) {
            // MUTE удерживается: ряд или столбец получает то же состояние, что и первый пад.
            val state = app.isMuted(from)
            val b = to / 16
            val row = (from % 16) / 4 == (to % 16) / 4
            val col = (from % 16) % 4 == (to % 16) % 4
            val list = when {
                row -> (0 until 4).map { b * 16 + (to % 16) / 4 * 4 + it }
                col -> (0 until 4).map { b * 16 + it * 4 + (to % 16) % 4 }
                else -> listOf(to)
            }
            for (p in list) if (PadLibrary.infos[p] != null) app.setMuted(p, state)
            flash((if (row) "ROW" else if (col) "COLUMN" else padName(to)) + if (state) " MUTED" else " UNMUTED")
            return
        }
        if (app.mode != Mode.PAD || !app.eraseHolding) return
        flash(if (quickErase(to)) "${padName(to)} DELETED" else "${padName(to)} EMPTY")
    }

    fun padDown(global: Int, vel: Float) {
        pressedMask.longValue = pressedMask.longValue or (1L shl global)
        route[global] = 0
        if (app.chokeHold) {
            // CHOKE удерживается: пад входит/выходит из группы глушения.
            // OFF → 1 → 2 → 3 → 4 → OFF: группы свои в каждом банке.
            val g = (app.pads[global].choke + 1) % 5
            val prev = app.pads[global]
            app.pushUndo("CHOKE ${padName(global)}") { app.applyPadUi(global, prev) }
            app.update(global) { it.copy(choke = g) }
            AudioEngine.setParam(global, PadParam.CHOKE, chokeEngineValue(global, g))
            flash("CHOKE ${padName(global)} " + if (g == 0) "OFF" else "GROUP $g")
            return
        }
        when {
            app.mode == Mode.PAD && (app.muteHolding || app.muteLatch) -> {
                route[global] = 0
                if (app.muteHolding) app.muteTook = true
                if (PadLibrary.infos[global] == null) {
                    flash("${padName(global)} EMPTY")
                } else {
                    app.setMuted(global, !app.isMuted(global))
                    flash(padName(global) + if (app.isMuted(global)) " MUTED" else " UNMUTED")
                }
            }
            app.mode == Mode.PAD && app.eraseHolding -> {
                // ERASE удерживается: касание пада — сразу удалить (свайп — пройденные пады, см. padSlide).
                route[global] = 0
                flash(if (quickErase(global)) "${padName(global)} DELETED" else "${padName(global)} EMPTY")
            }
            app.mode == Mode.PAD -> {
                if (tryPaste(global)) return
                app.selected = global
                when {
                    app.eraseLatch -> {
                        app.eraseLatch = false
                        if (app.loopRecPad == global) return
                        app.loopStop(global)
                        app.forgetLoop(global)
                        undoPadSample(global, "DELETE ${padName(global)}")
                        PadStore.delete(context, global)
                        app.applyPadUi(global, PadUi())   // и в движке: иначе новый звук пада заиграет со старым PITCH
                        flash("SAMPLE ${padName(global)} DELETED")
                    }
                    // ISL-1 LOOPER: касание — запись, второе касание — конец записи и петля играет.
                    // (пад с обычным семплом в LOOPER играет как one-shot — петли пишутся только на пустые пады)
                    app.looperMode &&
                        (PadLibrary.infos[global] == null || app.pads[global].mode == PlayMode.LOOP || global == app.loopRecPad) ->
                        looperTap(global)
                    // ISL-1: пад в режиме LOOP включается и выключается касанием (держать не нужно).
                    app.pads[global].mode == PlayMode.LOOP && PadLibrary.infos[global] != null -> loopPadDown(global)
                    // SRC SELF: пока держат пад, пишется выход приложения; остальные пады в это время играют.
                    app.selfArmed && !app.looperMode && app.selfHoldPad < 0 && !app.resampling -> {
                        route[global] = 6
                        app.selfHoldPad = global
                        if (app.sampleHeld) app.sampleTook = true
                        AudioEngine.resampleStart()
                    }
                    // В LOOPER вход открыт для петель: пад с семплом играет, а не записывается.
                    PadCapture.isArmed && !app.looperMode -> {
                        if (PadCapture.holdingPad < 0) {
                            route[global] = 2
                            if (app.sampleHeld) app.sampleTook = true
                            PadCapture.begin(global)
                        }
                    }
                    else -> {
                        route[global] = 1
                        AudioEngine.noteOn(global, vel)
                        app.captureHit(global, vel)   // CAPTURE помнит сыгранное
                        // Удержание 1,5 с — меню пада (COPY / SLICE / EQ / PTCH ENV).
                        if (PadLibrary.infos[global] != null) {
                            longPress[global] = scope.launch {
                                delay(1500)
                                app.padMenu = global
                            }
                        }
                    }
                }
            }
            !app.stepView -> {
                // SEQ: пад = паттерн
                val src = app.copySource
                when {
                    app.songRec -> {
                        // Запись цепочки: паттерны идут в том порядке, в каком их касаются.
                        AudioEngine.songAppend(global)
                        app.songDraft = app.songDraft + global
                        if (!app.seqPlaying) AudioEngine.seqSelect(global, immediate = true)
                        app.flash(
                            "SONG ${app.songDraft.size}: " + app.songDraft.takeLast(4).joinToString(" ") { patternName(it) },
                            2500,
                        )
                    }
                    src >= 0 -> {
                        app.copySource = -1
                        if (src == global) {
                            flash("COPY CANCELLED")
                        } else {
                            app.undoPattern(global, "COPY ${patternName(global)}")
                            AudioEngine.seqCopyPattern(src, global)
                            app.flash("COPIED ${patternName(src)} → ${patternName(global)}", 1800)
                        }
                    }
                    app.eraseLatch -> {
                        app.eraseLatch = false
                        app.undoPattern(global, "CLEAR ${patternName(global)}")
                        AudioEngine.seqClearPattern(global)
                        flash("CLEARED ${patternName(global)}")
                    }
                    else -> {
                        AudioEngine.seqSelect(global, immediate = !app.seqPlaying)
                        flash("PATTERN ${patternName(global)}" + if (app.seqPlaying) " NEXT" else "")
                        // Долгое нажатие — скопировать этот паттерн, следующее касание пада вставит копию.
                        longPress[global] = scope.launch {
                            delay(600)
                            app.copySource = global
                            app.flash("COPY ${patternName(global)} → TOUCH A PAD", 4000)
                        }
                    }
                }
            }
            app.soundPick -> {
                app.soundPick = false
                app.selected = global
                route[global] = 1
                AudioEngine.noteOn(global, vel)
                flash("SOUND ${padName(global)}")
            }
            else -> {
                // STEP: пустой шаг ставится сразу; занятый снимается на отпускании, если его не правили (P-LOCK).
                val step = app.stepBar * STEPS_PER_BAR + global % 16
                val has = (app.stepMask shr step) and 1L != 0L
                app.undoPattern(app.seqPattern, "STEP ${step + 1}")
                if (!has) {
                    AudioEngine.seqToggleStep(app.seqPattern, app.selected, step * STEP_TICKS, STEP_TICKS, Settings.stepVelocity)
                    app.stepMask = app.stepMask or (1L shl step)   // мгновенный отклик: опрос подтвердит через ~100 мс
                }
                stepAdded[global] = !has
                route[global] = 3
                app.heldStep = step
                app.heldStepEdited = false
                app.heldStepUi = stepUi(step)
                flash("STEP ${step + 1}: TURN A KNOB")
            }
        }
    }

    fun padUp(global: Int) {
        pressedMask.longValue = pressedMask.longValue and (1L shl global).inv()
        longPress[global]?.cancel()
        longPress[global] = null
        when (route[global]) {
            5 -> if (!partsHeld[global]) {
                app.loopToggle(global)
                flash(
                    "LOOP ${padName(global)} " + when (global) {
                        in app.loopsStopping -> "■ BAR END"
                        in app.loopsPlaying -> "▶"
                        in app.loopsWaiting -> "▶ NEXT BAR"
                        else -> "■ · ERASE TO RE-RECORD"
                    }
                )
            }
            1 -> {
                AudioEngine.noteOff(global)
                app.captureRelease(global)
            }
            2 -> PadCapture.end()
            6 -> {
                app.selfHoldPad = -1
                AudioEngine.resampleStop()
                scope.launch {
                    val data = withContext(Dispatchers.Default) { AudioEngine.resampleRead() }
                    if (data.isEmpty()) flash("RESAMPLE: EMPTY")
                    else {
                        PreLowCut.apply(data, AudioEngine.sampleRate(), 2)
                        PadSampler.importClip(global, SampleClip(data, 2, AudioEngine.sampleRate()), "REC_" + padName(global), false)
                    }
                }
            }
            3 -> {
                val step = app.stepBar * STEPS_PER_BAR + global % 16
                if (!stepAdded[global] && !app.heldStepEdited) {
                    AudioEngine.seqToggleStep(app.seqPattern, app.selected, step * STEP_TICKS, STEP_TICKS, Settings.stepVelocity)
                    app.stepMask = app.stepMask and (1L shl step).inv()
                }
                if (app.heldStep == step) {
                    app.heldStep = -1
                    app.heldStepUi = null
                }
            }
        }
        route[global] = 0
    }

    // ---- ручки выбранного пада ----
    val pad = app.selected
    // STEP: пока шаг удерживается, ручки правят только этот шаг (P-LOCK).
    val inStepEdit = app.mode == Mode.SEQ && app.stepView && app.heldStep >= 0
    val ui = (if (inStepEdit) app.heldStepUi else null) ?: app.pads[pad]
    fun setPad(id: Int, label: String, text: String, v: Float, f: (PadUi) -> PadUi) {
        val step = app.heldStep
        if (inStepEdit && step >= 0) {
            // P-LOCK: значение только для этого шага.
            AudioEngine.seqSetLock(app.seqPattern, pad, step * STEP_TICKS, STEP_TICKS, id, v)
            app.heldStepUi = f(app.heldStepUi ?: app.pads[pad])
            app.heldStepEdited = true
            app.flash("STEP ${step + 1} $label $text", 900)
        } else {
            app.setParam(pad, id, v, f)
            app.flash("$label $text", 900)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(CHROME_BG)
            .safeDrawingPadding()
            .padding(4.dp),
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .brushedAluminium()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ================= левая панель =================
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // KAOSS выключается только своей кнопкой: окно частей петли лишь временно закрывает его экран.
                if (app.partsEditPad >= 0) LoopParts(
                    app = app,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) else if (app.kaossMode) KaossPad(
                    app = app,
                    shownX = { kShown[0] },
                    shownY = { kShown[1] },
                    shownTouch = { kShown[2] > 0.5f },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) else Lcd(
                    app = app,
                    recording = holdPad.intValue >= 0 || app.selfHoldPad >= 0,
                    armedLabel = when {
                        app.selfArmed -> "ARM:SELF"
                        cap.arming -> "ARMING"
                        cap.armed -> if (cap.source == CaptureSource.INTERNAL) "ARM:INT" else "ARM:MIC"
                        else -> null
                    },
                    recLevel = { recLevel.floatValue },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )

                // --- софт-клавиши ---
                if (app.kaossMode) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // KAOSS: 1–5 — наборы эффектов; повторное нажатие листает программы набора.
                    for (b in Kaoss.BANKS.indices) {
                        val cur = app.kaossProgram / Kaoss.PER_BANK
                        SoftKey(b + 1, Kaoss.BANKS[b], Modifier.weight(1f), active = cur == b) {
                            val n = Kaoss.PER_BANK
                            app.kaossProgram = if (cur == b) {
                                b * Kaoss.PER_BANK + (app.kaossProgram % Kaoss.PER_BANK + 1) % n
                            } else {
                                b * Kaoss.PER_BANK + kaossLast[b].coerceAtMost(n - 1)
                            }
                            kaossLast[b] = app.kaossProgram % Kaoss.PER_BANK
                            flash(Kaoss.PROGRAMS[app.kaossProgram].title)
                        }
                    }
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SoftKey(1, "PAD", Modifier.weight(1f), active = app.mode == Mode.PAD) {
                        finishSongRec()
                        app.switchView(Mode.PAD, false); app.soundPick = false; app.eraseLatch = false; app.copySource = -1
                        // Вошли в PAD играть: включённый SAMPLE не должен записывать поверх падов.
                        if ((PadCapture.isBusy || app.selfArmed) && !app.looperMode) {
                            sampleOff()
                            flash("SAMPLE OFF")
                        }
                    }
                    SoftKey(2, "SEQ", Modifier.weight(1f), active = app.mode == Mode.SEQ && !app.stepView) {
                        finishSongRec()
                        app.switchView(Mode.SEQ, false); app.soundPick = false; app.eraseLatch = false; app.copySource = -1
                    }
                    SoftKey(3, if (app.stepView) "SOUND" else "STEP", Modifier.weight(1f), active = app.stepView && app.soundPick) {
                        if (app.stepView) {
                            app.soundPick = !app.soundPick
                            flash(if (app.soundPick) "TOUCH A PAD: SOUND" else "STEP INPUT")
                        } else {
                            finishSongRec()
                            app.switchView(Mode.SEQ, true); app.eraseLatch = false; app.copySource = -1
                            flash("STEP: ${padName(app.selected)}")
                        }
                    }
                    SoftKey(4, "EDIT", Modifier.weight(1f)) {
                        sampleVm.loadFromPad(app.selected)
                        app.editorOpen = true
                    }
                    SoftKey(5, "IMPORT", Modifier.weight(1f), enabled = !PadCapture.isBusy) {
                        actions.importToPad(app.selected)
                        flash("IMPORT → ${padName(app.selected)}")
                    }
                }

                // --- ручки ---
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Knob("VOL", ui.volume / 2f, { n ->
                        val v = n * 2f
                        setPad(PadParam.VOLUME, "VOL", "%.0f%%".format(v * 100), v) { it.copy(volume = v) }
                    }, diameter = 34.dp, defaultValue = 0.5f)
                    Knob("PAN", (ui.pan + 1f) / 2f, { n ->
                        val v = (n * 2f - 1f).let { if (kotlin.math.abs(it) < 0.04f) 0f else it }
                        setPad(PadParam.PAN, "PAN", "%+.2f".format(v), v) { it.copy(pan = v) }
                    }, diameter = 34.dp, defaultValue = 0.5f)
                    Knob("PITCH", (ui.pitch + 12f) / 24f, { n ->
                        val v = (n * 24f - 12f).roundToInt().toFloat()
                        setPad(PadParam.PITCH, "PITCH", "%+.0f ST".format(v), v) { it.copy(pitch = v) }
                    }, diameter = 34.dp, defaultValue = 0.5f)
                    Knob("START", ui.start, { n ->
                        val v = n.coerceAtMost(ui.end - 0.01f).coerceAtLeast(0f)
                        setPad(PadParam.START, "START", "%.1f%%".format(v * 100), v) { it.copy(start = v) }
                    }, diameter = 34.dp, brass = true, defaultValue = 0f)
                    Knob("END", ui.end, { n ->
                        val v = n.coerceAtLeast(ui.start + 0.01f).coerceAtMost(1f)
                        setPad(PadParam.END, "END", "%.1f%%".format(v * 100), v) { it.copy(end = v) }
                    }, diameter = 34.dp, brass = true, defaultValue = 1f)
                    Knob("ATK", norm(ui.attackMs, 0f, 200f), { n ->
                        val v = denorm(n, 0f, 200f)
                        setPad(PadParam.ATTACK_MS, "ATTACK", "%.0f MS".format(v), v) { it.copy(attackMs = v) }
                    }, diameter = 34.dp, defaultValue = 0f)
                    Knob("REL", sqrt(norm(ui.releaseMs, 5f, 2000f)), { n ->
                        val v = 5f + n * n * 1995f
                        setPad(PadParam.RELEASE_MS, "RELEASE", "%.0f MS".format(v), v) { it.copy(releaseMs = v) }
                    }, diameter = 34.dp, defaultValue = 0.07f)
                }

                // --- ряд A: транспорт ---
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HwButton("PLAY", Modifier.weight(1f), led = app.seqPlaying, ledColor = Skin.LedGreen, glyph = Glyph.PLAY, height = 28.dp) {
                        AudioEngine.seqPlay()
                    }
                    HwButton("STOP", Modifier.weight(1f), glyph = Glyph.STOP, height = 28.dp) {
                        AudioEngine.seqStop()
                        AudioEngine.seqSetRecording(false)
                        AudioEngine.allNotesOff()
                        app.loopStopAll()
                    }
                    HwButton("REC", Modifier.weight(1f), led = app.seqRecording || app.seqCountIn || app.songRec || app.fxRecording, ledColor = Skin.LedRed, glyph = Glyph.REC, height = 28.dp) {
                        if (app.kaossMode) {
                            // KAOSS: REC пишет все манипуляции с эффектами на одну длину Song.
                            if (app.fxRecording) {
                                app.fxRecording = false
                                flash("FX REC STOPPED")
                            } else {
                                val prev = app.fxAuto
                                app.pushUndo("FX REC") { app.fxAuto = prev }
                                app.fxRecProgress = 0f
                                app.fxRecording = true
                                if (!app.seqPlaying) AudioEngine.seqPlay()
                                app.flash("FX REC: PLAY WITH EFFECTS", 2500)
                            }
                        } else if (app.songRec) {
                            finishSongRec()
                        } else if (app.mode == Mode.SEQ && !app.stepView && !app.seqRecording && !app.seqCountIn) {
                            // SEQ: REC пишет цепочку паттернов — касайся паттернов по порядку, REC — готово.
                            val prev = app.song
                            app.pushUndo("SONG") {
                                AudioEngine.songClear()
                                prev.forEach { AudioEngine.songAppend(it) }
                                AudioEngine.songEnable(prev.isNotEmpty())
                                app.song = prev
                            }
                            app.copySource = -1
                            app.songRec = true
                            app.songDraft = emptyList()
                            AudioEngine.songClear()
                            app.flash("SONG REC: TOUCH PATTERNS IN ORDER", 3000)
                        } else if (app.seqRecording || app.seqCountIn) {
                            AudioEngine.seqSetRecording(false)
                            if (app.seqCountIn) AudioEngine.seqStop()
                        } else {
                            app.undoPattern(app.seqPattern, "REC ${patternName(app.seqPattern)}")
                            AudioEngine.seqSetRecording(true)
                            if (!app.seqPlaying) AudioEngine.seqPlay()
                        }
                    }
                    HwButton("TAP", Modifier.weight(1f), height = 28.dp) {
                        app.tapTempo(System.currentTimeMillis())?.let { bpm ->
                            val v = (bpm * 10f).roundToInt() / 10f
                            Settings.bpm = v
                            AudioEngine.seqSetBpm(v)
                            flash("TEMPO %.1f".format(v))
                        }
                    }
                    HwButton("UNDO", Modifier.weight(1f), enabled = app.canUndo, height = 28.dp) {
                        val what = app.undo()
                        flash(if (what != null) "UNDO: $what" else "NOTHING TO UNDO")
                    }
                }

                // --- ряд B: настройки секвенсора ---
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HwButton("QUANT", Modifier.weight(1f), led = Settings.quantize != 0, height = 28.dp) {
                        val g = Settings.QUANTIZE_GRIDS
                        val next = g[(g.indexOf(Settings.quantize) + 1) % g.size]
                        Settings.quantize = next
                        AudioEngine.seqSetQuantize(next)
                        flash("QUANTIZE ${Settings.quantizeLabel(next)}")
                    }
                    HwButton("METRO", Modifier.weight(1f), led = Settings.metroMode != 0, height = 28.dp) {
                        val next = when (Settings.metroMode) { 0 -> 2; 2 -> 1; else -> 0 }
                        Settings.metroMode = next
                        AudioEngine.seqSetMetronome(next, Settings.metroLevel)
                        flash("METRONOME " + when (next) { 0 -> "OFF"; 2 -> "REC ONLY"; else -> "ON" })
                    }
                    HwButton("KAOSS", Modifier.weight(1f), led = app.kaossMode, ledColor = Skin.LedRed, height = 28.dp) {
                        app.kaossMode = !app.kaossMode
                        if (!app.kaossMode) {
                            app.motionPlaying = -1
                            app.motionRecording = -1
                            app.kTouch = false
                        }
                        // ISL-1: KAOSS переключает SRC на SELF — приложение слышит само себя (дальше — вручную).
                        val toSelf = app.kaossMode && !app.srcSelf && app.loopRecPad < 0
                        if (toSelf) changeSource { app.srcSelf = true }
                        flash(if (toSelf) "KAOSS · SRC: SELF" else if (app.kaossMode) "KAOSS: TOUCH THE SCREEN" else "KAOSS OFF")
                    }
                    HwButton("LEN", Modifier.weight(1f), height = 28.dp) {
                        val bars = app.patternBars % 4 + 1
                        val pat = app.seqPattern
                        val old = app.patternBars
                        app.pushUndo("LENGTH") { AudioEngine.seqSetLength(pat, old) }
                        AudioEngine.seqSetLength(app.seqPattern, bars)
                        app.patternBars = bars
                        if (app.stepBar >= bars) app.stepBar = 0
                        flash("LENGTH $bars BAR")
                    }
                    val padMode = app.pads[app.selected].mode
                    HwButton(
                        when (padMode) { PlayMode.GATE -> "GATE"; PlayMode.LOOP -> "LOOP"; else -> "ONE" },
                        Modifier.weight(1f), led = padMode != PlayMode.ONE_SHOT, height = 28.dp,
                    ) {
                        val prev = app.pads[app.selected]
                        val sel = app.selected
                        app.pushUndo("MODE ${padName(sel)}") { app.applyPadUi(sel, prev) }
                        val next = app.cyclePlayMode(app.selected)
                        flash(
                            "${padName(app.selected)} " + when (next) {
                                PlayMode.GATE -> "GATE"
                                PlayMode.LOOP -> "LOOP: GAIN = LOOP LENGTH"
                                else -> "ONE SHOT"
                            }
                        )
                    }
                }

                // --- ряд C: ввод семплов ---
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HwButton(
                        "ERASE", Modifier.weight(1f), led = app.eraseLatch, ledColor = Skin.LedRed, height = 28.dp,
                        onPress = { down ->
                            eraseHold?.cancel()
                            if (!app.kaossMode && app.mode == Mode.PAD) app.eraseHolding = down
                            if (down && app.kaossMode) {
                                // ISL-1, KAOSS: удержание ERASE стирает запись эффектов (короткое — удаление петли).
                                eraseHold = scope.launch {
                                    delay(700)
                                    eraseHeld = true
                                    app.eraseLatch = false
                                    val prev = app.fxAuto
                                    if (prev == null) {
                                        flash("NO FX AUTOMATION")
                                    } else {
                                        app.pushUndo("FX AUTOMATION") { app.fxAuto = prev }
                                        app.fxAuto = null
                                        flash("FX AUTOMATION DELETED")
                                    }
                                }
                            } else if (down && !app.kaossMode && app.mode == Mode.SEQ && !app.stepView) {
                                // SEQ: удержание ERASE стирает цепочку паттернов (Song).
                                eraseHold = scope.launch {
                                    delay(700)
                                    eraseHeld = true
                                    val prev = app.song
                                    app.pushUndo("SONG") {
                                        AudioEngine.songClear()
                                        prev.forEach { AudioEngine.songAppend(it) }
                                        AudioEngine.songEnable(prev.isNotEmpty())
                                        app.song = prev
                                    }
                                    AudioEngine.songClear()
                                    AudioEngine.songEnable(false)
                                    app.song = emptyList()
                                    app.eraseLatch = false
                                    app.flash(if (prev.isEmpty()) "NO SONG TO ERASE" else "SONG ERASED", 1800)
                                }
                            }
                        },
                    ) {
                        if (eraseHeld) {
                            eraseHeld = false
                        } else if (app.kaossMode) {
                            // ISL-1, KAOSS: ERASE → коснуться пада-петли — петля удаляется.
                            if (app.mode != Mode.PAD) app.switchView(Mode.PAD, false)
                            app.eraseLatch = !app.eraseLatch
                            if (app.eraseLatch) app.flash("TOUCH A LOOP PAD: DELETE · HOLD ERASE = FX AUTOMATION", 3000)
                            else flash("ERASE OFF")
                        } else if (app.kaossMode) {
                            val prev = app.fxAuto
                            if (prev == null) {
                                flash("NO FX AUTOMATION")
                            } else {
                                app.pushUndo("FX AUTOMATION") { app.fxAuto = prev }
                                app.fxAuto = null
                                flash("FX AUTOMATION DELETED")
                            }
                        } else if (inStepEdit) {
                            val step = app.heldStep
                            AudioEngine.seqClearLocks(app.seqPattern, app.selected, step * STEP_TICKS, STEP_TICKS)
                            app.heldStepUi = app.pads[app.selected]
                            app.heldStepEdited = true
                            flash("STEP ${step + 1}: P-LOCKS CLEARED")
                        } else if (app.mode == Mode.SEQ && app.stepView) {
                            app.undoPattern(app.seqPattern, "ERASE ${padName(app.selected)}")
                            AudioEngine.seqErasePad(app.seqPattern, app.selected)
                            app.stepMask = 0L
                            flash("ERASED ${padName(app.selected)}")
                        } else {
                            app.eraseLatch = !app.eraseLatch
                            if (app.eraseLatch) app.flash(if (app.mode == Mode.PAD) "TOUCH PAD: DELETE SAMPLE" else "TOUCH PATTERN TO CLEAR", 3000)
                            else flash("ERASE OFF")
                        }
                    }
                    if (app.kaossMode) HwButton(
                        "RESMPL", Modifier.weight(1f),
                        led = app.resampling || app.resampleClip != null || (app.looperMode && app.srcSelf),
                        ledColor = if (app.resampling || (app.loopRecFromEngine && app.loopRecPad >= 0)) Skin.LedRed else Skin.LedAmber,
                        height = 28.dp,
                        onPress = { down ->
                            if (down) {
                                resampleHold?.cancel()
                                resampleHold = scope.launch {
                                    delay(600)
                                    val clip = app.resampleClip
                                    if (clip != null && !app.resampling) {
                                        resampleHeld = true
                                        app.clipboard = SampleClipboard(clip, "RESAMPLE", null, true, -1)
                                        app.pasteArmed = true
                                        app.switchView(Mode.PAD, false)
                                        app.flash("RESAMPLE COPIED → TOUCH A PAD", 4000)
                                    }
                                }
                            } else {
                                resampleHold?.cancel()
                            }
                        },
                    ) {
                        if (resampleHeld) {
                            resampleHeld = false
                        } else if (app.looperMode) {
                            // ISL-1: RESMP в LOOPER — петли пишутся с выхода (сумма петель через эффекты KAOSS).
                            if (app.loopRecPad >= 0) {
                                flash("CLOSE THE LOOP ON ${padName(app.loopRecPad)} FIRST")
                            } else {
                                app.srcSelf = !app.srcSelf
                                app.flash(
                                    if (app.srcSelf) "LOOPER ← MIX + FX · TAP AN EMPTY PAD" else "LOOPER ← INPUT (${if (app.sampleSource == CaptureSource.MIC) "MIC" else "INT"})",
                                    2500,
                                )
                            }
                        } else if (app.resampling) {
                            finishResample()
                        } else {
                            AudioEngine.resampleStart()
                            app.resampling = true
                            app.flash("● RESAMPLING (MAX 30 S) · PRESS TO STOP", 3000)
                        }
                    } else HwButton(
                        "SAMPLE", Modifier.weight(1f),
                        led = cap.armed || cap.arming || app.selfArmed, ledColor = if (cap.armed || app.selfArmed) Skin.LedRed else Skin.LedAmber,
                        height = 28.dp,
                        // Нажатие включает запись одного семпла; пока кнопку держат — пишется в несколько падов подряд.
                        onPress = { down ->
                            if (down) {
                                app.sampleHeld = true
                                app.sampleTook = false
                                app.sampleArmedByPress = !PadCapture.isBusy && !app.selfArmed && !app.looperMode
                                if (app.sampleArmedByPress) {
                                    app.switchView(Mode.PAD, false)
                                    armSample()
                                    flash("HOLD A PAD TO RECORD")
                                }
                            } else if (app.sampleHeld) {
                                app.sampleHeld = false
                                if (app.looperMode) {
                                    // ISL-1: вход уже открыт лупером — просто переходим к обычной записи семплов.
                                    if (app.loopRecPad >= 0) {
                                        flash("CLOSE THE LOOP ON ${padName(app.loopRecPad)} FIRST")
                                    } else {
                                        app.looperMode = false
                                        app.looperIndividual = false
                                        if (app.srcSelf) {
                                            if (PadCapture.isBusy) actions.disarmPadCapture()
                                            app.selfArmed = true
                                        } else if (!PadCapture.isBusy) actions.armPadCapture()
                                        app.flash("SAMPLE: HOLD A PAD TO RECORD · LOOPER OFF", 2500)
                                    }
                                } else if (app.sampleTook) {
                                    // Запись на паде ещё идёт — SAMPLE выключится, когда семпл будет готов.
                                    if (PadCapture.holdingPad < 0 && app.selfHoldPad < 0) sampleOff()
                                    flash("SAMPLE OFF")
                                } else if (!app.sampleArmedByPress && (PadCapture.isBusy || app.selfArmed)) {
                                    sampleOff()
                                    flash("SAMPLE OFF")
                                }
                            }
                        },
                    ) {}
                    HwButton(
                        "SRC", Modifier.weight(1f),
                        glyph = when {
                            app.srcSelf -> Glyph.SELF
                            app.sampleSource == CaptureSource.MIC -> Glyph.MIC
                            else -> Glyph.CASSETTE
                        },
                        // Источник переключается всегда, кроме самой записи (вход переоткрывается)
                        enabled = app.loopRecPad < 0 && holdPad.intValue < 0 && app.selfHoldPad < 0,
                        height = 28.dp,
                    ) {
                        changeSource {
                            when {
                                app.srcSelf -> { app.srcSelf = false; app.sampleSource = CaptureSource.MIC }
                                app.sampleSource == CaptureSource.MIC -> app.sampleSource = CaptureSource.INTERNAL
                                else -> app.srcSelf = true
                            }
                        }
                        flash(
                            when {
                                app.srcSelf -> "SOURCE: SELF (APP OUTPUT)"
                                app.sampleSource == CaptureSource.MIC -> "SOURCE: MIC"
                                else -> "SOURCE: INTERNAL AUDIO"
                            }
                        )
                    }
                    HwButton("T-HEAD", Modifier.weight(1f), led = Settings.trimStart, height = 28.dp) {
                        Settings.trimStart = !Settings.trimStart
                        app.applyTrim(head = true, on = Settings.trimStart)
                        flash("TRIM HEAD " + if (Settings.trimStart) "ON" else "OFF")
                    }
                    HwButton("T-TAIL", Modifier.weight(1f), led = Settings.trimEnd, height = 28.dp) {
                        Settings.trimEnd = !Settings.trimEnd
                        app.applyTrim(head = false, on = Settings.trimEnd)
                        flash("TRIM TAIL " + if (Settings.trimEnd) "ON" else "OFF")
                    }
                }
            }

            // ================= банки =================
            Column(
                Modifier.width(50.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            ) {
                // DRY/WET эффекта KAOSS: своё значение у каждой программы, 3 тапа — все на 100%.
                val mixProg = app.shownProgram.coerceIn(0, app.kaossMix.size - 1)
                Knob("DRY/WET", app.kaossMix[mixProg], { n ->
                    app.kaossMix[mixProg] = n
                    app.flash("${Kaoss.PROGRAMS[mixProg].code} MIX %.0f%%".format(n * 100), 900)
                }, diameter = 42.dp, accent = Color(0xFFFF6A00), onTripleTap = {
                    for (i in app.kaossMix.indices) app.kaossMix[i] = 1f
                    app.flash("ALL FX MIX 100%", 1500)
                })
                for (b in 3 downTo 0) {
                    val kaoss = app.kaossMode
                    val pageMode = !kaoss && app.mode == Mode.SEQ && app.stepView
                    HwButton(
                        ('A' + b).toString(), Modifier.fillMaxWidth(),
                        led = when {
                            kaoss -> app.motions[b] != null || app.motionRecording == b
                            pageMode -> app.stepBar == b
                            else -> app.bank == b
                        },
                        ledColor = when {
                            kaoss && app.motionRecording == b -> Skin.LedRed
                            kaoss && app.motionPlaying == b -> Skin.LedGreen
                            else -> Skin.LedAmber
                        },
                        enabled = !pageMode || b < app.patternBars,
                        height = 34.dp,
                        onPress = { down ->
                            if (app.kaossMode) motionButton(b, down)
                        },
                    ) {
                        when {
                            app.kaossMode -> {}   // MOTION: обрабатывается в onPress
                            pageMode -> {
                                // STEP: A–D — такты паттерна (страницы шагов)
                                app.stepBar = b
                                flash("PAGE ${b + 1}/${app.patternBars}")
                            }
                            else -> app.bank = b
                        }
                    }
                }
                // ISL-1: LOOPER — запись петель прямо на пады
                HwButton(
                    if (app.looperIndividual) "iLOOPER" else "LOOPER", Modifier.fillMaxWidth(),
                    led = app.looperMode,
                    ledColor = if (app.loopRecPad >= 0) Skin.LedRed else if (app.looperIndividual) Skin.LedBlue else Skin.LedAmber,
                    height = 30.dp,
                    labelSize = 7.5.sp,
                ) {
                    when {
                        app.loopRecPad >= 0 -> flash("CLOSE THE LOOP ON ${padName(app.loopRecPad)} FIRST")
                        app.looperMode && !app.looperIndividual -> {
                            app.looperIndividual = true
                            app.flash("iLOOPER · LOOPS DRY · SRC SELF: ONLY THE NEW PART IS RECORDED", 3000)
                        }
                        app.looperMode -> {
                            app.looperMode = false
                            app.looperIndividual = false
                            actions.disarmPadCapture()
                            app.flash("LOOPER OFF · TAP A LOOP PAD = PLAY / STOP", 2500)
                        }
                        else -> {
                            app.switchView(Mode.PAD, false)
                            app.selfArmed = false
                            app.looperMode = true
                            app.flash("LOOPER ON · EMPTY PAD = REC · LOOP PAD = PLAY/STOP", 3000)
                        }
                    }
                }
                HwButton(
                    "CAPTURE", Modifier.fillMaxWidth(),
                    led = app.captureCount > 0, ledColor = Skin.LedAmber,
                    height = 28.dp,
                    labelSize = 7.sp,
                    onPress = { down ->
                        captureHold?.cancel()
                        if (down) captureHold = scope.launch {
                            delay(2000)   // удержание > 2 с — буфер пуст
                            captureHeld = true
                            app.captureClear()
                            flash("CAPTURE CLEARED")
                        }
                    },
                ) {
                    when {
                        captureHeld -> captureHeld = false
                        app.seqPlaying -> flash("CAPTURE WORKS WHILE STOPPED · PLAY PADS, THEN CAPTURE")
                        else -> app.capture(context)
                    }
                }
                // Нажатие: красная, касание пада глушит его; выключение открывает все пады.
                // Удержание: касания и свайпы как у ERASE, после отпускания пады играют, заглушённые молчат.
                HwButton(
                    "MUTE", Modifier.fillMaxWidth(),
                    led = app.muteLatch || app.muteHolding || app.muteMask != 0L, ledColor = Skin.LedRed,
                    height = 28.dp,
                    labelSize = 7.5.sp,
                    onPress = { down ->
                        if (down) {
                            app.switchView(Mode.PAD, false)
                            app.muteTook = false
                            app.muteHolding = true
                            flash("MUTE: TOUCH OR SWIPE PADS")
                        } else if (app.muteHolding) {
                            app.muteHolding = false
                            if (!app.muteTook) {
                                if (app.muteLatch || app.muteMask != 0L) {
                                    app.unmuteAll()
                                    flash("MUTE OFF · ALL PADS OPEN")
                                } else {
                                    app.muteLatch = true
                                    flash("MUTE: TOUCH A PAD")
                                }
                            }
                        }
                    },
                ) {}
            }

            // ================= пады =================
            val inStep = app.mode == Mode.SEQ && app.stepView
            val inPatterns = app.mode == Mode.SEQ && !app.stepView
            PadGrid(
                bank = app.bank,
                selectedPad = if (app.mode == Mode.PAD) app.selected else if (inStep) -1 else -1,
                fullLevel = Settings.fullLevel,
                lit = { if (app.mode == Mode.PAD) engineMask.longValue or pressedMask.longValue else pressedMask.longValue },
                marked = {
                    var m = 0L
                    when {
                        app.chokeHold -> for (i in 0 until 64) if (app.pads[i].choke > 0) m = m or (1L shl i)
                        inPatterns -> for (i in 0 until 64) if (app.patternCounts[i] > 0) m = m or (1L shl i)
                        inStep -> m = ((app.stepMask shr (app.stepBar * STEPS_PER_BAR)) and 0xFFFFL) shl (app.bank * 16)
                        else -> for (i in 0 until 64) if (PadLibrary.infos[i] != null) m = m or (1L shl i)
                    }
                    m
                },
                cursor = {
                    when {
                        inPatterns -> if (app.copySource >= 0) app.copySource else app.seqPattern
                        inStep && app.seqPlaying && app.seqPos / BAR_TICKS_PAGE == app.stepBar ->
                            app.bank * 16 + (app.seqPos % BAR_TICKS_PAGE) / STEP_TICKS
                        else -> -1
                    }
                },
                recPad = { holdPad.intValue },
                // Пока держат CHOKE — на падах номер группы (G1…G4), чтобы разные группы было видно.
                badge = { g -> if (app.chokeHold && app.pads[g].choke > 0) "G${app.pads[g].choke}" else null },
                muted = { app.muteMask },
                loopRing = { g ->
                    loopFrame.longValue   // читаем такт кадра — круг перерисовывается
                    when {
                        app.mode != Mode.PAD -> Float.NaN
                        g == app.loopRecPad ->
                            if (System.nanoTime() < app.loopRecStartNanos) LOOP_RING_WAIT else LOOP_RING_REC
                        g in app.loopsWaiting || g in app.loopsStopping -> LOOP_RING_WAIT
                        app.pads[g].mode != PlayMode.LOOP || PadLibrary.infos[g] == null -> Float.NaN
                        else -> app.loopsPlaying[g]?.let { t0 ->
                            val len = app.loopLengthNanos(g)
                            if (len <= 0) 0f else ((System.nanoTime() - t0) % len).toFloat() / len
                        } ?: LOOP_RING_STOPPED
                    }
                },
                onPadDown = ::padDown,
                onPadUp = ::padUp,
                onPadSlide = ::padSlide,
                modifier = Modifier.fillMaxHeight().aspectRatio(1f),
            )

            // ================= правая колонка =================
            Column(
                Modifier.width(122.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().height(42.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Логотипы есть только в фирменной сборке: они лежат в приватной папке branding/
                    // (её нет в открытом коде). Без неё — просто название текстом.
                    val logoSalych = brandingDrawable("logo_salych")
                    val logoIsl1 = brandingDrawable("logo_isl1")
                    if (logoSalych != 0) {
                        Image(
                            painterResource(logoSalych), "SALYCH MUSIC",
                            Modifier.height(42.dp).aspectRatio(613f / 814f),
                            contentScale = ContentScale.Fit,
                        )
                        if (logoIsl1 != 0) Image(
                            painterResource(logoIsl1), "ISL-1",
                            Modifier.width(88.dp),
                            contentScale = ContentScale.Fit,
                        ) else Text(
                            "ISL-1", color = Skin.Ink, fontSize = 22.sp, fontWeight = FontWeight.Black,
                        )
                    } else {
                        Text(
                            "ISL-1",
                            color = Skin.Ink,   // тёмная «шелкография» на алюминиевой панели
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Row(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // PreLC: срез низа только для того, что будет записано. Внизу — выключен, вверху — 1000 Гц.
                    Fader("PreLC", if (Settings.preLcHz <= 0f) 0f else ln(Settings.preLcHz / 20f) / ln(50f), { n ->
                        val hz = if (n < 0.02f) 0f else 20f * 50f.pow(n)
                        Settings.preLcHz = hz
                        app.flash(if (hz <= 0f) "PRE LOW CUT OFF" else "PRE LOW CUT %.0f HZ".format(hz), 900)
                    }, Modifier.weight(1f), trackWidth = 30.dp, capWidth = 30.dp, capHeight = 22.dp, defaultValue = 0f)
                    // ISL-1: SEQ — громкость секвенсора и one-shot отдельно от петель (клик — в SETUP).
                    Fader("SEQ", if (app.seqDucked) 0f else Settings.seqVolume / 1.5f, { n ->
                        val v = n * 1.5f
                        app.unduckSeq()
                        Settings.seqVolume = v
                        AudioEngine.setSeqVolume(v)
                        app.flash("SEQ VOL %.0f%%".format(v * 100), 900)
                    }, Modifier.weight(1f), trackWidth = 30.dp, capWidth = 30.dp, capHeight = 22.dp, defaultValue = 1f / 1.5f)
                    // ISL-1: LOOPER — громкость всех петель (пара к SEQ). Отдельная петля — ручкой VOL,
                    // уровень входа — в SETUP.
                    Fader("LOOPER", Settings.loopVolume / 1.5f, { n ->
                        val v = n * 1.5f
                        Settings.loopVolume = v
                        AudioEngine.setLoopVolume(v)
                        app.flash("LOOPER VOL %.0f%%".format(v * 100), 900)
                    }, Modifier.weight(1f), trackWidth = 30.dp, capWidth = 30.dp, capHeight = 22.dp, defaultValue = 1f / 1.5f)
                    Fader("TRIM", norm(Settings.trimDb, -72f, -20f), { n ->
                        val v = denorm(n, -72f, -20f).roundToInt().toFloat()
                        Settings.trimDb = v
                        app.flash("TRIM THRESH %.0f DB".format(v), 900)
                    }, Modifier.weight(1f), trackWidth = 30.dp, capWidth = 30.dp, capHeight = 22.dp, defaultValue = norm(-50f, -72f, -20f))
                }
                PeakMeter(level = { peak.floatValue }, modifier = Modifier.fillMaxWidth().height(7.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HwButton(
                        "CHOKE", Modifier.weight(1f), led = app.chokeHold, ledColor = Skin.LedRed, height = 24.dp,
                        onPress = { held ->
                            app.chokeHold = held
                            if (held) app.flash("CHOKE: TOUCH PADS", 1500)
                        },
                    ) {}
                    HwButton("EXPORT", Modifier.weight(1f), led = app.exportOpen, ledColor = Skin.LedRed, height = 24.dp) {
                        if (app.seqRecording) AudioEngine.seqSetRecording(false)
                        app.exportOpen = true
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HwButton("FULL", Modifier.weight(1f), led = Settings.fullLevel, height = 24.dp) {
                        Settings.fullLevel = !Settings.fullLevel
                        flash("FULL LEVEL " + if (Settings.fullLevel) "ON" else "OFF")
                    }
                    HwButton("SETUP", Modifier.weight(1f), led = Settings.linkEnabled, height = 24.dp) {
                        app.setupOpen = true
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HwButton("PRJCT", Modifier.weight(1f), led = app.projectOpen, height = 24.dp) {
                        app.projectOpen = true
                    }
                    // LIVE REC: всё, что слышно, — в WAV (Музыка/ISL-1 LIVE); начало отмечено белой вспышкой экрана.
                    HwButton("LIVE REC", Modifier.weight(1f), led = app.liveRec, ledColor = Skin.LedRed, height = 24.dp, labelSize = 7.5.sp) {
                        if (app.liveRec) {
                            LiveRecorder.stop()
                            app.liveRec = false
                            val sec = (System.nanoTime() - app.liveStartNanos) / 1_000_000_000L
                            app.flash(
                                "LIVE REC ■ %d:%02d → %s".format(sec / 60, sec % 60, LiveRecorder.folderLabel().uppercase()) +
                                    if (LiveRecorder.dropouts > 0) " · ${LiveRecorder.dropouts} GAPS" else "",
                                3500,
                            )
                        } else if (LiveRecorder.start(context)) {
                            app.liveRec = true
                            app.liveStartNanos = System.nanoTime()
                            app.liveFlash = true
                            app.flash("● LIVE REC · PRESS AGAIN TO STOP", 3000)
                        } else {
                            flash("LIVE REC: CANNOT CREATE FILE")
                        }
                    }
                }
            }
        }

        // ---- длинные сообщения ----
        app.toast?.let { t ->
            Text(
                t,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 14.dp, start = 24.dp, end = 24.dp)
                    .background(Color(0xE6101113), RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        // ---- ввод темпа цифрами ----
        if (app.tempoEntry) TempoEntry(app, Modifier.fillMaxSize())

        // ---- ISL-1: меню пада (удержание 1,5 с, у петли — кнопка MENU в окне частей) ----
        if (app.padMenu >= 0) {
            PadMenu(app, onCopy = { p -> scope.launch { copyPad(p) } }, Modifier.fillMaxSize())
        }

        // ---- PRJCT: сохранить / открыть проект ----
        if (app.projectOpen) ProjectScreen(app, actions, Modifier.fillMaxSize())

        // ---- EXPORT мастера ----
        if (app.exportOpen) ExportScreen(app, Modifier.fillMaxSize())

        // ---- настройки (Ableton Link, latency) ----
        if (app.setupOpen) {
            SetupScreen(app, actions, Modifier.fillMaxSize())
        }

        // ---- LIVE REC: белая вспышка — метка начала записи для склейки звука с видео экрана ----
        if (app.liveFlash) {
            Box(Modifier.fillMaxSize().background(Color.White))
            LaunchedEffect(Unit) {
                delay(300)
                app.liveFlash = false
            }
        }

        // ---- редактор семпла (waveform, маркеры, chop) поверх всего ----
        if (app.editorOpen) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Bg)
                    .padding(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("EDIT · PAD ${padName(app.selected)}", color = TextMain, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Chip("CLOSE", active = true, compact = true) {
                        sampleVm.stopPreview()
                        app.editorOpen = false
                    }
                }
                Spacer(Modifier.height(6.dp))
                SampleBody(
                    app = app,
                    actions = actions,
                    recLevel = { recLevel.floatValue },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }
}

/** Тиков в одном такте (страница шагов = один такт). */
private const val BAR_TICKS_PAGE = STEPS_PER_BAR * STEP_TICKS

/**
 * Id картинки из приватной папки branding/ или 0, если её нет в этой сборке.
 * Ищем по имени, чтобы открытый код собирался без фирменных логотипов.
 */
@SuppressLint("DiscouragedApi")
@Composable
private fun brandingDrawable(name: String): Int {
    val context = LocalContext.current
    return remember(name) { context.resources.getIdentifier(name, "drawable", context.packageName) }
}
