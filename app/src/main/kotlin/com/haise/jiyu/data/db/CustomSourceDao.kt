package com.haise.jiyu.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.haise.jiyu.data.db.entity.CustomSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomSourceDao {

    @Query("SELECT * FROM custom_source ORDER BY name ASC")
    fun observeAll(): Flow<List<CustomSourceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: CustomSourceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sources: List<CustomSourceEntity>)

    @Query("SELECT * FROM custom_source ORDER BY name ASC")
    suspend fun getAllOnce(): List<CustomSourceEntity>

    @Delete
    suspend fun delete(source: CustomSourceEntity)
}
