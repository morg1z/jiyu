package com.haise.jiyu.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "translated_novel")
data class TranslatedNovelEntity(
    /** "$chapterId::$sourceLang::$targetLang" */
    @PrimaryKey val id: String,
    val translatedText: String,
    val createdAt: Long = System.currentTimeMillis(),
)
