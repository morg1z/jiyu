package com.haise.jiyu.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "manga_note")
data class MangaNoteEntity(
    @PrimaryKey val mangaId: String,
    val content: String,
    val updatedAt: Long = System.currentTimeMillis(),
)
