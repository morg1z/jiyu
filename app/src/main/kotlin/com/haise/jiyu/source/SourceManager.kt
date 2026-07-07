package com.haise.jiyu.source

import com.haise.jiyu.data.db.CustomSourceDao
import com.haise.jiyu.source.comick.ComicKSource
import com.haise.jiyu.source.hitomi.HitomiSource
import com.haise.jiyu.source.nhentai.NhentaiSource
import com.haise.jiyu.source.madara.MadaraSelectors
import com.haise.jiyu.source.madara.MadaraSource
import com.haise.jiyu.source.mangadex.MangaDexSource
import com.haise.jiyu.source.mangaplus.MangaPlusSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centrální registr zdrojů. Statické zdroje (MangaDex, MANGA Plus, ComicK)
 * jsou pevně dané; k nim se přidávají uživatelem nakonfigurované generické
 * Madara zdroje z `CustomSourceDao` - proto je seznam reaktivní (Flow),
 * ne statický snapshot.
 */
@Singleton
class SourceManager @Inject constructor(
    mangaDexSource: MangaDexSource,
    mangaPlusSource: MangaPlusSource,
    comicKSource: ComicKSource,
    hitomiSource: HitomiSource,
    nhentaiSource: NhentaiSource,
    private val customSourceDao: CustomSourceDao,
    private val client: OkHttpClient,
) {
    private val staticSources: List<MangaSource> = listOf(
        mangaDexSource,
        mangaPlusSource,
        comicKSource,
        hitomiSource,
        nhentaiSource,
    )

    fun observeAll(): Flow<List<MangaSource>> =
        customSourceDao.observeAll().map { customs ->
            staticSources + customs.map { custom ->
                val defaults = MadaraSelectors.DEFAULT
                MadaraSource(
                    id = "madara:${custom.id}",
                    name = custom.name,
                    baseUrl = custom.baseUrl,
                    client = client,
                    selectors = MadaraSelectors(
                        listItem = custom.listItemSelector?.ifBlank { null } ?: defaults.listItem,
                        titleLink = custom.titleLinkSelector?.ifBlank { null } ?: defaults.titleLink,
                        description = custom.descriptionSelector?.ifBlank { null } ?: defaults.description,
                        status = custom.statusSelector?.ifBlank { null } ?: defaults.status,
                        chapterList = custom.chapterListSelector?.ifBlank { null } ?: defaults.chapterList,
                        pageImage = custom.pageImageSelector?.ifBlank { null } ?: defaults.pageImage,
                    ),
                )
            }
        }

    suspend fun getAll(): List<MangaSource> = observeAll().first()

    suspend fun getById(id: String): MangaSource? = getAll().find { it.id == id }
}
