package com.example.mpcpad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mpcpad.Settings
import com.example.mpcpad.audio.PPQ
import com.example.mpcpad.audio.PlayMode
import com.example.mpcpad.sample.PadLibrary

/** LCD: трек/паттерн и состояние сверху, waveform выбранного пэда, строка-подсказка снизу. */
@Composable
fun Lcd(
    app: AppViewModel,
    recording: Boolean,
    armedLabel: String?,
    recLevel: () -> Float,
    modifier: Modifier = Modifier,
) {
    val pad = app.selected
    val info = PadLibrary.infos[pad]
    val stepEdit = app.mode == Mode.SEQ && app.stepView && app.heldStep >= 0
    // Текущее состояние семпла: параметры пада или удерживаемого шага (P-LOCK).
    val ui = (if (stepEdit) app.heldStepUi else null) ?: app.pads[pad]
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier
            .background(Skin.LcdFrame, shape)
            .padding(3.dp)
            .background(Brush.verticalGradient(listOf(Skin.LcdBgTop, Skin.LcdBgBottom)), RoundedCornerShape(4.dp))
            .border(1.dp, Color(0x55000000), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val head = if (app.songRec) {
                "SONG REC ${app.songDraft.size}"
            } else if (app.mode == Mode.SEQ && app.songIndex >= 0 && app.song.isNotEmpty()) {
                "SONG ${app.songIndex + 1}/${app.song.size} " + patternName(app.seqPattern)
            } else if (app.mode == Mode.SEQ) {
                val queued = if (app.seqQueued >= 0) ">" + patternName(app.seqQueued) else ""
                "PTN " + patternName(app.seqPattern) + queued
            } else {
                String.format("TRACK %02d", pad % 16 + 1) + " " + ('A' + pad / 16)
            }
            Text(head, style = Skin.Lcd, maxLines = 1)
            val state = when {
                recording -> "● REC"
                armedLabel != null -> armedLabel
                app.seqCountIn -> "COUNT"
                app.seqLinkWaiting -> "LINK…"
                app.seqRecording -> "● RECSEQ"
                app.seqPlaying -> "▶"
                else -> ""
            }
            // Долгое нажатие на темп — ввести BPM цифрами.
            Text(
                String.format("$state  %.1f", Settings.bpm),
                style = Skin.Lcd,
                maxLines = 1,
                modifier = Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { app.tempoEntry = true }) },
            )
        }
        Canvas(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0x14000000))
        ) {
            val w = size.width
            val h = size.height
            val mid = h / 2f
            drawLine(Skin.LcdInkDim, Offset(0f, mid), Offset(w, mid), strokeWidth = 1f)
            val peaks = info?.peaks
            if (peaks != null) {
                val bins = peaks.size / 2
                val bw = w / bins
                for (i in 0 until bins) {
                    val x = (i + 0.5f) * bw
                    val top = mid - peaks[i * 2 + 1].coerceIn(0f, 1f) * mid
                    val bottom = mid - peaks[i * 2].coerceIn(-1f, 0f) * mid
                    drawLine(Skin.LcdInk, Offset(x, top), Offset(x, maxOf(bottom, top + 1f)), strokeWidth = maxOf(bw, 1f))
                }
            }
            // Регион воспроизведения START..END: вне его — затемнение.
            val sx = ui.start * w
            val ex = ui.end * w
            if (sx > 0f) drawRect(Skin.LcdInkDim, topLeft = Offset(0f, 0f), size = Size(sx, h))
            if (ex < w) drawRect(Skin.LcdInkDim, topLeft = Offset(ex, 0f), size = Size(w - ex, h))
            if (app.mode == Mode.SEQ && app.seqPlaying) {
                val total = (app.patternBars * PPQ * 4).toFloat()
                val x = app.seqPos / total * w
                drawLine(Skin.LcdInk, Offset(x, 0f), Offset(x, h), strokeWidth = 2f)
            }
            if (recording) {
                val l = recLevel().coerceIn(0f, 1f)
                drawRect(Skin.LcdInk, topLeft = Offset(0f, h - 4.dp.toPx()), size = Size(w * l, 4.dp.toPx()))
            }
        }
        val small = Skin.Lcd.copy(fontSize = 9.sp)
        val params = buildString {
            if (stepEdit) append("ST${app.heldStep + 1} ")
            append(String.format("V%.0f ", ui.volume * 100))
            append(
                when {
                    kotlin.math.abs(ui.pan) < 0.01f -> "C"
                    ui.pan < 0 -> String.format("L%.0f", -ui.pan * 100)
                    else -> String.format("R%.0f", ui.pan * 100)
                }
            )
            append(String.format(" P%+.0f A%.0f R%.0f ", ui.pitch, ui.attackMs, ui.releaseMs))
            append(
                when (ui.mode) {
                    PlayMode.GATE -> "GATE"
                    PlayMode.LOOP -> if (ui.loopDiv >= 0) "LOOP " + LOOP_DIVS[ui.loopDiv].first else "LOOP"
                    else -> "ONE"
                }
            )
            if (ui.choke > 0) append(" CHK${ui.choke}")
        }
        Text(params, Modifier.fillMaxWidth(), style = small, maxLines = 1, overflow = TextOverflow.Clip)
        val flags = buildString {
            if (Settings.trimStart || Settings.trimEnd) {
                append(" T:")
                append(listOfNotNull(if (Settings.trimStart) "H" else null, if (Settings.trimEnd) "T" else null).joinToString("+"))
            }
            if (Settings.linkEnabled) append(" LNK")
            if (app.fxAuto != null) append(" FXA")
        }
        val bottom = app.readout
            ?: if (app.copySource >= 0) "COPY ${patternName(app.copySource)} → PAD?"
            else (if (info != null) info.name.take(10) else "---") + flags
        Text(bottom, Modifier.fillMaxWidth(), style = Skin.Lcd, maxLines = 1, overflow = TextOverflow.Clip)
    }
}
