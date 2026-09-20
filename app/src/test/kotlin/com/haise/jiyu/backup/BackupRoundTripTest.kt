package com.haise.jiyu.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.haise.jiyu.data.db.AppDatabase
import com.haise.jiyu.data.db.MangaCategoryMapping
import com.haise.jiyu.data.db.entity.CategoryEntity
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.CustomSourceEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.db.entity.GlossaryEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.db.entity.MangaNoteEntity
import com.haise.jiyu.data.db.entity.ManualTranslationEntity
import com.haise.jiyu.data.db.entity.ReadHistoryEntity
import com.haise.jiyu.data.repository.MangaRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Export se zapisuje streamem (`JsonWriter`), import čte `org.json` - tenhle test hlídá, že obě strany
 * zůstaly shodné: co se vyexportuje, se stejné načte zpátky (včetně uvozovek, Unicode a volitelných polí).
 */
@RunWith(RobolectricTestRunner::class)
class BackupRoundTripTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: MangaRepository
    private lateinit var manager: BackupManager
    private lateinit var file: File

    private val manga = MangaEntity(
        id = "src::/manga/1", sourceId = "src", url = "/manga/1", title = "Název s \"uvozovkami\" a 日本語",
        coverUrl = "https://c/1.jpg", description = "Řádek 1\nŘádek 2", status = "ongoing", inLibrary = true,
        genres = "Action,Drama", year = 2020, malId = 42, malScore = 8.5f, userRating = 9, isFavorite = true,
        translationCompleted = true, rating = 7.25, followCount = 100, rank = 3, alternateTitles = "Alt",
        mangaUpdatesId = 123456789012L, readingTimeMs = 5000L, addedAt = 111L, lastReadAt = 222L,
    )
    private val chapter = ChapterEntity(
        id = "src::/ch/1", mangaId = manga.id, sourceId = "src", url = "/ch/1", name = "Ch 1 – „test“",
        chapterNumber = 1.5f, dateUpload = 999L, read = true, lastPageRead = 4, lastReadAt = 333L,
        lastScrollOffset = 77, downloadStatus = DownloadStatus.DOWNLOADED, localPath = "/dl/1", pageCount = 12,
        verifiedPageCount = 12, isFallbackSource = false,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = mockk(relaxed = true)
        coEvery { repository.getAllLibraryManga() } returns listOf(manga)
        coEvery { repository.getAllLibraryChapters() } returns listOf(chapter)
        coEvery { repository.getAllCategories() } returns listOf(CategoryEntity("cat1", "Favorites", "#112233"))
        coEvery { repository.getAllCategoryMappings() } returns listOf(MangaCategoryMapping(manga.id, "cat1"))
        coEvery { repository.getAllCustomSourcesOnce() } returns
            listOf(CustomSourceEntity(id = "cs1", name = "Vlastní", baseUrl = "https://x.test", contentType = "MANHWA"))
        manager = BackupManager(
            context = context, repository = repository,
            mangaNoteDao = db.mangaNoteDao(), mangaTagDao = db.mangaTagDao(), readHistoryDao = db.readHistoryDao(),
            glossaryDao = db.glossaryDao(), manualTranslationDao = db.manualTranslationDao(), db = db,
        )
        file = File.createTempFile("backup", ".json")
    }

    @After
    fun tearDown() {
        db.close()
        file.delete()
    }

    @Test
    fun `everything that is exported is read back unchanged`() = runTest {
        db.mangaNoteDao().upsertAll(listOf(MangaNoteEntity(manga.id, "poznámka", 55L)))
        db.readHistoryDao().upsertAll(listOf(ReadHistoryEntity(chapter.id, manga.id, manga.title, null, chapter.name, 444L)))
        db.glossaryDao().upsertAll(listOf(GlossaryEntity("g1", manga.id, "原", "Original", "English", protectExact = true)))
        db.manualTranslationDao().upsertAll(
            listOf(ManualTranslationEntity("${chapter.id}::0::原", chapter.id, 0, "原", "Fix", 66L, offsetXDp = 1.5f, offsetYDp = null)),
        )

        assertTrue(manager.exportToFile(file).isSuccess)
        val parsed = parseBackupJson(file.readText())

        assertEquals(listOf(manga), parsed.manga)
        assertEquals(listOf(chapter), parsed.chapters)
        assertEquals(listOf(manga.id to "cat1"), parsed.categoryAssignments)
        assertEquals(listOf(CategoryEntity("cat1", "Favorites", "#112233")), parsed.categories)
        assertEquals("MANHWA", parsed.customSources.single().contentType)
        assertEquals("poznámka", parsed.notes.single().content)
        assertEquals("Fix", parsed.manualTranslations.single().text)
        assertEquals(1.5f, parsed.manualTranslations.single().offsetXDp)
        assertEquals(null, parsed.manualTranslations.single().offsetYDp)
        assertEquals(true, parsed.glossary.single().protectExact)
        assertEquals(chapter.id, parsed.readHistory.single().chapterId)
        assertEquals(0, parsed.skippedCount)
    }

    @Test
    fun `a failed export leaves an existing backup file untouched and no temp file behind`() = runTest {
        file.writeText("PREVIOUS BACKUP")
        coEvery { repository.getAllLibraryChapters() } throws IllegalStateException("db down")

        assertTrue(manager.exportToFile(file).isFailure)

        assertEquals("PREVIOUS BACKUP", file.readText())
        assertFalse(File(file.parentFile, file.name + ".tmp").exists())
    }
}
