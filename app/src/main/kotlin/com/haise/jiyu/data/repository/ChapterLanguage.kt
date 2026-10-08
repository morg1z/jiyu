package com.haise.jiyu.data.repository

import com.haise.jiyu.data.db.entity.ChapterEntity

/** Jazykova rodina kodu - "en", "en-us" i "EN" spadaji do "en". null zustava null. */
private fun languageFamily(lang: String?): String? =
    lang?.substringBefore('-')?.lowercase()?.ifBlank { null }

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
