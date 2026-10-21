package com.haise.jiyu.ui.resolver

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.SourceManager
import com.haise.jiyu.source.comick.ComicKChapterResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Regresni test na hlaseny bug "nedokazu se dostat za kapitolu 61":
 * resolver linkoval i NEJBLIZSI kapitolu, kdyz cilova na zdroji chybela
 * (kap.62 -> kap.61 pres setChapterFallbackTarget) - a ten link pak fast-path A
 * otviral navzdy pri kazdem dalsim pokusu, i kdyz zdroj skutecnou kapitolu
 * pozdeji pridal. Fast path A link validuje proti cislum kapitol a otraveny
 * smaze; selectCandidate vybira jen PRESNOU shodu (zadny minByOrNull/nearest).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceResolverViewModelLinkTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var resolver: ComicKChapterResolver
    private lateinit var repository: MangaRepository
    private lateinit var sourceManager: SourceManager
    private lateinit var settings: SettingsRepository
    private lateinit var context: Context

    private class FakeSource(override val id: String, override val name: String) : MangaSource {
        override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = emptyList()
        override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = emptyList()
        override suspend fun getMangaDetails(manga: SManga): SManga = manga
        override suspend fun getChapterList(manga: SManga): List<SChapter> = emptyList()
        override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()
    }

    private fun comickChapter(number: Float, fallback: String? = null) = ChapterEntity(
        id = "c$number", mangaId = "cm", sourceId = "comick", url = "/c/$number",
        name = "Ch $number", chapterNumber = number, dateUpload = 0L,
        language = "en", fallbackChapterId = fallback,
    )

    private fun resolvedChapter(number: Float, sourceId: String = "asura") = ChapterEntity(
        id = "r${number.toInt()}-$sourceId", mangaId = "m-$sourceId", sourceId = sourceId, url = "/r/$number",
        name = "Ch $number", chapterNumber = number, dateUpload = 0L, language = "en",
    )

    private val comickManga = MangaEntity(
        id = "cm", sourceId = "comick", url = "/cm", title = "Test",
        coverUrl = null, description = null, status = null,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        resolver = mockk()
        repository = mockk(relaxed = true)
        sourceManager = mockk(relaxed = true)
        settings = mockk(relaxed = true)
        context = mockk(relaxed = true)

        coEvery { repository.getManga("cm") } returns comickManga
        coEvery { repository.getAllChapters("cm") } returns listOf(
            comickChapter(60f), comickChapter(61f), comickChapter(62f),
        )
        coEvery { sourceManager.getAllForCrossSourceSearch() } returns emptyList()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(chapterId: String) = SourceResolverViewModel(
        savedStateHandle = SavedStateHandle(mapOf("chapterId" to chapterId)),
        resolver = resolver,
        repository = repository,
        sourceManager = sourceManager,
        settings = settings,
        appContext = context,
    )

    @Test
    fun `poisoned fallback link to a different chapter is cleared and never opened`() {
        val c62 = comickChapter(62f, fallback = "r61-asura")
        coEvery { repository.getChapter("c62") } returns c62
        coEvery { repository.getChapter("r61-asura") } returns resolvedChapter(61f)
        every {
            resolver.findCandidatesFlow(any(), any(), any(), any(), any(), any())
        } returns flowOf()

        val vm = viewModel("c62")

        // Otraveny link se smaze a nikdy se neotevre kap.61 - sweep pokracuje normalne.
        coVerify { repository.setChapterFallbackTarget("c62", null) }
        assertNull(vm.openedChapterId.value)
    }

    @Test
    fun `valid fallback link opens the resolved chapter without a sweep`() {
        val c62 = comickChapter(62f, fallback = "r62-asura")
        coEvery { repository.getChapter("c62") } returns c62
        coEvery { repository.getChapter("r62-asura") } returns resolvedChapter(62f)

        val vm = viewModel("c62")

        assertEquals("r62-asura", vm.openedChapterId.value)
        // Platny link = nula site - sweep se nespusti.
        io.mockk.verify(exactly = 0) {
            resolver.findCandidatesFlow(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `when no candidate has the requested chapter nothing wrong opens and no link is written`() {
        val c62 = comickChapter(62f)
        coEvery { repository.getChapter("c62") } returns c62
        // Kandidat bez pozadovane kapitoly (ma jen do 61) - driv by se otevrel
        // "nejblizsi" = kap.61 a trvale nalinkoval.
        val thin = com.haise.jiyu.source.comick.ResolvedCandidate(
            source = FakeSource("asura", "Asura"),
            manga = SManga(sourceId = "asura", url = "/m", title = "Test", coverUrl = null),
            matchedChapterCount = 61,
            hasRequestedChapter = false,
            isFavorite = false,
            nearestChapterDistance = 1f,
            minChapterNumber = 1f,
            maxChapterNumber = 61f,
        )
        every {
            resolver.findCandidatesFlow(any(), any(), any(), any(), any(), any())
        } returns flowOf(thin)
        coEvery { repository.openPreview(thin.manga) } returns "m-asura"
        coEvery { repository.getAllChapters("m-asura") } returns listOf(
            resolvedChapter(60f), resolvedChapter(61f),
        )

        val vm = viewModel("c62")

        assertNull(vm.openedChapterId.value)
        assertNotNull(vm.error.value)
        // Zadny poison link - pristi pokus resolvuje znovu (zdroj mezitim muze kap.62 pridat).
        coVerify(exactly = 0) { repository.setChapterFallbackTarget("c62", any()) }
    }
}
