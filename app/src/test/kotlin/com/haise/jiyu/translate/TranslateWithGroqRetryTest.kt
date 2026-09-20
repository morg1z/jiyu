package com.haise.jiyu.translate

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TranslateRepository.translateWithGroq] je sdílená cesta pro Češtinu (jako fallback po
 * Gemini) i pro VŠECHNY ostatní cílové jazyky (jediná cesta - viz [TranslateRepository.
 * translatePage]) - na rozdíl od Gemini větve ale dřív vůbec nevolala [isRepetitionLoop],
 * takže zacyklený/opakující se výstup se přijal jako hotový překlad beze změny (nahlášeno
 * uživatelem na angličtině: "MANY OF THE USERS WERE HOSPITALIZED AND WERE HOSPITALIZED").
 *
 * Konstrukce [TranslateRepository] přímo bez Hiltu - stejný vzor jako [NovelCacheKeyTest].
 */
class TranslateWithGroqRetryTest {

    private fun classified(text: String) = ClassifiedBubble(
        raw = RawTextBlock(text = text, leftF = 0f, topF = 0f, rightF = 0.1f, bottomF = 0.1f),
        sizeTag = SizeTag.MEDIUM,
        bubbleType = BubbleType.SPEECH,
        isSfx = false,
        lineCount = 1,
    )

    private fun repository(groqClient: GroqTranslateClient) = TranslateRepository(
        context = mockk(relaxed = true),
        ocrEngine = mockk(relaxed = true),
        pageBitmapLoader = mockk(relaxed = true),
        groqClient = groqClient,
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
    fun `a bubble stuck in a repetition loop is retried once and the retry result wins`() = runTest {
        val classified = listOf(classified("A"), classified("Take him to the hospital."))
        val groqClient = mockk<GroqTranslateClient>()
        coEvery {
            groqClient.translateBatch(
                texts = listOf("A", "Take him to the hospital."),
                targetLanguage = "English", sourceLanguage = "Auto", glossary = emptyMap(),
                provider = "groq", mangaContext = "", previousLines = emptyList(),
            )
        } returns listOf("Á", "hospitalized hospitalized hospitalized hospitalized hospitalized")
        // Retry posle jen tu jednu spatnou bublinu - druha uz je hotova, netreba jeste jednou.
        coEvery {
            groqClient.translateBatch(
                texts = listOf("Take him to the hospital."),
                targetLanguage = "English", sourceLanguage = "Auto", glossary = emptyMap(),
                provider = "groq", mangaContext = "", previousLines = emptyList(),
            )
        } returns listOf("Take him to the hospital, now.")

        val result = repository(groqClient).translateWithGroq(
            classified = classified,
            glossary = emptyMap(),
            targetLanguage = "English",
            sourceLanguage = "Auto",
            mangaId = "m1",
        )

        assertEquals("Á", result?.get(0)?.translatedText)
        assertEquals("Take him to the hospital, now.", result?.get(1)?.translatedText)
    }

    @Test
    fun `a normal good translation is not retried`() = runTest {
        val classified = listOf(classified("Hello there."))
        val groqClient = mockk<GroqTranslateClient>()
        coEvery {
            groqClient.translateBatch(
                texts = listOf("Hello there."),
                targetLanguage = "English", sourceLanguage = "Auto", glossary = emptyMap(),
                provider = "groq", mangaContext = "", previousLines = emptyList(),
            )
        } returns listOf("Hi there.")

        val result = repository(groqClient).translateWithGroq(
            classified = classified,
            glossary = emptyMap(),
            targetLanguage = "English",
            sourceLanguage = "Auto",
            mangaId = "m1",
        )

        assertEquals("Hi there.", result?.get(0)?.translatedText)
        coVerify(exactly = 1) { groqClient.translateBatch(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a failed retry is discarded and the original garbled text is kept rather than crashing`() = runTest {
        val classified = listOf(classified("Take him to the hospital."))
        val groqClient = mockk<GroqTranslateClient>()
        coEvery {
            groqClient.translateBatch(
                texts = listOf("Take him to the hospital."),
                targetLanguage = "English", sourceLanguage = "Auto", glossary = emptyMap(),
                provider = "groq", mangaContext = "", previousLines = emptyList(),
            )
        } returnsMany listOf(
            listOf("hospitalized hospitalized hospitalized hospitalized hospitalized"),
            emptyList(),
        )

        val result = repository(groqClient).translateWithGroq(
            classified = classified,
            glossary = emptyMap(),
            targetLanguage = "English",
            sourceLanguage = "Auto",
            mangaId = "m1",
        )

        assertEquals(
            "hospitalized hospitalized hospitalized hospitalized hospitalized",
            result?.get(0)?.translatedText,
        )
    }
}
