// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mpcpad.audio.PadParam
import com.example.mpcpad.capture.CaptureSource
import com.example.mpcpad.capture.CaptureStatus
import com.example.mpcpad.sample.SampleViewModel
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt

private val WaveBg = Color(0xFF111113)
private val WaveOutside = Color(0xFF55555C)
private val MarkColor = Color(0xFF4FC3F7)

/**
 * Редактор семплов: запись (микрофон / внутренний звук), waveform с выделением и маркерами,
 * обработка, нарезка (chop) и назначение на пэды.
 */
@Composable
fun SampleBody(
    app: AppViewModel,
    actions: SampleActions,
    recLevel: () -> Float,
    modifier: Modifier = Modifier,
) {
    val vm: SampleViewModel = viewModel()
    val cap by vm.capture.collectAsState()
    val clip = vm.clip
    val recording = cap.status != CaptureStatus.IDLE
    val canEdit = clip != null && !vm.busy && !recording
    var recentOpen by remember { mutableStateOf(false) }

    Column(
        modifier
            .background(Panel, RoundedCornerShape(12.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Запись: источник, порог, отсчёт, уровень.
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Chip(if (recording) "■ STOP" else "● REC", enabled = !vm.busy, danger = true, compact = true) {
                if (recording) actions.stopRecording() else actions.startRecording()
            }
            Chip("MIC", active = vm.source == CaptureSource.MIC, enabled = !recording, compact = true) {
                vm.source = CaptureSource.MIC
            }
            Chip("INTERNAL", active = vm.source == CaptureSource.INTERNAL, enabled = !recording, compact = true) {
                vm.source = CaptureSource.INTERNAL
            }
            if (vm.source == CaptureSource.MIC) {
                Chip(if (vm.micStereo) "STEREO" else "MONO", enabled = !recording, compact = true) {
                    vm.micStereo = !vm.micStereo
                }
            }
            Chip("THRESHOLD", active = vm.thresholdOn, enabled = !recording, compact = true) {
                vm.thresholdOn = !vm.thresholdOn
            }
            if (vm.thresholdOn) {
                Slider(
                    value = vm.thresholdDb,
                    onValueChange = { vm.thresholdDb = it },
                    valueRange = -60f..-6f,
                    enabled = !recording,
                    modifier = Modifier.width(90.dp),
                )
                Text(String.format("%.0f dB", vm.thresholdDb), color = TextMain, fontSize = 11.sp)
            }
            Chip("COUNT 3 s", active = vm.countIn, enabled = !recording, compact = true) {
                vm.countIn = !vm.countIn
            }
            PeakMeter(recLevel, Modifier.width(70.dp).height(10.dp))
        }

        val silentWarning = vm.source == CaptureSource.INTERNAL &&
            (cap.status == CaptureStatus.ARMED || cap.status == CaptureStatus.RECORDING) &&
            cap.silentMs > 3000
        val (statusText, statusColor) = when {
            silentWarning -> "${cap.silentMs / 1000} s of silence: nothing is playing in the other app, or it " +
                "blocks audio capture (DRM / app setting)" to RecRed
            cap.status == CaptureStatus.COUNT_IN ->
                "Recording starts in ${ceil(cap.countInLeftMs / 1000.0).toInt()}…" to TextMain
            cap.status == CaptureStatus.ARMED ->
                String.format("Waiting for signal above %.0f dB…", vm.thresholdDb) to TextMain
            cap.status == CaptureStatus.RECORDING ->
                String.format("● REC %.1f s", cap.elapsedMs / 1000.0) to RecRed
            vm.message != null -> vm.message!! to TextMain
            vm.source == CaptureSource.INTERNAL ->
                "Start audio in another app (e.g. YouTube), then press REC. " +
                    "Stop here or from the notification." to TextDim
            else -> "Tip: use headphones so the speaker does not bleed into the mic." to TextDim
        }
        Text(statusText, color = statusColor, fontSize = 11.sp, maxLines = 2)

        if (clip != null) {
            val total = clip.durationMs / 1000.0
            Text(
                String.format(
                    "%.2f s · %d Hz · %s · selection %.2f–%.2f s",
                    total,
                    clip.sampleRate,
                    if (clip.channels == 1) "mono" else "stereo",
                    vm.selStart * total,
                    vm.selEnd * total,
                ),
                color = TextDim,
                fontSize = 11.sp,
            )
        }

        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 70.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WaveformView(vm, Modifier.weight(1f).fillMaxSize())
            // Высота выбранного пада: PITCH — полутоны, DETUNE — центы между ними.
            // Отпустил ручку — сразу слышно выделение с новой высотой.
            val pu = app.pads[app.selected]
            vm.previewRate = 2f.pow((pu.pitch + pu.detune / 100f) / 12f)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Knob("PITCH", (pu.pitch + 12f) / 24f, { n ->
                        val v = (n * 24f - 12f).roundToInt().toFloat()
                        app.setParam(app.selected, PadParam.PITCH, v) { it.copy(pitch = v) }
                    }, diameter = 34.dp, defaultValue = 0.5f, onTouch = { down -> if (!down) vm.previewSelection() })
                    Text("%+.0f ST".format(pu.pitch), color = TextMain, fontSize = 10.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Knob("DETUNE", (pu.detune + 50f) / 100f, { n ->
                        val v = (n * 100f - 50f).roundToInt().toFloat()
                        app.setParam(app.selected, PadParam.DETUNE, v) { it.copy(detune = v) }
                    }, diameter = 34.dp, defaultValue = 0.5f, onTouch = { down -> if (!down) vm.previewSelection() })
                    Text("%+.0f CT".format(pu.detune), color = TextMain, fontSize = 10.sp)
                }
            }
        }

        // Обработка выделения.
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Chip("▶ PREVIEW", enabled = canEdit, compact = true) { vm.previewSelection() }
            Chip("■", enabled = clip != null, compact = true) { vm.stopPreview() }
            Chip("TRIM", enabled = canEdit, compact = true) { vm.trimToSelection() }
            Chip("NORMALIZE", enabled = canEdit, compact = true) { vm.normalize() }
            Chip("FADE IN", enabled = canEdit, compact = true) { vm.fadeIn() }
            Chip("FADE OUT", enabled = canEdit, compact = true) { vm.fadeOut() }
            Chip("REVERSE", enabled = canEdit, compact = true) { vm.reverse() }
            Chip("SILENCE", enabled = canEdit, compact = true) { vm.trimSilence() }
            Chip("UNDO", enabled = vm.canUndo && !vm.busy && !recording, compact = true) { vm.undo() }
        }

        // Нарезка и назначение.
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Chip("MARKERS +", active = vm.chopEdit, enabled = clip != null, compact = true) {
                vm.chopEdit = !vm.chopEdit
            }
            Chip("AUTO", enabled = canEdit, compact = true) { vm.autoChop() }
            for (n in listOf(4, 8, 16)) {
                Chip("÷$n", enabled = canEdit, compact = true) { vm.equalChop(n) }
            }
            Chip("RESET", enabled = vm.marks.isNotEmpty(), compact = true) { vm.clearMarks() }
            Chip(
                "SLICES (${vm.marks.size + 1}) → ${padName(app.bank * 16)}…",
                enabled = canEdit && vm.marks.isNotEmpty(),
                compact = true,
            ) {
                vm.assignChops(app.bank * 16) { app.resetRegion(it) }
            }
            Chip("→ PAD ${padName(app.selected)}", enabled = canEdit, compact = true) {
                vm.assignToPad(app.selected) { app.resetRegion(it) }
            }
            Chip("IMPORT", enabled = !recording && !vm.busy, compact = true) { actions.importFile() }
            Box {
                Chip("RECORDINGS (${vm.recordings.size})", enabled = !recording, compact = true) {
                    vm.refreshRecordings()
                    recentOpen = true
                }
                DropdownMenu(expanded = recentOpen, onDismissRequest = { recentOpen = false }) {
                    if (vm.recordings.isEmpty()) {
                        DropdownMenuItem(text = { Text("No recordings yet") }, onClick = { recentOpen = false })
                    }
                    for (file in vm.recordings.take(15)) {
                        DropdownMenuItem(
                            text = {
                                Text(file.name.removePrefix("rec_").removeSuffix(".wav") + " · " + file.length() / 1024 + " KB")
                            },
                            onClick = {
                                recentOpen = false
                                vm.loadFile(file)
                            },
                        )
                    }
                }
            }
            Chip("CLEAR", enabled = clip != null && !recording, compact = true) { vm.clear() }
        }
    }
}

/** Waveform: тап в режиме маркеров ставит/снимает маркер, протяжка двигает ближайшую границу выделения. */
@Composable
private fun WaveformView(vm: SampleViewModel, modifier: Modifier = Modifier) {
    Box(modifier.background(WaveBg, RoundedCornerShape(8.dp))) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { offset ->
                        if (vm.clip != null && vm.chopEdit) {
                            val f = offset.x / size.width
                            if (!vm.removeNearestMark(f, 0.02f)) vm.addMark(f)
                        }
                    })
                }
                .pointerInput(Unit) {
                    var which = 0
                    detectDragGestures(
                        onDragStart = { offset ->
                            val f = offset.x / size.width
                            which = if (abs(f - vm.selStart) <= abs(f - vm.selEnd)) 1 else 2
                        },
                        // отпустил границу выделения — сразу слышно, что осталось
                        onDragEnd = { if (vm.clip != null) vm.previewSelection() },
                        onDrag = { change, _ ->
                            change.consume()
                            if (vm.clip != null) {
                                val f = (change.position.x / size.width).coerceIn(0f, 1f)
                                if (which == 1) vm.setSelection(f, vm.selEnd) else vm.setSelection(vm.selStart, f)
                            }
                        },
                    )
                }
        ) {
            val w = size.width
            val h = size.height
            val mid = h / 2f
            drawLine(Color(0x33FFFFFF), Offset(0f, mid), Offset(w, mid), strokeWidth = 1.dp.toPx())

            val peaks = vm.peaks
            val selS = vm.selStart
            val selE = vm.selEnd
            if (peaks != null && vm.clip != null) {
                val bins = peaks.size / 2
                val bw = w / bins
                for (i in 0 until bins) {
                    val x = (i + 0.5f) * bw
                    val frac = (i + 0.5f) / bins
                    val top = mid - peaks[i * 2 + 1].coerceIn(0f, 1f) * mid
                    val bottom = mid - peaks[i * 2].coerceIn(-1f, 0f) * mid
                    val color = if (frac in selS..selE) PadLit else WaveOutside
                    drawLine(color, Offset(x, top), Offset(x, maxOf(bottom, top + 1f)), strokeWidth = maxOf(bw, 1f))
                }

                val sx = selS * w
                val ex = selE * w
                drawRect(Color(0x66000000), topLeft = Offset(0f, 0f), size = Size(sx, h))
                drawRect(Color(0x66000000), topLeft = Offset(ex, 0f), size = Size(w - ex, h))
                drawLine(PadSelected, Offset(sx, 0f), Offset(sx, h), strokeWidth = 2.dp.toPx())
                drawLine(PadSelected, Offset(ex, 0f), Offset(ex, h), strokeWidth = 2.dp.toPx())
                val handle = Size(12.dp.toPx(), 16.dp.toPx())
                drawRoundRect(PadSelected, topLeft = Offset(sx - handle.width / 2f, 0f), size = handle, cornerRadius = CornerRadius(3.dp.toPx()))
                drawRoundRect(PadSelected, topLeft = Offset(ex - handle.width / 2f, 0f), size = handle, cornerRadius = CornerRadius(3.dp.toPx()))
                for (m in vm.marks) {
                    drawLine(MarkColor, Offset(m * w, 0f), Offset(m * w, h), strokeWidth = 1.5.dp.toPx())
                }
            }
        }
        if (vm.clip == null) {
            Text(
                "No sample: press REC, IMPORT or pick one from RECORDINGS",
                color = TextDim,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(12.dp),
            )
        }
    }
}
