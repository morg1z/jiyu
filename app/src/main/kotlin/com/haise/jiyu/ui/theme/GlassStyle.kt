package com.haise.jiyu.ui.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Gradient přechod pro nadpisy (violet → cyan) */
val titleGradient get() = Brush.linearGradient(
    colors = listOf(VioletLight, CyanLight),
)

/** Gradient pozadí pro celou obrazovku */
val screenGradient get() = Brush.verticalGradient(
    colors = listOf(
        Color(0xFF0A0F20),
        DeepSpace,
    ),
)

/** Gradient pozadí glass karty */
val glassGradient get() = Brush.linearGradient(
    colors = listOf(
        NavyGlass.copy(alpha = 0.9f),
        NightBlue.copy(alpha = 0.95f),
    ),
    start = Offset(0f, 0f),
    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
)

/** Purple → cyan border brush pro glass efekt */
val glassBorderBrush get() = Brush.linearGradient(
    colors = listOf(
        GlowViolet.copy(alpha = 0.35f),
        GlowCyan.copy(alpha = 0.15f),
    ),
)

/** Přidá glass border (bez pozadí – to se řeší zvlášť) */
fun Modifier.glassBorder(radius: Dp = 16.dp): Modifier = this.border(
    width = 1.dp,
    brush = glassBorderBrush,
    shape = RoundedCornerShape(radius),
)

/** Fialová záře jako shadow pod kartou */
fun Modifier.violetGlow(radius: Float = 24f, alpha: Float = 0.25f): Modifier =
    this.drawBehind {
        drawIntoCanvas { canvas ->
            val paint = Paint().also { p ->
                p.asFrameworkPaint().apply {
                    isAntiAlias = true
                    color = android.graphics.Color.TRANSPARENT
                    setShadowLayer(radius, 0f, 4f,
                        android.graphics.Color.argb(
                            (alpha * 255).toInt(), 0x8B, 0x5C, 0xF6
                        )
                    )
                }
            }
            canvas.drawRoundRect(
                left = 0f, top = 0f,
                right = size.width, bottom = size.height,
                radiusX = 16.dp.toPx(), radiusY = 16.dp.toPx(),
                paint = paint,
            )
        }
    }
