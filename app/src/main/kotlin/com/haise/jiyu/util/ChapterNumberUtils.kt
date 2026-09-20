package com.haise.jiyu.util

private val KEYWORD_NUMBER = Regex(
    """(?<![\p{L}\d])(?:chapters?|chap|ch|cap[ií]tulo|cap|episodes?|ep|kapitola|kap|chapitre|épisode|episodio)\s*\.?\s*(\d+(?:\.\d+)?)""",
    RegexOption.IGNORE_CASE,
)

private val FIRST_NUMBER = Regex("""\d+(?:\.\d+)?""")

/**
 * Číslo kapitoly z jejího názvu. Nejdřív číslo za klíčovým slovem (Chapter/Ch./Cap./Ep./Kapitola...),
 * teprve pak první číslo v textu. Záměrně NE holé `[\d.]+`, které dřív používala většina zdrojů: chytí i
 * samotnou tečku ("Cap. 12", "Ep. 3" → ".") a `toFloatOrNull()` pak dá null, takže se všechny kapitoly
 * titulu staly 0, a "Vol.3 Chapter 15" dalo 0.3 (audit nález JIYU-SRC-2). `null` = v názvu žádné číslo není.
 */
fun parseChapterNumber(name: String): Float? =
    KEYWORD_NUMBER.find(name)?.groupValues?.get(1)?.toFloatOrNull()
        ?: FIRST_NUMBER.find(name)?.value?.toFloatOrNull()
