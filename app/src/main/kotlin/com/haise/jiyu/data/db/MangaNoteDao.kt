package com.haise.jiyu.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.haise.jiyu.data.db.entity.MangaNoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MangaNoteDao {
    @Query("SELECT * FROM manga_note WHERE mangaId = :mangaId")
    fun observeForManga(mangaId: String): Flow<MangaNoteEntity?>

    @Upsert
    suspend fun upsert(note: MangaNoteEntity)

    @Query("DELETE FROM manga_note WHERE mangaId = :mangaId")
    suspend fun deleteForManga(mangaId: String)

    @Query("SELECT * FROM manga_note")
    suspend fun getAll(): List<MangaNoteEntity>

    @Upsert
    suspend fun upsertAll(notes: List<MangaNoteEntity>)
}
