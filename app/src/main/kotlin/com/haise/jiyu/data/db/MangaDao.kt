package com.haise.jiyu.data.db

import androidx.room.Dao
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.haise.jiyu.data.db.entity.MangaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MangaDao {

    @Upsert
    suspend fun upsert(manga: MangaEntity)

    @Query("SELECT * FROM manga WHERE inLibrary = 1 ORDER BY title ASC")
    fun observeLibrary(): Flow<List<MangaEntity>>

    @Query("SELECT * FROM manga WHERE id = :id")
    suspend fun getById(id: String): MangaEntity?

    @Query("UPDATE manga SET inLibrary = :inLibrary WHERE id = :id")
    suspend fun setInLibrary(id: String, inLibrary: Boolean)
}
