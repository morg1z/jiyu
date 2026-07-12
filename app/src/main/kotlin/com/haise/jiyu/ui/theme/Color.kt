package com.haise.jiyu.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
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

/** Aplikuje paletu pro daný ThemeOption (SYSTEM se řeší mimo, jako DARK/LIGHT). */
fun applyPaletteMode(mode: String) {
    when (mode) {
        ThemeOption.LIGHT -> {
            DeepSpace = Color(0xFFFAFAFC)
            Midnight = Color(0xFFFFFFFF)
            NightBlue = Color(0xFFF1F1F6)
            CardBorder = Color(0xFFE3E3EA)
            AccentLight = Color(0xFF9B84F8)
            Accent = Color(0xFF6D4CE0)
            AccentDark = Color(0xFF4F35B3)
            TextPrimary = Color(0xFF15161C)
            TextSecondary = Color(0xFF5B5F6E)
            TextMuted = Color(0xFF9096A8)
        }
        ThemeOption.TRUE_BLACK -> {
            DeepSpace = Color(0xFF000000)
            Midnight = Color(0xFF000000)
            NightBlue = Color(0xFF0D0D0F)
            CardBorder = Color(0xFF221F2B)
            AccentLight = Color(0xFFAB99F5)
            Accent = Color(0xFF8266FF)
            AccentDark = Color(0xFF5B3FD1)
            TextPrimary = Color(0xFFEDEFF4)
            TextSecondary = Color(0xFF9096A8)
            TextMuted = Color(0xFF565C6D)
        }
        else -> { // DARK (klasické) - výchozí
            DeepSpace = Color(0xFF0B0D12)
            Midnight = Color(0xFF14161C)
            NightBlue = Color(0xFF181B23)
            CardBorder = Color(0xFF262A35)
            AccentLight = Color(0xFFAB99F5)
            Accent = Color(0xFF7C5CFC)
            AccentDark = Color(0xFF5B3FD1)
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
