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
    // Parovani podle zaokrouhleneho klice (tisicina), NE presne Float rovnosti - `chapterNumber`
    // muze do appky prijit dvema ruznymi cestami (String->Float regex parsing ze zivych zdroju,
    // vs. Double->Float konverze pri Tachiyomi/JSON zaloha importu), ktere se u binarne
    // nepresnych desetinnych cisel (napr. 1.005) mohou zaokrouhlit jinak - presna shoda by pak
    // "stejnou" kapitolu vyhodnotila jako novou (duplikat), viz audit nalez.
    val oldByNumber = oldChapters.associateBy { chapterMatchKey(it.chapterNumber) }
    val seenNumbers = mutableSetOf<Int>()
    val relink = mutableListOf<Pair<ChapterEntity, SChapter>>()
    val newOnly = mutableListOf<SChapter>()
    for (newCh in newChapters) {
        val key = chapterMatchKey(newCh.chapterNumber)
        if (!seenNumbers.add(key)) continue
        val old = oldByNumber[key]
        if (old != null) relink.add(old to newCh) else newOnly.add(newCh)
    }
    return ChapterMigrationPlan(relink, newOnly)
}

/** Tolerance na tisicinu - vstrebe zaokrouhlovaci sum mezi Double->Float a String->Float cestami, ale porad rozlisi opravdu ruzne kapitoly (1.1 vs 1.2). */
internal fun chapterMatchKey(chapterNumber: Float): Int = Math.round(chapterNumber * 1000f)
