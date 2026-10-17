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
import com.example.mpcpad.capture.CaptureSource
import com.example.mpcpad.capture.CaptureStatus
import com.example.mpcpad.sample.SampleViewModel
import kotlin.math.abs
import kotlin.math.ceil

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
            Chip("Микрофон", active = vm.source == CaptureSource.MIC, enabled = !recording, compact = true) {
                vm.source = CaptureSource.MIC
            }
            Chip("Внутр. звук", active = vm.source == CaptureSource.INTERNAL, enabled = !recording, compact = true) {
                vm.source = CaptureSource.INTERNAL
            }
            if (vm.source == CaptureSource.MIC) {
                Chip(if (vm.micStereo) "Стерео" else "Моно", enabled = !recording, compact = true) {
                    vm.micStereo = !vm.micStereo
                }
            }
            Chip("Порог", active = vm.thresholdOn, enabled = !recording, compact = true) {
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
                Text(String.format("%.0f дБ", vm.thresholdDb), color = TextMain, fontSize = 11.sp)
            }
            Chip("Отсчёт 3 с", active = vm.countIn, enabled = !recording, compact = true) {
                vm.countIn = !vm.countIn
            }
            PeakMeter(recLevel, Modifier.width(70.dp).height(10.dp))
        }

        val silentWarning = vm.source == CaptureSource.INTERNAL &&
            (cap.status == CaptureStatus.ARMED || cap.status == CaptureStatus.RECORDING) &&
            cap.silentMs > 3000
        val (statusText, statusColor) = when {
            silentWarning -> "Уже ${cap.silentMs / 1000} с тишины: в другом приложении ничего не играет либо оно " +
                "запрещает захват звука (DRM / настройка приложения)" to RecRed
            cap.status == CaptureStatus.COUNT_IN ->
                "Запись начнётся через ${ceil(cap.countInLeftMs / 1000.0).toInt()}…" to TextMain
            cap.status == CaptureStatus.ARMED ->
                String.format("Ждём сигнал громче %.0f дБ…", vm.thresholdDb) to TextMain
            cap.status == CaptureStatus.RECORDING ->
                String.format("● Запись %.1f с", cap.elapsedMs / 1000.0) to RecRed
            vm.message != null -> vm.message!! to TextMain
            vm.source == CaptureSource.INTERNAL ->
                "Запустите звук в другом приложении (например YouTube), затем нажмите REC. " +
                    "Остановить можно здесь или в уведомлении." to TextDim
            else -> "Совет: слушайте в наушниках, чтобы звук динамика не попал в микрофон." to TextDim
        }
        Text(statusText, color = statusColor, fontSize = 11.sp, maxLines = 2)

        if (clip != null) {
            val total = clip.durationMs / 1000.0
            Text(
                String.format(
                    "%.2f с · %d Гц · %s · выделение %.2f–%.2f с",
                    total,
                    clip.sampleRate,
                    if (clip.channels == 1) "моно" else "стерео",
                    vm.selStart * total,
                    vm.selEnd * total,
                ),
                color = TextDim,
                fontSize = 11.sp,
            )
        }

        WaveformView(
            vm,
            Modifier
                .weight(1f)
                .heightIn(min = 70.dp)
                .fillMaxSize(),
        )

        // Обработка выделения.
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Chip("▶ Прослушать", enabled = canEdit, compact = true) { vm.previewSelection() }
            Chip("■", enabled = clip != null, compact = true) { vm.stopPreview() }
            Chip("Обрезать", enabled = canEdit, compact = true) { vm.trimToSelection() }
            Chip("Нормализ.", enabled = canEdit, compact = true) { vm.normalize() }
            Chip("Fade in", enabled = canEdit, compact = true) { vm.fadeIn() }
            Chip("Fade out", enabled = canEdit, compact = true) { vm.fadeOut() }
            Chip("Reverse", enabled = canEdit, compact = true) { vm.reverse() }
            Chip("Тишина", enabled = canEdit, compact = true) { vm.trimSilence() }
            Chip("Undo", enabled = vm.canUndo && !vm.busy && !recording, compact = true) { vm.undo() }
        }

        // Нарезка и назначение.
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Chip("Маркеры +", active = vm.chopEdit, enabled = clip != null, compact = true) {
                vm.chopEdit = !vm.chopEdit
            }
            Chip("Авто", enabled = canEdit, compact = true) { vm.autoChop() }
            for (n in listOf(4, 8, 16)) {
                Chip("÷$n", enabled = canEdit, compact = true) { vm.equalChop(n) }
            }
            Chip("Сброс", enabled = vm.marks.isNotEmpty(), compact = true) { vm.clearMarks() }
            Chip(
                "Срезы (${vm.marks.size + 1}) → ${padName(app.bank * 16)}…",
                enabled = canEdit && vm.marks.isNotEmpty(),
                compact = true,
            ) {
                vm.assignChops(app.bank * 16) { app.resetRegion(it) }
            }
            Chip("→ пад ${padName(app.selected)}", enabled = canEdit, compact = true) {
                vm.assignToPad(app.selected) { app.resetRegion(it) }
            }
            Chip("Импорт", enabled = !recording && !vm.busy, compact = true) { actions.importFile() }
            Box {
                Chip("Записи (${vm.recordings.size})", enabled = !recording, compact = true) {
                    vm.refreshRecordings()
                    recentOpen = true
                }
                DropdownMenu(expanded = recentOpen, onDismissRequest = { recentOpen = false }) {
                    if (vm.recordings.isEmpty()) {
                        DropdownMenuItem(text = { Text("Записей пока нет") }, onClick = { recentOpen = false })
                    }
                    for (file in vm.recordings.take(15)) {
                        DropdownMenuItem(
                            text = {
                                Text(file.name.removePrefix("rec_").removeSuffix(".wav") + " · " + file.length() / 1024 + " КБ")
                            },
                            onClick = {
                                recentOpen = false
                                vm.loadFile(file)
                            },
                        )
                    }
                }
            }
            Chip("Очистить", enabled = clip != null && !recording, compact = true) { vm.clear() }
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
                "Нет семпла: нажмите REC, «Импорт» или выберите из «Записи»",
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
