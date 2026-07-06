package com.haise.jiyu.source

import com.haise.jiyu.source.comick.ComicKSource
import com.haise.jiyu.source.mangadex.MangaDexSource
import com.haise.jiyu.source.mangaplus.MangaPlusSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centrální registr zdrojů. Nový zdroj se přidá tak, že se sem
 * přidá do seznamu `sources` - nikde jinde v appce se nic měnit nemusí.
 */
@Singleton
class SourceManager @Inject constructor(
    mangaDexSource: MangaDexSource,
    mangaPlusSource: MangaPlusSource,
    comicKSource: ComicKSource,
) {
    private val sources: List<MangaSource> = listOf(
        mangaDexSource,
        mangaPlusSource,
        comicKSource,
    )

    fun getAll(): List<MangaSource> = sources

    fun getById(id: String): MangaSource? = sources.find { it.id == id }
}
