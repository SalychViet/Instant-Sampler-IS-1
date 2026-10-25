// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Поворотная ручка. [value] — 0..1. Управление: вертикальный драг (вверх — больше), двойной тап — сброс
 * к [defaultValue]. Подпись печатается над ручкой, как шелкография на панели.
 */
@Composable
internal fun Knob(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 40.dp,
    brass: Boolean = false,
    defaultValue: Float? = null,
    onTouch: (Boolean) -> Unit = {},
    /** Тройной тап (например, сброс всех значений). Если задан — двойной тап не сбрасывает. */
    onTripleTap: (() -> Unit)? = null,
    /** Особая ручка: тёмный корпус и светящееся кольцо значения этого цвета. */
    accent: Color? = null,
) {
    val v by rememberUpdatedState(value)
    val set by rememberUpdatedState(onChange)
    val touch by rememberUpdatedState(onTouch)
    val reset by rememberUpdatedState(defaultValue)
    val triple by rememberUpdatedState(onTripleTap)

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(
            label,
            style = if (accent != null) Skin.Silk.copy(fontSize = 9.sp, color = lerp(accent, Color.Black, 0.35f)) else Skin.Silk,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.wrapContentWidth(unbounded = true),
        )
        Canvas(
            Modifier
                .size(diameter)
                .pointerInput(Unit) {
                    var taps = 0
                    var last = 0L
                    detectTapGestures(
                        onTap = {
                            val now = System.currentTimeMillis()
                            taps = if (now - last < 450) taps + 1 else 1
                            last = now
                            if (taps >= 3) { taps = 0; triple?.invoke() }
                        },
                        onDoubleTap = if (onTripleTap == null) ({ reset?.let { set(it) } }) else null,
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { touch(true) },
                        onDragEnd = { touch(false) },
                        onDragCancel = { touch(false) },
                    ) { change, drag ->
                        change.consume()
                        val range = diameter.toPx() * 3.2f
                        set((v - drag.y / range + drag.x / range * 0.5f).coerceIn(0f, 1f))
                    }
                },
        ) {
            val r = size.minDimension / 2f
            val c = center
            if (accent != null) {
                // кольцо: тёмная дорожка и светящаяся дуга значения
                val sw = r * 0.16f
                val tl = Offset(c.x - r + sw / 2f, c.y - r + sw / 2f)
                val arcSize = Size(2f * r - sw, 2f * r - sw)
                drawArc(Color(0xFF2A2C30), 135f, 270f, false, tl, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
                drawArc(accent.copy(alpha = 0.35f), 135f, 270f * v, false, tl, arcSize, style = Stroke(sw * 1.8f, cap = StrokeCap.Round))
                drawArc(accent, 135f, 270f * v, false, tl, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
                drawCircle(Color(0x66000000), radius = r * 0.64f, center = c + Offset(0f, r * 0.05f))
                drawCircle(
                    Brush.radialGradient(listOf(Color(0xFF4A4D53), Color(0xFF141518)), center = c - Offset(r * 0.18f, r * 0.22f), radius = r * 0.9f),
                    radius = r * 0.6f,
                    center = c,
                )
                drawCircle(accent.copy(alpha = 0.8f), radius = r * 0.6f, center = c, style = Stroke(1.6f))
                val a = Math.toRadians(135.0 + 270.0 * v.toDouble()).toFloat()
                drawLine(
                    accent,
                    c + Offset(cos(a) * r * 0.12f, sin(a) * r * 0.12f),
                    c + Offset(cos(a) * r * 0.52f, sin(a) * r * 0.52f),
                    strokeWidth = 3.2f,
                    cap = StrokeCap.Round,
                )
                return@Canvas
            }
            // шкала
            for (i in 0..10) {
                val a = Math.toRadians(135.0 + 270.0 * i / 10.0).toFloat()
                val len = if (i % 5 == 0) 0.22f else 0.14f
                drawLine(
                    Skin.Ink,
                    c + Offset(cos(a) * r * (0.98f - len), sin(a) * r * (0.98f - len)),
                    c + Offset(cos(a) * r * 0.98f, sin(a) * r * 0.98f),
                    strokeWidth = 1.2f,
                )
            }
            // тело
            val hi = if (brass) Skin.BrassLight else Color(0xFFF2F3F5)
            val lo = if (brass) Skin.BrassDark else Color(0xFF7D8187)
            drawCircle(Color(0x55000000), radius = r * 0.72f, center = c + Offset(0f, r * 0.06f))
            drawCircle(
                Brush.radialGradient(listOf(hi, lo), center = c - Offset(r * 0.2f, r * 0.25f), radius = r * 0.95f),
                radius = r * 0.68f,
                center = c,
            )
            drawCircle(Skin.Ink.copy(alpha = 0.75f), radius = r * 0.68f, center = c, style = Stroke(1.4f))
            // указатель
            val a = Math.toRadians(135.0 + 270.0 * v.toDouble()).toFloat()
            drawLine(
                if (brass) Color(0xFF3A2A0C) else Skin.Ink,
                c + Offset(cos(a) * r * 0.14f, sin(a) * r * 0.14f),
                c + Offset(cos(a) * r * 0.62f, sin(a) * r * 0.62f),
                strokeWidth = 2.6f,
            )
        }
    }
}

/** Вертикальный фейдер: касание ставит значение по высоте, драг двигает. Двойное касание — [defaultValue]. */
@Composable
internal fun Fader(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackWidth: Dp = 34.dp,
    defaultValue: Float? = null,
    onTouch: (Boolean) -> Unit = {},
    capWidth: Dp = 24.dp,
    capHeight: Dp = 18.dp,
) {
    val v by rememberUpdatedState(value)
    val set by rememberUpdatedState(onChange)
    val touch by rememberUpdatedState(onTouch)
    val reset by rememberUpdatedState(defaultValue)

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Canvas(
            Modifier
                .width(trackWidth)
                .weight(1f)
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { reset?.let { set(it) } }) }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val capH = capHeight.toPx()
                        fun setFromY(y: Float) {
                            val travel = (size.height - capH).coerceAtLeast(1f)
                            set((1f - (y - capH / 2f) / travel).coerceIn(0f, 1f))
                        }
                        touch(true)
                        setFromY(down.position.y)
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) { ch.consume(); break }
                            setFromY(ch.position.y)
                            ch.consume()
                        }
                        touch(false)
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val capH = capHeight.toPx()
            val slotW = 4.dp.toPx()
            val cx = w / 2f
            // риски по бокам
            for (i in 0..10) {
                val y = capH / 2f + (h - capH) * i / 10f
                val l = if (i % 5 == 0) 7.dp.toPx() else 4.dp.toPx()
                drawLine(Skin.Ink, Offset(cx - slotW - 3.dp.toPx() - l, y), Offset(cx - slotW - 3.dp.toPx(), y), 1f)
            }
            // прорезь
            drawRoundRect(Color(0xFF0B0B0D), Offset(cx - slotW / 2f, 2f), Size(slotW, h - 4f), CornerRadius(slotW / 2f))
            // колпачок
            val capW = capWidth.toPx()
            val y = capH / 2f + (h - capH) * (1f - v.coerceIn(0f, 1f)) - capH / 2f
            drawRoundRect(Color(0x66000000), Offset(cx - capW / 2f + 1f, y + 3f), Size(capW, capH), CornerRadius(3.dp.toPx()))
            drawRoundRect(
                Brush.verticalGradient(listOf(Skin.BrassLight, Skin.BrassDark), startY = y, endY = y + capH),
                Offset(cx - capW / 2f, y),
                Size(capW, capH),
                CornerRadius(3.dp.toPx()),
            )
            drawLine(Color(0xFF2B1F08), Offset(cx - capW / 2f + 2f, y + capH / 2f), Offset(cx + capW / 2f - 2f, y + capH / 2f), 2f)
        }
        Text(label, style = Skin.Silk.copy(fontSize = 7.sp, letterSpacing = 0.sp), maxLines = 1, softWrap = false, textAlign = TextAlign.Center, modifier = Modifier.wrapContentWidth(unbounded = true))
    }
}

internal enum class Glyph { NONE, PLAY, REC, STOP }

/**
 * Кнопка «железа»: светлый колпачок с подписью, светодиод-индикатор в углу.
 * [led] подсвечивает индикатор [ledColor]; [onClick] срабатывает на отпускание.
 */
@Composable
internal fun HwButton(
    label: String,
    modifier: Modifier = Modifier,
    led: Boolean = false,
    ledColor: Color = Skin.LedAmber,
    glyph: Glyph = Glyph.NONE,
    enabled: Boolean = true,
    height: Dp = 30.dp,
    /** Нажата/отпущена — для кнопок-модификаторов, которые держат (CHOKE). */
    onPress: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    if (onPress != null) LaunchedEffect(pressed) { onPress(pressed) }
    val shape = RoundedCornerShape(5.dp)
    // Активная кнопка целиком окрашивается в свой цвет — видно издалека (вместо маленькой лампочки).
    val lit = led && enabled
    val face = when {
        lit && pressed -> listOf(ledColor.copy(red = ledColor.red * 0.8f, green = ledColor.green * 0.8f, blue = ledColor.blue * 0.8f), ledColor)
        lit -> listOf(lerp(ledColor, Color.White, 0.35f), ledColor)
        pressed -> listOf(Skin.CapDark, Color(0xFFA2A5AA))
        else -> listOf(Skin.CapLight, Skin.CapDark)
    }
    val ink = if (lit && ledColor == Skin.LedRed) Color.White else Skin.Ink

    Box(
        modifier = modifier
            .height(height)
            .alpha(if (enabled) 1f else 0.45f)
            .background(Color(0x55000000), shape)
            .padding(top = if (pressed) 1.5.dp else 0.dp, bottom = if (pressed) 0.dp else 1.5.dp)
            .background(Brush.verticalGradient(face), shape)
            .border(if (lit) 1.5.dp else 1.dp, if (lit) lerp(ledColor, Color.Black, 0.45f) else Skin.Ink.copy(alpha = 0.55f), shape)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (glyph != Glyph.NONE) {
                Canvas(Modifier.size(9.dp)) {
                    when (glyph) {
                        Glyph.PLAY -> drawPath(
                            Path().apply {
                                moveTo(0f, 0f); lineTo(size.width, size.height / 2f); lineTo(0f, size.height); close()
                            },
                            ink,
                        )
                        Glyph.STOP -> drawRect(ink, Offset(size.width * 0.1f, size.height * 0.1f), Size(size.width * 0.8f, size.height * 0.8f))
                        Glyph.REC -> drawCircle(if (lit) Color.White else Color(0xFFB3141A), radius = size.minDimension / 2f)
                        Glyph.NONE -> {}
                    }
                }
            }
            Text(label, style = Skin.Silk.copy(fontSize = 9.sp, color = ink), maxLines = 1)
        }
    }
}

/** Программная клавиша под экраном (F1–F5) с динамической подписью. */
@Composable
internal fun SoftKey(
    index: Int,
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        HwButton(
            label = index.toString(),
            led = active,
            ledColor = Skin.LedAmber,
            enabled = enabled,
            height = 20.dp,
            modifier = Modifier.fillMaxWidth(),
            onClick = onClick,
        )
        Text(label, style = Skin.Silk.copy(fontSize = 7.sp), maxLines = 1, textAlign = TextAlign.Center)
    }
}

/** Пустая заглушка, занимающая место в ряду. */
@Composable
internal fun Gap(modifier: Modifier = Modifier) { Box(modifier.fillMaxHeight()) }
