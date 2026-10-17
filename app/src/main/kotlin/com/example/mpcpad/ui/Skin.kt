package com.example.mpcpad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Оформление «железа»: матовый алюминий, чёрная шелкография, латунные колпачки, LCD. */
internal object Skin {
    val AluLight = Color(0xFFD9DBDE)
    val AluMid = Color(0xFFC3C6CA)
    val AluDark = Color(0xFFA9ADB3)
    val Ink = Color(0xFF15161A)
    val InkSoft = Color(0xFF3B3D43)

    val LcdBgTop = Color(0xFFB9C792)
    val LcdBgBottom = Color(0xFF9DB078)
    val LcdInk = Color(0xFF1F2A12)
    val LcdInkDim = Color(0x661F2A12)
    val LcdFrame = Color(0xFF111214)

    val BrassLight = Color(0xFFEBCB8A)
    val BrassDark = Color(0xFFB4863A)

    val Rubber = Color(0xFF2A2B2F)
    val RubberTop = Color(0xFF35363B)
    val RubberEdge = Color(0xFF0E0F11)

    val CapLight = Color(0xFFE3E5E8)
    val CapDark = Color(0xFFB7BABF)

    val LedRed = Color(0xFFFF3B30)
    val LedGreen = Color(0xFF3DDC6B)
    val LedAmber = Color(0xFFFFB020)

    val PadHot = Color(0xFFFF7A1A)
    val PadHotGlow = Color(0x66FF7A1A)
    val PadRec = Color(0xFFFF3B30)
    val PadGreen = Color(0xFF45D37A)
    val PadBlue = Color(0xFF59A8FF)

    /** Мелкая шелкография (подписи ручек и кнопок). */
    val Silk = TextStyle(
        color = Ink,
        fontSize = 8.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        lineHeight = 9.sp,
    )
    val SilkBig = TextStyle(
        color = Ink,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
    )
    val Lcd = TextStyle(
        color = LcdInk,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 12.sp,
    )
}

/** Шлифованный алюминий: вертикальный градиент и тонкие горизонтальные «царапины». */
internal fun Modifier.brushedAluminium(): Modifier = drawBehind {
    drawRect(
        Brush.verticalGradient(
            0f to Skin.AluLight,
            0.45f to Skin.AluMid,
            1f to Skin.AluLight,
        )
    )
    val step = 2.2.dp.toPx().coerceAtLeast(1.5f)
    var y = 0f
    var i = 0
    while (y < size.height) {
        // псевдослучайная, но детерминированная интенсивность — без мерцания при перерисовке
        val k = ((i * 2654435761L) shr 7 and 0xFF).toInt() / 255f
        drawLine(
            color = (if (k > 0.5f) Color.White else Color.Black).copy(alpha = 0.015f + 0.045f * k),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f,
        )
        y += step
        i++
    }
}

/** Винт с прорезью. */
@Composable
internal fun Screw(diameter: Dp = 12.dp, angleDeg: Float = 25f, modifier: Modifier = Modifier) {
    Box(modifier.size(diameter)) {
        Canvas(Modifier.size(diameter)) {
            val r = size.minDimension / 2f
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFFF0F1F3), Color(0xFF8C9096)), center, r),
                radius = r,
            )
            drawCircle(Color(0x88000000), radius = r, style = Stroke(1f))
            val a = Math.toRadians(angleDeg.toDouble())
            val dx = (kotlin.math.cos(a) * r * 0.7).toFloat()
            val dy = (kotlin.math.sin(a) * r * 0.7).toFloat()
            drawLine(Color(0xFF303236), center - Offset(dx, dy), center + Offset(dx, dy), strokeWidth = r * 0.3f)
        }
    }
}
