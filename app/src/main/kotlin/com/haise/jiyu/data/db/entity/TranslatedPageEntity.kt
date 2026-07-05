package com.haise.jiyu.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "translated_page")
data class TranslatedPageEntity(
    /** "$chapterId::$pageIndex::$targetLang" */
    @PrimaryKey val id: String,
    val blocksJson: String,
    val createdAt: Long = System.currentTimeMillis(),
)
