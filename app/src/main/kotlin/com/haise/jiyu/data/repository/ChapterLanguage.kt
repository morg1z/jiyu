package com.haise.jiyu.data.repository

import com.haise.jiyu.data.db.entity.ChapterEntity

/** Jazykova rodina kodu - "en", "en-us" i "EN" spadaji do "en". null zustava null. */
private fun languageFamily(lang: String?): String? =
    lang?.substringBefore('-')?.lowercase()?.ifBlank { null }

/**
 * Strict EN gate pro "co se smi otevrit" - silnejsi nez [preferEnglishChapters]
 * (dedup schvalne necha ne-EN cisla bez EN protejsku kvuli kompletnosti cislovani
 * seznamu). Agregovany rezim cte anglicky: radek s jinym jazykem se pro otevreni/
 * navigaci/pokracovani preskoci uplne, i kdyz EN verze tehoz cisla neexistuje
 * (audit - "Coffee wo Shizuka ni" oteviralo vietnamskou kapitolu, protoze
 * vyber ciloveho radku bezel jen podle cisla).
 *
 * null jazyk PROJDE - zdroj jazyk nerozlisuje a EN-only zdroje uz jsou odfiltrovane
 * driv ve vyhledavani (viz ComicKChapterResolver.isSearchLanguage).
 */
fun isEnglishChapterLanguage(lang: String?): Boolean {
    val fam = languageFamily(lang) ?: return true
    return fam == "en"
}

/** Viz [isEnglishChapterLanguage]. */
fun ChapterEntity.isEnglishChapter(): Boolean = isEnglishChapterLanguage(language)

/** Jen radky otevritelne v anglictine - viz [isEnglishChapterLanguage]. */
fun List<ChapterEntity>.englishOnlyChapters(): List<ChapterEntity> =
    filter { it.isEnglishChapter() }

/**
 * Dedup viceradkovych kapitol podle jazyka. Agregatory (ComicK, comickart) vraci
 * jednu logickou kapitolu per jazyk - bez filtru se v seznamu objevi "Ch.5" dvakrat
 * (anglicky i portugalsky) a radek neslibuje, co se otevre (audit - uzivatel hlasil
 * cizojazycnou kapitolu u Sato-san).
 *
 * Pravidla:
 * - U kazdeho cisla kapitoly (>0): existuje-li EN verze, zahodi se vsechny ne-EN
 *   radky; EN neexistuje-li, necha se vse (jinak by se kompletnost cisel ztratila
 *   u kapitol vydanych jen v jinem jazyce - duvod, proc zdroje jazyky nedropuji
 *   uz v getChapterList).
 * - Radky bez jazyka (null - zdroj ho nehlasi, nebo stary radek pred migraci)
 *   se nikdy nezahazuji.
 * - Cislo 0 (= zdroj cislo neudelil) se nededuplikuje - vice bezciselnych kapitol
 *   jsou vzdy odlisne entity (stejna konvence jako chapterKey v ChapterDao).
 * - Filtr se aktivuje jen kdyz se v seznamu skutecne michaji jazykove rodiny
 *   (>=2) - u beznich jednojazycnych zdroju je to no-op.
 *
 * Poradi se zachovava - vola se na uz serazeny seznam.
 */
fun List<ChapterEntity>.preferEnglishChapters(): List<ChapterEntity> {
    if (mapNotNullTo(HashSet()) { languageFamily(it.language) }.size < 2) return this
    return groupBy { if (it.chapterNumber > 0f) it.chapterNumber else null }
        .flatMap { (num, rows) ->
            val en = rows.filter { languageFamily(it.language) == "en" }
            if (num != null && en.isNotEmpty()) rows.filter {
                languageFamily(it.language) == "en" || it.language == null
            } else rows
        }
}
