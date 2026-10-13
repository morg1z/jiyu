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

    @Test
    fun `continue reading item exposes last chapter progress for the percent badge`() = runTest {
        dao.upsert(manga("m1").copy(lastReadChapterId = "ch-1", lastReadAt = 5L))
        db.chapterDao().insertNewOnly(listOf(
            com.haise.jiyu.data.db.entity.ChapterEntity(
                id = "ch-1", mangaId = "m1", sourceId = "comick", url = "u",
                name = "Ch 12", chapterNumber = 12f, dateUpload = 0L,
                read = false, lastPageRead = 7, verifiedPageCount = 30,
            ),
        ))

        val item = dao.getContinueReadingForManga("m1")!!
        assertEquals(12f, item.lastChapterNumber)
        assertEquals(false, item.lastChapterRead)
        assertEquals(7, item.lastPageRead)
        // verifiedPageCount má přednost před download pageCount.
        assertEquals(30, item.lastPageCount)
    }

    @Test
    fun `continue reading item falls back to download pageCount when not verified`() = runTest {
        dao.upsert(manga("m1").copy(lastReadChapterId = "ch-1", lastReadAt = 5L))
        db.chapterDao().insertNewOnly(listOf(
            com.haise.jiyu.data.db.entity.ChapterEntity(
                id = "ch-1", mangaId = "m1", sourceId = "mangadex", url = "u",
                name = "Ch 3", chapterNumber = 3f, dateUpload = 0L,
                read = true, lastPageRead = 0, pageCount = 20,
            ),
        ))

        val item = dao.getContinueReadingForManga("m1")!!
        assertEquals(true, item.lastChapterRead)
        assertEquals(20, item.lastPageCount)
    }

    private fun comickManga(id: String, hid: String? = "hid-$id") =
        manga(id).copy(sourceId = "comick", comickHid = hid)

    private fun chapter(id: String, mangaId: String) =
        com.haise.jiyu.data.db.entity.ChapterEntity(
            id = id, mangaId = mangaId, sourceId = "comick", url = "u",
            name = "Ch 1", chapterNumber = 1f, dateUpload = 0L,
        )

    @Test
    fun `comickMangaMissingChapters returns imported comick manga without chapters`() = runTest {
        dao.upsert(comickManga("imported"))
        assertEquals(listOf("imported"), dao.comickMangaMissingChapters().map { it.id })
    }

    @Test
    fun `comickMangaMissingChapters skips comick manga that already has chapters`() = runTest {
        dao.upsert(comickManga("with-chapters"))
        db.chapterDao().insertNewOnly(listOf(chapter("ch-1", "with-chapters")))
        assertEquals(emptyList<MangaEntity>(), dao.comickMangaMissingChapters())
    }

    @Test
    fun `comickMangaMissingChapters skips comick manga not bound to a library entry`() = runTest {
        // Bez comickHid nelze remote sync provest - prohlizene Comick tituly
        // (browse, ne import) backfill neresit, jejich kapitoly se dotahne linie.
        dao.upsert(comickManga("unlinked", hid = null))
        assertEquals(emptyList<MangaEntity>(), dao.comickMangaMissingChapters())
    }

    @Test
    fun `comickMangaMissingChapters skips chapterless manga from other sources`() = runTest {
        dao.upsert(manga("regular"))
        assertEquals(emptyList<MangaEntity>(), dao.comickMangaMissingChapters())
    }

    @Test
    fun `comickMangaMissingGenres returns imported comick manga with empty genres`() = runTest {
        dao.upsert(comickManga("imported"))
        assertEquals(listOf("imported"), dao.comickMangaMissingGenres().map { it.id })
    }

    @Test
    fun `comickMangaMissingGenres skips comick manga that already has genres`() = runTest {
        dao.upsert(comickManga("with-genres").copy(genres = "Action,Fantasy"))
        assertEquals(emptyList<MangaEntity>(), dao.comickMangaMissingGenres())
    }

    @Test
    fun `comickMangaMissingGenres skips comick manga not bound to a library entry`() = runTest {
        dao.upsert(comickManga("unlinked", hid = null))
        assertEquals(emptyList<MangaEntity>(), dao.comickMangaMissingGenres())
    }

    @Test
    fun `comickMangaMissingGenres skips genreless manga from other sources`() = runTest {
        // Jinym zdrojum genre-backfill neslouzi - jejich detail se naplni linie.
        dao.upsert(manga("regular"))
        assertEquals(emptyList<MangaEntity>(), dao.comickMangaMissingGenres())
    }

    @Test
    fun `comickMangaMissingChapters skips comick manga removed from library`() = runTest {
        // Tombstone (inLibrary=0) - backfill nesmi ozivit uzivatelem odebrany titul.
        dao.upsert(comickManga("removed").copy(inLibrary = false))
        assertEquals(emptyList<MangaEntity>(), dao.comickMangaMissingChapters())
    }

    @Test
    fun `comickMangaMissingGenres skips comick manga removed from library`() = runTest {
        dao.upsert(comickManga("removed").copy(inLibrary = false))
        assertEquals(emptyList<MangaEntity>(), dao.comickMangaMissingGenres())
    }

    @Test
    fun `findByComickHidInLibrary returns only library rows`() = runTest {
        dao.upsert(comickManga("in-lib"))
        dao.upsert(comickManga("removed", hid = "hid-x").copy(inLibrary = false))
        dao.upsert(comickManga("other"))

        assertEquals("in-lib", dao.findByComickHidInLibrary("hid-in-lib")?.id)
        // Pasivni sync nesmi najit odebrany titul, byt ma stale comickHid.
        assertNull(dao.findByComickHidInLibrary("hid-x"))
        // Nefiltrovana varianta (pro import match) ho stale vidi.
        assertEquals("removed", dao.findByComickHid("hid-x")?.id)
    }

    @Test
    fun `findByResolvedSource skips removed comick parents`() = runTest {
        dao.upsert(manga("real"))
        dao.upsert(comickManga("parent-in-lib").copy(resolvedMangaId = "real"))
        dao.upsert(comickManga("parent-removed").copy(resolvedMangaId = "real", inLibrary = false))

        // Propagace progressu ma sahat jen na zive tituly - jinak by re-stamp
        // lastReadAt chranil odebrany radek pred browsed cleanup.
        assertEquals(listOf("parent-in-lib"), dao.findByResolvedSource("real").map { it.id })
    }
}
