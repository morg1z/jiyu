package com.haise.jiyu.ui.duplicates

import com.haise.jiyu.data.db.entity.MangaEntity
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private fun manga(id: String, title: String, author: String? = null, artist: String? = null) =
        MangaEntity(
            id = id, sourceId = "comick", url = "https://api.comick.dev/comic/$id",
            title = title, coverUrl = null, description = null, status = null,
            inLibrary = true, author = author, artist = artist,
        )

    @Test
    fun `same title different authors is not a duplicate group`() {
        // ComicK ma dve odlisne serie jmenem "Demon Devourer" - spolecny nazev
        // sam o sobe duplikat nedela (nahlaska z knihovny).
        val groups = findDuplicateGroups(
            listOf(
                manga("a", "Demon Devourer", author = "빵먹는다람쥐"),
                manga("b", "Demon Devourer", author = "Kim Soong-Nyung"),
            ),
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `same title same author stays a duplicate group`() {
        val groups = findDuplicateGroups(
            listOf(
                manga("a", "Oversummoned, Overpowered, and Over It!", author = "Saitousa"),
                manga("b", "Oversummoned Overpowered and Over It", author = "Saitousa"),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].items.size)
    }

    @Test
    fun `blank author joins the populated subgroup`() {
        val groups = findDuplicateGroups(
            listOf(
                manga("a", "Title X", author = "Author A"),
                manga("b", "Title X", author = "Author B"),
                manga("c", "Title X", author = "Author A"),
                manga("d", "Title X"), // bez autora - pravdepodobne duplicate of A nebo B
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(listOf("a", "c", "d"), groups[0].items.map { it.id }.sorted())
    }

    @Test
    fun `artist is used when author is missing`() {
        val groups = findDuplicateGroups(
            listOf(
                manga("a", "Title Y", artist = "Artist 1"),
                manga("b", "Title Y", artist = "Artist 2"),
            ),
        )
        assertTrue(groups.isEmpty())
    }
}
