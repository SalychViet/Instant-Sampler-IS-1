// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val PadIdle = Color(0xFF3A3A40)
val PadLit = Color(0xFFFF7A1A)
val PadSelected = Color(0xFFFFC38A)

/**
 * Сетка 4×4 (резиновые пады) в одном Canvas с мультитачем: каждый палец — по pointer id.
 * Раскладка как у MPC: пад 1 — слева внизу, пад 13 — слева вверху.
 *
 * Все маски — по глобальному номеру пада 0..63 и читаются только при рисовании (без рекомпозиции):
 *  [lit] — оранжевая подсветка (звучит/нажат), [marked] — зелёный светодиод в углу (есть семпл / непустой паттерн / шаг включён),
 *  [cursor] — глобальный номер пада с синей рамкой (плейхед или играющий паттерн), -1 — нет;
 *  [recPad] — пад, на который сейчас идёт запись, -1 — нет.
 *
 * Velocity (если не Full Level) берётся из вертикальной позиции касания: верх пада — тихо (0.30), низ — громко (1.00).
 */
@Composable
fun PadGrid(
    bank: Int,
    selectedPad: Int,
    fullLevel: Boolean,
    lit: () -> Long,
    marked: () -> Long,
    cursor: () -> Int,
    recPad: () -> Int,
    onPadDown: (globalPad: Int, velocity: Float) -> Unit,
    onPadUp: (globalPad: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentBank by rememberUpdatedState(bank)
    val currentFull by rememberUpdatedState(fullLevel)
    val down by rememberUpdatedState(onPadDown)
    val up by rememberUpdatedState(onPadUp)

    val measurer = rememberTextMeasurer()
    val labelStyle = remember {
        TextStyle(color = Color(0xFFE8E8EA), fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
    val labels = remember(measurer) { (1..16).map { measurer.measure(it.toString(), labelStyle) } }

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            // pointer id -> глобальный номер пада, который этот палец нажал
            val active = HashMap<Long, Int>()
            try {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        for (c in event.changes) {
                            val id = c.id.value
                            if (c.pressed && !c.previousPressed) {
                                val fx = (c.position.x / w * 4f).coerceIn(0f, 3.999f)
                                val fy = (c.position.y / h * 4f).coerceIn(0f, 3.999f)
                                val col = fx.toInt()
                                val row = fy.toInt()
                                val global = currentBank * 16 + (3 - row) * 4 + col
                                val relY = fy - row                      // 0 = верх пада, 1 = низ
                                val vel = if (currentFull) 1f else 0.30f + 0.70f * relY
                                active[id] = global
                                down(global, vel)
                                c.consume()
                            } else if (!c.pressed && c.previousPressed) {
                                active.remove(id)?.let { up(it) }
                                c.consume()
                            }
                        }
                    }
                }
            } finally {
                // Composable ушёл из композиции с нажатыми пальцами — отпускаем, чтобы не залипали Gate/Loop.
                active.values.forEach { up(it) }
                active.clear()
            }
        },
    ) {
        val cw = size.width / 4f
        val ch = size.height / 4f
        val gap = 7.dp.toPx()
        val radius = CornerRadius(10.dp.toPx())
        val litBits = lit()
        val markedBits = marked()
        val cur = cursor()
        val rec = recPad()
        for (row in 0 until 4) {
            for (col in 0 until 4) {
                val padInBank = (3 - row) * 4 + col
                val global = bank * 16 + padInBank
                val topLeft = Offset(col * cw + gap / 2f, row * ch + gap / 2f)
                val sz = Size(cw - gap, ch - gap)
                val isLit = (litBits shr global) and 1L != 0L
                val isRec = global == rec

                // колодец вокруг пада
                drawRoundRect(Skin.RubberEdge, topLeft - Offset(2f, 2f), Size(sz.width + 4f, sz.height + 4f), radius)
                val body = when {
                    isRec -> listOf(Skin.PadRec, Color(0xFF9C1A12))
                    isLit -> listOf(Color(0xFFFFA45A), Skin.PadHot)
                    else -> listOf(Skin.RubberTop, Skin.Rubber)
                }
                drawRoundRect(Brush.verticalGradient(body, startY = topLeft.y, endY = topLeft.y + sz.height), topLeft, sz, radius)
                // блик сверху
                if (!isLit && !isRec) {
                    drawRoundRect(
                        Color(0x12FFFFFF), topLeft + Offset(3.dp.toPx(), 3.dp.toPx()),
                        Size(sz.width - 6.dp.toPx(), sz.height * 0.32f), CornerRadius(8.dp.toPx()),
                    )
                }
                if ((markedBits shr global) and 1L != 0L) {
                    drawCircle(Skin.LedGreen, 3.dp.toPx(), topLeft + Offset(sz.width - 9.dp.toPx(), 9.dp.toPx()))
                }
                if (global == cur) {
                    drawRoundRect(Skin.PadBlue, topLeft, sz, radius, style = Stroke(3.dp.toPx()))
                }
                if (global == selectedPad) {
                    drawRoundRect(PadSelected, topLeft + Offset(2.dp.toPx(), 2.dp.toPx()),
                        Size(sz.width - 4.dp.toPx(), sz.height - 4.dp.toPx()), radius, style = Stroke(1.5.dp.toPx()))
                }
                drawText(labels[padInBank], topLeft = Offset(topLeft.x + 8.dp.toPx(), topLeft.y + 5.dp.toPx()))
            }
        }
    }
}
