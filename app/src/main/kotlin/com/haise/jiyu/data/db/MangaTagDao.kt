package com.haise.jiyu.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.haise.jiyu.data.db.entity.MangaTagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MangaTagDao {
    @Query("SELECT * FROM manga_tag WHERE mangaId = :mangaId ORDER BY tag ASC")
    fun observeForManga(mangaId: String): Flow<List<MangaTagEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: MangaTagEntity)

    @Delete
    suspend fun delete(tag: MangaTagEntity)

    @Query("SELECT DISTINCT tag FROM manga_tag ORDER BY tag ASC")
    fun observeAllTags(): Flow<List<String>>

    @Query("SELECT mangaId FROM manga_tag WHERE tag = :tag")
    suspend fun getMangaIdsForTag(tag: String): List<String>

    @Query("SELECT * FROM manga_tag")
    suspend fun getAll(): List<MangaTagEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tags: List<MangaTagEntity>)
}
