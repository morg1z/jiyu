package com.haise.jiyu.data.repository

import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga

/**
 * Vybere nejjistějšího kandidáta pro "tohle je stejný titul, jen na nové URL" - viz
 * [MangaRepository.recoverMangaLink]. Radši žádnou shodu než špatnou: pokud je přesných
 * shod (case-insensitive) víc, nebo není žádná přesná a kandidátů je víc než 1, appka se
 * vzdá (vrátí null).
 */
fun findBestTitleMatch(candidates: List<SManga>, originalTitle: String): SManga? {
    val exact = candidates.filter { it.title.equals(originalTitle, ignoreCase = true) }
    return when {
        exact.size == 1 -> exact.first()
        exact.isEmpty() && candidates.size == 1 -> candidates.single()
        else -> null
    }
}

data class ChapterMigrationPlan(
    val relink: List<Pair<ChapterEntity, SChapter>>,
    val newOnly: List<SChapter>,
)

/**
 * Napáruje STARÉ (uložené) a NOVÉ (čerstvě stažené ze zdroje) kapitoly podle čísla kapitoly -
 * viz [MangaRepository.recoverMangaLink]. Předpokládá, že [newChapters] nemá duplicitní čísla
 * (agregované zdroje typu ComicK, kde by to neplatilo, [MangaRepository.recoverMangaLink]
 * vůbec nevolá). Duplicitní číslo v [newChapters] se přesto ošetří použitím jen PRVNÍHO
 * výskytu, aby se žádný starý řádek nepřemapoval dvakrát.
 */
fun planChapterMigration(oldChapters: List<ChapterEntity>, newChapters: List<SChapter>): ChapterMigrationPlan {
    val oldByNumber = oldChapters.associateBy { it.chapterNumber }
    val seenNumbers = mutableSetOf<Float>()
    val relink = mutableListOf<Pair<ChapterEntity, SChapter>>()
    val newOnly = mutableListOf<SChapter>()
    for (newCh in newChapters) {
        if (!seenNumbers.add(newCh.chapterNumber)) continue
        val old = oldByNumber[newCh.chapterNumber]
        if (old != null) relink.add(old to newCh) else newOnly.add(newCh)
    }
    return ChapterMigrationPlan(relink, newOnly)
}
