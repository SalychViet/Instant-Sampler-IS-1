// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Bg = Color(0xFF1B1B1D)
internal val Panel = Color(0xFF26262A)
internal val TextDim = Color(0xFF9A9AA2)
internal val TextMain = Color(0xFFE8E8EA)
internal val RecRed = Color(0xFFE53935)

/** Кнопка-«таблетка». [active] подсвечивает оранжевым, [danger] — красным (REC). */
@Composable
internal fun Chip(
    label: String,
    active: Boolean = false,
    enabled: Boolean = true,
    danger: Boolean = false,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg = when {
        danger -> RecRed
        active -> PadLit
        else -> Panel
    }
    val fg = when {
        danger -> Color.White
        active -> Color.Black
        else -> TextMain
    }
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .background(bg, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 6.dp else 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontSize = if (compact) 12.sp else 14.sp)
    }
}

/** Индикатор уровня 0..1. [level] читается в фазе рисования — перерисовка без рекомпозиции. */
@Composable
internal fun PeakMeter(level: () -> Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRect(Panel)
        val p = level().coerceIn(0f, 1f)
        val color = when {
            p > 0.97f -> RecRed
            p > 0.75f -> Color(0xFFFFC107)
            else -> Color(0xFF4CAF50)
        }
        drawRect(color, size = Size(size.width * p, size.height))
    }
}

@Composable
internal fun ParamSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(top = 4.dp)) {
        Row {
            Text(label, color = TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(valueText, color = TextMain, fontSize = 12.sp)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}
