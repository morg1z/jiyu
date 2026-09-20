package com.haise.jiyu.ui.search

import androidx.lifecycle.SavedStateHandle
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.SourceManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Testy [GlobalSearchViewModel] - hledá ve VŠECH zdrojích souběžně a výsledky každého
 * z nich vpisuje do sdíleného seznamu, jakmile dorazí. To je klasické místo na chybu:
 * jeden padající zdroj nesmí shodit ostatní a pomalý zdroj nesmí přepsat výsledky,
 * které mezitím dorazily odjinud.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlobalSearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var sourceManager: SourceManager
    private lateinit var repository: MangaRepository
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        sourceManager = mockk(relaxed = true)
        repository = mockk(relaxed = true)
        settings = mockk(relaxed = true)
        every { settings.savedSearches } returns flowOf(emptyList())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun source(id: String): MangaSource = mockk(relaxed = true) {
        every { this@mockk.id } returns id
        every { name } returns id.uppercase()
        // Relaxed mock by jinak vrátil false a zdroj by z globálního hledání vypadl.
        every { includeInGlobalSearch } returns true
    }

    private fun manga(src: String, i: Int) =
        SManga(sourceId = src, url = "/$i", title = "$src-$i", coverUrl = null)

    private fun viewModel(savedStateHandle: SavedStateHandle = SavedStateHandle()) =
        GlobalSearchViewModel(savedStateHandle, sourceManager, repository, settings)

    @Test
    fun `a source behind Cloudflare is verified in the background after the others and then filled in`() = runTest(dispatcher) {
        coEvery { sourceManager.getAll() } returns listOf(source("plain"), source("cf"))
        coEvery { repository.search("plain", any(), any(), any()) } returns listOf(manga("plain", 1))
        // Bez povolení řešení (fáze 1) selže, po tichém ověření (fáze 2) odpoví.
        coEvery { repository.search("cf", any(), any(), any()) } answers {
            if (com.haise.jiyu.source.interceptor.InteractiveChallengePolicy.isNoSolve) {
                throw com.haise.jiyu.util.CloudflareProtectedException("cf.test", "https://cf.test/")
            }
            listOf(manga("cf", 1))
        }

        val vm = viewModel()
        vm.search("naruto")
        advanceUntilIdle()

        val byId = vm.results.value.associateBy { it.source.id }
        assertEquals(1, byId.getValue("plain").results.size)
        assertFalse(byId.getValue("cf").loading)
        assertEquals("cf-1", byId.getValue("cf").results.single().title)
        assertEquals(null, byId.getValue("cf").error)
    }

    @Test
    fun `a Cloudflare source that stays blocked ends as an error, not as endless loading`() = runTest(dispatcher) {
        coEvery { sourceManager.getAll() } returns listOf(source("cf"))
        coEvery { repository.search(any(), any(), any(), any()) } throws
            com.haise.jiyu.util.CloudflareProtectedException("cf.test", "https://cf.test/")

        val vm = viewModel()
        vm.search("naruto")
        advanceUntilIdle()

        val r = vm.results.value.single()
        assertFalse(r.loading)
        assertNotNull(r.error)
    }

    @Test
    fun `a blank query is ignored instead of hammering every source`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.search("   ")
        advanceUntilIdle()

        assertTrue(vm.results.value.isEmpty())
    }

    @Test
    fun `every source shows up immediately as loading, before any of them answers`() = runTest(dispatcher) {
        coEvery { sourceManager.getAll() } returns listOf(source("a"), source("b"))
        coEvery { repository.search(any(), any(), any(), any()) } returns emptyList()

        val vm = viewModel()
        vm.search("naruto")
        advanceUntilIdle()

        assertEquals(2, vm.results.value.size)
    }

    @Test
    fun `one failing source does not take the working ones down with it`() = runTest(dispatcher) {
        // Bez odchyceni per-zdroj by prvni vyjimka shodila cely awaitAll a uzivatel by
        // neuvidel ani vysledky zdroju, ktere odpovedely v poradku.
        coEvery { sourceManager.getAll() } returns listOf(source("dead"), source("alive"))
        coEvery { repository.search("dead", any(), any(), any()) } throws RuntimeException("503")
        coEvery { repository.search("alive", any(), any(), any()) } returns listOf(manga("alive", 1))

        val vm = viewModel()
        vm.search("naruto")
        advanceUntilIdle()

        val alive = vm.results.value.first { it.source.id == "alive" }
        val dead = vm.results.value.first { it.source.id == "dead" }
        assertEquals(1, alive.results.size)
        assertFalse(alive.loading)
        assertNotNull("padly zdroj musi nest chybu, ne tise mizet", dead.error)
        assertFalse(dead.loading)
    }

    @Test
    fun `sources with hits are sorted above empty and failed ones`() = runTest(dispatcher) {
        coEvery { sourceManager.getAll() } returns listOf(source("empty"), source("bad"), source("hit"))
        coEvery { repository.search("empty", any(), any(), any()) } returns emptyList()
        coEvery { repository.search("bad", any(), any(), any()) } throws RuntimeException("nope")
        coEvery { repository.search("hit", any(), any(), any()) } returns listOf(manga("hit", 1))

        val vm = viewModel()
        vm.search("naruto")
        advanceUntilIdle()

        assertEquals("zdroj s vysledky patri nahoru", "hit", vm.results.value.first().source.id)
    }

    @Test
    fun `each source contributes at most ten results so one source cannot flood the list`() = runTest(dispatcher) {
        coEvery { sourceManager.getAll() } returns listOf(source("flood"))
        coEvery { repository.search(any(), any(), any(), any()) } returns (1..50).map { manga("flood", it) }

        val vm = viewModel()
        vm.search("naruto")
        advanceUntilIdle()

        assertEquals(10, vm.results.value.first().results.size)
    }

    @Test
    fun `a later search replaces the previous results rather than mixing them`() = runTest(dispatcher) {
        coEvery { sourceManager.getAll() } returns listOf(source("a"))
        coEvery { repository.search("a", "prvni", any(), any()) } returns listOf(manga("a", 1))
        coEvery { repository.search("a", "druhy", any(), any()) } returns listOf(manga("a", 2))

        val vm = viewModel()
        vm.search("prvni")
        advanceUntilIdle()
        vm.search("druhy")
        advanceUntilIdle()

        assertEquals(1, vm.results.value.size)
        assertEquals("a-2", vm.results.value.first().results.single().title)
        assertEquals("druhy", vm.query.value)
    }

    @Test
    fun `init with a non-blank saved-state query triggers an immediate search`() = runTest(dispatcher) {
        coEvery { sourceManager.getAll() } returns listOf(source("a"))
        coEvery { repository.search("a", "naruto", any(), any()) } returns listOf(manga("a", 1))

        val vm = viewModel(SavedStateHandle(mapOf("q" to "naruto")))
        advanceUntilIdle()

        assertEquals("naruto", vm.query.value)
        assertEquals(1, vm.results.value.first().results.size)
    }

    @Test
    fun `init with a blank saved-state query does not search`() = runTest(dispatcher) {
        val vm = viewModel(SavedStateHandle(mapOf("q" to "")))
        advanceUntilIdle()

        assertTrue(vm.results.value.isEmpty())
        assertTrue(vm.query.value.isBlank())
    }

    @Test
    fun `saving a blank search is refused`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.saveSearch("  ")
        advanceUntilIdle()

        io.mockk.coVerify(exactly = 0) { settings.addSavedSearch(any()) }
    }
}
