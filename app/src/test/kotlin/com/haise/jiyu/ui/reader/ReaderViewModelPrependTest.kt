package com.haise.jiyu.ui.reader

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.haise.jiyu.anilist.AniListRepository
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.repository.HistoryRepository
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.data.tracking.KitsuRepository
import com.haise.jiyu.data.tracking.MalRepository
import com.haise.jiyu.data.tracking.MangaUpdatesRepository
import com.haise.jiyu.data.tracking.TrackerSyncCoordinator
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.translate.GlossaryRepository
import com.haise.jiyu.translate.TranslateRepository
import com.haise.jiyu.util.NetworkMonitor
import com.haise.jiyu.util.SleepTimerManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Zpetne nekonecne cteni - [ReaderViewModel.prependPreviousWebtoonSegment].
 *
 * Symetrie k appendNextWebtoonSegment (testovano v [ReaderViewModelBatchTranslateTest]):
 * `allChapters` je DESC (index 0 = nejnovejsi), takze "predchozi"/starsi kapitola je
 * `allChapters[idx + 1]` a segment se vklada na ZACATEK [_webtoonSegments].
 *
 * Harness je identicky s [ReaderViewModelBatchTranslateTest] - UnconfinedTestDispatcher +
 * runBlocking kvuli nekonecne smycce casovace v init ViewModelu.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelPrependTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var repository: MangaRepository
    private lateinit var translateRepository: TranslateRepository
    private lateinit var settings: SettingsRepository
    private lateinit var context: Context

    // DESC poradi: chNew (nejnovejsi) -> chCur (aktualne otevrena) -> chOld (nejstarsi).
    private val chapterNew = ChapterEntity(
        id = "chNew", mangaId = "m1", sourceId = "src", url = "/chNew",
        name = "Chapter 3", chapterNumber = 3f, dateUpload = 0L, pageCount = 1,
    )
    private val chapterCur = ChapterEntity(
        id = "chCur", mangaId = "m1", sourceId = "src", url = "/chCur",
        name = "Chapter 2", chapterNumber = 2f, dateUpload = 0L, pageCount = 2,
    )
    private val chapterOld = ChapterEntity(
        id = "chOld", mangaId = "m1", sourceId = "src", url = "/chOld",
        name = "Chapter 1", chapterNumber = 1f, dateUpload = 0L, pageCount = 1,
    )
    private val manga = MangaEntity(
        id = "m1", sourceId = "src", url = "/m1", title = "Test",
        coverUrl = null, description = null, status = null,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mockk(relaxed = true)
        translateRepository = mockk(relaxed = true)
        settings = mockk(relaxed = true)
        context = mockk(relaxed = true)

        coEvery { repository.getChapter(any()) } returns chapterCur
        coEvery { repository.getChapter("chCur") } returns chapterCur
        coEvery { repository.getAllChapters("m1") } returns listOf(chapterNew, chapterCur, chapterOld)
        coEvery { repository.getManga("m1") } returns manga
        coEvery { repository.getChapterPages(any(), any(), any()) } returns listOf(
            com.haise.jiyu.source.Page(0, "p1.jpg", "p1.jpg"),
            com.haise.jiyu.source.Page(1, "p2.jpg", "p2.jpg"),
        )
        // Aktualni kapitola ma 8 stranek - pri malem poctu by plochy index 0 zaroven
        // splnil PRAH APPENDU (local >= size - 4) a test by tak ziskal i dalsi segment.
        // MockK: vyhrava POSLEDNI deklarovany matching stub, proto az za obecnym.
        coEvery { repository.getChapterPages("src", "/chCur", any()) } returns (0 until 8).map {
            com.haise.jiyu.source.Page(it, "cur$it.jpg", "cur$it.jpg")
        }
        every { settings.sourceLanguage } returns flowOf("Auto")
        every { settings.targetLanguage } returns flowOf("Czech")
        every { settings.infiniteScrollEnabled } returns flowOf(true)
        every { context.getString(any()) } returns "chybova-hlaska"
        coEvery { translateRepository.getCachedPage(any(), any(), any(), any(), any()) } returns null
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = ReaderViewModel(
        savedStateHandle = SavedStateHandle(mapOf("chapterId" to "chCur")),
        context = context,
        repository = repository,
        translateRepository = translateRepository,
        settings = settings,
        historyRepository = mockk<HistoryRepository>(relaxed = true),
        trackerSyncCoordinator = TrackerSyncCoordinator(
            aniListRepository = mockk<AniListRepository>(relaxed = true),
            malRepository = mockk<MalRepository>(relaxed = true),
            kitsuRepository = mockk<KitsuRepository>(relaxed = true),
            muRepository = mockk<MangaUpdatesRepository>(relaxed = true),
        ),
        glossaryRepository = mockk<GlossaryRepository>(relaxed = true),
        sleepTimerManager = mockk<SleepTimerManager>(relaxed = true),
        networkMonitor = mockk<NetworkMonitor>(relaxed = true),
        errorActionHandler = mockk(relaxed = true),
    )

    private suspend fun awaitLoaded(vm: ReaderViewModel) {
        withTimeout(5_000) {
            while (vm.webtoonSegments.value.isEmpty()) delay(10)
        }
    }

    private suspend fun awaitSegment(vm: ReaderViewModel, chapterId: String, index: Int) {
        withTimeout(5_000) {
            while (vm.webtoonSegments.value.getOrNull(index)?.chapterId != chapterId) delay(10)
        }
    }

    @Test
    fun `prepend inserts the older chapter at the front of the stream`() = runBlocking {
        val vm = viewModel()
        awaitLoaded(vm)
        assertEquals("chCur", vm.webtoonSegments.value.single().chapterId)

        vm.prependPreviousWebtoonSegment()
        awaitSegment(vm, "chOld", 0)

        assertEquals(
            "predchozi kapitola patri na zacatek, otevrena zustava za ni",
            listOf("chOld", "chCur"),
            vm.webtoonSegments.value.map { it.chapterId },
        )
        assertFalse("loading flag se po dobehnuti shodi", vm.webtoonPrependingPrev.value)
    }

    @Test
    fun `prepend at the oldest chapter is a no-op`() = runBlocking {
        coEvery { repository.getAllChapters("m1") } returns listOf(chapterNew, chapterCur)

        val vm = viewModel()
        awaitLoaded(vm)

        vm.prependPreviousWebtoonSegment()
        delay(200)

        assertEquals(listOf("chCur"), vm.webtoonSegments.value.map { it.chapterId })
        assertFalse(vm.webtoonPrependingPrev.value)
    }

    @Test
    fun `prepend is a no-op when infinite scroll is disabled`() = runBlocking {
        every { settings.infiniteScrollEnabled } returns flowOf(false)

        val vm = viewModel()
        awaitLoaded(vm)

        vm.prependPreviousWebtoonSegment()
        delay(200)

        assertEquals(listOf("chCur"), vm.webtoonSegments.value.map { it.chapterId })
    }

    @Test
    fun `prepend does not duplicate an already loaded segment`() = runBlocking {
        val vm = viewModel()
        awaitLoaded(vm)

        vm.prependPreviousWebtoonSegment()
        awaitSegment(vm, "chOld", 0)

        // Druhy pokus (napr. dalsi trigger u zacatku) - chOld uz v seznamu je.
        vm.prependPreviousWebtoonSegment()
        delay(200)

        assertEquals(
            listOf("chOld", "chCur"),
            vm.webtoonSegments.value.map { it.chapterId },
        )
    }

    @Test
    fun `webtoonEpoch survives a prepend unchanged`() = runBlocking {
        val vm = viewModel()
        awaitLoaded(vm)
        assertEquals("chCur", vm.webtoonEpoch.value)

        vm.prependPreviousWebtoonSegment()
        awaitSegment(vm, "chOld", 0)

        assertEquals(
            "epoch se meni jen pri plnem loadChapter, prepend ho drzi",
            "chCur",
            vm.webtoonEpoch.value,
        )
    }

    @Test
    fun `paged flat page near the stream start triggers a prepend`() = runBlocking {
        val vm = viewModel()
        awaitLoaded(vm)

        // Plochy index 0 = prvni stranka prvniho segmentu -> PAGED_PREPEND_PREFETCH_DISTANCE.
        vm.onPagedFlatPageChanged(0)
        awaitSegment(vm, "chOld", 0)

        assertEquals(listOf("chOld", "chCur"), vm.webtoonSegments.value.map { it.chapterId })
    }

    @Test
    fun `flat index after prepend still maps to the same chapter`() = runBlocking {
        val vm = viewModel()
        awaitLoaded(vm)

        vm.prependPreviousWebtoonSegment()
        awaitSegment(vm, "chOld", 0)

        // chOld ma 2 stranky (stub), takze lokalni index 0 kapitoly chCur je flat 2.
        vm.onPagedFlatPageChanged(2)
        delay(50)

        assertEquals(
            "plochy index po prependu musi stale vest do puvodni kapitoly",
            "chCur",
            vm.currentChapterId.value,
        )
    }
}
