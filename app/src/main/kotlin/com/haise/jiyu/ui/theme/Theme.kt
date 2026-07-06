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

private val JiyuDarkColors = darkColorScheme(
    primary             = Violet,
    onPrimary           = Color.White,
    primaryContainer    = VioletDeep,
    onPrimaryContainer  = VioletLight,
    secondary           = Cyan,
    onSecondary         = Color.White,
    secondaryContainer  = CyanDark,
    onSecondaryContainer= CyanLight,
    tertiary            = Pink,
    onTertiary          = Color.White,
    tertiaryContainer   = Color(0xFF831843),
    onTertiaryContainer = PinkLight,
    background          = DeepSpace,
    onBackground        = TextPrimary,
    surface             = Midnight,
    onSurface           = TextPrimary,
    surfaceVariant      = NightBlue,
    onSurfaceVariant    = TextSecondary,
    outline             = Color(0xFF2D3D5C),
    outlineVariant      = Color(0xFF1A2540),
    error               = Color(0xFFF87171),
    onError             = Color.White,
)

private val JiyuLightColors = lightColorScheme(
    primary              = VioletDark,
    onPrimary            = Color.White,
    primaryContainer     = Color(0xFFEDE9FE),
    onPrimaryContainer   = Color(0xFF3B0764),
    secondary            = CyanDark,
    onSecondary          = Color.White,
    secondaryContainer   = Color(0xFFCFFAFE),
    onSecondaryContainer = Color(0xFF0E4F5C),
    tertiary             = Pink,
    onTertiary           = Color.White,
    tertiaryContainer    = Color(0xFFFCE7F3),
    onTertiaryContainer  = Color(0xFF831843),
    background           = Color(0xFFF1F5FF),
    onBackground         = Color(0xFF0F172A),
    surface              = Color(0xFFFFFFFF),
    onSurface            = Color(0xFF0F172A),
    surfaceVariant       = Color(0xFFE8EDFF),
    onSurfaceVariant     = Color(0xFF334155),
    outline              = Color(0xFF94A3B8),
    outlineVariant       = Color(0xFFCBD5E1),
    error                = Color(0xFFDC2626),
    onError              = Color.White,
)

val JiyuShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small      = RoundedCornerShape(12.dp),
    medium     = RoundedCornerShape(16.dp),
    large      = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * @param forceDark true = tmavé, false = světlé, null = systémové
 */
@Composable
fun JiyuTheme(forceDark: Boolean? = null, content: @Composable () -> Unit) {
    val dark = forceDark ?: isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) JiyuDarkColors else JiyuLightColors,
        shapes = JiyuShapes,
        content = content,
    )
}
