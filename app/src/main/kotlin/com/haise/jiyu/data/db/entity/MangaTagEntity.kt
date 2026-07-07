package com.haise.jiyu.data.db.entity

import androidx.room.Entity

@Entity(tableName = "manga_tag", primaryKeys = ["mangaId", "tag"])
data class MangaTagEntity(
    val mangaId: String,
    val tag: String,
)
