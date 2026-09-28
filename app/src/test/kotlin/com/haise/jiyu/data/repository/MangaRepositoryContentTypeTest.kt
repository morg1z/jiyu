package com.haise.jiyu.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.haise.jiyu.data.db.AppDatabase
import com.haise.jiyu.data.db.MangaDao
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.tracking.MangaUpdatesRepository
import com.haise.jiyu.data.tracking.MuManga
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `verifyContentType` - oprava spatnych tagu typu (MANGA/MANHWA/MANHUA/NOVEL/COMIC)
 * proti katalogum ComicK (country) a MangaUpdates (type). Hlaseny bug: Vagabond na
 * manhwa webu se ukazal jako Manhwa, korejska manhwa jako Manga - protoze vetsina
 * parseru SManga.contentType neplni a webova pole "Type" lhou o zemi puvodu.
 */
@RunWith(RobolectricTestRunner::class)
class MangaRepositoryContentTypeTest {

    private lateinit var db: AppDatabase
    private lateinit var mangaDao: MangaDao
    private lateinit var sourceManager: SourceManager
    private lateinit var comickSource: MangaSource
    private lateinit var muRepository: MangaUpdatesRepository
    private lateinit var repository: MangaRepository

    private fun entity(
        sourceId: String = "somesite",
        title: String = "Vagabond",
        type: String = "MANHWA", // spatne nalepene zdrojem
        verified: Boolean = false,
    ) = MangaEntity(
        id = "$sourceId::/m", sourceId = sourceId, url = "/m", title = title,
        coverUrl = null, description = null, status = null, inLibrary = true,
        contentType = type, contentTypeVerified = verified,
    )

    private fun comickResult(title: String, country: String?, alts: List<String> = emptyList()) =
        SManga(sourceId = "comick", url = "/comic/x", title = title, coverUrl = null,
            countryOfOrigin = country, alternateTitles = alts)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        mangaDao = spyk(db.mangaDao())
        sourceManager = mockk()
        comickSource = mockk()
        muRepository = mockk()
        // Default: oba oraculi "odpovedeli" (prazdno) - katalog titul nema.
        coEvery { comickSource.search(any(), any(), any()) } returns emptyList()
        coEvery { sourceManager.getById("comick") } returns comickSource
        coEvery { muRepository.searchManga(any()) } returns emptyList()
        repository = MangaRepository(
            sourceManager, mangaDao, db.chapterDao(), db.categoryDao(), db.customSourceDao(),
            mangaDexSource = mockk(relaxed = true),
            mangaUpdatesRepository = muRepository,
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
    fun tearDown() { db.close() }

    // ── ComicK oracle ────────────────────────────────────────────────────────

    @Test
    fun `comick country jp corrects wrong MANHWA tag to MANGA`() = runTest {
        // Nalozeny bug: Vagabond (japonska manga) hostovany na manhwa webu.
        mangaDao.upsert(entity())
        coEvery { comickSource.search("Vagabond", any(), any()) } returns
            listOf(comickResult("Vagabond", "jp"))

        repository.verifyContentType("somesite::/m")

        val m = mangaDao.getById("somesite::/m")!!
        assertEquals("MANGA", m.contentType)
        assertTrue(m.contentTypeVerified)
    }

    @Test
    fun `comick country kr corrects default MANGA tag to MANHWA`() = runTest {
        mangaDao.upsert(entity(title = "+99 Reinforced Wooden Stick", type = "MANGA"))
        coEvery { comickSource.search("+99 Reinforced Wooden Stick", any(), any()) } returns
            listOf(comickResult("+99 Reinforced Wooden Stick", "kr"))

        repository.verifyContentType("somesite::/m")

        assertEquals("MANHWA", mangaDao.getById("somesite::/m")!!.contentType)
    }

    @Test
    fun `comick country cn resolves to MANHUA`() = runTest {
        mangaDao.upsert(entity(type = "MANGA"))
        coEvery { comickSource.search(any(), any(), any()) } returns
            listOf(comickResult("Vagabond", "cn"))

        repository.verifyContentType("somesite::/m")

        assertEquals("MANHUA", mangaDao.getById("somesite::/m")!!.contentType)
    }

    @Test
    fun `comick match through entity alternateTitles`() = runTest {
        // Entity drzi japonsky nazev, ComicK anglicky - paruje se pres alternateTitles.
        mangaDao.upsert(entity(title = "めだかボックス")
            .copy(alternateTitles = """["Medaka Box"]"""))
        coEvery { comickSource.search(any(), any(), any()) } returns
            listOf(comickResult("Medaka Box", "jp"))

        repository.verifyContentType("somesite::/m")

        assertEquals("MANGA", mangaDao.getById("somesite::/m")!!.contentType)
    }

    @Test
    fun `comick others country is not proof of MANGA`() = runTest {
        // "others" muze byt western comic/OEL - nesmi prepsat MANHWA na MANGA.
        mangaDao.upsert(entity())
        coEvery { comickSource.search(any(), any(), any()) } returns
            listOf(comickResult("Vagabond", "others"))

        repository.verifyContentType("somesite::/m")

        val m = mangaDao.getById("somesite::/m")!!
        assertEquals("MANHWA", m.contentType)
        assertTrue(m.contentTypeVerified) // katalogy odpovedely - dalsi refreshy uz netazi
    }

    // ── MangaUpdates fallback ────────────────────────────────────────────────

    @Test
    fun `mangaupdates type resolves novel when comick has no match`() = runTest {
        // Novela hostovana na manga agregatoru (tag MANGA) - ComicK ji nema, MU ano.
        mangaDao.upsert(entity(title = "Solo Leveling", type = "MANGA"))
        coEvery { muRepository.searchManga("Solo Leveling") } returns
            listOf(MuManga(1, "Solo Leveling", null, null, null, type = "Novel"))

        repository.verifyContentType("somesite::/m")

        assertEquals("NOVEL", mangaDao.getById("somesite::/m")!!.contentType)
    }

    @Test
    fun `mangaupdates unmapped type leaves tag alone`() = runTest {
        mangaDao.upsert(entity())
        coEvery { muRepository.searchManga(any()) } returns
            listOf(MuManga(1, "Vagabond", null, null, null, type = "Doujinshi"))

        repository.verifyContentType("somesite::/m")

        assertEquals("MANHWA", mangaDao.getById("somesite::/m")!!.contentType)
    }

    // ── Flag / retry semantika ───────────────────────────────────────────────

    @Test
    fun `nothing found marks verified so it is not retried`() = runTest {
        mangaDao.upsert(entity())

        repository.verifyContentType("somesite::/m")
        repository.verifyContentType("somesite::/m")

        coVerify(exactly = 1) { comickSource.search(any(), any(), any()) }
        assertTrue(mangaDao.getById("somesite::/m")!!.contentTypeVerified)
    }

    @Test
    fun `already verified entity is never queried again`() = runTest {
        mangaDao.upsert(entity(verified = true))

        repository.verifyContentType("somesite::/m")

        coVerify(exactly = 0) { comickSource.search(any(), any(), any()) }
        coVerify(exactly = 0) { muRepository.searchManga(any()) }
    }

    @Test
    fun `uniform source types skip the oracle entirely`() = runTest {
        mangaDao.upsert(entity(type = "NOVEL")) // novelovy web = autorita pro svuj typ

        repository.verifyContentType("somesite::/m")

        coVerify(exactly = 0) { comickSource.search(any(), any(), any()) }
        assertTrue(mangaDao.getById("somesite::/m")!!.contentTypeVerified)
    }

    @Test
    fun `comick-sourced titles are skipped - already per-title`() = runTest {
        mangaDao.upsert(entity(sourceId = "comick", type = "MANHUA"))

        repository.verifyContentType("comick::/m")

        coVerify(exactly = 0) { comickSource.search(any(), any(), any()) }
        assertTrue(mangaDao.getById("comick::/m")!!.contentTypeVerified)
    }

    @Test
    fun `network failure on both oracles leaves unverified for retry`() = runTest {
        mangaDao.upsert(entity())
        coEvery { comickSource.search(any(), any(), any()) } throws java.io.IOException("offline")
        coEvery { muRepository.searchManga(any()) } returns emptyList() // MU chybu polyka do emptyList

        repository.verifyContentType("somesite::/m")

        assertFalse(mangaDao.getById("somesite::/m")!!.contentTypeVerified)
    }
}
