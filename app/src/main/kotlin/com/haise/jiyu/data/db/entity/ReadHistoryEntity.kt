package com.haise.jiyu.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "read_history",
    indices = [Index("mangaId"), Index("readAt")],
)
data class ReadHistoryEntity(
    @PrimaryKey val chapterId: String,
    val mangaId: String,
    val mangaTitle: String,
    val coverUrl: String?,
    val chapterName: String,
    val readAt: Long,
)
