package com.haise.jiyu.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
}
