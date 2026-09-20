package com.haise.jiyu.source.community

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import com.haise.jiyu.source.zeistmanga.ZeistMangaSource
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class CommunitySourcesTest {

    private val sources = CommunitySources.build(OkHttpClient())

    @Test
    fun `catalog is not empty and ids are unique with the ext prefix`() {
        assertTrue(sources.isNotEmpty())
        assertEquals("duplicitní id", sources.size, sources.map { it.id }.toSet().size)
        assertTrue(sources.all { it.id.startsWith("ext:") })
    }

    @Test
    fun `every site has a distinct host, a name and a language code`() {
        val hosts = sources.map { URI(it.homepageUrl!!).host.removePrefix("www.") }
        assertEquals("dva zdroje na stejné doméně", hosts.size, hosts.toSet().size)
        assertTrue(sources.all { it.name.isNotBlank() })
        assertTrue(sources.all { Regex("[a-z]{2,3}").matches(it.language) })
    }

    @Test
    fun `catalog sources stay out of global search and are not marked broken`() {
        assertTrue(sources.none { it.includeInGlobalSearch })
        assertFalse(sources.any { it.isBroken })
    }

    @Test
    fun `adult flag and content type are carried over`() {
        assertTrue("v katalogu mají být i 18+ zdroje", sources.any { it.isAdult })
        assertTrue(sources.any { !it.isAdult })
        assertTrue(sources.all { it.contentType in setOf("MANGA", "COMIC") })
    }

    @Test
    fun `sources offer tag filter and title sorting`() {
        assertTrue(sources.all { it.supportsTagFilter })
        // Blogger feed (ZeistManga) umí řadit jen podle data vydání.
        assertTrue(sources.filter { it !is ZeistMangaSource }.all { "title" in it.availableSorts })
        assertTrue(sources.filterIsInstance<ZeistMangaSource>().all { it.availableSorts == setOf("latest") })
    }
}
