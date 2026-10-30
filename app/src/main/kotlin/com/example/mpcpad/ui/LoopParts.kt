// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mpcpad.audio.AudioEngine
import com.example.mpcpad.audio.PlayMode
import com.example.mpcpad.audio.SampleOps
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Кнопка в окне частей: рамка на LCD, [on] — инверсия. */
@Composable
private fun LcdKey(label: String, on: Boolean = false, onTap: () -> Unit) {
    Text(
        label,
        style = Skin.Lcd.copy(fontSize = 13.sp, color = if (on) Skin.LcdBgTop else Skin.LcdInk),
        maxLines = 1,
        modifier = Modifier
            .background(if (on) Skin.LcdInk else Color.Transparent, RoundedCornerShape(4.dp))
            .border(1.5.dp, Skin.LcdInk, RoundedCornerShape(4.dp))
            .pointerInput(label) { detectTapGestures { onTap() } }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * ISL-1: окно звука вместо LCD (петля или one-shot). Части: звук поделён на 4 части, касание части
 * включает/выключает её, круглые ручки двигают границы. У one-shot — ещё огибающая высоты (PTCH).
 */
@Composable
fun LoopParts(app: AppViewModel, modifier: Modifier = Modifier) {
    val pad = app.partsEditPad
    val orig = app.loopOriginal(pad)
    val mask = app.loopParts[pad] ?: 0b1111
    val peaks = remember(orig) { orig?.let { SampleOps.computePeaks(it.data, it.channels, 160) } }
    val ui = app.pads[pad]
    val oneShot = ui.mode != PlayMode.LOOP
    val showEnv = app.partsShowEnv && oneShot
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Skin.LcdBgTop, Skin.LcdBgBottom)), shape)
            .border(3.dp, Skin.LcdFrame, shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    padName(pad) + if (showEnv) " · P.ENV" else " · ${Integer.bitCount(mask)}/4",
                    style = Skin.Lcd.copy(fontSize = 14.sp),
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (showEnv) {
                    LcdKey("FLAT") { app.resetPitchEnv(pad) }
                    LcdKey("OFF") {
                        app.setPitchEnvOn(pad, false)
                        app.partsShowEnv = false
                    }
                    LcdKey("BACK") { app.partsShowEnv = false }
                } else {
                    LcdKey("RVS", on = ui.reverse) { app.toggleLoopReverse(pad) }
                    if (oneShot) LcdKey("PTCH", on = ui.pEnvOn) {
                        if (!ui.pEnvOn) app.setPitchEnvOn(pad, true)
                        app.partsShowEnv = true
                    }
                    LcdKey("DONE") { app.partsEditPad = -1 }
                }
            }
            if (showEnv) PitchEnvEditor(app, pad, peaks) else PartsEditor(app, pad, mask, peaks)
        }
    }
}

private fun DrawScope.wave(peaks: FloatArray?, shade: (Float) -> Color) {
    val p = peaks ?: return
    val bins = p.size / 2
    val bw = size.width / bins
    val mid = size.height * 0.5f
    for (b in 0 until bins) {
        val x = (b + 0.5f) * bw
        val top = mid - p[b * 2 + 1].coerceIn(0f, 1f) * mid * 0.8f
        val bottom = mid - p[b * 2].coerceIn(-1f, 0f) * mid * 0.8f
        drawLine(shade(x / size.width), Offset(x, top), Offset(x, maxOf(bottom, top + 1f)), strokeWidth = maxOf(bw * 0.8f, 1f))
    }
}

/** Части: касание — вкл/выкл, круглые ручки внизу — двигают границы. */
@Composable
private fun PartsEditor(app: AppViewModel, pad: Int, mask: Int, peaks: FloatArray?) {
    val bounds = app.boundsOf(pad)
    val measurer = rememberTextMeasurer()
    Canvas(
        Modifier
            .fillMaxSize()
            .padding(top = 6.dp)
            .pointerInput(pad) {
                val reach = 18.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat()
                    val b = app.boundsOf(pad)
                    val near = b.indices.minByOrNull { abs(b[it] * w - down.position.x) } ?: 0
                    val drag = abs(b[near] * w - down.position.x) <= reach
                    down.consume()
                    var moved = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        if (drag && ch.position != ch.previousPosition) {
                            moved = true
                            app.movePartBound(pad, near, ch.position.x / w)
                        }
                        ch.consume()
                    }
                    if (drag) {
                        if (moved) app.commitPartBounds(pad)
                    } else {
                        val x = down.position.x / w
                        val cur = app.boundsOf(pad)
                        app.toggleLoopPart(pad, cur.count { it <= x })
                    }
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val edges = listOf(0f) + bounds + 1f
        for (i in 0 until 4) {
            val on = mask and (1 shl i) != 0
            val x0 = edges[i] * w
            if (!on) drawRect(Skin.LcdInk.copy(alpha = 0.18f), Offset(x0, 0f), Size((edges[i + 1] - edges[i]) * w, h))
            drawText(
                measurer.measure("${i + 1}" + if (on) "" else " OFF", Skin.Lcd.copy(fontSize = 12.sp)),
                topLeft = Offset(x0 + 5.dp.toPx(), 1.dp.toPx()),
            )
        }
        wave(peaks) { x ->
            val part = bounds.count { it <= x }
            if (mask and (1 shl part) != 0) Skin.LcdInk else Skin.LcdInkDim
        }
        // границы с круглыми ручками
        val r = 8.dp.toPx()
        for (b in bounds) {
            val x = b * w
            drawLine(Skin.LcdInk, Offset(x, 0f), Offset(x, h), strokeWidth = 2.dp.toPx())
            drawCircle(Skin.LcdInk, r, Offset(x, h - r))
            drawCircle(Skin.LcdBgTop, r * 0.45f, Offset(x, h - r))
        }
    }
}

/** Огибающая высоты поверх волны: 5 точек, ±24 полутона. Отпустили точку — звук проигрывается. */
@Composable
private fun PitchEnvEditor(app: AppViewModel, pad: Int, peaks: FloatArray?) {
    val u = app.pads[pad]
    var active by remember(pad) { mutableIntStateOf(-1) }
    val measurer = rememberTextMeasurer()
    Canvas(
        Modifier
            .fillMaxSize()
            .padding(top = 6.dp)
            .pointerInput(pad) {
                val reach = 30.dp.toPx()
                val edge = 9.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val span = h / 2f - edge
                    val cur = app.pads[pad]
                    val near = (0 until 5).minByOrNull {
                        hypot(cur.pEnvX[it] * w - down.position.x, h / 2f - cur.pEnvY[it] / 24f * span - down.position.y)
                    } ?: 0
                    val dist = hypot(cur.pEnvX[near] * w - down.position.x, h / 2f - cur.pEnvY[near] / 24f * span - down.position.y)
                    down.consume()
                    val grabbed = dist <= reach
                    if (grabbed) active = near
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        if (grabbed) {
                            val semis = ((h / 2f - ch.position.y) / span * 24f).roundToInt().toFloat()
                            app.movePitchEnvPoint(pad, near, ch.position.x / w, semis)
                        }
                        ch.consume()
                    }
                    if (grabbed) {
                        active = -1
                        AudioEngine.noteOn(pad, 1f)   // результат слышно сразу
                    }
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val edge = 9.dp.toPx()
        val span = h / 2f - edge
        wave(peaks) { Skin.LcdInkDim }
        // сетка: 0 и ±12 / ±24
        for (st in listOf(-24, -12, 0, 12, 24)) {
            val y = h / 2f - st / 24f * span
            drawLine(Skin.LcdInk.copy(alpha = if (st == 0) 0.6f else 0.25f), Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx())
        }
        val path = Path()
        for (i in 0 until 5) {
            val x = u.pEnvX[i] * w
            val y = h / 2f - u.pEnvY[i] / 24f * span
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Skin.LcdInk, style = Stroke(2.5.dp.toPx()))
        val r = 7.dp.toPx()
        for (i in 0 until 5) {
            val c = Offset((u.pEnvX[i] * w).coerceIn(r, w - r), h / 2f - u.pEnvY[i] / 24f * span)
            drawCircle(Skin.LcdInk, r, c)
            drawCircle(Skin.LcdBgTop, r * 0.45f, c)
        }
        val a = active
        val label = if (a >= 0) "%+.0f ST".format(u.pEnvY[a]) else "+24 / -24 ST"
        drawText(measurer.measure(label, Skin.Lcd.copy(fontSize = 12.sp)), topLeft = Offset(4.dp.toPx(), 0f))
    }
}
