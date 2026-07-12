package com.haise.jiyu.source.comic

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Konfiguracni sanity test pro vsech 14 konkretnich comic zdroju (comic/ComicSources.kt).
 * Sdileny parsovaci engine (ComicSiteSource) uz je dukladne testovany v ComicSiteSourceTest
 * pres konfiguraci shodnou s ReadComicOnlineSource - tady jen overujeme, ze kazda podtrida
 * ma smysluplnou/unikatni konfiguraci (chytlo by napr. zapomenuty duplicitni "id" nebo
 * prazdnou "base" URL pri copy-paste chybe).
 */
class ComicSourcesConfigTest {

    private val client = OkHttpClient()

    private val sources: List<ComicSiteSource> = listOf(
        ReadComicOnlineSource(client),
        ReadAllComicsSource(client),
        ViewComicSource(client),
        XoxoComicsSource(client),
        ZipComicSource(client),
        ComicPunchSource(client),
        ComicBookPlusSource(client),
        GetComicsSource(client),
        GoComicsSource(client),
        GlobalComixSource(client),
        ComicKingdomSource(client),
        ComicExtraSource(client),
        ReadComicsOnline2Source(client),
        SuperHeroComicsSource(client),
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
