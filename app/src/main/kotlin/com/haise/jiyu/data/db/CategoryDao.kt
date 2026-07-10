package com.haise.jiyu.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.haise.jiyu.data.db.entity.CategoryEntity
import com.haise.jiyu.data.db.entity.MangaCategoryEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import kotlinx.coroutines.flow.Flow

data class MangaCategoryMapping(val mangaId: String, val categoryId: String)

@Dao
interface CategoryDao {

    @Query("SELECT * FROM category ORDER BY name ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(category: CategoryEntity)

    @Delete
    suspend fun delete(category: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMangaToCategory(link: MangaCategoryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addAllMangaToCategories(links: List<MangaCategoryEntity>)

    @Query("DELETE FROM manga_category WHERE mangaId = :mangaId AND categoryId = :categoryId")
    suspend fun removeMangaFromCategory(mangaId: String, categoryId: String)

    @Query("SELECT categoryId FROM manga_category WHERE mangaId = :mangaId")
    fun observeCategoryIdsForManga(mangaId: String): Flow<List<String>>

    @Query("SELECT categoryId FROM manga_category WHERE mangaId = :mangaId")
    suspend fun getCategoryIdsForManga(mangaId: String): List<String>

    @Query("SELECT * FROM category ORDER BY name ASC")
    suspend fun getAllOnce(): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(categories: List<CategoryEntity>)

    @Query("SELECT mangaId, categoryId FROM manga_category")
    suspend fun getAllMappings(): List<MangaCategoryMapping>

    @Query("""
        SELECT m.* FROM manga m
        INNER JOIN manga_category mc ON m.id = mc.mangaId
        WHERE mc.categoryId = :categoryId AND m.inLibrary = 1
        ORDER BY m.title ASC
    """)
    fun observeMangaInCategory(categoryId: String): Flow<List<MangaEntity>>
}
