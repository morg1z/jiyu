package com.haise.jiyu.translate

/**
 * Čisté (bez Androidu) pomocné funkce pro observabilitu vykreslování bublin - viz
 * [com.haise.jiyu.ui.reader.BubbleOverlayLayer]/[com.haise.jiyu.ui.reader.TranslationOverlay].
 *
 * ## Co se dělo
 * Nahlášeno se srovnávací dvojicí snímků: bublina "YAH!" (natěsno vedle "STOP IT, HATSU!!",
 * dvou "FWISH" SFX a dlouhé bubliny s hrozbou) v přeloženém snímku úplně zmizela - ani originál,
 * ani překlad, čistě prázdno. Kód mezi kontrolou "má bublina co zobrazit"
 * ([hasTranslatableLetters]) a skutečným vykreslením (matchOriginalCase/tidyStrandedPunctuation)
 * neumí z neprázdného textu udělat prázdný, takže příčina je buď (a) bublina se vůbec
 * nevykresluje (SFX/neaccessibilní překlad) - to by ale mělo nechat prosvítat originál, ne
 * prázdno - nebo (b) box/font vyjde v natěsno namačkaném trsu bublin degenerovaně malý.
 * Bez reálných čísel z appky (jaký typ bubliny, jak velký box vyšel) by další krok byl jen
 * hádání na magickou konstantu - odtud tahle observabilita, viz `adb logcat -s BubbleSkip`
 * a `adb logcat -s TinyBubbleBox`.
 */

/** Minimální rozměr (v Dp), pod kterým je box na běžné obrazovce prakticky neviditelný. */
internal const val MIN_REASONABLE_BUBBLE_DP = 12f

/**
 * Box tak malý, že text v něm nemůže být čitelný (nebo se vůbec nevejde) - kandidát na
 * vysvětlení "bublina zmizela", i když gate v [com.haise.jiyu.ui.reader.BubbleOverlayLayer]
 * řekl, že se má vykreslit.
 */
internal fun isSuspiciouslyTinyBubbleBox(widthDp: Float, maxHeightDp: Float): Boolean =
    widthDp < MIN_REASONABLE_BUBBLE_DP || maxHeightDp < MIN_REASONABLE_BUBBLE_DP

/**
 * Proč [com.haise.jiyu.ui.reader.BubbleOverlayLayer] tuhle bublinu vůbec nevykreslí (nechá
 * prosvítat originál) - null = kreslí se. Sdíleno mezi skutečnou podmínkou v BubbleOverlayLayer
 * a logováním, aby se obě nemohly rozejít.
 */
internal fun bubbleSkipReason(isSfx: Boolean, isUntranslated: Boolean, hasTranslatableLetters: Boolean): String? = when {
    isSfx -> "sfx"
    isUntranslated -> "untranslated"
    !hasTranslatableLetters -> "no_letters"
    else -> null
}
