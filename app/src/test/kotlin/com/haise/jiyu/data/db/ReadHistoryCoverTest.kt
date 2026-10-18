package com.haise.jiyu.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.db.entity.ReadHistoryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Obalky v historii cteni - [ReadHistoryDao.observeRecent] zije pres join na manga
 * tabulku (snapshot z doby cteni byva u resolved ComicK kapitol null, protoze
 * entity se zakladaji preview bez detailu). Padalo by to jako "u 50 polozek 3
 * obalky" - nahlaseny bug.
 */
@RunWith(RobolectricTestRunner::class)
class ReadHistoryCoverTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun manga(id: String, coverUrl: String?, resolvedMangaId: String? = null) =
        MangaEntity(
            id = id, sourceId = "src", url = "https://x/$id", title = "T$id",
            coverUrl = coverUrl, description = null, status = null,
            resolvedMangaId = resolvedMangaId,
        )

    private fun entry(chapterId: String, mangaId: String, coverUrl: String?, readAt: Long = 1L) =
        ReadHistoryEntity(
            chapterId = chapterId, mangaId = mangaId, mangaTitle = "M$mangaId",
            coverUrl = coverUrl, chapterName = "Kap", readAt = readAt,
        )

    @Test
    fun `null snapshot se doplni zive z manga entity kdyz obalka dorazi pozdeji`() = runTest {
        db.mangaDao().upsert(manga("m1", coverUrl = null))
        db.readHistoryDao().record(entry("c1", "m1", coverUrl = null))
        assertEquals(null, db.readHistoryDao().observeRecent().first().single().coverUrl)

        // Detail se dotahl az po zapisu historie - bez joinu by radek zustal bez obalky.
        db.mangaDao().upsert(manga("m1", coverUrl = "https://img/c.jpg"))
        assertEquals("https://img/c.jpg", db.readHistoryDao().observeRecent().first().single().coverUrl)
    }

    @Test
    fun `obalka comick rodice se pouzije kdyz resolved manga vlastni nema`() = runTest {
        // Resolved entita bez obalky, ComicK rodic na ni ukazuje a obalku ma.
        db.mangaDao().upsert(manga("resolved", coverUrl = null))
        db.mangaDao().upsert(manga("ck-parent", coverUrl = "https://ck/c.jpg", resolvedMangaId = "resolved"))
        db.readHistoryDao().record(entry("c1", "resolved", coverUrl = null))

        assertEquals("https://ck/c.jpg", db.readHistoryDao().observeRecent().first().single().coverUrl)
    }

    @Test
    fun `vlastni obalka entity ma prednost pred rodicem`() = runTest {
        db.mangaDao().upsert(manga("resolved", coverUrl = "https://own/c.jpg"))
        db.mangaDao().upsert(manga("ck-parent", coverUrl = "https://ck/c.jpg", resolvedMangaId = "resolved"))
        db.readHistoryDao().record(entry("c1", "resolved", coverUrl = null))

        assertEquals("https://own/c.jpg", db.readHistoryDao().observeRecent().first().single().coverUrl)
    }

    @Test
    fun `snapshot se pouzije jako posledni fallback a radek se nededuplikuje`() = runTest {
        // Manga uz v DB neni (odstranena), snapshot zustane jediny zdroj obalky.
        db.readHistoryDao().record(entry("c1", "gone", coverUrl = "https://snap/c.jpg", readAt = 2L))
        db.readHistoryDao().record(entry("c2", "gone2", coverUrl = null, readAt = 1L))

        val rows = db.readHistoryDao().observeRecent().first()
        assertEquals(2, rows.size)
        assertEquals("https://snap/c.jpg", rows.first { it.chapterId == "c1" }.coverUrl)
        assertEquals(null, rows.first { it.chapterId == "c2" }.coverUrl)
    }
}
