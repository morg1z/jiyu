package com.haise.jiyu.ui.browse

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.SourceManager
import com.haise.jiyu.util.NetworkMonitor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Testy stránkování a chybových stavů [SourceBrowseViewModel].
 *
 * Proč zrovna tenhle ViewModel: drží čítač stránek napříč asynchronními voláními a při
 * selhání ho vrací zpátky - přesně ten typ stavové logiky, kde se chyba neprojeví pádem,
 * ale tím, že uživateli tiše zmizí nebo se zdvojí výsledky.
 *
 * Závislosti jsou konkrétní final třídy bez rozhraní, proto mockk (viz build.gradle.kts).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceBrowseViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var repository: MangaRepository
    private lateinit var sourceManager: SourceManager
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var context: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mockk(relaxed = true)
        sourceManager = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { networkMonitor.isOnline } returns true
        every { networkMonitor.networkState } returns MutableStateFlow(true)
        coEvery { sourceManager.getById(any()) } returns null
        every { context.getString(any()) } returns "chyba"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun manga(i: Int) = SManga(sourceId = "src", url = "/m$i", title = "Manga $i", coverUrl = null)

    private fun fullPage() = (1..20).map { manga(it) }

    private fun viewModel(
        handler: com.haise.jiyu.source.ErrorActionHandler = mockk(relaxed = true),
    ) = SourceBrowseViewModel(
        savedStateHandle = SavedStateHandle(mapOf("sourceId" to "src")),
        repository = repository,
        sourceManager = sourceManager,
        networkMonitor = networkMonitor,
        appContext = context,
        errorActionHandler = handler,
    )

    @Test
    fun `a cloudflare error exposes the solve action and performing it retries the load`() = runTest(dispatcher) {
        val handler = mockk<com.haise.jiyu.source.ErrorActionHandler>()
        coEvery { handler.perform(any()) } returns true
        coEvery { repository.getPopular("src", 1, any()) } throws
            com.haise.jiyu.util.CloudflareProtectedException("site.test", "https://site.test/manga/")

        val vm = viewModel(handler)
        advanceUntilIdle()
        assertEquals(com.haise.jiyu.util.ErrorAction.SolveCloudflare("https://site.test/manga/"), vm.errorAction.value)

        // Po ručním vyřešení web odpoví normálně.
        coEvery { repository.getPopular("src", 1, any()) } returns listOf(manga(1))
        vm.performErrorAction()
        advanceUntilIdle()

        coVerify(exactly = 1) { handler.perform(com.haise.jiyu.util.ErrorAction.SolveCloudflare("https://site.test/manga/")) }
        assertEquals(1, vm.results.value.size)
        assertEquals(null, vm.errorAction.value)
        assertEquals(null, vm.error.value)
    }

    @Test
    fun `a moved source is retried after the new address is applied, or suggested`() = runTest(dispatcher) {
        val handler = mockk<com.haise.jiyu.source.ErrorActionHandler>()
        coEvery { handler.resolveConnectionError("src", any()) } returnsMany listOf(
            com.haise.jiyu.source.MirrorResolution.Applied("new.example"),
        )
        // První pokus spadne na spojení, po použití nové adresy projde.
        coEvery { repository.getPopular("src", 1, any()) } throws java.net.UnknownHostException("old") andThen listOf(manga(1))
        val vm = viewModel(handler)
        advanceUntilIdle()
        assertEquals(1, vm.results.value.size)
        assertEquals(null, vm.error.value)
        coVerify(exactly = 2) { repository.getPopular("src", 1, any()) }

        // Jiná značka domény: jen návrh.
        val suggest = com.haise.jiyu.util.ErrorAction.UseNewDomain("src", "elsewhere.org")
        val handler2 = mockk<com.haise.jiyu.source.ErrorActionHandler>()
        coEvery { handler2.resolveConnectionError("src", any()) } returns com.haise.jiyu.source.MirrorResolution.Suggested(suggest)
        coEvery { repository.getPopular("src", 1, any()) } throws java.net.UnknownHostException("old")
        val vm2 = viewModel(handler2)
        advanceUntilIdle()
        assertEquals(suggest, vm2.errorAction.value)
    }

    @Test
    fun `an unsuccessful action does not retry and plain errors have no action`() = runTest(dispatcher) {
        val handler = mockk<com.haise.jiyu.source.ErrorActionHandler>()
        coEvery { handler.perform(any()) } returns false
        coEvery { handler.resolveConnectionError(any(), any()) } returns com.haise.jiyu.source.MirrorResolution.None
        coEvery { repository.getPopular("src", 1, any()) } throws
            com.haise.jiyu.util.CloudflareProtectedException("site.test", "https://site.test/manga/")
        val vm = viewModel(handler)
        advanceUntilIdle()
        vm.performErrorAction()
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.getPopular("src", 1, any()) }

        coEvery { repository.getPopular("src", 1, any()) } throws java.io.IOException("net")
        val plain = viewModel(handler)
        advanceUntilIdle()
        assertEquals(null, plain.errorAction.value)
    }

    @Test
    fun `a full first page means there may be more to load`() = runTest(dispatcher) {
        coEvery { repository.getPopular("src", 1, any()) } returns fullPage()

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(20, vm.results.value.size)
        assertTrue("plna stranka -> ocekavame dalsi", vm.hasMore.value)
    }

    @Test
    fun `a small non-empty first page still means there may be more to load`() = runTest(dispatcher) {
        // Jadro opravy: hasMore driv hadal "plna stranka = 20 polozek", takze zdroj s
        // prirozene mensi strankou (9, 13, 16...) vypadal po prvni strance jako
        // vycerpany, i kdyz web mel dalsi stranky plne titulu (overeno zive u MangaWorld,
        // KuraManga a ~17 dalsich zdroju - jina strana 2 nez strana 1). "Konec seznamu"
        // ted pozna appka jedine podle PRAZDNE stranky, ne podle poctu polozek.
        coEvery { repository.getPopular("src", 1, any()) } returns listOf(manga(1), manga(2))

        val vm = viewModel()
        advanceUntilIdle()

        assertTrue("neprazdna stranka, i kdyz mala -> muze byt dalsi", vm.hasMore.value)
    }

    @Test
    fun `an empty first page means there is nothing to show`() = runTest(dispatcher) {
        coEvery { repository.getPopular("src", 1, any()) } returns emptyList()

        val vm = viewModel()
        advanceUntilIdle()

        assertFalse(vm.hasMore.value)
    }

    @Test
    fun `loadMore appends the next page instead of replacing the results`() = runTest(dispatcher) {
        coEvery { repository.getPopular("src", 1, any()) } returns fullPage()
        coEvery { repository.getPopular("src", 2, any()) } returns listOf(manga(21))

        val vm = viewModel()
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()

        assertEquals("prvni stranka musi zustat, druha se pripojit", 21, vm.results.value.size)
        assertEquals("Manga 21", vm.results.value.last().title)
    }

    @Test
    fun `a failed loadMore rewinds the page counter so retry asks for the SAME page`() = runTest(dispatcher) {
        // Jadro veci: kdyby se citac nevratil, dalsi pokus by stranku 2 preskocil a
        // uzivateli by 20 titulu tise zmizelo, aniz by se cokoli tvarilo jako chyba.
        coEvery { repository.getPopular("src", 1, any()) } returns fullPage()
        coEvery { repository.getPopular("src", 2, any()) } throws RuntimeException("503")

        val vm = viewModel()
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()

        coEvery { repository.getPopular("src", 2, any()) } returns listOf(manga(21))
        vm.loadMore()
        advanceUntilIdle()

        coVerify(exactly = 2) { repository.getPopular("src", 2, any()) }
        assertEquals(21, vm.results.value.size)
    }

    @Test
    fun `loadMore does nothing once an empty page confirmed the end of the list`() = runTest(dispatcher) {
        coEvery { repository.getPopular("src", 1, any()) } returns listOf(manga(1))
        coEvery { repository.getPopular("src", 2, any()) } returns emptyList()

        val vm = viewModel()
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.getPopular("src", 2, any()) }
        coVerify(exactly = 0) { repository.getPopular("src", 3, any()) }
    }

    @Test
    fun `loadMore stops when the site repeats a page it already returned`() = runTest(dispatcher) {
        // Web za koncem seznamu vrací znovu stránku 1 - bez téhle pojistky by hasMore zůstal true navždy.
        coEvery { repository.getPopular("src", any(), any()) } returns listOf(manga(1), manga(2))

        val vm = viewModel()
        advanceUntilIdle()
        assertTrue(vm.hasMore.value)
        vm.loadMore()
        advanceUntilIdle()
        assertFalse("stránka nepřidala nic nového -> konec", vm.hasMore.value)
        assertEquals(2, vm.results.value.size)
        vm.loadMore()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getPopular("src", 3, any()) }
    }

    @Test
    fun `being offline reports an error instead of silently showing an empty grid`() = runTest(dispatcher) {
        every { networkMonitor.isOnline } returns false

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals("chyba", vm.error.value)
        assertFalse(vm.hasMore.value)
        coVerify(exactly = 0) { repository.getPopular(any(), any(), any()) }
    }

    @Test
    fun `search results replace popular results rather than piling onto them`() = runTest(dispatcher) {
        coEvery { repository.getPopular("src", 1, any()) } returns fullPage()
        coEvery { repository.search("src", "naruto", 1, any()) } returns listOf(manga(99))

        val vm = viewModel()
        advanceUntilIdle()
        vm.search("naruto", MangaFilter())
        advanceUntilIdle()

        assertEquals(1, vm.results.value.size)
        assertEquals("Manga 99", vm.results.value.first().title)
    }

    @Test
    fun `loadMore after a search keeps searching, it does not fall back to popular`() = runTest(dispatcher) {
        coEvery { repository.getPopular("src", 1, any()) } returns fullPage()
        coEvery { repository.search("src", "naruto", 1, any()) } returns fullPage()
        coEvery { repository.search("src", "naruto", 2, any()) } returns listOf(manga(99))

        val vm = viewModel()
        advanceUntilIdle()
        vm.search("naruto", MangaFilter())
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.search("src", "naruto", 2, any()) }
        coVerify(exactly = 0) { repository.getPopular("src", 2, any()) }
    }

    @Test
    fun `opening the same manga twice in a row does not fire two requests`() = runTest(dispatcher) {
        coEvery { repository.getPopular("src", 1, any()) } returns fullPage()
        coEvery { repository.openPreview(any()) } returns "manga-1"

        val vm = viewModel()
        advanceUntilIdle()
        vm.openManga(manga(1)) {}
        vm.openManga(manga(1)) {}
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.openPreview(any()) }
    }

    @Test
    fun `a failed open surfaces its own error without wiping the loaded grid`() = runTest(dispatcher) {
        coEvery { repository.getPopular("src", 1, any()) } returns fullPage()
        coEvery { repository.openPreview(any()) } throws RuntimeException("nope")

        val vm = viewModel()
        advanceUntilIdle()
        vm.openManga(manga(1)) {}
        advanceUntilIdle()

        assertEquals("mrizka vysledku musi zustat", 20, vm.results.value.size)
        assertTrue("chyba otevreni ma vlastni stav", vm.openError.value != null)
        assertEquals("a nesmi se prelit do celoobrazovkove chyby", null, vm.error.value)
    }

    @Test
    fun `fetchCoverIfMissing patches only the matching item once the cover arrives`() = runTest(dispatcher) {
        coEvery { repository.getPopular("src", 1, any()) } returns listOf(manga(1), manga(2))
        coEvery { repository.fetchCover(manga(1)) } returns "https://cdn.example.com/cover1.jpg"

        val vm = viewModel()
        advanceUntilIdle()
        vm.fetchCoverIfMissing(manga(1))
        advanceUntilIdle()

        assertEquals("https://cdn.example.com/cover1.jpg", vm.results.value[0].coverUrl)
        assertEquals("sousedni polozka nedotcena", null, vm.results.value[1].coverUrl)
    }

    @Test
    fun `fetchCoverIfMissing for the same item twice fires only one network call`() = runTest(dispatcher) {
        coEvery { repository.getPopular("src", 1, any()) } returns listOf(manga(1))
        coEvery { repository.fetchCover(manga(1)) } returns "https://cdn.example.com/cover1.jpg"

        val vm = viewModel()
        advanceUntilIdle()
        vm.fetchCoverIfMissing(manga(1))
        vm.fetchCoverIfMissing(manga(1))
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.fetchCover(manga(1)) }
    }

    @Test
    fun `fetchCoverIfMissing does nothing when the item already has a cover`() = runTest(dispatcher) {
        val withCover = manga(1).copy(coverUrl = "https://cdn.example.com/already-has-one.jpg")
        coEvery { repository.getPopular("src", 1, any()) } returns listOf(withCover)

        val vm = viewModel()
        advanceUntilIdle()
        vm.fetchCoverIfMissing(withCover)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.fetchCover(any()) }
    }
}
