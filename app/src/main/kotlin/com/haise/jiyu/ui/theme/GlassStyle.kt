package com.haise.jiyu.ui.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Barva nadpisů (dřív duotone gradient, teď jednotná akcentní barva - klidnější, čitelnější) */
val titleGradient get() = Brush.linearGradient(colors = listOf(TextPrimary, TextPrimary))

/** Gradient pozadí pro celou obrazovku - jemný, téměř neznatelný přechod */
val screenGradient get() = Brush.verticalGradient(
    colors = listOf(
        Color(0xFF0D0F16),
        DeepSpace,
    ),
)

/** Plné pozadí karty (dřív "glass" gradient) */
val glassGradient get() = Brush.linearGradient(
    colors = listOf(NightBlue, NightBlue),
)

/** Jemný jednobarevný okraj karty - žádný duotone lem */
val glassBorderBrush get() = Brush.linearGradient(
    colors = listOf(CardBorder, CardBorder),
)

/** Přidá tenký, klidný border (bez pozadí – to se řeší zvlášť) */
fun Modifier.glassBorder(radius: Dp = 16.dp): Modifier = this.border(
    width = 1.dp,
    brush = glassBorderBrush,
    shape = RoundedCornerShape(radius),
)

/**
 * Dřív vrhala výraznou barevnou "glow" záři pod kartou (typický cyberpunk/AI
 * efekt) - teď jen velmi jemný, téměř neviditelný stín pro nepatrnou hloubku,
 * ať karty nepůsobí naprosto ploše na tmavém pozadí.
 */
fun Modifier.violetGlow(radius: Float = 24f, alpha: Float = 0.25f): Modifier =
    this.drawBehind {
        drawIntoCanvas { canvas ->
            val paint = Paint().also { p ->
                p.asFrameworkPaint().apply {
                    isAntiAlias = true
                    color = android.graphics.Color.TRANSPARENT
                    setShadowLayer(radius * 0.4f, 0f, 2f,
                        android.graphics.Color.argb(
                            (alpha * 60).toInt(), 0, 0, 0
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
