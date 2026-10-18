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
import com.haise.jiyu.translate.TranslateRepository
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
import org.junit.Before
import org.junit.Test

/**
 * Regresni test na hlaseny bug: po chybe prekladu "hodilo" ctenare z kapitoly 1
 * na nejnovejsi kapitolu (~200). Pricina - kdyz se aktualni kapitola v seznamu
 * [allChapters] nenajde (refresh/migrace/relink mezi getChapter a
 * getAllChapters ji nahradil jinym id), `indexOfFirst` vrati -1 a stara
 * `navigatePrev` ho protahla pres `idx + 1` na index 0 = NEJNOVEJSI kapitolu
 * (seznam je DESC). Totez umoznovalo i tlacitko prev, ktere updateNavState
 * u idx=-1 chybne zapnul.
 *
 * UnconfinedTestDispatcher + runBlocking - viz komentar v
 * [ReaderViewModelBatchTranslateTest] (nekonecna smycka casovace v init).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelNavigationTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var repository: MangaRepository
    private lateinit var context: Context

    private val chapter = ChapterEntity(
        id = "ch1", mangaId = "m1", sourceId = "src", url = "/ch1",
        name = "Chapter 1", chapterNumber = 1f, dateUpload = 0L, pageCount = 2,
    )
    private val newest = ChapterEntity(
        id = "ch200", mangaId = "m1", sourceId = "src", url = "/ch200",
        name = "Chapter 200", chapterNumber = 200f, dateUpload = 0L,
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

        coEvery { repository.getChapter("ch1") } returns chapter
        coEvery { repository.getManga("m1") } returns manga
        coEvery { repository.getChapterPages(any(), any(), any()) } returns listOf(
            com.haise.jiyu.source.Page(0, "p1.jpg", "p1.jpg"),
            com.haise.jiyu.source.Page(1, "p2.jpg", "p2.jpg"),
        )
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
        sourceManager = mockk(relaxed = true),
        pageGapFiller = mockk(relaxed = true),
            comicKResolver = mockk(relaxed = true),
    )

    @Test
    fun `navigatePrev with a chapter missing from the refreshed list stays put`() = runBlocking {
        // Seznam po relinku - otevrena kapitola "ch1" v nem uz neni; loadChapter ji
        // dosadi zpet na konec DESC seznamu, takze prev zustane zakazany.
        coEvery { repository.getAllChapters("m1") } returns listOf(newest)
        coEvery { repository.getChapter("ch200") } returns newest

        val vm = viewModel()
        vm.navigatePrev()

        coVerify(exactly = 0) { repository.getChapter("ch200") }
        assertEquals("ch1", vm.currentChapterId.value)
    }

    @Test
    fun `a chapter missing from the refreshed list is inserted back and can still navigate`() = runBlocking {
        // Relink vyhodil "ch1" ze seznamu - ale reader ji vlozi zpet na jeji misto
        // (DESC podle chapterNumber), takze next navigace porad mifi spravne na ch2.
        val ch2 = ChapterEntity(
            id = "ch2", mangaId = "m1", sourceId = "src", url = "/ch2",
            name = "Chapter 2", chapterNumber = 2f, dateUpload = 0L, pageCount = 2,
        )
        coEvery { repository.getAllChapters("m1") } returns listOf(newest, ch2)
        coEvery { repository.getChapter("ch2") } returns ch2

        val vm = viewModel()
        vm.navigateNext()

        coVerify(exactly = 1) { repository.getChapter("ch2") }
        assertEquals("ch2", vm.currentChapterId.value)
    }

    @Test
    fun `navigatePrev on the oldest chapter does nothing`() = runBlocking {
        // Normalni stav: ch1 je v seznamu, ale jako NEJSTARSI (posledni index) -
        // prev nesmi nikam navigovat.
        coEvery { repository.getAllChapters("m1") } returns listOf(newest, chapter)
        coEvery { repository.getChapter("ch200") } returns newest

        val vm = viewModel()
        vm.navigatePrev()

        coVerify(exactly = 0) { repository.getChapter("ch200") }
        assertEquals("ch1", vm.currentChapterId.value)
    }

    @Test
    fun `navigatePrev moves to the previous chapter when possible`() = runBlocking {
        val older = ChapterEntity(
            id = "ch0", mangaId = "m1", sourceId = "src", url = "/ch0",
            name = "Chapter 0", chapterNumber = 0f, dateUpload = 0L, pageCount = 2,
        )
        // DESC poradi: [ch200, ch1, ch0] - ch1 ma next=ch200 (idx-1) i prev=ch0 (idx+1).
        coEvery { repository.getAllChapters("m1") } returns listOf(newest, chapter, older)
        coEvery { repository.getChapter("ch0") } returns older

        val vm = viewModel()
        vm.navigatePrev()

        coVerify(exactly = 1) { repository.getChapter("ch0") }
    }

    private fun row(id: String, number: Float, group: String? = null, read: Boolean = false) =
        ChapterEntity(
            id = id, mangaId = "m1", sourceId = "src", url = "/$id",
            name = "Chapter $id", chapterNumber = number, dateUpload = 0L,
            pageCount = 2, scanlationGroup = group, read = read,
        )

    @Test
    fun `navigateNext skips duplicate rows of the same chapter number`() = runBlocking {
        // Hlaseny bug: zdroj ma kap.5 od 3 skupin - "dalsi kapitola" prochazela
        // vsechny 3 verze 5ky (4 kliky na postup o jednu kapitolu).
        val ch5a = row("ch5a", 5f, "GroupA")
        val ch5b = row("ch5b", 5f, "GroupB")
        val ch5c = row("ch5c", 5f, "GroupC")
        val ch6 = row("ch6", 6f, "GroupA")
        coEvery { repository.getChapter("ch5a") } returns ch5a
        coEvery { repository.getChapter("ch6") } returns ch6
        coEvery { repository.getAllChapters("m1") } returns listOf(ch6, ch5a, ch5b, ch5c)

        val vm = viewModel("ch5a")
        vm.navigateNext()

        coVerify(exactly = 1) { repository.getChapter("ch6") }
        assertEquals("ch6", vm.currentChapterId.value)
    }

    @Test
    fun `navigateNext prefers the same scanlation group`() = runBlocking {
        // Kap.6 ma verze od B i A; aktualni kapitola je od A -> drzet se A.
        val ch6b = row("ch6b", 6f, "GroupB")
        val ch6a = row("ch6a", 6f, "GroupA")
        val ch5a = row("ch5a", 5f, "GroupA")
        coEvery { repository.getChapter("ch5a") } returns ch5a
        coEvery { repository.getChapter("ch6a") } returns ch6a
        coEvery { repository.getChapter("ch6b") } returns ch6b
        coEvery { repository.getAllChapters("m1") } returns listOf(ch6b, ch6a, ch5a)

        val vm = viewModel("ch5a")
        vm.navigateNext()

        coVerify(exactly = 1) { repository.getChapter("ch6a") }
        coVerify(exactly = 0) { repository.getChapter("ch6b") }
        assertEquals("ch6a", vm.currentChapterId.value)
    }

    @Test
    fun `navigateNext falls back to another group when the preferred one is missing`() = runBlocking {
        // Kap.6 existuje jen od C - i kdyz cteme skupinu B, musime ji vzit
        // (jinak by kapitola sla preskocit uplne).
        val ch6c = row("ch6c", 6f, "GroupC")
        val ch5b = row("ch5b", 5f, "GroupB")
        coEvery { repository.getChapter("ch5b") } returns ch5b
        coEvery { repository.getChapter("ch6c") } returns ch6c
        coEvery { repository.getAllChapters("m1") } returns listOf(ch6c, ch5b)

        val vm = viewModel("ch5b")
        vm.navigateNext()

        coVerify(exactly = 1) { repository.getChapter("ch6c") }
        assertEquals("ch6c", vm.currentChapterId.value)
    }

    @Test
    fun `navigatePrev skips duplicate rows of the same chapter number`() = runBlocking {
        val ch6 = row("ch6", 6f, "GroupA")
        val ch5a = row("ch5a", 5f, "GroupA")
        val ch5b = row("ch5b", 5f, "GroupB")
        val ch5c = row("ch5c", 5f, "GroupC")
        val ch4 = row("ch4", 4f, "GroupA")
        coEvery { repository.getChapter("ch5b") } returns ch5b
        coEvery { repository.getChapter("ch4") } returns ch4
        coEvery { repository.getAllChapters("m1") } returns listOf(ch6, ch5a, ch5b, ch5c, ch4)

        val vm = viewModel("ch5b")
        vm.navigatePrev()

        coVerify(exactly = 1) { repository.getChapter("ch4") }
        assertEquals("ch4", vm.currentChapterId.value)
    }

    @Test
    fun `hasNextChapter is false when only duplicates of the current number remain`() = runBlocking {
        // Posledni radky v seznamu jsou jen dalsi verze tehoz cisla - tlacitko
        // "dalsi kapitola" nesmi svitit, protoze zadne dalsi CISLO neexistuje.
        val ch5a = row("ch5a", 5f, "GroupA")
        val ch5b = row("ch5b", 5f, "GroupB")
        coEvery { repository.getChapter("ch5a") } returns ch5a
        coEvery { repository.getAllChapters("m1") } returns listOf(ch5b, ch5a)

        val vm = viewModel("ch5a")

        assertEquals(false, vm.hasNextChapter.value)
        assertEquals(false, vm.hasPrevChapter.value)
    }
}
