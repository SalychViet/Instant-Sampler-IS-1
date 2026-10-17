package com.example.mpcpad.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mpcpad.Settings
import com.example.mpcpad.audio.PPQ
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
            val head = if (app.mode == Mode.SEQ) {
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
            Text(String.format("$state  %.1f", Settings.bpm), style = Skin.Lcd, maxLines = 1)
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
        val bottom = app.readout ?: if (info != null) "SAMPLE: " + info.name else "SAMPLE: ---"
        Text(bottom, Modifier.fillMaxWidth(), style = Skin.Lcd, maxLines = 1, overflow = TextOverflow.Clip)
    }
}
