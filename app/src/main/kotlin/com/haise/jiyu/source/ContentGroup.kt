package com.haise.jiyu.source

/**
 * MANGA/MANHWA/MANHUA se pro účely křížového hledání titulu berou jako jedna skupina (region
 * asijského komiksu) - spousta zdrojů má title-level typ smíchaný a jen jeden "výchozí"
 * contentType na úrovni celého zdroje. Novely a americké komiksy se mezi sebou nemíchají.
 * Sdílené pravidlo: používá ho ComicKChapterResolver i CrossSourceSearch (relink na jiný
 * zdroj) - stejná konvence jako `BrowseViewModel.MANGA_GROUP` pro Procházet.
 */
internal fun isSameContentGroup(sourceType: String, targetType: String): Boolean {
    val asianComicTypes = setOf("MANGA", "MANHWA", "MANHUA")
    return if (targetType in asianComicTypes) sourceType in asianComicTypes else sourceType == targetType
}
