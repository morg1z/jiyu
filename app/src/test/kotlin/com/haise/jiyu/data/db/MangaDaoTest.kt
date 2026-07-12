package com.haise.jiyu.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.haise.jiyu.data.db.entity.MangaEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pokryva presne ty operace, ktere tato session pridala pro tracker (MAL/Kitsu/
 * MangaUpdates) propojeni a per-manga cas cteni - viz [[project_jiyu_implemented]].
 */
@RunWith(RobolectricTestRunner::class)
class MangaDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MangaDao

    private fun manga(id: String) = MangaEntity(
        id = id, sourceId = "mangadex", url = "https://example.com/$id",
        title = "Test $id", coverUrl = null, description = null, status = null,
        inLibrary = true,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.mangaDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `setMalId, setKitsuId and setMangaUpdatesId persist independently`() = runTest {
        dao.upsert(manga("m1"))

        dao.setMalId("m1", 5114)
        dao.setKitsuId("m1", "kitsu-42")
        dao.setMangaUpdatesId("m1", 99L)

        val result = dao.getById("m1")!!
        assertEquals(5114, result.malId)
        assertEquals("kitsu-42", result.kitsuId)
        assertEquals(99L, result.mangaUpdatesId)
    }

    @Test
    fun `unlinking a tracker sets its id back to null without touching the others`() = runTest {
        dao.upsert(manga("m1"))
        dao.setMalId("m1", 5114)
        dao.setKitsuId("m1", "kitsu-42")

        dao.setMalId("m1", null)

        val result = dao.getById("m1")!!
        assertNull(result.malId)
        assertEquals("kitsu-42", result.kitsuId)
    }

    @Test
    fun `addReadingTime accumulates across multiple calls instead of overwriting`() = runTest {
        dao.upsert(manga("m1"))

        dao.addReadingTime("m1", 60_000L)
        dao.addReadingTime("m1", 30_000L)
        dao.addReadingTime("m1", 15_000L)

        assertEquals(105_000L, dao.getById("m1")!!.readingTimeMs)
    }

    @Test
    fun `addReadingTime on one manga does not affect another`() = runTest {
        dao.upsert(manga("m1"))
        dao.upsert(manga("m2"))

        dao.addReadingTime("m1", 60_000L)

        assertEquals(60_000L, dao.getById("m1")!!.readingTimeMs)
        assertEquals(0L, dao.getById("m2")!!.readingTimeMs)
    }

    @Test
    fun `setReadingStatus filters observeByReadingStatus correctly`() = runTest {
        dao.upsert(manga("m1"))
        dao.upsert(manga("m2"))
        dao.setReadingStatus("m1", "READING")
        dao.setReadingStatus("m2", "COMPLETED")

        assertEquals("READING", dao.getById("m1")!!.readingStatus)
        assertEquals("COMPLETED", dao.getById("m2")!!.readingStatus)
    }
}
