package com.example.mpcpad.ui

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mpcpad.R
import com.example.mpcpad.Settings
import com.example.mpcpad.audio.AudioEngine
import com.example.mpcpad.audio.PadParam
import com.example.mpcpad.capture.CaptureController
import com.example.mpcpad.capture.CaptureSource
import com.example.mpcpad.capture.PadCapture
import com.example.mpcpad.sample.PadLibrary
import com.example.mpcpad.sample.PadSampler
import com.example.mpcpad.sample.SampleViewModel
import com.example.mpcpad.sample.SamplerEvent
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

private fun norm(v: Float, lo: Float, hi: Float) = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
private fun denorm(n: Float, lo: Float, hi: Float) = lo + n * (hi - lo)

private val COUNT_IN_OPTIONS = listOf(0, 1, 2, 4)
private val CHROME_BG = Color(0xFF0E0F11)

/** Корневой экран: «железо» IS-1 — LCD, ручки, кнопки секвенсора, пады, фейдеры и логотипы. */
@Composable
fun MpcRoot(actions: SampleActions) {
    val app: AppViewModel = viewModel()
    val sampleVm: SampleViewModel = viewModel()
    val haptic = LocalHapticFeedback.current

    val pressedMask = remember { mutableLongStateOf(0L) }
    val engineMask = remember { mutableLongStateOf(0L) }
    val peak = remember { mutableFloatStateOf(0f) }
    val recLevel = remember { mutableFloatStateOf(0f) }
    val holdPad = remember { mutableIntStateOf(-1) }
    /** Как был нажат каждый пад: 0 — никак, 1 — нота, 2 — запись семпла. */
    val route = remember { IntArray(64) }
    val cap by PadCapture.state.collectAsState()

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

                val st = AudioEngine.seqState()
                app.seqPos = st.position
                app.seqPlaying = st.playing
                app.seqRecording = st.recording
                app.seqCountIn = st.countIn
                app.seqPattern = st.pattern
                app.seqQueued = st.queued
                app.seqLinkWaiting = st.linkWaiting

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
                    app.resetRegion(e.pad)
                    if (app.mode == Mode.PAD) app.selected = e.pad
                    val trim = e.headTrimMs + e.tailTrimMs
                    app.flash(
                        "${e.name} → ${padName(e.pad)}" + (if (trim > 0) "  TRIM ${trim}MS" else ""),
                        2500,
                    )
                    if (e.capped) app.showToast("Запись/файл длиннее лимита — обрезано")
                }
                is SamplerEvent.Rejected -> app.showToast(e.reason, 4500)
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

    // ---- обработка падов ----
    fun padDown(global: Int, vel: Float) {
        pressedMask.longValue = pressedMask.longValue or (1L shl global)
        if (Settings.haptic) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        route[global] = 0
        when {
            app.mode == Mode.PAD -> {
                app.selected = global
                when {
                    app.eraseLatch -> {
                        app.eraseLatch = false
                        AudioEngine.seqErasePad(app.seqPattern, global)
                        flash("ERASED ${padName(global)} IN ${patternName(app.seqPattern)}")
                    }
                    PadCapture.isArmed -> {
                        if (PadCapture.holdingPad < 0) {
                            route[global] = 2
                            PadCapture.begin(global)
                        }
                    }
                    else -> {
                        route[global] = 1
                        AudioEngine.noteOn(global, vel)
                    }
                }
            }
            !app.stepView -> {
                // SEQ: пад = паттерн
                if (app.eraseLatch) {
                    app.eraseLatch = false
                    AudioEngine.seqClearPattern(global)
                    flash("CLEARED ${patternName(global)}")
                } else {
                    AudioEngine.seqSelect(global, immediate = !app.seqPlaying)
                    flash("PATTERN ${patternName(global)}" + if (app.seqPlaying) " NEXT" else "")
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
                val step = app.stepBar * STEPS_PER_BAR + global % 16
                AudioEngine.seqToggleStep(app.seqPattern, app.selected, step * STEP_TICKS, STEP_TICKS, Settings.stepVelocity)
                // мгновенный отклик: опрос подтвердит через ~100 мс
                app.stepMask = app.stepMask xor (1L shl step)
            }
        }
    }

    fun padUp(global: Int) {
        pressedMask.longValue = pressedMask.longValue and (1L shl global).inv()
        when (route[global]) {
            1 -> AudioEngine.noteOff(global)
            2 -> PadCapture.end()
        }
        route[global] = 0
    }

    // ---- ручки выбранного пада ----
    val pad = app.selected
    val ui = app.pads[pad]
    fun setPad(id: Int, label: String, text: String, v: Float, f: (PadUi) -> PadUi) {
        app.setParam(pad, id, v, f)
        app.flash("$label $text", 900)
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
                Lcd(
                    app = app,
                    recording = holdPad.intValue >= 0,
                    armedLabel = when {
                        cap.arming -> "ARMING"
                        cap.armed -> if (cap.source == CaptureSource.INTERNAL) "ARM:INT" else "ARM:MIC"
                        else -> null
                    },
                    recLevel = { recLevel.floatValue },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )

                // --- софт-клавиши ---
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SoftKey(1, "PAD", Modifier.weight(1f), active = app.mode == Mode.PAD) {
                        app.mode = Mode.PAD; app.stepView = false; app.soundPick = false; app.eraseLatch = false
                    }
                    SoftKey(2, "SEQ", Modifier.weight(1f), active = app.mode == Mode.SEQ && !app.stepView) {
                        app.mode = Mode.SEQ; app.stepView = false; app.soundPick = false; app.eraseLatch = false
                    }
                    SoftKey(3, if (app.stepView) "SOUND" else "STEP", Modifier.weight(1f), active = app.stepView && app.soundPick) {
                        if (app.stepView) {
                            app.soundPick = !app.soundPick
                            flash(if (app.soundPick) "TOUCH A PAD: SOUND" else "STEP INPUT")
                        } else {
                            app.mode = Mode.SEQ; app.stepView = true; app.eraseLatch = false
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
                        val v = n.coerceAtMost(app.pads[pad].end - 0.01f).coerceAtLeast(0f)
                        setPad(PadParam.START, "START", "%.1f%%".format(v * 100), v) { it.copy(start = v) }
                    }, diameter = 34.dp, brass = true, defaultValue = 0f)
                    Knob("END", ui.end, { n ->
                        val v = n.coerceAtLeast(app.pads[pad].start + 0.01f).coerceAtMost(1f)
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
                    }
                    HwButton("REC", Modifier.weight(1f), led = app.seqRecording || app.seqCountIn, ledColor = Skin.LedRed, glyph = Glyph.REC, height = 28.dp) {
                        if (app.seqRecording || app.seqCountIn) {
                            AudioEngine.seqSetRecording(false)
                            if (app.seqCountIn) AudioEngine.seqStop()
                        } else {
                            // запись нот делается с падов, играющих звуки
                            if (app.mode == Mode.SEQ && !app.stepView) { app.mode = Mode.PAD; flash("PAD MODE: RECORD") }
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
                    HwButton("PAGE", Modifier.weight(1f), enabled = app.stepView, height = 28.dp) {
                        app.stepBar = (app.stepBar + 1) % app.patternBars.coerceIn(1, MAX_UI_BARS)
                        flash("PAGE ${app.stepBar + 1}/${app.patternBars}")
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
                    HwButton("COUNT", Modifier.weight(1f), led = Settings.countInBars != 0, height = 28.dp) {
                        val next = COUNT_IN_OPTIONS[(COUNT_IN_OPTIONS.indexOf(Settings.countInBars) + 1) % COUNT_IN_OPTIONS.size]
                        Settings.countInBars = next
                        AudioEngine.seqSetCountIn(next)
                        flash(if (next == 0) "COUNT-IN OFF" else "COUNT-IN $next BAR")
                    }
                    HwButton("LEN", Modifier.weight(1f), height = 28.dp) {
                        val bars = app.patternBars % MAX_UI_BARS + 1
                        AudioEngine.seqSetLength(app.seqPattern, bars)
                        app.patternBars = bars
                        if (app.stepBar >= bars) app.stepBar = 0
                        flash("LENGTH $bars BAR")
                    }
                    var clearArmedAt by remember { mutableLongStateOf(0L) }
                    HwButton("CLEAR", Modifier.weight(1f), led = clearArmedAt != 0L, ledColor = Skin.LedRed, height = 28.dp) {
                        val now = System.currentTimeMillis()
                        if (clearArmedAt != 0L && now - clearArmedAt < 2500) {
                            AudioEngine.seqClearPattern(app.seqPattern)
                            clearArmedAt = 0L
                            flash("CLEARED ${patternName(app.seqPattern)}")
                        } else {
                            clearArmedAt = now
                            app.flash("CLEAR ${patternName(app.seqPattern)}? PRESS AGAIN", 2500)
                        }
                    }
                }

                // --- ряд C: ввод семплов ---
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HwButton("ERASE", Modifier.weight(1f), led = app.eraseLatch, ledColor = Skin.LedRed, height = 28.dp) {
                        if (app.mode == Mode.SEQ && app.stepView) {
                            AudioEngine.seqErasePad(app.seqPattern, app.selected)
                            app.stepMask = 0L
                            flash("ERASED ${padName(app.selected)}")
                        } else {
                            app.eraseLatch = !app.eraseLatch
                            if (app.eraseLatch) flash(if (app.mode == Mode.PAD) "TOUCH PAD TO ERASE" else "TOUCH PATTERN TO CLEAR")
                        }
                    }
                    HwButton(
                        "SAMPLE", Modifier.weight(1f),
                        led = cap.armed || cap.arming, ledColor = if (cap.armed) Skin.LedRed else Skin.LedAmber,
                        height = 28.dp,
                    ) {
                        if (PadCapture.isBusy) {
                            actions.disarmPadCapture()
                            flash("SAMPLE OFF")
                        } else {
                            app.mode = Mode.PAD; app.stepView = false
                            actions.armPadCapture()
                            flash("HOLD A PAD TO RECORD")
                        }
                    }
                    HwButton(
                        if (app.sampleSource == CaptureSource.MIC) "SRC:MIC" else "SRC:INT", Modifier.weight(1f),
                        enabled = !PadCapture.isBusy, height = 28.dp,
                    ) {
                        app.sampleSource = if (app.sampleSource == CaptureSource.MIC) CaptureSource.INTERNAL else CaptureSource.MIC
                        flash(if (app.sampleSource == CaptureSource.MIC) "SOURCE: MIC" else "SOURCE: INTERNAL AUDIO")
                    }
                    HwButton("T-HEAD", Modifier.weight(1f), led = Settings.trimStart, height = 28.dp) {
                        Settings.trimStart = !Settings.trimStart
                        flash("TRIM HEAD " + if (Settings.trimStart) "ON" else "OFF")
                    }
                    HwButton("T-TAIL", Modifier.weight(1f), led = Settings.trimEnd, height = 28.dp) {
                        Settings.trimEnd = !Settings.trimEnd
                        flash("TRIM TAIL " + if (Settings.trimEnd) "ON" else "OFF")
                    }
                }
            }

            // ================= банки =================
            Column(
                Modifier.width(34.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            ) {
                for (b in 3 downTo 0) {
                    HwButton(
                        ('A' + b).toString(), Modifier.fillMaxWidth(), led = app.bank == b,
                        height = 44.dp,
                    ) { app.bank = b }
                }
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
                        inPatterns -> for (i in 0 until 64) if (app.patternCounts[i] > 0) m = m or (1L shl i)
                        inStep -> m = ((app.stepMask shr (app.stepBar * STEPS_PER_BAR)) and 0xFFFFL) shl (app.bank * 16)
                        else -> for (i in 0 until 64) if (PadLibrary.infos[i] != null) m = m or (1L shl i)
                    }
                    m
                },
                cursor = {
                    when {
                        inPatterns -> app.seqPattern
                        inStep && app.seqPlaying && app.seqPos / BAR_TICKS_PAGE == app.stepBar ->
                            app.bank * 16 + (app.seqPos % BAR_TICKS_PAGE) / STEP_TICKS
                        else -> -1
                    }
                },
                recPad = { holdPad.intValue },
                onPadDown = ::padDown,
                onPadUp = ::padUp,
                modifier = Modifier.fillMaxHeight().aspectRatio(1f),
            )

            // ================= правая колонка =================
            Column(
                Modifier.width(138.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().height(42.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painterResource(R.drawable.logo_salych), "SALYCH MUSIC",
                        Modifier.height(42.dp).aspectRatio(613f / 814f),
                        contentScale = ContentScale.Fit,
                    )
                    Image(
                        painterResource(R.drawable.logo_is1), "IS-1",
                        Modifier.width(78.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
                Row(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Fader("MAIN", Settings.master / 1.2f, { n ->
                        val v = n * 1.2f
                        Settings.master = v
                        AudioEngine.setMasterVolume(v)
                        app.flash("MASTER %.0f%%".format(v * 100), 900)
                    }, Modifier.weight(1f), trackWidth = 22.dp, defaultValue = 0.8f / 1.2f)
                    Fader("TEMPO", norm(Settings.bpm, 30f, 300f), { n ->
                        val v = (denorm(n, 30f, 300f) * 2f).roundToInt() / 2f
                        Settings.bpm = v
                        AudioEngine.seqSetBpm(v)
                        app.flash("TEMPO %.1f".format(v), 900)
                    }, Modifier.weight(1f), trackWidth = 22.dp, defaultValue = norm(120f, 30f, 300f))
                    Fader("CLICK", Settings.metroLevel, { n ->
                        Settings.metroLevel = n
                        AudioEngine.seqSetMetronome(Settings.metroMode, n)
                        app.flash("CLICK %.0f%%".format(n * 100), 900)
                    }, Modifier.weight(1f), trackWidth = 22.dp, defaultValue = 0.7f)
                    Fader("GAIN", norm(Settings.recGainDb, -12f, 24f), { n ->
                        val v = denorm(n, -12f, 24f).roundToInt().toFloat()
                        Settings.recGainDb = v
                        app.flash("REC GAIN %+.0f DB".format(v), 900)
                    }, Modifier.weight(1f), trackWidth = 22.dp, defaultValue = norm(0f, -12f, 24f))
                    Fader("TRIM", norm(Settings.trimDb, -72f, -20f), { n ->
                        val v = denorm(n, -72f, -20f).roundToInt().toFloat()
                        Settings.trimDb = v
                        app.flash("TRIM THRESH %.0f DB".format(v), 900)
                    }, Modifier.weight(1f), trackWidth = 22.dp, defaultValue = norm(-50f, -72f, -20f))
                }
                PeakMeter(level = { peak.floatValue }, modifier = Modifier.fillMaxWidth().height(7.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HwButton("FULL", Modifier.weight(1f), led = Settings.fullLevel, height = 24.dp) {
                        Settings.fullLevel = !Settings.fullLevel
                        flash("FULL LEVEL " + if (Settings.fullLevel) "ON" else "OFF")
                    }
                    HwButton("HAPT", Modifier.weight(1f), led = Settings.haptic, height = 24.dp) {
                        Settings.haptic = !Settings.haptic
                        flash("HAPTIC " + if (Settings.haptic) "ON" else "OFF")
                    }
                    HwButton("SETUP", Modifier.weight(1f), led = Settings.linkEnabled, height = 24.dp) {
                        app.setupOpen = true
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

        // ---- настройки (Ableton Link, latency) ----
        if (app.setupOpen) {
            SetupScreen(app, Modifier.fillMaxSize())
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
                    Chip("ЗАКРЫТЬ", active = true, compact = true) {
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
