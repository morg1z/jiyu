package com.haise.jiyu.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.haise.jiyu.data.db.AppDatabase
import com.haise.jiyu.data.db.CategoryDao
import com.haise.jiyu.data.db.ChapterDao
import com.haise.jiyu.data.db.CustomSourceDao
import com.haise.jiyu.data.db.MangaDao
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.SourceManager
import com.haise.jiyu.source.mangadex.MangaDexSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pokrývá [MangaRepository.refreshMangaDetails] - žádný test pro repository dřív
 * neexistoval (jen [SerializeChapterGroupsTest] pro čistou pomocnou funkci). Ověřuje
 * fallback na existující DB hodnotu ((a)/(b), viz `?:` v refreshMangaDetails) a
 * podmíněný upsert přidaný revizí commitu 4f8bac1 (c) - viz komentář u `if (updated
 * != existing)` v [MangaRepository.refreshMangaDetails].
 *
 * Room potřebuje Android SQLite nativní kód, proto RobolectricTestRunner + in-memory
 * DB - stejný vzor jako [com.haise.jiyu.data.db.MangaDaoTest]. sourceManager a
 * mangaDexSource jsou finální třídy bez rozhraní -> mockk (viz komentář u
 * SourceBrowseViewModelTest a build.gradle.kts).
 */
@RunWith(RobolectricTestRunner::class)
class MangaRepositoryRefreshDetailsTest {

    private lateinit var db: AppDatabase
    private lateinit var mangaDao: MangaDao
    private lateinit var chapterDao: ChapterDao
    private lateinit var categoryDao: CategoryDao
    private lateinit var customSourceDao: CustomSourceDao
    private lateinit var sourceManager: SourceManager
    private lateinit var mangaDexSource: MangaDexSource
    private lateinit var repository: MangaRepository

    private fun entity(id: String) = MangaEntity(
        id = id,
        sourceId = "comick",
        url = "https://api.comick.dev/comic/test-series",
        title = "Test Series",
        coverUrl = null,
        description = "Old description",
        status = "Vychází",
        inLibrary = true,
        contentType = "MANHWA",
    )

    private fun sManga() = SManga(
        sourceId = "comick",
        url = "https://api.comick.dev/comic/test-series",
        title = "Test Series",
        coverUrl = null,
        contentType = "MANHWA",
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        mangaDao = spyk(db.mangaDao())
        chapterDao = db.chapterDao()
        categoryDao = db.categoryDao()
        customSourceDao = db.customSourceDao()
        sourceManager = mockk()
        mangaDexSource = mockk(relaxed = true)
        repository = MangaRepository(
            sourceManager, mangaDao, chapterDao, categoryDao, customSourceDao, mangaDexSource,
            manualTranslationDao = mockk(relaxed = true),
            readHistoryDao = mockk(relaxed = true),
            translatedPageDao = mockk(relaxed = true),
            translatedNovelDao = mockk(relaxed = true),
            settings = mockk(relaxed = true),
            db = db,
            context = context,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `null-ish fields from the source keep the existing DB value`() = runTest {
        mangaDao.upsert(entity("m1"))
        val source = mockk<MangaSource>()
        coEvery { source.getMangaDetails(any()) } returns sManga().copy(
            description = null,
            status = null,
            contentType = "MANHWA",
        )
        coEvery { sourceManager.getById("comick") } returns source

        repository.refreshMangaDetails("m1", sManga())

        val result = mangaDao.getById("m1")!!
        assertEquals("Old description", result.description)
        assertEquals("Vychází", result.status)
    }

    @Test
    fun `a real value from the source overwrites the existing DB value`() = runTest {
        mangaDao.upsert(entity("m1"))
        val source = mockk<MangaSource>()
        coEvery { source.getMangaDetails(any()) } returns sManga().copy(
            description = "New description",
            status = "Dokončeno",
            contentType = "MANHWA",
        )
        coEvery { sourceManager.getById("comick") } returns source

        repository.refreshMangaDetails("m1", sManga())

        val result = mangaDao.getById("m1")!!
        assertEquals("New description", result.description)
        assertEquals("Dokončeno", result.status)
    }

    @Test
    fun `a no-op refresh (detail identical to what's already stored) does not trigger a DB write`() = runTest {
        val seeded = entity("m1")
        mangaDao.upsert(seeded)
        val source = mockk<MangaSource>()
        // Detail matches exactly what's already in the DB - the copy() result should equal
        // the existing entity, so refreshMangaDetails must skip the upsert entirely (Fix 4).
        coEvery { source.getMangaDetails(any()) } returns sManga().copy(
            description = seeded.description,
            status = seeded.status,
            contentType = seeded.contentType,
        )
        coEvery { sourceManager.getById("comick") } returns source

        repository.refreshMangaDetails("m1", sManga())

        // exactly 1 = the seeding upsert() above; refreshMangaDetails must not add a second one.
        coVerify(exactly = 1) { mangaDao.upsert(any()) }
    }

    @Test
    fun `an unknown source is a no-op`() = runTest {
        mangaDao.upsert(entity("m1"))
        coEvery { sourceManager.getById("comick") } returns null

        repository.refreshMangaDetails("m1", sManga())

        coVerify(exactly = 1) { mangaDao.upsert(any()) }
    }

    @Test
    fun `recoverMangaLink keeps the relinked last-read chapter and changes made during the network calls`() = runTest {
        val id = "src::/old"
        mangaDao.upsert(entity(id).copy(sourceId = "src", url = "/old", inLibrary = true, lastReadChapterId = "src::/old-ch1"))
        chapterDao.upsertAll(
            listOf(
                com.haise.jiyu.data.db.entity.ChapterEntity(
                    id = "src::/old-ch1", mangaId = id, sourceId = "src", url = "/old-ch1",
                    name = "Chapter 1", chapterNumber = 1f, dateUpload = 0L, pageCount = 0,
                    read = true, lastPageRead = 0,
                ),
            ),
        )
        val source = mockk<MangaSource>()
        val newManga = SManga(sourceId = "src", url = "/new", title = "Test Series", coverUrl = null, contentType = "MANHWA")
        coEvery { sourceManager.getById("src") } returns source
        coEvery { source.search(any(), any(), any()) } returns listOf(newManga)
        coEvery { source.getChapterList(any()) } coAnswers {
            // Uzivatel mezitim odebere titul z knihovny - recovery to nesmi vratit zpet.
            mangaDao.setInLibrary(id, false)
            listOf(
                com.haise.jiyu.source.SChapter(
                    sourceId = "src", mangaUrl = "/new", url = "/new-ch1", name = "Chapter 1",
                    chapterNumber = 1f, dateUpload = 0L,
                ),
            )
        }

        assertEquals(true, repository.recoverMangaLink(id))

        val after = mangaDao.getById(id)!!
        assertEquals("/new", after.url)
        assertEquals(false, after.inLibrary)
        assertEquals("src::/new-ch1", after.lastReadChapterId)
    }
    @Test
    fun `removing a title from the library resets its download columns`() = runTest {
        mangaDao.upsert(entity("m1"))
        chapterDao.upsertAll(
            listOf(
                ChapterEntity(
                    id = "c1", mangaId = "m1", sourceId = "comick", url = "/c1", name = "Ch 1",
                    chapterNumber = 1f, dateUpload = 0L, downloadStatus = DownloadStatus.DOWNLOADED,
                    localPath = "/nonexistent/downloads/m1/c1", pageCount = 12,
                ),
            ),
        )

        repository.removeFromLibrary("m1")

        val c = chapterDao.getAllForManga("m1").single()
        assertEquals(DownloadStatus.NOT_DOWNLOADED, c.downloadStatus)
        assertEquals(null, c.localPath)
        assertEquals(0, c.pageCount)
    }

    @Test
    fun `a poorer listing entry does not overwrite metadata the detail already fetched`() = runTest {
        mangaDao.upsert(entity("comick::https://api.comick.dev/comic/test-series").copy(author = "Known Author"))
        val source = mockk<MangaSource>(relaxed = true)
        coEvery { source.getChapterList(any()) } returns emptyList()
        coEvery { sourceManager.getById("comick") } returns source

        repository.openPreview(sManga().copy(description = null, author = null))

        val m = mangaDao.getById("comick::https://api.comick.dev/comic/test-series")!!
        assertEquals("Old description", m.description)
        assertEquals("Known Author", m.author)
        assertEquals("MANHWA", m.contentType)
    }
    private fun storedChapter(id: String, number: Float, read: Boolean = false) = ChapterEntity(
        id = id, mangaId = "m1", sourceId = "mangadex", url = id.substringAfter("::"), name = "Ch $number",
        chapterNumber = number, dateUpload = 0L, read = read, lastPageRead = if (read) 7 else 0,
    )

    private fun sChapter(url: String, number: Float) =
        SChapter(sourceId = "mangadex", mangaUrl = "/manga/x", url = url, name = "Ch $number", chapterNumber = number, dateUpload = 0L)

    private suspend fun refreshWith(sourceId: String, fresh: List<SChapter>): List<ChapterEntity> {
        val source = mockk<MangaSource>()
        coEvery { source.getChapterList(any()) } returns fresh
        coEvery { sourceManager.getById(sourceId) } returns source
        return repository.refreshChapters("m1", SManga(sourceId = sourceId, url = "/manga/x", title = "T", coverUrl = null))
    }

    @Test
    fun `refresh after the source changed its chapter URL scheme keeps read state instead of duplicating`() = runTest {
        mangaDao.upsert(entity("m1").copy(sourceId = "mangadex"))
        chapterDao.upsertAll((1..4).map { storedChapter("mangadex::/old/$it", it.toFloat(), read = it <= 2) })

        val newlyAdded = refreshWith("mangadex", (1..4).map { sChapter("/new/$it", it.toFloat()) })

        val all = chapterDao.getAllForManga("m1")
        assertEquals(4, all.size)
        assertEquals(listOf("mangadex::/new/4", "mangadex::/new/3", "mangadex::/new/2", "mangadex::/new/1"), all.map { it.id })
        assertEquals(listOf(false, false, true, true), all.map { it.read })
        assertEquals(emptyList<ChapterEntity>(), newlyAdded)
    }

    @Test
    fun `a single replaced chapter is treated as a normal new chapter, nothing is relinked`() = runTest {
        mangaDao.upsert(entity("m1").copy(sourceId = "mangadex"))
        chapterDao.upsertAll((1..4).map { storedChapter("mangadex::/c/$it", it.toFloat(), read = true) })

        val newlyAdded = refreshWith("mangadex", listOf(sChapter("/c/1", 1f), sChapter("/c/2", 2f), sChapter("/c/3", 3f), sChapter("/other/4", 4f)))

        assertEquals(listOf("mangadex::/other/4"), newlyAdded.map { it.id })
        assertEquals(5, chapterDao.getAllForManga("m1").size)
    }
}
