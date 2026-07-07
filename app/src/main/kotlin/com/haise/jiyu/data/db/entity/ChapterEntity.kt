package com.haise.jiyu.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class DownloadStatus { NOT_DOWNLOADED, QUEUED, DOWNLOADING, DOWNLOADED, ERROR }

@Entity(tableName = "chapter", indices = [Index("mangaId"), Index("read"), Index("downloadStatus")])
data class ChapterEntity(
    @PrimaryKey val id: String,
    val mangaId: String,
    val sourceId: String,
    val url: String,
    val name: String,
    val chapterNumber: Float,
    val dateUpload: Long,
    val read: Boolean = false,
    val lastPageRead: Int = 0,
    val downloadStatus: DownloadStatus = DownloadStatus.NOT_DOWNLOADED,
    /** Lokální složka se staženými stránkami (pokud downloadStatus == DOWNLOADED). */
    val localPath: String? = null,
    val pageCount: Int = 0,
    val scanlationGroup: String? = null,
)
