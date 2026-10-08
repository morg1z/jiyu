package com.haise.jiyu.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * insertNewOnly() se pouziva pri kazdem refreshi kapitol z API - MUSI zachovat
 * read/downloadStatus existujicich kapitol. upsertAll() se pouziva jen pri
 * importu zalohy, kde chceme prepsat i tyto stavy. Regrese v tomhle uz jednou
 * nastala (viz project_jiyu_implemented pamet), proto testovano explicitne.
 */
@RunWith(RobolectricTestRunner::class)
class ChapterDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ChapterDao

    private fun chapter(
        id: String,
        read: Boolean = false,
        status: DownloadStatus = DownloadStatus.NOT_DOWNLOADED,
        chapterNumber: Float = 1f,
    ) = ChapterEntity(
        id = id,
        mangaId = "manga-1",
        sourceId = "mangadex",
        url = "https://example.com/$id",
        name = "Chapter $chapterNumber",
        chapterNumber = chapterNumber,
        dateUpload = 0L,
        read = read,
        downloadStatus = status,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.chapterDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insertNewOnly preserves existing read and download state on refresh`() = runTest {
        dao.insertNewOnly(listOf(chapter("ch-1", read = true, status = DownloadStatus.DOWNLOADED)))

        // API refresh vraci stejnou kapitolu jako nepřečtenou a nestazenou
        dao.insertNewOnly(listOf(chapter("ch-1", read = false, status = DownloadStatus.NOT_DOWNLOADED)))

        val result = dao.getById("ch-1")!!
        assertEquals(true, result.read)
        assertEquals(DownloadStatus.DOWNLOADED, result.downloadStatus)
    }

    @Test
    fun `insertNewOnly still inserts genuinely new chapters`() = runTest {
        dao.insertNewOnly(listOf(chapter("ch-1")))
        dao.insertNewOnly(listOf(chapter("ch-1"), chapter("ch-2")))

        assertEquals(2, dao.countForManga("manga-1"))
    }

    @Test
    fun `upsertAll overwrites read and download state (backup restore)`() = runTest {
        dao.insertNewOnly(listOf(chapter("ch-1", read = true, status = DownloadStatus.DOWNLOADED)))

        dao.upsertAll(listOf(chapter("ch-1", read = false, status = DownloadStatus.NOT_DOWNLOADED)))

        val result = dao.getById("ch-1")!!
        assertEquals(false, result.read)
        assertEquals(DownloadStatus.NOT_DOWNLOADED, result.downloadStatus)
    }

    @Test
    fun `relink preserves read and download state while changing id and url`() = runTest {
        dao.insertNewOnly(listOf(chapter("old-id", read = true, status = DownloadStatus.DOWNLOADED, chapterNumber = 5f)))

        dao.relink(
            oldId = "old-id",
            newId = "new-id",
            newSourceId = "test",
            newUrl = "https://new.example.com/ch5",
            newName = "Chapter 5 (renamed)",
            dateUpload = 123456L,
            scanlationGroup = "Some Group",
            volume = "2",
            language = "en",
            groupsJson = null,
        )

        val relinked = dao.getById("new-id")!!
        assertEquals(true, relinked.read)
        assertEquals(DownloadStatus.DOWNLOADED, relinked.downloadStatus)
        assertEquals("https://new.example.com/ch5", relinked.url)
        assertEquals("Chapter 5 (renamed)", relinked.name)
        assertEquals("Some Group", relinked.scanlationGroup)
        assertEquals("en", relinked.language)
        assertNull(dao.getById("old-id"))
    }

    @Test
    fun `relink onto an already existing chapter id survives the collision`() = runTest {
        // Audit DB-2: canonical kapitola se mezitim objevila samostatne (napr. paraleni
        // refresh), takze UPDATE pk->pk narazi na existujici radek. Bez OR REPLACE by
        // SQLITE_CONSTRAINT_PRIMARYKEY shodila celou relink transakci v MangaRepository
        // a nechala polovicne prelinkovana data.
        dao.insertNewOnly(listOf(chapter("old-id", read = true, status = DownloadStatus.DOWNLOADED, chapterNumber = 5f)))
        dao.insertNewOnly(listOf(chapter("new-id", chapterNumber = 5f)))

        dao.relink(
            oldId = "old-id",
            newId = "new-id",
            newSourceId = "test",
            newUrl = "https://new.example.com/ch5",
            newName = "Chapter 5",
            dateUpload = 123456L,
            scanlationGroup = null,
            volume = null,
            language = null,
            groupsJson = null,
        )

        val relinked = dao.getById("new-id")!!
        // Prelinkovany radek vyhral a udrzel si svuj uzivatelsky stav.
        assertEquals(true, relinked.read)
        assertEquals(DownloadStatus.DOWNLOADED, relinked.downloadStatus)
        assertEquals("https://new.example.com/ch5", relinked.url)
        assertNull(dao.getById("old-id"))
    }

    @Test
    fun `setVerifiedPageCount writes count and isFallback without touching other fields`() = runTest {
        dao.insertNewOnly(listOf(chapter("ch-1", read = true, status = DownloadStatus.DOWNLOADED)))

        dao.setVerifiedPageCount("ch-1", count = 5, isFallback = false)

        val result = dao.getById("ch-1")!!
        assertEquals(5, result.verifiedPageCount)
        assertEquals(false, result.isFallbackSource)
        assertNull(result.fallbackChapterId)
        assertEquals(true, result.read)
        assertEquals(DownloadStatus.DOWNLOADED, result.downloadStatus)
    }

    @Test
    fun `setVerifiedPageCount can record a redirect to a better chapter`() = runTest {
        dao.insertNewOnly(listOf(chapter("short-ch", chapterNumber = 19f)))
        dao.insertNewOnly(listOf(chapter("better-ch", chapterNumber = 19f)))

        dao.setVerifiedPageCount("short-ch", count = 5, isFallback = false, fallbackChapterId = "better-ch")
        dao.setVerifiedPageCount("better-ch", count = 13, isFallback = true)

        val short = dao.getById("short-ch")!!
        assertEquals(5, short.verifiedPageCount)
        assertEquals(false, short.isFallbackSource)
        assertEquals("better-ch", short.fallbackChapterId)

        val better = dao.getById("better-ch")!!
        assertEquals(13, better.verifiedPageCount)
        assertEquals(true, better.isFallbackSource)
        assertNull(better.fallbackChapterId)
    }
    @Test
    fun `updateProgress persists verifiedPageCount only when a real page count is provided`() = runTest {
        dao.insertNewOnly(listOf(chapter("ch-1")))

        // Čtečka zná skutečný počet vykreslených stránek - zapíše ho jako online ověření.
        dao.updateProgress("ch-1", read = false, lastPageRead = 4, lastReadAt = 1000L, pageCount = 12)
        var result = dao.getById("ch-1")!!
        assertEquals(12, result.verifiedPageCount)
        assertEquals(4, result.lastPageRead)
        assertEquals(false, result.read)

        // Ruční označení (pageCount = 0) verifiedPageCount nemění - jinak by "označit
        // přečtené" z detailu přepsalo známý počet stránek nulou.
        dao.updateProgress("ch-1", read = true, lastPageRead = 0, lastReadAt = 0L)
        result = dao.getById("ch-1")!!
        assertEquals(12, result.verifiedPageCount)
        assertEquals(true, result.read)
    }

    @Test
    fun `propagateProgressToFallbackParents copies reader progress to the linked comick chapter`() = runTest {
        dao.insertNewOnly(listOf(chapter("comick-ch", chapterNumber = 12f)))
        dao.insertNewOnly(listOf(chapter("resolved-ch", chapterNumber = 12f)))
        dao.insertNewOnly(listOf(chapter("other-ch", chapterNumber = 7f)))
        dao.setFallbackTarget("comick-ch", "resolved-ch")

        // Rozčteno uprostřed - rodič dostane pozici, ale zůstane nepřečtený.
        dao.propagateProgressToFallbackParents("resolved-ch", read = false, lastPageRead = 5, lastReadAt = 100L, pageCount = 30)
        var parent = dao.getById("comick-ch")!!
        assertEquals(false, parent.read)
        assertEquals(5, parent.lastPageRead)
        assertEquals(30, parent.verifiedPageCount)

        // Dočteno - rodič se označí přečtený (dřív se to stalo hned při výběru zdroje).
        dao.propagateProgressToFallbackParents("resolved-ch", read = true, lastPageRead = 29, lastReadAt = 200L, pageCount = 30)
        parent = dao.getById("comick-ch")!!
        assertEquals(true, parent.read)
        assertEquals(29, parent.lastPageRead)

        // read se nikdy nesníží - pozdější zápis s read=false dočtené nerozbije.
        dao.propagateProgressToFallbackParents("resolved-ch", read = false, lastPageRead = 3, lastReadAt = 300L, pageCount = 30)
        parent = dao.getById("comick-ch")!!
        assertEquals(true, parent.read)

        // Kapitoly bez linku se propagace netýká.
        val other = dao.getById("other-ch")!!
        assertEquals(false, other.read)
        assertEquals(0, other.lastPageRead)
    }

    @Test
    fun `setFallbackTarget relinks the redirect and clears it on null`() = runTest {
        dao.insertNewOnly(listOf(chapter("comick-ch"), chapter("r1"), chapter("r2")))

        dao.setFallbackTarget("comick-ch", "r1")
        assertEquals("r1", dao.getById("comick-ch")!!.fallbackChapterId)

        // Pozdější výběr jiného zdroje link prostě přepíše.
        dao.setFallbackTarget("comick-ch", "r2")
        assertEquals("r2", dao.getById("comick-ch")!!.fallbackChapterId)

        dao.setFallbackTarget("comick-ch", null)
        assertNull(dao.getById("comick-ch")!!.fallbackChapterId)
    }

    @Test
    fun `chapters without a parsed number are counted individually, numbered duplicates still merge`() = runTest {
        dao.upsertAll(
            listOf(
                chapter("a", chapterNumber = 0f),
                chapter("b", chapterNumber = 0f),
                chapter("c", chapterNumber = 5f),
                chapter("d", chapterNumber = 5f, read = true), // stejné číslo od jiné skupiny, jedna už přečtená
            ),
        )

        val total = dao.observeTotalCounts().first().single { it.mangaId == "manga-1" }.count
        val unread = dao.observeUnreadCounts().first().single { it.mangaId == "manga-1" }.count

        assertEquals(3, total)  // dvě bezčíselné + jedna kapitola 5
        assertEquals(2, unread) // kapitola 5 je díky přečtené kopii přečtená
    }
}
