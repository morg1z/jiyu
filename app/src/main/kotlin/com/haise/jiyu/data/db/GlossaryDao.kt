package com.haise.jiyu.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.haise.jiyu.data.db.entity.GlossaryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GlossaryDao {
    @Query("SELECT * FROM glossary_entry WHERE mangaId = :mangaId ORDER BY sourceTerm ASC")
    fun observeForManga(mangaId: String): Flow<List<GlossaryEntity>>

    @Query("SELECT * FROM glossary_entry WHERE mangaId = :mangaId AND targetLanguage = :targetLanguage")
    suspend fun getForMangaAndLanguage(mangaId: String, targetLanguage: String): List<GlossaryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: GlossaryEntity)

    @Delete
    suspend fun delete(entry: GlossaryEntity)
}
