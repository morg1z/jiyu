package com.haise.jiyu.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.haise.jiyu.data.db.entity.CategoryEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Pokryva kategorie assignment/removal pouzivane bulkAddToCategory / addMangaToCategory ve viewmodelech. */
@RunWith(RobolectricTestRunner::class)
class CategoryDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var categoryDao: CategoryDao
    private lateinit var mangaDao: MangaDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        categoryDao = db.categoryDao()
        mangaDao = db.mangaDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun manga(id: String) = mangaDao.upsert(
        MangaEntity(id = id, sourceId = "mangadex", url = "https://example.com/$id", title = "Test $id",
            coverUrl = null, description = null, status = null, inLibrary = true)
    )

    @Test
    fun `addMangaToCategory then removeMangaFromCategory round-trips cleanly`() = runTest {
        categoryDao.upsert(CategoryEntity(id = "c1", name = "Reading"))
        manga("m1")

        categoryDao.addMangaToCategory(com.haise.jiyu.data.db.entity.MangaCategoryEntity("m1", "c1"))
        assertEquals(listOf("c1"), categoryDao.getCategoryIdsForManga("m1"))

        categoryDao.removeMangaFromCategory("m1", "c1")
        assertTrue(categoryDao.getCategoryIdsForManga("m1").isEmpty())
    }

    @Test
    fun `addAllMangaToCategories bulk-assigns multiple manga at once`() = runTest {
        categoryDao.upsert(CategoryEntity(id = "c1", name = "Reading"))
        manga("m1")
        manga("m2")

        categoryDao.addAllMangaToCategories(
            listOf(
                com.haise.jiyu.data.db.entity.MangaCategoryEntity("m1", "c1"),
                com.haise.jiyu.data.db.entity.MangaCategoryEntity("m2", "c1"),
            )
        )

        assertEquals(listOf("c1"), categoryDao.getCategoryIdsForManga("m1"))
        assertEquals(listOf("c1"), categoryDao.getCategoryIdsForManga("m2"))
    }

    @Test
    fun `observeMangaInCategory only returns manga currently in library`() = runTest {
        categoryDao.upsert(CategoryEntity(id = "c1", name = "Reading"))
        manga("m1")
        categoryDao.addMangaToCategory(com.haise.jiyu.data.db.entity.MangaCategoryEntity("m1", "c1"))

        mangaDao.setInLibrary("m1", false)

        assertTrue(categoryDao.observeMangaInCategory("c1").first().isEmpty())
    }

    @Test
    fun `deleting a category cascades and removes its manga_category links`() = runTest {
        val category = CategoryEntity(id = "c1", name = "Reading")
        categoryDao.upsert(category)
        manga("m1")
        categoryDao.addMangaToCategory(com.haise.jiyu.data.db.entity.MangaCategoryEntity("m1", "c1"))

        categoryDao.delete(category)

        assertTrue(categoryDao.getCategoryIdsForManga("m1").isEmpty())
    }
}
