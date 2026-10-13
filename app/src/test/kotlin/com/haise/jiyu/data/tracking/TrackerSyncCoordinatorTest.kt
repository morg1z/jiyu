package com.haise.jiyu.data.tracking

import com.haise.jiyu.anilist.AniListRepository
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.settings.ComickSyncMode
import com.haise.jiyu.settings.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TrackerSyncCoordinatorTest {

    private val aniList = mockk<AniListRepository>(relaxed = true)
    private val mal = mockk<MalRepository>(relaxed = true)
    private val kitsu = mockk<KitsuRepository>(relaxed = true)
    private val mu = mockk<MangaUpdatesRepository>(relaxed = true)
    private val comick = mockk<ComickRepository>(relaxed = true)
    private val comickAuth = mockk<ComickAuthManager>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val coordinator = TrackerSyncCoordinator(aniList, mal, kitsu, mu, comick, comickAuth, settings)

    private fun manga(
        malId: Int? = null,
        kitsuId: String? = null,
        muId: Long? = null,
        comickHid: String? = null,
    ) = MangaEntity(
        id = "m1", sourceId = "s", url = "/m", title = "Title", coverUrl = null, description = null, status = null,
        malId = malId, kitsuId = kitsuId, mangaUpdatesId = muId, comickHid = comickHid,
    )

    private val chapter = ChapterEntity(
        id = "c1", mangaId = "m1", sourceId = "s", url = "/c", name = "Ch 12", chapterNumber = 12f, dateUpload = 0L,
    )

    @Test
    fun `only the trackers the title is linked to are called`() = runTest {
        coordinator.syncReadProgress(manga(malId = 42), chapter)

        coVerify(exactly = 1) { aniList.updateProgress("m1", "Title", 12f) }
        coVerify(exactly = 1) { mal.updateMangaStatus(42, "reading", null, 12) }
        coVerify(exactly = 0) { kitsu.updateProgress(any(), any()) }
        coVerify(exactly = 0) { mu.updateProgress(any(), any()) }
    }

    @Test
    fun `a failing tracker does not stop the others`() = runTest {
        coEvery { mal.updateMangaStatus(any(), any(), any(), any()) } throws IllegalStateException("token expired")

        coordinator.syncReadProgress(manga(malId = 42, kitsuId = "k1", muId = 7L), chapter)

        coVerify(exactly = 1) { kitsu.updateProgress("k1", 12) }
        coVerify(exactly = 1) { mu.updateProgress(7L, 12) }
    }

    @Test
    fun `comick progress pushes only in twoway mode with granted write scope`() = runTest {
        every { settings.comickSyncMode } returns flowOf(ComickSyncMode.TWOWAY)
        every { comickAuth.hasWriteScope } returns flowOf(true)

        coordinator.syncReadProgress(manga(comickHid = "h1"), chapter)

        coVerify(exactly = 1) { comick.updateProgress("h1", 12f, null) }
    }

    @Test
    fun `comick progress push is skipped when sync mode is off`() = runTest {
        every { settings.comickSyncMode } returns flowOf(ComickSyncMode.OFF)
        every { comickAuth.hasWriteScope } returns flowOf(true)

        coordinator.syncReadProgress(manga(comickHid = "h1"), chapter)

        coVerify(exactly = 0) { comick.updateProgress(any(), any(), any()) }
    }

    @Test
    fun `comick progress push is skipped when the user withheld write scope`() = runTest {
        every { settings.comickSyncMode } returns flowOf(ComickSyncMode.TWOWAY)
        every { comickAuth.hasWriteScope } returns flowOf(false)

        coordinator.syncReadProgress(manga(comickHid = "h1"), chapter)

        coVerify(exactly = 0) { comick.updateProgress(any(), any(), any()) }
    }

    @Test
    fun `comick chapters push the exact chapter hid carried in the url`() = runTest {
        every { settings.comickSyncMode } returns flowOf(ComickSyncMode.TWOWAY)
        every { comickAuth.hasWriteScope } returns flowOf(true)
        val comickChapter = chapter.copy(url = "https://api.comick.dev/chapter/abc123")

        coordinator.syncReadProgress(manga(comickHid = "h1"), comickChapter)

        coVerify(exactly = 1) { comick.updateProgress("h1", 12f, "abc123") }
    }
}
