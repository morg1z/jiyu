package com.haise.jiyu.translate

/**
 * Čisté (bez Androidu) pomocné funkce pro grafické doladění přeložené bubliny, aby výplň
 * splynula s originální kresbou místo aby vypadala jako nalepený štítek - viz reference
 * "clean scanlation" appek. Odděleno od ReaderScreen.kt kvůli JVM testovatelnosti
 * (BubbleRenderStyleTest) bez Compose/Androidu.
 */

/**
 * "Přichytí" nasamplovanou barvu pozadí bubliny k čistě bílé/černé, když je dost blízko.
 * Vzorkování prstence kolem OCR textu (viz OcrEngine.sampleBackgroundColor) vrátí u bílé
 * bubliny typicky "skoro bílou" (třeba #F2F2F2 kvůli antialiasingu okraje písma) - vyplnit
 * tím čistě bílou bublinu nechá viditelný našedlý obdélníkový flek. Přichycením na #FFFFFF
 * (resp. #000000 u tmavých bublin) výplň zmizí do kresby. Alfa kanál zůstává zachovaný.
 *
 * Práh 0.86 / 0.14 je záměrně blízko krajům - barevné bubliny (shout boxy, system okna)
 * mají luminanci ve středu rozsahu a nesmí se přichytit, jinak by ztratily barvu.
 */
internal fun snapBubbleBg(argb: Int): Int {
    val a = (argb ushr 24) and 0xFF
    val r = (argb ushr 16) and 0xFF
    val g = (argb ushr 8) and 0xFF
    val b = argb and 0xFF
    val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
    return when {
        luminance >= 0.86 -> (a shl 24) or 0x00FFFFFF // čistě bílá, zachovej alfu
        luminance <= 0.14 -> (a shl 24)               // čistě černá, zachovej alfu
        else -> argb
    }
}

/**
 * Průměr dvou ARGB barev (kanál po kanálu) - používá se pro sloučení odděleně
 * vzorkované horní/dolní barvy bubliny ([OcrEngine.sampleBackgroundColor]) do jedné
 * hodnoty tam, kde gradient nedává smysl (referenční barva pro [BubbleShapeDetector],
 * volba barvy textu podle jasu pozadí). Alfa kanál se bere z první barvy - obě strany
 * gradientu mají v praxi vždy stejnou alfu (viz TRANSLATION_BOX_ALPHA).
 */
internal fun averageArgb(a: Int, b: Int): Int {
    val alpha = (a ushr 24) and 0xFF
    val r = (((a ushr 16) and 0xFF) + ((b ushr 16) and 0xFF)) / 2
    val g = (((a ushr 8) and 0xFF) + ((b ushr 8) and 0xFF)) / 2
    val bl = ((a and 0xFF) + (b and 0xFF)) / 2
    return (alpha shl 24) or (r shl 16) or (g shl 8) or bl
}

/**
 * Průměrná barva vnitřních ~60 % záplaty (střed, kam se přeložený text skutečně sází).
 *
 * Používá se pro volbu barvy textu u bublin kreslených ZÁPLATOU (viz TranslationOverlay):
 * tam se dosud rozhodovalo podle navzorkovaného prstence kolem OCR boxu
 * ([OcrEngine.sampleBackgroundColor]) - ten ale zachytí i tmavou kresbu MIMO bublinu
 * (vlasy, černý line-art, tmavý kostým), dominantní kbelík pak vyjde tmavý a "jas pozadí"
 * vybere bílé písmo pro záplatu, která je ve skutečnosti světlá - nahlášené "bílý text
 * na bílé záplate" (nečitelné). Skutečné pixely záplaty jsou jediný zdroj pravdy o tom,
 * co se pod textem doopravdy vykreslí.
 *
 * Vzorkuje se mřížkou (~32x32 bodů) jen ve střední oblasti, ne celá plocha - text sedí
 * vycentrovaný, takže průměr z okrajů (kde může ležet jiná kresba) by rozhodnutí kazil.
 * Cena na bublinu zůstává zanedbatelná (~1 tisíc čtení pixelů).
 *
 * @param colorAt funkce čtení ARGB pixelu na souřadnici (typicky Bitmap::getPixel)
 * @return průměrný ARGB (alfa 0xFF), nebo null pro degenerovanou/nesvzorkovatelnou oblast
 */
internal fun patchMeanArgb(colorAt: (x: Int, y: Int) -> Int, width: Int, height: Int): Int? {
    val x0 = (width * 0.2f).toInt()
    val x1 = (width * 0.8f).toInt()
    val y0 = (height * 0.2f).toInt()
    val y1 = (height * 0.8f).toInt()
    if (x1 <= x0 || y1 <= y0) return null
    val stepX = ((x1 - x0) / PATCH_SAMPLE_STEPS).coerceAtLeast(1)
    val stepY = ((y1 - y0) / PATCH_SAMPLE_STEPS).coerceAtLeast(1)
    var r = 0L
    var g = 0L
    var b = 0L
    var n = 0L
    var y = y0
    while (y < y1) {
        var x = x0
        while (x < x1) {
            val c = colorAt(x, y)
            r += (c ushr 16) and 0xFF
            g += (c ushr 8) and 0xFF
            b += c and 0xFF
            n++
            x += stepX
        }
        y += stepY
    }
    if (n == 0L) return null
    return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
}

private const val PATCH_SAMPLE_STEPS = 32

/**
 * Sjednotí velikost písmen přeloženého textu s originálem, aby lettering vypadal jako
 * v původní bublině. Komiksový/manga lettering je konvenčně VELKÝMI PÍSMENY (viz reference
 * fotky: "IT'S OBVIOUS.", "I BET THEY'LL SAY..."), proto:
 *
 *  - Latinkový originál s převahou malých písmen (výjimka - třeba mixed-case webtoon
 *    naraceace) → necháme překlad tak, jak přišel (smíšený casing).
 *  - Cokoliv jiného (verzálkový latinkový originál NEBO CJK originál, kde velikost písmen
 *    vůbec neexistuje) → verzálky, což odpovídá klasickému komiksovému/manga letteringu.
 *
 * [text] je displayText (s měkkými rozdělovníky ze slabičného dělení) - uppercase je
 * neovlivní. Kotlin String.uppercase() zvládá i českou diakritiku (ř→Ř, ž→Ž, ů→Ů...).
 */
internal fun matchOriginalCase(text: String, original: String): String {
    val latinLetters = original.filter { it in 'a'..'z' || it in 'A'..'Z' }
    if (latinLetters.length >= 3) {
        val lower = latinLetters.count { it in 'a'..'z' }
        if (lower.toFloat() / latinLetters.length > 0.5f) return text
    }
    return text.uppercase()
}
