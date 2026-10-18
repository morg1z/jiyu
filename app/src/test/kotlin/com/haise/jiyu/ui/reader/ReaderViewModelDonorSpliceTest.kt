package com.haise.jiyu.ui.reader

import com.haise.jiyu.translate.GlossaryRepository
import com.haise.jiyu.data.tracking.TrackerSyncCoordinator
import com.haise.jiyu.data.repository.HistoryRepository
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.haise.jiyu.anilist.AniListRepository
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.data.tracking.ComickAuthManager
import com.haise.jiyu.data.tracking.ComickRepository
import com.haise.jiyu.data.tracking.KitsuRepository
import com.haise.jiyu.data.tracking.MalRepository
import com.haise.jiyu.data.tracking.MangaUpdatesRepository
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.PageGapFiller
import com.haise.jiyu.source.SourceManager
import com.haise.jiyu.translate.TranslateRepository
import com.haise.jiyu.util.LazyPageUrl
import com.haise.jiyu.util.NetworkMonitor
import com.haise.jiyu.util.SleepTimerManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Cross-source donor splice za běhu: stránka je v seznamu, ale její obsah je
 * mrtvý (404 / poškozená) - RetryableAsyncImage po vyčerpání retry zavolá
 * [ReaderViewModel.onPagePermanentlyFailed] a VM ji nahradí stránkou na stejném
 * indexu z donor zdroje (viz PageGapFiller.donorPagesFor). Donor URL musí nést
 * jiyu_lazy marker DONOROVA zdroje (správný referer při fetchi).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelDonorSpliceTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var repository: MangaRepository
    private lateinit var context: Context
    private lateinit var sourceManager: SourceManager
    private lateinit var pageGapFiller: PageGapFiller
    private lateinit var hostSource: MangaSource
    private lateinit var donorSource: MangaSource

    private val chapter = ChapterEntity(
        id = "ch1", mangaId = "m1", sourceId = "src", url = "/ch1",
        name = "Chapter 1", chapterNumber = 1f, dateUpload = 0L, pageCount = 3,
    )
    private val manga = MangaEntity(
        id = "m1", sourceId = "src", url = "/m1", title = "Test",
        coverUrl = null, description = null, status = null,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mockk(relaxed = true)
        context = mockk(relaxed = true)
        sourceManager = mockk()
        pageGapFiller = mockk()
        hostSource = mockk { every { id } returns "src" }
        donorSource = mockk { every { id } returns "donor" }

        coEvery { repository.getChapter("ch1") } returns chapter
        coEvery { repository.getAllChapters("m1") } returns listOf(chapter)
        coEvery { repository.getManga("m1") } returns manga
        coEvery { repository.getChapterPages(any(), any(), any()) } returns listOf(
            Page(0, "https://host/p1.jpg", "https://host/p1.jpg"),
            Page(1, "https://host/p2.jpg", "https://host/p2.jpg"),
            Page(2, "https://host/p3.jpg", "https://host/p3.jpg"),
        )
        coEvery { sourceManager.getById("src") } returns hostSource
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(chapterId: String = "ch1") = ReaderViewModel(
        savedStateHandle = SavedStateHandle(mapOf("chapterId" to chapterId)),
        context = context,
        repository = repository,
        translateRepository = mockk<TranslateRepository>(relaxed = true),
        settings = mockk<SettingsRepository>(relaxed = true) {
            every { sourceLanguage } returns flowOf("Auto")
            every { targetLanguage } returns flowOf("Czech")
        },
        historyRepository = mockk<HistoryRepository>(relaxed = true),
        trackerSyncCoordinator = TrackerSyncCoordinator(
            aniListRepository = mockk<AniListRepository>(relaxed = true),
            malRepository = mockk<MalRepository>(relaxed = true),
            kitsuRepository = mockk<KitsuRepository>(relaxed = true),
            muRepository = mockk<MangaUpdatesRepository>(relaxed = true),
            comickRepository = mockk<ComickRepository>(relaxed = true),
            comickAuthManager = mockk<ComickAuthManager>(relaxed = true),
            settings = mockk<SettingsRepository>(relaxed = true),
        ),
        glossaryRepository = mockk<GlossaryRepository>(relaxed = true),
        sleepTimerManager = mockk<SleepTimerManager>(relaxed = true),
        networkMonitor = mockk<NetworkMonitor>(relaxed = true),
        errorActionHandler = mockk(relaxed = true),
        translateQueue = mockk(relaxed = true),
        sourceManager = sourceManager,
        pageGapFiller = pageGapFiller,
        comicKResolver = mockk(relaxed = true),
    )

    @Test
    fun `mrtva stranka se splices donor URL s lazy markerem donorova zdroje`() = runBlocking {
        coEvery { pageGapFiller.donorPagesFor(hostSource, "/ch1", "/m1") } returns (
            donorSource to listOf(
                Page(0, "https://donor/d1.jpg", "https://donor/d1.jpg"),
                Page(1, "https://donor/d2.jpg", "https://donor/d2.jpg"),
                Page(2, "https://donor/d3.jpg", "https://donor/d3.jpg"),
            )
        )

        val vm = viewModel()
        vm.onPagePermanentlyFailed("ch1", 1)

        val spliced = vm.pages.value[1]
        assertTrue("donor URL musi byt v _pages, je: $spliced", spliced.startsWith("https://donor/d2.jpg"))
        // Marker donorova zdroje - fetcher resolvuje se SPRAVNYM refererem donora.
        val decoded = LazyPageUrl.decodeFragment(spliced.substringAfter('#', ""))
        assertEquals("donor" to 1, decoded)
        // Ostatni stranky nedotcene.
        assertEquals("https://host/p1.jpg", vm.pages.value[0])
        assertEquals("https://host/p3.jpg", vm.pages.value[2])
    }

    @Test
    fun `bez donoru se stranky nemeni`() = runBlocking {
        coEvery { pageGapFiller.donorPagesFor(hostSource, "/ch1", "/m1") } returns null

        val vm = viewModel()
        vm.onPagePermanentlyFailed("ch1", 1)

        assertEquals("https://host/p2.jpg", vm.pages.value[1])
    }

    @Test
    fun `donor s min strankami se ignoruje - same-index mimo rozsah`() = runBlocking {
        coEvery { pageGapFiller.donorPagesFor(hostSource, "/ch1", "/m1") } returns (
            donorSource to listOf(Page(0, "https://donor/d1.jpg", "https://donor/d1.jpg"))
        )

        val vm = viewModel()
        vm.onPagePermanentlyFailed("ch1", 2)

        assertEquals("https://host/p3.jpg", vm.pages.value[2])
    }

    @Test
    fun `donor lookup se pro kapitolu spusti jen jednou - cache`() = runBlocking {
        coEvery { pageGapFiller.donorPagesFor(hostSource, "/ch1", "/m1") } returns (
            donorSource to listOf(
                Page(0, "https://donor/d1.jpg", "https://donor/d1.jpg"),
                Page(1, "https://donor/d2.jpg", "https://donor/d2.jpg"),
                Page(2, "https://donor/d3.jpg", "https://donor/d3.jpg"),
            )
        )

        val vm = viewModel()
        vm.onPagePermanentlyFailed("ch1", 0)
        vm.onPagePermanentlyFailed("ch1", 2)

        coVerify(exactly = 1) { pageGapFiller.donorPagesFor(any(), any(), any()) }
        assertTrue(vm.pages.value[0].startsWith("https://donor/d1.jpg"))
        assertTrue(vm.pages.value[2].startsWith("https://donor/d3.jpg"))
    }

    @Test
    fun `comick kapitola donor lookup nespousti`() = runBlocking {
        val comickChapter = chapter.copy(id = "ck1", sourceId = "comick")
        coEvery { repository.getChapter("ck1") } returns comickChapter
        coEvery { repository.getAllChapters("m1") } returns listOf(comickChapter)

        val vm = viewModel("ck1")
        vm.onPagePermanentlyFailed("ck1", 0)

        coVerify(exactly = 0) { pageGapFiller.donorPagesFor(any(), any(), any()) }
    }
}
