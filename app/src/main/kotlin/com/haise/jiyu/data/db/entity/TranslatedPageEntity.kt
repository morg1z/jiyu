package com.haise.jiyu.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "translated_page")
data class TranslatedPageEntity(
    /** Viz TranslateRepository.cacheId: "$chapterId::$pageIndex::$sourceLanguage::$targetLanguage::v$PIPELINE_VERSION" */
    @PrimaryKey val id: String,
    val blocksJson: String,
    val createdAt: Long = System.currentTimeMillis(),
)
