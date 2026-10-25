// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mpcpad.audio.Kaoss
import kotlin.math.sqrt

private val KpBg = Color(0xFF07080C)
private val KpLedOff = Color(0xFF1A1D2A)
private val KpLed = Color(0xFF29B6FF)
private val KpHot = Color(0xFFE040FB)
private val KpText = Color(0xFFB3E5FC)

/**
 * Экран KAOSS: сенсорная X/Y-площадка с сеткой «светодиодов», как на KAOSS PAD 3.
 * Касание включает эффект, движение пальца меняет X/Y. [shownX]/[shownY]/[shownTouch] —
 * что сейчас реально управляет эффектом (палец или записанное движение).
 */
@Composable
fun KaossPad(
    app: AppViewModel,
    shownX: () -> Float,
    shownY: () -> Float,
    shownTouch: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val prog = Kaoss.PROGRAMS[app.shownProgram.coerceIn(0, Kaoss.PROGRAMS.size - 1)]
    val bank = app.shownProgram / Kaoss.PER_BANK
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .clip(shape)
            .background(KpBg)
            .border(2.dp, Color(0xFF2B2F3A), shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun set(o: Offset) {
                        app.kx = (o.x / size.width).coerceIn(0f, 1f)
                        app.ky = 1f - (o.y / size.height).coerceIn(0f, 1f)
                    }
                    set(down.position)
                    app.kTouch = true
                    down.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        set(ch.position)
                        ch.consume()
                    }
                    app.kTouch = false
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cols = 10
            val rows = 8
            val cw = size.width / cols
            val rh = size.height / rows
            val px = shownX() * size.width
            val py = (1f - shownY()) * size.height
            val on = shownTouch()
            val reach = maxOf(size.width, size.height) * 0.28f
            for (c in 0 until cols) for (r in 0 until rows) {
                val cx = (c + 0.5f) * cw
                val cy = (r + 0.5f) * rh
                val dx = cx - px
                val dy = cy - py
                val k = if (on) (1f - sqrt(dx * dx + dy * dy) / reach).coerceIn(0f, 1f) else 0f
                val col = if (k > 0.75f) KpHot else KpLed
                drawCircle(KpLedOff, radius = minOf(cw, rh) * 0.22f, center = Offset(cx, cy))
                if (k > 0f) drawCircle(col.copy(alpha = k), radius = minOf(cw, rh) * (0.22f + 0.12f * k), center = Offset(cx, cy))
            }
            // перекрестие и «светящаяся» точка
            val lineCol = if (on) KpHot.copy(alpha = 0.5f) else KpLed.copy(alpha = 0.18f)
            drawLine(lineCol, Offset(px, 0f), Offset(px, size.height), strokeWidth = 1.5f)
            drawLine(lineCol, Offset(0f, py), Offset(size.width, py), strokeWidth = 1.5f)
            if (on) {
                drawCircle(
                    Brush.radialGradient(listOf(KpHot.copy(alpha = 0.9f), Color.Transparent), Offset(px, py), reach * 0.45f),
                    radius = reach * 0.45f,
                    center = Offset(px, py),
                )
                drawCircle(Color.White, radius = 5f, center = Offset(px, py))
            }
        }
        val mono = FontFamily.Monospace
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "${bank + 1}·${Kaoss.BANKS[bank]}  ${app.shownProgram % Kaoss.PER_BANK + 1}/${Kaoss.PER_BANK}  ${prog.code}",
                    color = KpLed, fontSize = 10.sp, fontFamily = mono, modifier = Modifier.weight(1f),
                )
                val motion = when {
                    app.fxRecording -> "● FX REC ${(app.fxRecProgress * 100).toInt()}%"
                    app.motionRecording >= 0 -> "● REC MOTION ${'A' + app.motionRecording}"
                    app.motionPlaying >= 0 -> "▶ MOTION ${'A' + app.motionPlaying}"
                    app.fxAuto != null -> "▶ FX AUTO"
                    else -> ""
                }
                Text(motion, color = KpHot, fontSize = 10.sp, fontFamily = mono)
            }
            Text(prog.name, color = KpText, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = mono)
        }
        Text(
            "X ${prog.x}  ${(shownX() * 100).toInt()}   Y ${prog.y}  ${(shownY() * 100).toInt()}",
            color = KpText.copy(alpha = 0.75f),
            fontSize = 9.sp,
            fontFamily = mono,
            maxLines = 1,
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
