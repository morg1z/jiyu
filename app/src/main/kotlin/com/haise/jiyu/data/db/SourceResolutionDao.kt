package com.haise.jiyu.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.haise.jiyu.data.db.entity.SourceResolutionEntity

@Dao
interface SourceResolutionDao {
    @Query("SELECT * FROM source_resolution WHERE comickMangaId = :comickMangaId")
    suspend fun get(comickMangaId: String): SourceResolutionEntity?

    @Upsert
    suspend fun upsert(entity: SourceResolutionEntity)

    @Query("DELETE FROM source_resolution WHERE comickMangaId = :comickMangaId")
    suspend fun delete(comickMangaId: String)
}
