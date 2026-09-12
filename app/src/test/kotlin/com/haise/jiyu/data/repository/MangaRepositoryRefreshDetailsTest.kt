package com.haise.jiyu.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.haise.jiyu.data.db.AppDatabase
import com.haise.jiyu.data.db.CategoryDao
import com.haise.jiyu.data.db.ChapterDao
import com.haise.jiyu.data.db.CustomSourceDao
import com.haise.jiyu.data.db.MangaDao
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.source.MangaSource
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
            db = db,
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
}
