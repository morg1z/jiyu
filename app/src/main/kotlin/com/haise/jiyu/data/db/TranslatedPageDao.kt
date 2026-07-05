package com.haise.jiyu.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.haise.jiyu.data.db.entity.TranslatedPageEntity

@Dao
interface TranslatedPageDao {
    @Query("SELECT * FROM translated_page WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TranslatedPageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TranslatedPageEntity)

    @Query("SELECT COUNT(*) FROM translated_page")
    suspend fun count(): Int

    @Query("DELETE FROM translated_page")
    suspend fun deleteAll()
}
