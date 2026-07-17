package com.haise.jiyu.source.comic

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Konfiguracni sanity test pro comic zdroje postavene na sdilenem
 * ComicSiteSource enginu (ReadFreeComicsOnlineSource.kt).
 * Sdileny parsovaci engine uz je dukladne testovany v ComicSiteSourceTest -
 * tady jen overujeme, ze kazda podtrida ma smysluplnou/unikatni konfiguraci.
 * ComicBookPlusSource neni ComicSiteSource podtrida (vlastni parsovani pro
 * atypickou strukturu webu), proto tu neni. GetComicsSource byl odstranen
 * (jen download-agregator na cizi CBR/CBZ hostery, zadny skutecny reader -
 * viz konverzace 2026-07-18).
 */
class ComicSourcesConfigTest {

    private val client = OkHttpClient()

    private val sources: List<ComicSiteSource> = listOf(
        ReadFreeComicsOnlineSource(client),
    )

    @Test
    fun `every comic source has a unique, non-blank id`() {
        val ids = sources.map { it.id }
        assertEquals(sources.size, ids.toSet().size)
        assertTrue(ids.all { it.isNotBlank() })
    }

    @Test
    fun `every comic source has a non-blank name and valid https base URL`() {
        sources.forEach { source ->
            assertTrue("${source.id} has blank name", source.name.isNotBlank())
            assertTrue("${source.id} base does not start with https://: ${source.base}", source.base.startsWith("https://"))
        }
    }

    @Test
    fun `every comic source reports contentType COMIC`() {
        sources.forEach { source ->
            assertEquals("${source.id} contentType", "COMIC", source.contentType)
        }
    }

    @Test
    fun `every comic source has non-blank selectors for item, link and page image`() {
        sources.forEach { source ->
            assertTrue("${source.id} comicItemSelector blank", source.comicItemSelector.isNotBlank())
            assertTrue("${source.id} comicLinkSelector blank", source.comicLinkSelector.isNotBlank())
            assertTrue("${source.id} pageImgSelector blank", source.pageImgSelector.isNotBlank())
        }
    }
}
