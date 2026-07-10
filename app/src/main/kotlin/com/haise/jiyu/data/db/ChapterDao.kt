package com.haise.jiyu.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
import kotlinx.coroutines.flow.Flow

data class MangaUnreadCount(val mangaId: String, val count: Int)
data class MangaTotalCount(val mangaId: String, val count: Int)
data class MangaDownloadedCount(val mangaId: String, val count: Int)

data class UpdateItem(
    val chapterId: String,
    val chapterName: String,
    val chapterNumber: Float,
    val dateUpload: Long,
    val mangaId: String,
    val mangaTitle: String,
    val coverUrl: String?,
    val sourceId: String,
    val read: Boolean,
)

@Dao
interface ChapterDao {

    /** Full upsert — používej POUZE pro import zálohy kde chceme obnovit i read/download stav. */
    @Upsert
    suspend fun upsertAll(chapters: List<ChapterEntity>)

    /** Vloží jen nové kapitoly; existující nechá beze změny (zachová read/download stav). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNewOnly(chapters: List<ChapterEntity>)

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

    @Query("SELECT COUNT(*) FROM chapter WHERE mangaId = :mangaId")
    suspend fun countForManga(mangaId: String): Int

    @Query("SELECT * FROM chapter WHERE mangaId = :mangaId ORDER BY chapterNumber DESC")
    suspend fun getAllForManga(mangaId: String): List<ChapterEntity>

    @Query("SELECT COUNT(*) FROM chapter WHERE read = 1")
    suspend fun countRead(): Int

    @Query("SELECT COUNT(*) FROM chapter WHERE read = 1")
    fun observeReadCount(): Flow<Int>

    @Query("SELECT * FROM chapter WHERE mangaId IN (SELECT id FROM manga WHERE inLibrary = 1)")
    suspend fun getAllForLibrary(): List<ChapterEntity>

    // ── Counts per manga ──────────────────────────────────────────────────────

    @Query("SELECT mangaId, COUNT(*) as count FROM chapter WHERE read = 0 GROUP BY mangaId")
    fun observeUnreadCounts(): Flow<List<MangaUnreadCount>>

    @Query("SELECT mangaId, COUNT(*) as count FROM chapter GROUP BY mangaId")
    fun observeTotalCounts(): Flow<List<MangaTotalCount>>

    @Query("SELECT mangaId, COUNT(*) as count FROM chapter WHERE downloadStatus = 'DOWNLOADED' GROUP BY mangaId")
    fun observeDownloadedCountPerManga(): Flow<List<MangaDownloadedCount>>

    // ── Download management ───────────────────────────────────────────────────

    @Query("SELECT * FROM chapter WHERE downloadStatus != 'NOT_DOWNLOADED' ORDER BY mangaId ASC, chapterNumber DESC")
    fun observeNonEmptyDownloads(): Flow<List<ChapterEntity>>

    @Query("SELECT COUNT(*) FROM chapter WHERE downloadStatus = 'DOWNLOADED'")
    fun observeDownloadedCount(): Flow<Int>

    @Query("UPDATE chapter SET downloadStatus = 'NOT_DOWNLOADED', localPath = NULL, pageCount = 0 WHERE downloadStatus = 'DOWNLOADED'")
    suspend fun clearAllDownloaded()

    @Query("UPDATE chapter SET downloadStatus = 'NOT_DOWNLOADED', localPath = NULL, pageCount = 0 WHERE id = :id")
    suspend fun resetDownloadForChapter(id: String)

    @Query("""
        SELECT c.id as chapterId, c.name as chapterName, c.chapterNumber, c.dateUpload,
               c.mangaId, m.title as mangaTitle, m.coverUrl, c.sourceId, c.read
        FROM chapter c
        INNER JOIN manga m ON c.mangaId = m.id
        WHERE m.inLibrary = 1
        ORDER BY c.dateUpload DESC
        LIMIT 200
    """)
    fun observeUpdates(): Flow<List<UpdateItem>>

    @Query("UPDATE chapter SET read = 1 WHERE mangaId IN (SELECT id FROM manga WHERE inLibrary = 1)")
    suspend fun markAllRead()

    @Query("SELECT id FROM chapter WHERE mangaId = :mangaId")
    suspend fun getAllIdsForManga(mangaId: String): List<String>

    @Query("UPDATE chapter SET read = 1, lastPageRead = 0 WHERE mangaId IN (:mangaIds)")
    suspend fun markAllReadForMangas(mangaIds: List<String>)

    @Query("UPDATE chapter SET downloadStatus = 'NOT_DOWNLOADED' WHERE downloadStatus IN ('QUEUED', 'DOWNLOADING')")
    suspend fun resetActiveDownloads()
}
