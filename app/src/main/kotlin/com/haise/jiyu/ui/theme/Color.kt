package com.haise.jiyu.ui.theme

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.haise.jiyu.R
import com.haise.jiyu.settings.AccentOption
import com.haise.jiyu.settings.ThemeOption

// ── Jiyu paleta ──────────────────────────────────────────────────────────────
// Jedna promyšlená akcentní barva, žádné soupeřící duotone gradienty ani
// dekorativní "glow" efekty. Povrchové/textové barvy jsou reaktivní (Compose
// State) - zbytek appky na ně odkazuje jako na obyčejné `Color` konstanty
// (beze změny na volajících místech), ale při přepnutí motivu (viz
// applyPaletteMode níže) se všude samy přepočítají, protože Compose sleduje
// jejich čtení uvnitř kompozice.

var DeepSpace  by mutableStateOf(Color(0xFF0B0D12))   // hlavní pozadí
    private set
var ScreenGradientTop by mutableStateOf(Color(0xFF0D0F16))   // vršek screenGradient - musí se přepočítat s tématem, jinak zůstává tmavý i ve světlém režimu
    private set
var Midnight   by mutableStateOf(Color(0xFF14161C))   // surface
    private set
var NightBlue  by mutableStateOf(Color(0xFF181B23))   // karta / zvýšený povrch
    private set
var CardBorder by mutableStateOf(Color(0xFF262A35))   // jemný 1px okraj karet
    private set

var AccentLight by mutableStateOf(Color(0xFFAB99F5))
    private set
var Accent      by mutableStateOf(Color(0xFF7C5CFC))   // jediná akcentní barva appky
    private set
var AccentDark  by mutableStateOf(Color(0xFF5B3FD1))
    private set

var TextPrimary   by mutableStateOf(Color(0xFFEDEFF4))
    private set
var TextSecondary by mutableStateOf(Color(0xFF9096A8))
    private set
var TextMuted     by mutableStateOf(Color(0xFF565C6D))
    private set

// Sémantické stavy (stav kategorie/downloadu apod. - nese informaci, není dekorace)
val Success = Color(0xFF34D399)
val Warning = Color(0xFFF59E0B)
val Danger  = Color(0xFFEF4444)

// ── Akcentové barvy ─────────────────────────────────────────────────────────
// Trojice barev jednoho akcentu. Role (viz JiyuTheme): v dark módu je accent
// hlavní barva, dark pozadí kontejnerů (primaryContainer) a light text na něm
// (onPrimaryContainer); ve světlém se accent a dark prohodí (hluboká barva je
// čitelná na bílé, pastelová zůstane podkladem tintovaných kontejnerů).

class AccentPalette(val accent: Color, val light: Color, val dark: Color)

/**
 * Jeden nabízený akcent v Nastavení → Vzhled. [dark] paleta pro DARK i TRUE_BLACK
 * (u true black se jen hlavní barva mírně zesvětlí - [trueBlackAccent], černé
 * pozadí ji lépe nese), [light] paleta pro LIGHT motiv.
 */
class AccentSpec(
    val key: String,
    @param:StringRes val labelRes: Int,
    val dark: AccentPalette,
    val light: AccentPalette,
    val trueBlackAccent: Color,
    /** Pozice na spektrálním slideru (0-359) - presety nesou odstín své hlavní barvy. */
    val hue: Float = 0f,
)

val ACCENT_OPTIONS: List<AccentSpec> = listOf(
    AccentSpec(
        AccentOption.VIOLET, R.string.accent_violet,
        dark = AccentPalette(Color(0xFF7C5CFC), Color(0xFFAB99F5), Color(0xFF5B3FD1)),
        light = AccentPalette(Color(0xFF6D4CE0), Color(0xFF9B84F8), Color(0xFF4F35B3)),
        trueBlackAccent = Color(0xFF8266FF),
        hue = 250f,
    ),
    AccentSpec(
        AccentOption.BLUE, R.string.accent_blue,
        dark = AccentPalette(Color(0xFF60A5FA), Color(0xFF93C5FD), Color(0xFF2563EB)),
        light = AccentPalette(Color(0xFF2563EB), Color(0xFF60A5FA), Color(0xFF1D4ED8)),
        trueBlackAccent = Color(0xFF72AEFB),
        hue = 213f,
    ),
    AccentSpec(
        AccentOption.TEAL, R.string.accent_teal,
        dark = AccentPalette(Color(0xFF2DD4BF), Color(0xFF5EEAD4), Color(0xFF0D9488)),
        light = AccentPalette(Color(0xFF0D9488), Color(0xFF2DD4BF), Color(0xFF0F766E)),
        trueBlackAccent = Color(0xFF3BDBCB),
        hue = 173f,
    ),
    AccentSpec(
        AccentOption.GREEN, R.string.accent_green,
        dark = AccentPalette(Color(0xFF34D399), Color(0xFF86EFAC), Color(0xFF16A34A)),
        light = AccentPalette(Color(0xFF16A34A), Color(0xFF4ADE80), Color(0xFF15803D)),
        trueBlackAccent = Color(0xFF41DCA4),
        hue = 158f,
    ),
    AccentSpec(
        AccentOption.ORANGE, R.string.accent_orange,
        dark = AccentPalette(Color(0xFFFB923C), Color(0xFFFDBA74), Color(0xFFEA580C)),
        light = AccentPalette(Color(0xFFEA580C), Color(0xFFFB923C), Color(0xFFC2410C)),
        trueBlackAccent = Color(0xFFFC9D4B),
        hue = 25f,
    ),
    AccentSpec(
        AccentOption.PINK, R.string.accent_pink,
        dark = AccentPalette(Color(0xFFF472B6), Color(0xFFF9A8D4), Color(0xFFDB2777)),
        light = AccentPalette(Color(0xFFDB2777), Color(0xFFF472B6), Color(0xFF9D174D)),
        trueBlackAccent = Color(0xFFF57FBB),
        hue = 330f,
    ),
    AccentSpec(
        AccentOption.RED, R.string.accent_red,
        dark = AccentPalette(Color(0xFFF87171), Color(0xFFFCA5A5), Color(0xFFDC2626)),
        light = AccentPalette(Color(0xFFDC2626), Color(0xFFF87171), Color(0xFF991B1B)),
        trueBlackAccent = Color(0xFFF97F7F),
        hue = 0f,
    ),
    AccentSpec(
        AccentOption.AMBER, R.string.accent_amber,
        dark = AccentPalette(Color(0xFFFBBF24), Color(0xFFFCD34D), Color(0xFFD97706)),
        light = AccentPalette(Color(0xFFD97706), Color(0xFFFBBF24), Color(0xFF92400E)),
        trueBlackAccent = Color(0xFFFCC64A),
        hue = 45f,
    ),
)

/** Prefix pro uživatelsky zvolený odstín - uložená hodnota "h:<0-359>" (viz spektrální
 * slider ve Vzhledu). Pojmenované presety se ukládají svým klíčem ("teal", ...). */
const val ACCENT_HUE_PREFIX = "h:"

/** Paleta dopočítaná z libovolného odstínu přes HSL. Žlutá/krémová oblast (~38-80°) má
 * ve světlém motivu při běžné světlosti špatný kontrast na bílé - proto se tam hlavní
 * barva stmavuje a zesytí (čte se jako jantarová, ne vyblitá žlutá). */
fun hueAccentSpec(hue: Float): AccentSpec {
    val h = ((hue % 360f) + 360f) % 360f
    val yellowish = h in 38f..80f
    val lightAccentL = if (yellowish) 0.34f else 0.42f
    val lightAccentS = if (yellowish) 0.80f else 0.72f
    return AccentSpec(
        key = "$ACCENT_HUE_PREFIX${h.toInt()}",
        labelRes = R.string.accent_custom,
        dark = AccentPalette(
            accent = Color.hsl(h, 0.82f, 0.62f),
            light  = Color.hsl(h, 0.70f, 0.76f),
            dark   = Color.hsl(h, 0.85f, 0.45f),
        ),
        light = AccentPalette(
            accent = Color.hsl(h, lightAccentS, lightAccentL),
            light  = Color.hsl(h, 0.72f, lightAccentL + 0.18f),
            dark   = Color.hsl(h, 0.80f, (lightAccentL - 0.12f).coerceAtLeast(0.20f)),
        ),
        trueBlackAccent = Color.hsl(h, 0.85f, 0.66f),
        hue = h,
    )
}

/** "h:<hue>" -> dopočítaná paleta, jinak pojmenovaný preset; neznámá hodnota padne na fialovou. */
fun accentSpecFor(key: String): AccentSpec {
    if (key.startsWith(ACCENT_HUE_PREFIX)) {
        key.removePrefix(ACCENT_HUE_PREFIX).toFloatOrNull()?.let { return hueAccentSpec(it) }
    }
    return ACCENT_OPTIONS.firstOrNull { it.key == key } ?: ACCENT_OPTIONS.first()
}

/** Aplikuje paletu pro daný ThemeOption + akcent (SYSTEM se řeší mimo, jako DARK/LIGHT). */
fun applyPaletteMode(mode: String, accentKey: String = AccentOption.VIOLET) {
    val spec = accentSpecFor(accentKey)
    when (mode) {
        ThemeOption.LIGHT -> {
            DeepSpace = Color(0xFFFAFAFC)
            ScreenGradientTop = Color(0xFFEFEFF5)
            Midnight = Color(0xFFFFFFFF)
            NightBlue = Color(0xFFF1F1F6)
            CardBorder = Color(0xFFE3E3EA)
            AccentLight = spec.light.light
            Accent = spec.light.accent
            AccentDark = spec.light.dark
            TextPrimary = Color(0xFF15161C)
            TextSecondary = Color(0xFF5B5F6E)
            TextMuted = Color(0xFF9096A8)
        }
        ThemeOption.TRUE_BLACK -> {
            DeepSpace = Color(0xFF000000)
            ScreenGradientTop = Color(0xFF000000)
            Midnight = Color(0xFF000000)
            NightBlue = Color(0xFF0D0D0F)
            CardBorder = Color(0xFF221F2B)
            AccentLight = spec.dark.light
            Accent = spec.trueBlackAccent
            AccentDark = spec.dark.dark
            TextPrimary = Color(0xFFEDEFF4)
            TextSecondary = Color(0xFF9096A8)
            TextMuted = Color(0xFF565C6D)
        }
        else -> { // DARK (klasické) - výchozí
            DeepSpace = Color(0xFF0B0D12)
            ScreenGradientTop = Color(0xFF0D0F16)
            Midnight = Color(0xFF14161C)
            NightBlue = Color(0xFF181B23)
            CardBorder = Color(0xFF262A35)
            AccentLight = spec.dark.light
            Accent = spec.dark.accent
            AccentDark = spec.dark.dark
            TextPrimary = Color(0xFFEDEFF4)
            TextSecondary = Color(0xFF9096A8)
            TextMuted = Color(0xFF565C6D)
        }
    }
}

// ── Zpětně kompatibilní aliasy ───────────────────────────────────────────────
// Zbytek appky dosud odkazuje na tato jména (Violet/Cyan/GlowViolet/...).
// Sjednocením na jednu skutečnou barvu (Accent) mizí duotone gradienty a
// "rainbow" okraje napříč celou appkou bez nutnosti přepisovat každý soubor.
val Violet      get() = Accent
val VioletLight get() = AccentLight
val VioletDark  get() = AccentDark
val VioletDeep  = Color(0xFF3B2A6B)
val Cyan        get() = Accent
val CyanLight   get() = AccentLight
val CyanDark    get() = AccentDark
val GlowViolet  get() = Accent
val GlowCyan    get() = Accent
val NavyGlass   get() = NightBlue
val Pink        = Color(0xFFEC4899)
val PinkLight   = Color(0xFFF9A8D4)
/** Odznak typu obsahu MANHWA (viz contentTypeBadgeColor) - pevna barva nezavisla na motivu,
 * stejne jako Pink/Danger nize - drive GlowViolet/GlowCyan pro MANHWA/MANHUA byly ve
 * skutecnosti STEJNA barva (obe jen aliasy na Accent), odznaky tak byly k nerozeznani. */
val Blue        = Color(0xFF3B82F6)
/** Odznak typu obsahu MANHUA (viz contentTypeBadgeColor). */
val Gold        = Color(0xFFD4A017)
