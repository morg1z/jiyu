package com.haise.jiyu.ui.theme

import androidx.compose.ui.graphics.Color

// ── Jiyu Dark Theme ──────────────────────────────────────────────────────────
// Klidná tmavá paleta s jednou promyšlenou akcentní barvou. Žádné soupeřící
// duotone gradienty ani dekorativní "glow" efekty - jen jedna barva, použitá
// s rozvahou.

val DeepSpace  = Color(0xFF0B0D12)   // hlavní pozadí
val Midnight   = Color(0xFF14161C)   // surface
val NightBlue  = Color(0xFF181B23)   // karta / zvýšený povrch
val CardBorder = Color(0xFF262A35)   // jemný 1px okraj karet

val AccentLight = Color(0xFFAB99F5)
val Accent      = Color(0xFF7C5CFC)   // jediná akcentní barva appky
val AccentDark  = Color(0xFF5B3FD1)

val TextPrimary   = Color(0xFFEDEFF4)
val TextSecondary = Color(0xFF9096A8)
val TextMuted     = Color(0xFF565C6D)

// Sémantické stavy (stav kategorie/downloadu apod. - nese informaci, není dekorace)
val Success = Color(0xFF34D399)
val Warning = Color(0xFFF59E0B)
val Danger  = Color(0xFFEF4444)

// ── Zpětně kompatibilní aliasy ───────────────────────────────────────────────
// Zbytek appky dosud odkazuje na tato jména (Violet/Cyan/GlowViolet/...).
// Sjednocením na jednu skutečnou barvu (Accent) mizí duotone gradienty a
// "rainbow" okraje napříč celou appkou bez nutnosti přepisovat každý soubor.
val Violet      = Accent
val VioletLight = AccentLight
val VioletDark  = AccentDark
val VioletDeep  = Color(0xFF3B2A6B)
val Cyan        = Accent
val CyanLight   = AccentLight
val CyanDark    = AccentDark
val GlowViolet  = Accent
val GlowCyan    = Accent
val NavyGlass   = NightBlue
val Pink        = Color(0xFFEC4899)
val PinkLight   = Color(0xFFF9A8D4)
