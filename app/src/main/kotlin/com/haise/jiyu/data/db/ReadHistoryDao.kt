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

    /** Protějšek [com.haise.jiyu.data.db.ManualTranslationDao.relinkChapter] - `chapterId` je
     * tady navíc primární klíč, ale SQLite dovolí UPDATE i na PK sloupec. Bez tohohle by po
     * relinku (viz MangaRepository.recoverMangaLink) historie čtení ukazovala na neexistující
     * staré id kapitoly. */
    @Query("UPDATE read_history SET chapterId = :newChapterId WHERE chapterId = :oldChapterId")
    suspend fun relinkChapter(oldChapterId: String, newChapterId: String)

    @Query("DELETE FROM read_history")
    suspend fun deleteAll()

    // 'localtime' modifikator - bez nej strftime pocita hranice dne v UTC, takze pozdne
    // nocni cteni v casove zone pred UTC muze skoncit prirazene k jinemu kalendarnimu dni,
    // nez ve kterem uzivatel doopravdy cetl (nahlaseno v auditu).
    @Query("""
        SELECT strftime('%Y-%m-%d', readAt/1000, 'unixepoch', 'localtime') as day, COUNT(*) as count
        FROM read_history WHERE readAt >= :sinceMs
        GROUP BY day ORDER BY day ASC
    """)
    suspend fun getDailyReadCounts(sinceMs: Long): List<DayCount>

    @Query("SELECT * FROM read_history ORDER BY readAt DESC")
    suspend fun getAll(): List<ReadHistoryEntity>

    @Upsert
    suspend fun upsertAll(entries: List<ReadHistoryEntity>)
}
