package com.haise.jiyu.data.tracking

import com.haise.jiyu.anilist.AniListRepository
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TrackerSyncCoordinatorTest {

    private val aniList = mockk<AniListRepository>(relaxed = true)
    private val mal = mockk<MalRepository>(relaxed = true)
    private val kitsu = mockk<KitsuRepository>(relaxed = true)
    private val mu = mockk<MangaUpdatesRepository>(relaxed = true)
    private val coordinator = TrackerSyncCoordinator(aniList, mal, kitsu, mu)

    private fun manga(malId: Int? = null, kitsuId: String? = null, muId: Long? = null) = MangaEntity(
        id = "m1", sourceId = "s", url = "/m", title = "Title", coverUrl = null, description = null, status = null,
        malId = malId, kitsuId = kitsuId, mangaUpdatesId = muId,
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
}
