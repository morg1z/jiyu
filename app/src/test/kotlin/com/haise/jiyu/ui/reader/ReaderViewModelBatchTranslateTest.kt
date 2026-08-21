package com.haise.jiyu.ui.reader

import android.content.Context
import androidx.lifecycle.SavedStateHandle
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
import com.haise.jiyu.translate.RateLimitedException
import com.haise.jiyu.translate.TranslateRepository
import com.haise.jiyu.translate.TranslatedBlock
import com.haise.jiyu.util.NetworkMonitor
import com.haise.jiyu.util.SleepTimerManager
import io.mockk.coEvery
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Testy hromadného překladu v [ReaderViewModel].
 *
 * Proč zrovna tohle: `translateMode` přepíná UI z tlačítka "Přeložit vše" na přepínač
 * Originál/Překlad. Jakmile jednou zůstane `true` BEZ skutečného překladu, uživatel nemá
 * žádnou cestu zpět - tlačítko zmizí natrvalo. Přesně tohle se v praxi stalo (viz
 * uživatelská zpětná vazba o minimalizované appce, která po návratu tvrdila "hotovo",
 * ale nic přeloženého nebylo). Testy drží podmínku, která to hlídá.
 *
 * Pozn.: `init` ViewModelu má nekonečnou smyčku `while(true) { delay(1000) }` pro počítadlo
 * času čtení. Proto UnconfinedTestDispatcher (korutiny běží eagerně až k prvnímu suspendu)
 * ve dvojici s `runBlocking`, NE `runTest`: runTest na konci dotáčí virtuální čas, dokud
 * jsou nějaké úkoly - a ta smyčka jich generuje nekonečno, takže by se test zasekl.
 * S runBlocking virtuální čas nikdo neposouvá a smyčka jen zůstane viset na prvním delay.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelBatchTranslateTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var repository: MangaRepository
    private lateinit var translateRepository: TranslateRepository
    private lateinit var settings: SettingsRepository
    private lateinit var context: Context

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
        translateRepository = mockk(relaxed = true)
        settings = mockk(relaxed = true)
        context = mockk(relaxed = true)

        coEvery { repository.getChapter("ch1") } returns chapter
        coEvery { repository.getAllChapters("m1") } returns listOf(chapter)
        coEvery { repository.getManga("m1") } returns manga
        coEvery { repository.getChapterPages(any(), any(), any()) } returns listOf(
            com.haise.jiyu.source.Page(0, "p1.jpg", "p1.jpg"),
            com.haise.jiyu.source.Page(1, "p2.jpg", "p2.jpg"),
        )
        every { settings.sourceLanguage } returns flowOf("Auto")
        every { settings.targetLanguage } returns flowOf("Czech")
        every { translateRepository.isApiKeyConfigured } returns true
        every { context.getString(any()) } returns "chybova-hlaska"
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun block(text: String, sfx: Boolean = false) = TranslatedBlock(
        originalText = "orig", translatedText = text,
        leftF = 0.1f, topF = 0.1f, rightF = 0.4f, bottomF = 0.2f, isSfx = sfx,
    )

    private fun viewModel() = ReaderViewModel(
        savedStateHandle = SavedStateHandle(mapOf("chapterId" to "ch1")),
        context = context,
        repository = repository,
        translateRepository = translateRepository,
        settings = settings,
        historyDao = mockk<ReadHistoryDao>(relaxed = true),
        aniListRepository = mockk<AniListRepository>(relaxed = true),
        malRepository = mockk<MalRepository>(relaxed = true),
        kitsuRepository = mockk<KitsuRepository>(relaxed = true),
        muRepository = mockk<MangaUpdatesRepository>(relaxed = true),
        glossaryDao = mockk<GlossaryDao>(relaxed = true),
        sleepTimerManager = mockk<SleepTimerManager>(relaxed = true),
        networkMonitor = mockk<NetworkMonitor>(relaxed = true),
    )

    /** Nasimuluje průběh translateChapter - pro každou dvojici (index, bloky) zavolá callback. */
    private fun stubTranslateChapter(vararg pages: Pair<Int, List<TranslatedBlock>>) {
        coEvery {
            translateRepository.translateChapter(any(), any(), any(), any(), any(), any())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val onPageReady = arg<suspend (Int, List<TranslatedBlock>) -> Unit>(5)
            pages.forEach { (i, blocks) -> onPageReady(i, blocks) }
        }
    }

    @Test
    fun `a real translation switches the reader into translate mode`() = runBlocking {
        stubTranslateChapter(0 to listOf(block("Ahoj")))

        val vm = viewModel()
        vm.translateAllPages()

        assertTrue(vm.translateMode.value)
        assertNull("pri uspechu se nema hlasit chyba", vm.translationError.value)
    }

    @Test
    fun `a batch that translated nothing keeps the button available and reports why`() = runBlocking {
        // JADRO NAHLASENEHO BUGU: smycka dobehla, ale zadna stranka nema obsah. Driv se
        // translateMode nastavil bezpodminecne -> tlacitko "Prelozit vse" zmizelo natrvalo.
        stubTranslateChapter(0 to emptyList(), 1 to emptyList())

        val vm = viewModel()
        vm.translateAllPages()

        assertFalse("tlacitko musi zustat dostupne", vm.translateMode.value)
        assertNotNull("a uzivatel ma dostat konkretni hlasku", vm.translationError.value)
    }

    @Test
    fun `pages containing only sfx do not count as a successful translation`() = runBlocking {
        // SFX bubliny se nikdy nevykresluji, takze stranka slozena jen z nich je pro
        // uzivatele vizualne uplne stejna jako neprelozena.
        stubTranslateChapter(0 to listOf(block("BOOM", sfx = true), block("BANG", sfx = true)))

        val vm = viewModel()
        vm.translateAllPages()

        assertFalse(vm.translateMode.value)
        assertNotNull(vm.translationError.value)
    }

    @Test
    fun `a single real bubble among sfx is enough to count as translated`() = runBlocking {
        stubTranslateChapter(0 to listOf(block("BOOM", sfx = true), block("Ahoj")))

        val vm = viewModel()
        vm.translateAllPages()

        assertTrue(vm.translateMode.value)
    }

    @Test
    fun `hitting the rate limit shows the rate-limit message, not the generic failure`() = runBlocking {
        every { context.getString(com.haise.jiyu.R.string.reader_error_rate_limited) } returns "limit"
        coEvery {
            translateRepository.translateChapter(any(), any(), any(), any(), any(), any())
        } throws RateLimitedException()

        val vm = viewModel()
        vm.translateAllPages()

        assertEquals("limit", vm.translationError.value)
        assertFalse("po limitu se nesmi tvarit jako prelozeno", vm.translateMode.value)
    }

    @Test
    fun `the batch flag is cleared once the run finishes`() = runBlocking {
        stubTranslateChapter(0 to listOf(block("Ahoj")))

        val vm = viewModel()
        vm.translateAllPages()

        assertFalse("jinak by sel preklad spustit uz jen jednou", vm.batchTranslating.value)
        assertNull("ukazatel prubehu ma po dobehnuti zmizet", vm.batchProgress.value)
    }

    @Test
    fun `translated pages are exposed to the UI as they arrive`() = runBlocking {
        stubTranslateChapter(0 to listOf(block("Prvni")), 1 to listOf(block("Druha")))

        val vm = viewModel()
        vm.translateAllPages()

        assertEquals(2, vm.translatedPages.value.size)
        assertEquals("Prvni", vm.translatedPages.value[0]?.first()?.translatedText)
        assertEquals("Druha", vm.translatedPages.value[1]?.first()?.translatedText)
    }
}
