package com.haise.jiyu.ui.duplicates

import com.haise.jiyu.data.repository.MangaRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Hlídá, že `scan()` vždy vypne `isLoading`, i když `getAllLibraryManga()` selže.
 *
 * Proč tenhle test vznikl: `scan()` dřív nastavovalo `_isLoading.value = false` jako
 * poslední řádek přímo v těle korutiny, bez try/finally. Chyba z `getAllLibraryManga()`
 * (poškozený řádek v DB, IO chyba) by tak nechala `isLoading` navždy `true` - obrazovka
 * duplicit by se točila donekonečna bez jakékoli zprávy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DuplicateDetectorViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: MangaRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `scan turns off isLoading even when the repository throws`() = runTest {
        coEvery { repository.getAllLibraryManga() } throws IOException("db poškozena")

        val vm = DuplicateDetectorViewModel(repository, io.mockk.mockk(relaxed = true))
        advanceUntilIdle()

        assertFalse(
            "chyba pri scanu nesmi nechat isLoading navzdy true",
            vm.isLoading.value,
        )
    }
}
