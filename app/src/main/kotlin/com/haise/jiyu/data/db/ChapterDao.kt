package com.haise.jiyu.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {

    @Upsert
    suspend fun upsertAll(chapters: List<ChapterEntity>)

    @Query("SELECT * FROM chapter WHERE mangaId = :mangaId ORDER BY chapterNumber DESC")
    fun observeForManga(mangaId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapter WHERE id = :id")
    suspend fun getById(id: String): ChapterEntity?

    @Query("UPDATE chapter SET downloadStatus = :status WHERE id = :id")
    suspend fun setDownloadStatus(id: String, status: DownloadStatus)

    @Query("UPDATE chapter SET downloadStatus = :status, localPath = :localPath, pageCount = :pageCount WHERE id = :id")
    suspend fun markDownloaded(id: String, status: DownloadStatus, localPath: String, pageCount: Int)

    @Query("UPDATE chapter SET read = :read, lastPageRead = :lastPageRead WHERE id = :id")
    suspend fun updateProgress(id: String, read: Boolean, lastPageRead: Int)
}
