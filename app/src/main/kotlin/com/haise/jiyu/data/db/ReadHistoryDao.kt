package com.haise.jiyu.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.haise.jiyu.data.db.entity.ReadHistoryEntity
import kotlinx.coroutines.flow.Flow

data class DayCount(val day: String, val count: Int)

@Dao
interface ReadHistoryDao {
    @Upsert
    suspend fun record(entry: ReadHistoryEntity)

    @Query("SELECT * FROM read_history ORDER BY readAt DESC LIMIT 200")
    fun observeRecent(): Flow<List<ReadHistoryEntity>>

    @Query("SELECT * FROM read_history WHERE mangaId = :mangaId ORDER BY readAt DESC")
    fun observeForManga(mangaId: String): Flow<List<ReadHistoryEntity>>

    @Query("DELETE FROM read_history WHERE chapterId = :chapterId")
    suspend fun delete(chapterId: String)

    @Query("DELETE FROM read_history WHERE mangaId = :mangaId")
    suspend fun deleteForManga(mangaId: String)

    @Query("DELETE FROM read_history")
    suspend fun deleteAll()

    @Query("""
        SELECT strftime('%Y-%m-%d', readAt/1000, 'unixepoch') as day, COUNT(*) as count
        FROM read_history WHERE readAt >= :sinceMs
        GROUP BY day ORDER BY day ASC
    """)
    suspend fun getDailyReadCounts(sinceMs: Long): List<DayCount>
}
