package com.haise.jiyu.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.haise.jiyu.settings.ThemeOption

val JiyuShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small      = RoundedCornerShape(12.dp),
    medium     = RoundedCornerShape(16.dp),
    large      = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * @param mode ThemeOption.SYSTEM / DARK / LIGHT / TRUE_BLACK - SYSTEM se rozhodne
 * podle OS mezi DARK a LIGHT (true black je vždy jen explicitní volba, ne systémová).
 */
@Composable
fun JiyuTheme(mode: String = ThemeOption.SYSTEM, content: @Composable () -> Unit) {
    val resolvedMode = if (mode == ThemeOption.SYSTEM) {
        if (isSystemInDarkTheme()) ThemeOption.DARK else ThemeOption.LIGHT
    } else mode
    val isLight = resolvedMode == ThemeOption.LIGHT

    // Přepočítá reaktivní paletu (Color.kt) synchronně v rámci téhle kompozice -
    // odtud se to samo propíše do všech obrazovek, které na tyto barvy odkazují.
    // Volání je idempotentní (stejný mode = stejné hodnoty), takže je bezpečné
    // volat ho přímo v těle composable (ne přes LaunchedEffect, kde by se
    // hodnoty aplikovaly až o snímek později a colorScheme níže by na prvním
    // snímku ještě četl staré barvy).
    applyPaletteMode(resolvedMode)

    val colorScheme = if (isLight) {
        lightColorScheme(
            primary = Accent, onPrimary = Color.White,
            primaryContainer = AccentLight.copy(alpha = 0.25f), onPrimaryContainer = AccentDark,
            secondary = Accent, onSecondary = Color.White,
            secondaryContainer = AccentLight.copy(alpha = 0.25f), onSecondaryContainer = AccentDark,
            tertiary = Pink, onTertiary = Color.White,
            tertiaryContainer = PinkLight.copy(alpha = 0.4f), onTertiaryContainer = Color(0xFF831843),
            background = DeepSpace, onBackground = TextPrimary,
            surface = Midnight, onSurface = TextPrimary,
            surfaceVariant = NightBlue, onSurfaceVariant = TextSecondary,
            outline = CardBorder, outlineVariant = CardBorder,
            error = Color(0xFFDC2626), onError = Color.White,
        )
    } else {
        darkColorScheme(
            primary = Accent, onPrimary = Color.White,
            primaryContainer = AccentDark, onPrimaryContainer = AccentLight,
            secondary = Accent, onSecondary = Color.White,
            secondaryContainer = AccentDark, onSecondaryContainer = AccentLight,
            tertiary = Pink, onTertiary = Color.White,
            tertiaryContainer = Color(0xFF831843), onTertiaryContainer = PinkLight,
            background = DeepSpace, onBackground = TextPrimary,
            surface = Midnight, onSurface = TextPrimary,
            surfaceVariant = NightBlue, onSurfaceVariant = TextSecondary,
            outline = CardBorder, outlineVariant = CardBorder,
            error = Color(0xFFF87171), onError = Color.White,
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = JiyuShapes,
        content = content,
    )
}
