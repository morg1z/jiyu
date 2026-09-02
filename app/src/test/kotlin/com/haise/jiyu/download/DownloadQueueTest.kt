package com.haise.jiyu.download

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Hlídá, že dvojité zavolání enqueue() pro TUTÉŽ kapitolu nespustí dva souběžné
 * ChapterDownloadWorker nad stejnými soubory stránek.
 *
 * Proč tenhle test vznikl: enqueue() dřív volal obyčejné WorkManager.enqueue(), bez
 * unikátního jména práce. Dvojtap na "Stáhnout", souběžný auto-download z
 * ChapterUpdateWorker a ruční stažení stejné kapitoly z detailu, nebo znovu-zavolání po
 * restartu procesu tak mohly rozjet DVA workery nad stejnou kapitolou zároveň - oba by
 * zapisovaly do stejných "%03d.jpg" souborů najednou. Oprava přešla na
 * enqueueUniqueWork(..., ExistingWorkPolicy.KEEP, ...); tenhle test hlídá, že se to
 * znovu nerozjede.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadQueueTest {

    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
    }

    private fun chapter(id: String) = ChapterEntity(
        id = id,
        mangaId = "manga-1",
        sourceId = "test-source",
        url = "https://example.com/$id",
        name = "Chapter $id",
        chapterNumber = 1f,
        dateUpload = 0L,
    )

    @Test
    fun `enqueueing the same chapter twice keeps only one work item`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = mockk<SettingsRepository>()
        every { settings.downloadOnlyWifi } returns flowOf(false)
        val queue = DownloadQueue(context, settings)
        val ch = chapter("ch-1")

        runBlocking {
            queue.enqueue(ch, "https://example.com/manga")
            queue.enqueue(ch, "https://example.com/manga")
        }

        val infos = workManager.getWorkInfosForUniqueWork("download_ch-1").get()
        val active = infos.filter { it.state != WorkInfo.State.CANCELLED }
        assertEquals("dvoji enqueue stejne kapitoly nesmi vytvorit dve aktivni prace", 1, active.size)
    }

    @Test
    fun `enqueueing two different chapters keeps both`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = mockk<SettingsRepository>()
        every { settings.downloadOnlyWifi } returns flowOf(false)
        val queue = DownloadQueue(context, settings)

        runBlocking {
            queue.enqueue(chapter("ch-1"), "https://example.com/manga")
            queue.enqueue(chapter("ch-2"), "https://example.com/manga")
        }

        assertEquals(1, workManager.getWorkInfosForUniqueWork("download_ch-1").get().size)
        assertEquals(1, workManager.getWorkInfosForUniqueWork("download_ch-2").get().size)
    }
}
