// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mpcpad.audio.SampleOps

/**
 * ISL-1: части петли на экране (вместо LCD). Петля поделена на 4 равные части — касание части
 * включает/выключает её; играют только включённые, подряд. DONE — обратно к экрану.
 */
@Composable
fun LoopParts(app: AppViewModel, modifier: Modifier = Modifier) {
    val pad = app.partsEditPad
    val orig = app.loopOriginal(pad)
    val mask = app.loopParts[pad] ?: 0b1111
    val peaks = remember(orig) { orig?.let { SampleOps.computePeaks(it.data, it.channels, 160) } }
    val measurer = rememberTextMeasurer()
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Skin.LcdBgTop, Skin.LcdBgBottom)), shape)
            .border(3.dp, Skin.LcdFrame, shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "LOOP ${padName(pad)} · PARTS ${Integer.bitCount(mask)}/4",
                    style = Skin.Lcd.copy(fontSize = 15.sp),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "DONE",
                    style = Skin.Lcd.copy(fontSize = 15.sp),
                    modifier = Modifier
                        .border(1.5.dp, Skin.LcdInk, RoundedCornerShape(4.dp))
                        .pointerInput(Unit) { detectTapGestures { app.partsEditPad = -1 } }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Canvas(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .pointerInput(pad) {
                        detectTapGestures { o ->
                            val part = (o.x / size.width * 4f).toInt().coerceIn(0, 3)
                            app.toggleLoopPart(pad, part)
                        }
                    },
            ) {
                val w = size.width
                val h = size.height
                val pw = w / 4f
                val mid = h * 0.55f
                val p = peaks
                for (i in 0 until 4) {
                    val on = mask and (1 shl i) != 0
                    val x0 = i * pw
                    if (!on) drawRect(Skin.LcdInk.copy(alpha = 0.18f), Offset(x0, 0f), Size(pw, h))
                    if (i > 0) drawLine(Skin.LcdInk, Offset(x0, 0f), Offset(x0, h), strokeWidth = 2.dp.toPx())
                    val label = measurer.measure("${i + 1}" + if (on) "" else " OFF", Skin.Lcd.copy(fontSize = 13.sp))
                    drawText(label, topLeft = Offset(x0 + 6.dp.toPx(), 2.dp.toPx()))
                }
                if (p != null) {
                    val bins = p.size / 2
                    val bw = w / bins
                    for (b in 0 until bins) {
                        val x = (b + 0.5f) * bw
                        val part = (x / pw).toInt().coerceIn(0, 3)
                        val on = mask and (1 shl part) != 0
                        val top = mid - p[b * 2 + 1].coerceIn(0f, 1f) * mid * 0.8f
                        val bottom = mid - p[b * 2].coerceIn(-1f, 0f) * mid * 0.8f
                        drawLine(
                            if (on) Skin.LcdInk else Skin.LcdInkDim,
                            Offset(x, top), Offset(x, maxOf(bottom, top + 1f)),
                            strokeWidth = maxOf(bw * 0.8f, 1f),
                        )
                    }
                }
            }
        }
    }
}
