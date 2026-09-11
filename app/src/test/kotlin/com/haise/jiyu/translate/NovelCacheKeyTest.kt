package com.haise.jiyu.translate

import io.mockk.mockk
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hlídá, že klíč cache přeložených novel nese verzi pipeline.
 *
 * Proč zrovna tohle: klíč stránek ji má odjakživa, klíč novel ne - překlady novel se proto
 * po opravě promptu nikdy nepřepočítaly a uživatel viděl starou, rozbitou verzi navždycky.
 * Přesně tomu mělo verzování zabránit ("nainstaloval jsem opravu a nic si neopravil").
 *
 * Test schválně neporovnává s konkrétním číslem verze - to se bude zvedat dál. Ověřuje jen,
 * že se verze v klíči vůbec projeví, tedy že bump cache opravdu zneplatní.
 */
class NovelCacheKeyTest {

    private fun repository() = TranslateRepository(
        context = mockk(relaxed = true),
        ocrEngine = mockk(relaxed = true),
        pageBitmapLoader = mockk(relaxed = true),
        groqClient = mockk(relaxed = true),
        geminiClient = mockk(relaxed = true),
        glossaryRepository = mockk(relaxed = true),
        providerHealth = mockk(relaxed = true),
        mangaDao = mockk(relaxed = true),
        dao = mockk(relaxed = true),
        novelDao = mockk(relaxed = true),
        manualDao = mockk(relaxed = true),
        byokClient = mockk(relaxed = true),
    )

    @Test
    fun `the novel cache key carries a pipeline version`() {
        val key = repository().novelCacheId("ch1", "Japanese", "Czech")

        assertTrue("v klici chybi verze pipeline: $key", Regex("::v\\d+$").containsMatchIn(key))
    }

    @Test
    fun `different language pairs never share a cache entry`() {
        val repo = repository()

        assertNotEquals(
            repo.novelCacheId("ch1", "Japanese", "Czech"),
            repo.novelCacheId("ch1", "English", "Czech"),
        )
        assertNotEquals(
            repo.novelCacheId("ch1", "Japanese", "Czech"),
            repo.novelCacheId("ch1", "Japanese", "English"),
        )
        assertNotEquals(
            repo.novelCacheId("ch1", "Japanese", "Czech"),
            repo.novelCacheId("ch2", "Japanese", "Czech"),
        )
    }
}
