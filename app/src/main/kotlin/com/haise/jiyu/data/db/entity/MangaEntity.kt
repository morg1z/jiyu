package com.haise.jiyu.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `id` je vždy "{sourceId}::{url}" - díky tomu je unikátní napříč zdroji
 * a nepotřebujeme žádný centrální generátor ID.
 */
@Entity(
    tableName = "manga",
    indices = [
        Index("inLibrary"),
        Index("sourceId"),
        Index(value = ["inLibrary", "sourceId"]),
        Index("lastReadAt"),
        Index("title"),
        Index("addedAt"),
        Index("lastUpdated"),
        Index("isFavorite"),
        Index("url"),
    ],
)
data class MangaEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val url: String,
    val title: String,
    val coverUrl: String?,
    val description: String?,
    val status: String?,
    val inLibrary: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis(),
    val lastReadChapterId: String? = null,
    val lastReadAt: Long = 0L,
    val readerDirectionOverride: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val genres: String = "",
    val year: Int? = null,
    val autoDownload: Boolean = false,
    val userRating: Int? = null,
    val contentType: String = "MANGA",
    val excludeFromUpdates: Boolean = false,
    val malId: Int? = null,
    val malScore: Float? = null,
    val malStatus: String? = null,
    val addedAt: Long = 0,
    val readingStatus: String? = null,
    val kitsuId: String? = null,
    val kitsuScore: Float? = null,
    val mangaUpdatesId: Long? = null,
    val readingTimeMs: Long = 0L,
    val isFavorite: Boolean = false,
    val demographic: String? = null,
    val translationCompleted: Boolean? = null,
    val hasAnime: Boolean? = null,
    val finalChapter: String? = null,
    val rating: Double? = null,
    val followCount: Int? = null,
    val rank: Int? = null,
    /** JSON pole řetězců - viz [com.haise.jiyu.data.repository.serializeAltTitles]. */
    val alternateTitles: String = "",
    /**
     * Volitelný volný text posílaný AI překladači jako doplňkový kontext díla (viz
     * [com.haise.jiyu.translate.GeminiUltraPrompt.buildMangaContext]) - třeba "hlavní hrdina
     * je ve skutečnosti žena v přestrojení" nebo "děj je celý retrospektiva". Na rozdíl od
     * [MangaNoteEntity] (čistě soukromá poznámka čtenáře, NIKDY se neposílá žádnému API) je
     * tohle POLE VÝSLOVNĚ určené k odeslání ven - proto samostatné pole, ne recyklace
     * poznámky, aby si uživatel omylem neposlal soukromý text tam, kam nechtěl.
     */
    val translationContextNote: String? = null,
)
