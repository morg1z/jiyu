package com.haise.jiyu.ui.reader

import com.haise.jiyu.translate.GlossaryRepository
import com.haise.jiyu.data.tracking.TrackerSyncCoordinator
import com.haise.jiyu.data.repository.HistoryRepository
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.anilist.AniListRepository
import com.haise.jiyu.data.db.GlossaryDao
import com.haise.jiyu.data.db.ReadHistoryDao
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.data.tracking.KitsuRepository
import com.haise.jiyu.data.tracking.MalRepository
import com.haise.jiyu.data.tracking.MangaUpdatesRepository
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.translate.TranslateRepository
import com.haise.jiyu.util.NetworkMonitor
import com.haise.jiyu.util.SleepTimerManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Testy inkognito režimu ve čtečce.
 *
 * Proč zrovna tohle: inkognito dřív vynechávalo jen zápis do historie a hlášení trackerům.
 * Postup čtení, "naposledy čteno", nastřádaný čas i počet stránek se ukládaly dál - kapitola
 * se tedy po anonymním přečtení tvářila jako přečtená a čas naskočil do Statistik. Název
 * "Číst anonymně" sliboval víc, než appka dělala. Testy drží obě strany: pod inkognitem se
 * nezapíše nic, běžné čtení zapisuje všechno jako dřív.
 *
 * Pozn. k `runBlocking` místo `runTest`: `init` ViewModelu má nekonečnou smyčku
 * `while(true) { delay(1000) }` pro počítadlo času - `runTest` by na konci dotáčel virtuální
 * čas donekonečna a test by se zasekl. Viz [ReaderViewModelBatchTranslateTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelIncognitoTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var repository: MangaRepository
    private lateinit var settings: SettingsRepository
    private lateinit var historyRepository: HistoryRepository
    private lateinit var context: Context
    private lateinit var malRepository: MalRepository

    private val chapter = ChapterEntity(
        id = "ch1", mangaId = "m1", sourceId = "src", url = "/ch1",
        name = "Chapter 1", chapterNumber = 1f, dateUpload = 0L, pageCount = 2,
    )
    private val manga = MangaEntity(
        id = "m1", sourceId = "src", url = "/m1", title = "Test",
        coverUrl = null, description = null, status = null,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mockk(relaxed = true)
        settings = mockk(relaxed = true)
        historyRepository = mockk(relaxed = true)
        context = mockk(relaxed = true)
        malRepository = mockk(relaxed = true)

        coEvery { repository.getChapter("ch1") } returns chapter
        coEvery { repository.getAllChapters("m1") } returns listOf(chapter)
        coEvery { repository.getManga("m1") } returns manga
        coEvery { repository.getChapterPages(any(), any(), any()) } returns listOf(
            com.haise.jiyu.source.Page(0, "p1.jpg", "p1.jpg"),
            com.haise.jiyu.source.Page(1, "p2.jpg", "p2.jpg"),
        )
        every { settings.sourceLanguage } returns flowOf("Auto")
        every { settings.targetLanguage } returns flowOf("Czech")
        // Musí se nastavit: `maybeAutoDelete` na tenhle flow volá `.first()` a relaxed mock
        // vrací PRÁZDNÝ flow, takže by to vyhodilo NoSuchElementException uvnitř korutiny,
        // kterou nikdo nečte. Taková výjimka test neshodí - vyplave až o pár tříd dál jako
        // `UncaughtExceptionsBeforeTest` u úplně nesouvisejícího testu.
        every { settings.autoDeleteRead } returns flowOf(false)
        every { settings.autoDeleteDelayDays } returns flowOf(0)
        every { context.getString(any()) } returns "hlaska"
    }

    /**
     * ViewModely vyrobené v testu se musí po sobě uklidit. `init` jich rozjíždí několik
     * včetně nekonečné smyčky počítadla času a `viewModelScope` bez Androidu nikdo neuzavře -
     * korutiny by tedy přežily konec testu a jejich výjimky by vyplavaly až v úplně jiné
     * testovací třídě jako `UncaughtExceptionsBeforeTest`. Přesně to se taky stalo:
     * shazovalo to `GlobalSearchViewModelTest`, který s touhle třídou nemá vůbec nic
     * společného, takže hledání viníka stálo pár kol bisekce.
     */
    private val created = mutableListOf<ReaderViewModel>()

    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
        created.clear()
        Dispatchers.resetMain()
    }

    private fun viewModel(incognito: Boolean) = build(incognito).also { created += it }

    private fun build(incognito: Boolean) = ReaderViewModel(
        savedStateHandle = SavedStateHandle(
            mapOf("chapterId" to "ch1", "incognito" to incognito),
        ),
        context = context,
        repository = repository,
        translateRepository = mockk<TranslateRepository>(relaxed = true),
        settings = settings,
        historyRepository = historyRepository,
        trackerSyncCoordinator = TrackerSyncCoordinator(
            aniListRepository = mockk<AniListRepository>(relaxed = true),
            malRepository = malRepository,
            kitsuRepository = mockk<KitsuRepository>(relaxed = true),
            muRepository = mockk<MangaUpdatesRepository>(relaxed = true),
        ),
        glossaryRepository = mockk<GlossaryRepository>(relaxed = true),
        sleepTimerManager = mockk<SleepTimerManager>(relaxed = true),
        networkMonitor = mockk<NetworkMonitor>(relaxed = true),
        errorActionHandler = mockk(relaxed = true),
    )

    @Test
    fun `incognito does not mark the chapter read or move last-read`() = runBlocking {
        val vm = viewModel(incognito = true)
        vm.onPageChanged(1)   // posledni stranka -> normalne by se oznacila prectena

        coVerify(exactly = 0) { repository.updateReadProgress(any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.updateLastReadChapter(any(), any()) }
    }

    @Test
    fun `incognito keeps reading time and page count out of the stats`() = runBlocking {
        val vm = viewModel(incognito = true)
        vm.onPageChanged(0)
        vm.onPageChanged(1)

        coVerify(exactly = 0) { settings.addReadingTime(any()) }
        coVerify(exactly = 0) { settings.addPagesRead(any()) }
        coVerify(exactly = 0) { repository.addMangaReadingTime(any(), any()) }
    }

    @Test
    fun `incognito does not bump the reading streak`() = runBlocking {
        viewModel(incognito = true)

        coVerify(exactly = 0) { settings.updateReadingStreak() }
    }

    @Test
    fun `incognito writes nothing into the history`() = runBlocking {
        val vm = viewModel(incognito = true)
        vm.onPageChanged(1)

        coVerify(exactly = 0) { historyRepository.record(any()) }
    }

    @Test
    fun `normal reading still records progress`() = runBlocking {
        // Druha strana mince - at oprava inkognita nevypne zapisovani uplne vsem.
        val vm = viewModel(incognito = false)
        vm.onPageChanged(1)

        coVerify(atLeast = 1) { repository.updateReadProgress("ch1", true, 1, any()) }
        coVerify(atLeast = 1) { repository.updateLastReadChapter("m1", "ch1") }
    }

    @Test
    fun `normal reading still counts pages and the streak`() = runBlocking {
        val vm = viewModel(incognito = false)
        vm.onPageChanged(0)

        coVerify(atLeast = 1) { settings.addPagesRead(1) }
        coVerify(atLeast = 1) { settings.updateReadingStreak() }
    }

    @Test
    fun `stepping back from the last page never un-reads the chapter and trackers fire only once`() = runBlocking {
        coEvery { repository.getManga("m1") } returns manga.copy(malId = 42)
        val vm = viewModel(incognito = false)

        vm.onPageChanged(1)   // posledni stranka -> prectena
        vm.onPageChanged(0)   // krok zpet
        vm.onPageChanged(1)   // znovu na posledni

        // Dřív krok zpět zapsal read = false a dočtená kapitola se tvářila jako nepřečtená.
        coVerify(exactly = 0) { repository.updateReadProgress("ch1", false, any(), any()) }
        // Dřív se při každém návratu na poslední stránku znovu volaly všechny trackery.
        coVerify(exactly = 1) { malRepository.updateMangaStatus(malId = 42, status = "reading", numChaptersRead = 1) }
    }
}
