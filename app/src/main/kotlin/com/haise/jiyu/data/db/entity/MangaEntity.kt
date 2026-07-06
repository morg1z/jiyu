package com.haise.jiyu.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `id` je vždy "{sourceId}::{url}" - díky tomu je unikátní napříč zdroji
 * a nepotřebujeme žádný centrální generátor ID.
 */
@Entity(tableName = "manga")
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
)
