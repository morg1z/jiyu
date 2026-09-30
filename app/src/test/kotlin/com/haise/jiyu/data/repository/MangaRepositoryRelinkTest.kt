package com.haise.jiyu.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.haise.jiyu.data.db.AppDatabase
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.db.entity.ManualTranslationEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.db.entity.ReadHistoryEntity
import com.haise.jiyu.data.db.entity.TranslatedNovelEntity
import com.haise.jiyu.data.db.entity.TranslatedPageEntity
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.SourceManager
import com.haise.jiyu.source.mangadex.MangaDexSource
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pokrývá [MangaRepository.relinkMangaToSource] - přesun titulu na JINÝ zdroj (mrtvá
 * doména/zaniklý web, viz CrossSourceSearch). Na rozdíl od same-source
 * [MangaRepository.recoverMangaLink] se mění i `chapter.sourceId` a `manga.sourceId`,
 * `MangaEntity.id` ale zůstává (stabilní identita - manga_category na ni má FK bez
 * ON UPDATE CASCADE). Skutečné DAO na in-memory Room DB (stejný vzor jako
 * [MangaRepositoryRefreshDetailsTest]), protože pointa je přesně v SQL přemapování.
 */
@RunWith(RobolectricTestRunner::class)
class MangaRepositoryRelinkTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: MangaRepository

    private fun manga(id: String = "src::/dead", sourceId: String = "src", url: String = "/dead") = MangaEntity(
        id = id, sourceId = sourceId, url = url, title = "Solo Leveling", coverUrl = null,
        description = null, status = null, inLibrary = true, contentType = "MANHWA",
    )

    private fun num(number: Float) =
        if (number == number.toInt().toFloat()) number.toInt().toString() else number.toString()

    private fun oldChapter(number: Float, sourceId: String = "src", urlSuffix: String = "ch${num(number)}") = ChapterEntity(
        id = "$sourceId::/$urlSuffix", mangaId = "src::/dead", sourceId = sourceId,
        url = "/$urlSuffix", name = "Ch ${num(number)}", chapterNumber = number, dateUpload = 0L,
        read = number <= 2f,
    )

    private fun newChapter(number: Float, sourceId: String = "dst", urlSuffix: String = "ch${num(number)}") = SChapter(
        sourceId = sourceId, mangaUrl = "/new", url = "/$urlSuffix", name = "Ch ${num(number)}",
        chapterNumber = number, dateUpload = 100L,
    )

    private val target = SManga(sourceId = "dst", url = "/new", title = "Solo Leveling", coverUrl = null)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MangaRepository(
            sourceManager = mockk(),
            mangaDao = spyk(db.mangaDao()),
            chapterDao = db.chapterDao(),
            categoryDao = db.categoryDao(),
            customSourceDao = db.customSourceDao(),
            mangaDexSource = mockk(relaxed = true),
            mangaUpdatesRepository = mockk(relaxed = true),
            manualTranslationDao = db.manualTranslationDao(),
            readHistoryDao = db.readHistoryDao(),
            translatedPageDao = db.translatedPageDao(),
            translatedNovelDao = db.translatedNovelDao(),
            settings = mockk(relaxed = true),
            db = db,
            context = context,
            contentCache = SourceContentCache(),
        )
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `relink switches sourceId and remaps chapters to the new source ids`() = runTest {
        db.mangaDao().upsert(manga())
        db.chapterDao().upsertAll(listOf(oldChapter(1f), oldChapter(2f)))

        assertTrue(repository.relinkMangaToSource("src::/dead", target, listOf(newChapter(1f), newChapter(2f), newChapter(3f))))

        val after = db.mangaDao().getById("src::/dead")!!
        // Stabilni id se nemeni - jen sourceId/url/title se prepisou na cilovy zdroj.
        assertEquals("src::/dead", after.id)
        assertEquals("dst", after.sourceId)
        assertEquals("/new", after.url)
        val chapters = db.chapterDao().getAllForManga("src::/dead").sortedBy { it.chapterNumber }
        assertEquals(listOf("dst::/ch1", "dst::/ch2", "dst::/ch3"), chapters.map { it.id })
        assertTrue(chapters.all { it.sourceId == "dst" })
        // Precteny stav se prenese na nova id (1, 2 byly read=true).
        assertEquals(listOf(true, true, false), chapters.map { it.read })
    }

    @Test
    fun `relink keeps download state and dependent rows pointing at the new chapter id`() = runTest {
        db.mangaDao().upsert(manga().copy(lastReadChapterId = "src::/ch1"))
        db.chapterDao().upsertAll(
            listOf(
                oldChapter(1f).copy(downloadStatus = DownloadStatus.DOWNLOADED, localPath = "/x/c1", pageCount = 10),
                oldChapter(2f),
            ),
        )
        db.readHistoryDao().record(ReadHistoryEntity("src::/ch1", "src::/dead", "Solo Leveling", null, "Ch 1", 5L))
        db.manualTranslationDao().upsert(
            ManualTranslationEntity("src::/ch1::0::orig", "src::/ch1", 0, "orig", "fix", 7L)
        )
        db.translatedPageDao().upsert(TranslatedPageEntity("src::/ch1::0::en::cs::v1", "[]", 9L))
        db.translatedNovelDao().upsert(TranslatedNovelEntity("src::/ch1::en::cs", "text", 9L))

        assertTrue(repository.relinkMangaToSource("src::/dead", target, listOf(newChapter(1f), newChapter(2f))))

        // lastReadChapterId sleduje relinkovanou kapitolu.
        assertEquals("dst::/ch1", db.mangaDao().getById("src::/dead")!!.lastReadChapterId)
        // Download stav+localPath zustavaji (chapterDao.relink je zamerne nemeni).
        val ch1 = db.chapterDao().getById("dst::/ch1")!!
        assertEquals(DownloadStatus.DOWNLOADED, ch1.downloadStatus)
        assertEquals("/x/c1", ch1.localPath)
        // Vsechny chapter-keyed tabulky se premapuji na nove id (zadne sirotci).
        assertEquals(listOf("dst::/ch1"), db.readHistoryDao().getAll().map { it.chapterId })
        assertNotNull(db.manualTranslationDao().getById("dst::/ch1::0::orig"))
        assertNotNull(db.translatedPageDao().getById("dst::/ch1::0::en::cs::v1"))
        assertNotNull(db.translatedNovelDao().getById("dst::/ch1::en::cs"))
    }

    @Test
    fun `relink is refused when the target source+url already belongs to another library entry`() = runTest {
        db.mangaDao().upsert(manga())
        // Jiná knihovní entita už na stejné (sourceId, url) ukazuje - přesun by sjel
        // dva knihovní řádky na jeden zdrojový titul; sjednocení řeší detektor duplicit.
        db.mangaDao().upsert(manga(id = "dst::/new", sourceId = "dst", url = "/new"))
        db.chapterDao().upsertAll(listOf(oldChapter(1f)))

        assertEquals(false, repository.relinkMangaToSource("src::/dead", target, listOf(newChapter(1f))))

        val after = db.mangaDao().getById("src::/dead")!!
        assertEquals("src", after.sourceId)
        assertEquals("/dead", after.url)
        assertEquals(listOf("src::/ch1"), db.chapterDao().getAllForManga("src::/dead").map { it.id })
    }

    @Test
    fun `relink to the SAME source is refused - that is what recoverMangaLink is for`() = runTest {
        db.mangaDao().upsert(manga())
        db.chapterDao().upsertAll(listOf(oldChapter(1f)))

        assertEquals(false, repository.relinkMangaToSource(
            "src::/dead", target.copy(sourceId = "src"), listOf(newChapter(1f, sourceId = "src")),
        ))
        assertEquals(listOf("src::/ch1"), db.chapterDao().getAllForManga("src::/dead").map { it.id })
    }

    @Test
    fun `relink with an empty target chapter list is refused`() = runTest {
        db.mangaDao().upsert(manga())
        assertEquals(false, repository.relinkMangaToSource("src::/dead", target, emptyList()))
    }

    @Test
    fun `fallback chapters are excluded from remapping so a same-id fallback row cannot collide`() = runTest {
        // ComicK resolver naučil fallback kapitolu už na cílovém zdroji - její id JE
        // id nové kapitoly ("dst::/ch1"). Bez vyražení fallbacků z migrace by UPDATE
        // chapter SET id narazil na PK kolizi a shodil celou transakci.
        db.mangaDao().upsert(manga())
        db.chapterDao().upsertAll(
            listOf(
                oldChapter(1f),
                oldChapter(2f),
                ChapterEntity(
                    id = "dst::/ch1", mangaId = "src::/dead", sourceId = "dst", url = "/ch1",
                    name = "Ch 1 (fallback)", chapterNumber = 1f, dateUpload = 0L,
                    isFallbackSource = true,
                ),
            ),
        )

        assertTrue(repository.relinkMangaToSource("src::/dead", target, listOf(newChapter(1f), newChapter(2f))))

        val chapters = db.chapterDao().getAllForManga("src::/dead")
        // Puvodni src::/ch1 se NEPREMAPUJE (novy id je obsazeny fallback radkem) a zustava
        // vedle nej; src::/ch2 se relinkuje normalne. Fallback radek je nedotceny.
        assertEquals(
            setOf("dst::/ch1", "dst::/ch2", "src::/ch1"),
            chapters.mapTo(HashSet()) { it.id },
        )
        assertTrue(chapters.single { it.id == "dst::/ch1" }.isFallbackSource)
        assertEquals("src", chapters.single { it.id == "src::/ch1" }.sourceId)
    }

    @Test
    fun `fallback pointers of other chapters follow the relink`() = runTest {
        // Kapitola ukazujici fallbackChapterId na relinkovanou kapitolu nesmi skoncit
        // ukazujici na neexistujici stare id (viz ChapterDao.relinkFallbackChapterId).
        db.mangaDao().upsert(manga())
        db.chapterDao().upsertAll(
            listOf(
                oldChapter(1f),
                oldChapter(2f).copy(fallbackChapterId = "src::/ch1"),
            ),
        )

        assertTrue(repository.relinkMangaToSource("src::/dead", target, listOf(newChapter(1f), newChapter(2f))))

        assertNull(db.chapterDao().getById("src::/ch1"))
        assertEquals("dst::/ch1", db.chapterDao().getById("dst::/ch2")!!.fallbackChapterId)
    }
}
