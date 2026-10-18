package com.haise.jiyu.translate

import android.graphics.Bitmap
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proxy hlásí upstream model v poli "model" odpovědi (viz translate-proxy/index.ts,
 * GROQ_FALLBACK_MODEL/GEMINI_FALLBACK_MODEL). Překlad ze záložního - slabšího - modelu se
 * UKÁZÁ i ULOŽÍ: původní "necachovat degradovaný výstup" vedlo k tomu, že při celodenně
 * vyčerpané kvótě proxy se každé otevření kapitoly překládalo kompletně znovu (minuty
 * čekání + další spálená kvóta). Kvalitu hlídají per-blok gaty (lint, self-review,
 * safety gate) a degradace se jen zaznamená do diagnostiky (note="degraded").
 *
 * Konstrukce [TranslateRepository] přímo bez Hiltu - stejný vzor jako [TranslateWithGroqRetryTest].
 * Přeložený text držím pod 40 znaky, aby [isWrongTargetLanguage] skončila předčasně a
 * nikdy nespustila ML Kit `LanguageIdentification` (ten v JVM testu neexistuje).
 */
class DegradedModelCacheTest {

    private val rawBlock = RawTextBlock(text = "Hello there, friend.", leftF = 0f, topF = 0f, rightF = 0.2f, bottomF = 0.1f)

    private fun repository(
        groqClient: GroqTranslateClient,
        dao: com.haise.jiyu.data.db.TranslatedPageDao,
        geminiClient: GeminiTranslateClient = mockk(relaxed = true), // isConfigured=false -> Gemini kroky se přeskočí
        providerHealth: ProviderHealth = mockk(relaxed = true), // allUnavailable() -> false
    ): TranslateRepository {
        val context = mockk<android.content.Context>(relaxed = true).also {
            // Diagnostika zapisuje do getExternalFilesDir - null = zápis se přeskočí,
            // jinak by relaxed mock vyrobil File("") a test zanechal soubor v cwd.
            every { it.getExternalFilesDir(any()) } returns null
        }
        val pageBitmapLoader = mockk<PageBitmapLoader>().also {
            coEvery { it.load(any()) } returns mockk<Bitmap>()
        }
        val ocrEngine = mockk<OcrEngine>().also {
            coEvery { it.recognize(any(), any(), any(), any()) } returns listOf(rawBlock)
        }
        val glossaryRepository = mockk<GlossaryRepository>().also {
            coEvery { it.getMap(any(), any()) } returns emptyMap()
            coEvery { it.getProtectedEntries(any(), any()) } returns emptyList()
        }
        // Relaxed DAO vrací pro nullable typy MOCK instanci, ne null - getCachedPage by
        // pak našel "prázdnou" stránku a translatePage skončil před jakýmkoli překladem.
        coEvery { dao.getById(any()) } returns null
        val mangaDao = mockk<com.haise.jiyu.data.db.MangaDao>(relaxed = true).also {
            coEvery { it.getById(any()) } returns null
        }
        return TranslateRepository(
            context = context,
            ocrEngine = ocrEngine,
            pageBitmapLoader = pageBitmapLoader,
            groqClient = groqClient,
            geminiClient = geminiClient,
            glossaryRepository = glossaryRepository,
            providerHealth = providerHealth,
            mangaDao = mangaDao,
            dao = dao,
            novelDao = mockk(relaxed = true),
            manualDao = mockk(relaxed = true),
            byokClient = mockk(relaxed = true), // isConfigured() -> false -> žádný vision OCR rozpočet
        )
    }

    private fun groqReturning(model: String?): GroqTranslateClient = mockk<GroqTranslateClient>().also { client ->
        every { client.isConfigured } returns true
        coEvery {
            client.translateBatch(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            // onModel je 8. parametr (index 7) - proxy nahlášený model se předává ven.
            arg<(String?) -> Unit>(7)(model)
            // TR-7: poziční řetězec odmítá odpověď s jiným počtem položek než vstup.
            List(arg<List<String>>(0).size) { "Krátká odpověď." }
        }
    }

    @Test
    fun `degraded fallback model translation is shown AND cached`() = runTest {
        // Politika se změnila po auditu Vagabondu: degradace proxy trvá klidně celý den
        // a necachování znamenalo kompletní re-překlad kapitoly při KAŽDÉM otevření.
        val dao = mockk<com.haise.jiyu.data.db.TranslatedPageDao>(relaxed = true)
        val result = repository(groqReturning("qwen/qwen3.8-27b"), dao)
            .translatePage("page-url", "ch1", "m1", pageIndex = 0)

        assertEquals("Krátká odpověď.", result.single().translatedText)
        coVerify(exactly = 1) { dao.upsert(any()) }
    }

    @Test
    fun `primary model translation IS cached`() = runTest {
        val dao = mockk<com.haise.jiyu.data.db.TranslatedPageDao>(relaxed = true)
        repository(groqReturning("openai/gpt-oss-120b"), dao)
            .translatePage("page-url", "ch1", "m1", pageIndex = 0)

        coVerify(exactly = 1) { dao.upsert(any()) }
    }

    @Test
    fun `missing model field from an older proxy still caches`() = runTest {
        // Starší proxy "model" neposílá - nemůže dělat interní fallback, takže odpověď
        // je vždy hlavní model a chování zůstává jako dřív (výsledek se uloží).
        val dao = mockk<com.haise.jiyu.data.db.TranslatedPageDao>(relaxed = true)
        repository(groqReturning(null), dao)
            .translatePage("page-url", "ch1", "m1", pageIndex = 0)

        coVerify(exactly = 1) { dao.upsert(any()) }
    }

    private fun degradedGemini(): GeminiTranslateClient = mockk<GeminiTranslateClient>().also { client ->
        every { client.isConfigured } returns true
        // Self-review (běží jen pro nedegradovaný výsledek) tady neřešíme - fail-open.
        coEvery { client.reviewPairs(any(), any(), any()) } returns null
        coEvery {
            // Reálně proxy na vyčerpaném primáru vrátí flash-lite jen u providera
            // "gemini"; openrouter gemma cesta tu pro jistotu spadne (null).
            client.translateBubbles(any(), any(), any(), any(), any(), any())
        } coAnswers {
            if (arg<String>(2) != "gemini") return@coAnswers null
            GeminiTranslationResponse(
                bubbles = listOf(
                    GeminiBubbleTranslation(
                        id = 0,
                        original = "Hello there, friend.",
                        translated = "Tichá odpověď.",
                        bubbleSizeTag = "MEDIUM",
                        isSfx = false,
                        syllableBreaks = "",
                    )
                ),
                model = "gemini-3.5-flash-lite",
            )
        }
    }

    @Test
    fun `degraded gemini result is rejected when a healthy alternative exists`() = runTest {
        // Hlášené "jedna kapitola a hned omezená kvalita": proxy degradovala na
        // flash-lite, ale groq/gpt-oss-120b byl zdravý - degradovaný výsledek se teď
        // zahodí, provider se odstaví a řetězec dojede na plnou kvalitu.
        val health = mockk<ProviderHealth>(relaxed = true).also {
            every { it.isAvailable(any()) } returns true
            every { it.allUnavailable() } returns false
        }
        val dao = mockk<com.haise.jiyu.data.db.TranslatedPageDao>(relaxed = true)
        val groq = groqReturning("openai/gpt-oss-120b")
        val result = repository(groq, dao, geminiClient = degradedGemini(), providerHealth = health)
            .translatePage("page-url", "ch1", "m1", pageIndex = 0)

        assertEquals("Krátká odpověď.", result.single().translatedText)
        assertTrue("výsledek ze zdravého primáru nesmí nést degraded flag", !result.single().degraded)
        coVerify { health.markUnavailable("gemini") }
        coVerify(exactly = 1) { groq.translateBatch(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `degraded gemini result is accepted when it is the last option`() = runTest {
        // Všichni ostatní provideři odstavení -> degradace je lepší než nic a musí se
        // označit, ať banner "omezená kvalita" mluví pravdu.
        val health = mockk<ProviderHealth>(relaxed = true).also {
            every { it.isAvailable(match { it != "gemini" }) } returns false
            every { it.isAvailable("gemini") } returns true
            every { it.allUnavailable() } returns false
        }
        val dao = mockk<com.haise.jiyu.data.db.TranslatedPageDao>(relaxed = true)
        val result = repository(groqReturning("openai/gpt-oss-120b"), dao, geminiClient = degradedGemini(), providerHealth = health)
            .translatePage("page-url", "ch1", "m1", pageIndex = 0)

        assertEquals("Tichá odpověď.", result.single().translatedText)
        assertTrue("akceptovaná degradace se má označit", result.single().degraded)
    }

    @Test
    fun `degraded set matches the proxy fallback models`() {
        // Odráží GROQ_FALLBACK_MODEL/GEMINI_FALLBACK_MODEL v translate-proxy/index.ts -
        // sada se používá jen pro diagnostický štítek "degraded" v page záznamu.
        assertTrue("qwen/qwen3.8-27b" in TranslateRepository.DEGRADED_MODELS)
        assertTrue("gemini-3.5-flash-lite" in TranslateRepository.DEGRADED_MODELS)
    }
}
