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
)
