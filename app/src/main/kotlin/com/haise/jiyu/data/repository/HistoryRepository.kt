package com.haise.jiyu.data.repository

import com.haise.jiyu.data.db.DayCount
import com.haise.jiyu.data.db.ReadHistoryDao
import com.haise.jiyu.data.db.entity.ReadHistoryEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Historie čtení - tenká vrstva nad [ReadHistoryDao], aby k DAO nesahaly ViewModely přímo. */
@Singleton
class HistoryRepository @Inject constructor(
    private val dao: ReadHistoryDao,
) {
    fun observeRecent(): Flow<List<ReadHistoryEntity>> = dao.observeRecent()
    suspend fun record(entry: ReadHistoryEntity) = dao.record(entry)
    suspend fun delete(chapterId: String) = dao.delete(chapterId)
    suspend fun deleteAll() = dao.deleteAll()
    suspend fun dailyReadCounts(sinceMs: Long): List<DayCount> = dao.getDailyReadCounts(sinceMs)
}
