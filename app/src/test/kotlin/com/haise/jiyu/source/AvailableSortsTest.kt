package com.haise.jiyu.source

import com.haise.jiyu.source.madara.MadaraSource
import com.haise.jiyu.source.mangathemesia.MangaThemesiaSource
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class AvailableSortsTest {

    private class FakeSource(private val sortable: Boolean) : MangaSource {
        override val id = "fake"
        override val name = "Fake"
        override val supportsSortOrder get() = sortable
        override suspend fun search(query: String, page: Int, filter: MangaFilter) = emptyList<SManga>()
        override suspend fun getPopular(page: Int, filter: MangaFilter) = emptyList<SManga>()
        override suspend fun getMangaDetails(manga: SManga) = manga
        override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
        override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
    }

    @Test
    fun `default sorts derive from supportsSortOrder`() {
        assertEquals(setOf("popular", "latest"), FakeSource(true).availableSorts)
        assertEquals(setOf("popular"), FakeSource(false).availableSorts)
    }

    @Test
    fun `templates advertise alphabetical sorting`() {
        val client = OkHttpClient()
        assertEquals(setOf("popular", "latest", "title"), MangaThemesiaSource("a", "A", "https://a.test", client).availableSorts)
        assertEquals(setOf("popular", "latest", "title"), MadaraSource("b", "B", "https://b.test", client).availableSorts)
    }
}
