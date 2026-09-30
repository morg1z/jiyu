package com.haise.jiyu.translate

import android.graphics.Bitmap
import com.haise.jiyu.data.db.entity.TranslatedPageEntity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Regresní testy audit fáze 12 (provider výstupy):
 *
 * - TR-7: poziční provider cesty (Groq/OpenRouter/Mistral/BYOK/on-device) musí
 *   odmítnout odpověď s jiným počtem položek, než se poslalo - vynechaná položka
 *   uprostřed jinak posune každý další překlad na cizí bublinu. Gemini cesta má
 *   echo-guard [originalMatches], holý řetězec nemá.
 * - TR-8: SFX se nesmí posílat do Gemini promptu vůbec - dřív zabíraly id sloty
 *   a model je "překládal" nadarmo (výsledek se stejně zahazoval přes sfxBlock).
 *   Odpověď se po parse přemapuje z filtrovaných pozic zpět na původní indexy.
 * - TR-13: jednopísmenný untranslated blok nesmí držet stránku "retryable"
 *   (repair cesta ho stejně neopraví - shodný práh >=2 písmen).
 * - TR-14: první výskyt fráze, co byl untranslated, se do-přeloží z phrase cache,
 *   jakmile ho pozdější výskyt přeloží (jednosměrný zápis dřív nefungoval zpět).
 */
class ProviderResponseGuardsTest {

    private val context = mockk<android.content.Context>(relaxed = true).also {
        every { it.getExternalFilesDir(any()) } returns null
        every { it.getSystemService(android.app.ActivityManager::class.java) } returns null
        every { it.getSystemService(android.content.Context.ACTIVITY_SERVICE) } returns null
    }

    private fun speechBlock(text: String, top: Float) = RawTextBlock(
        text = text, leftF = 0.2f, topF = top, rightF = 0.8f, bottomF = top + 0.08f,
    )

    private fun repository(
        groqClient: GroqTranslateClient,
        byokClient: ByokTranslateClient,
    ): TranslateRepository {
        val dao = mockk<com.haise.jiyu.data.db.TranslatedPageDao>(relaxed = true).also {
            coEvery { it.getById(any()) } returns null
        }
        val mangaDao = mockk<com.haise.jiyu.data.db.MangaDao>(relaxed = true).also {
            coEvery { it.getById(any()) } returns null
        }
        val glossaryRepository = mockk<GlossaryRepository>().also {
            coEvery { it.getMap(any(), any()) } returns emptyMap()
            coEvery { it.getProtectedEntries(any(), any()) } returns emptyList()
        }
        val ocrEngine = mockk<OcrEngine>().also {
            coEvery { it.recognize(any(), any(), any(), any()) } returns listOf(
                speechBlock("FIRST SENTENCE HERE.", 0.10f),
                speechBlock("SECOND SENTENCE HERE.", 0.40f),
            )
        }
        return TranslateRepository(
            context = context,
            ocrEngine = ocrEngine,
            pageBitmapLoader = mockk<PageBitmapLoader>().also {
                coEvery { it.load(any()) } returns mockk<Bitmap>()
            },
            groqClient = groqClient,
            // Relaxed Gemini klient vrátí null z translateBubbles - řetězec padá
            // na holé Groq cesty, přesně jak potřebujeme otestovat.
            geminiClient = mockk(relaxed = true),
            glossaryRepository = glossaryRepository,
            providerHealth = mockk(relaxed = true), // allUnavailable() -> false
            mangaDao = mangaDao,
            dao = dao,
            novelDao = mockk(relaxed = true),
            manualDao = mockk(relaxed = true),
            byokClient = byokClient,
        )
    }

    @Test
    fun `TR-7 - kratsi groq odpoved se zahodi a retezec pada na dalsiho providera`() = runTest {
        val groqClient = mockk<GroqTranslateClient>().also { client ->
            every { client.isConfigured } returns true
            coEvery {
                client.translateBatch(any(), any(), any(), any(), any(), any(), any(), any())
            } coAnswers {
                // KRÁTČÍ odpověď než vstup - model vynechal položku. Poziční mapování
                // by překlad 2. bubliny chybně přiřadilo nikam a 1. dostala "Položka 0"
                // - ale horší varianta (delší mezera uprostřed) by vše za ní posunula.
                arg<(String?) -> Unit>(7)("openai/gpt-oss-120b")
                listOf("Jediná odpověď.")
            }
        }
        val byokClient = mockk<ByokTranslateClient>().also {
            coEvery { it.isConfigured() } returns true
            coEvery { it.translateBatch(any(), any(), any(), any()) } returns
                listOf("První věta tady.", "Druhá věta tady.")
            // readBubbleText volá jen vision fallback - nepoužije se (budget až v recognize).
            coEvery { it.readBubbleText(any()) } returns null
        }

        val result = repository(groqClient, byokClient)
            .translatePage("page-url", "ch1", "m1", pageIndex = 0)

        // Krátká Groq odpověď se nesmí namapovat na bubliny - překlady přišly z BYOK.
        assertEquals(2, result.size)
        assertEquals("První věta tady.", result[0].translatedText)
        assertEquals("Druhá věta tady.", result[1].translatedText)
        assertTrue(result.none { it.isUntranslated })
    }

    @Test
    fun `TR-8 - SFX se do gemini promptu neposilaji a id se mapuji na puvodni indexy`() = runTest {
        val capturedBody = AtomicReference<String>()
        // Model odpovídá na FILTROVANÝ seznam - 2 položky s id 0/1, musí se
        // přemapovat na původní indexy 1/2 (index 0 je SFX).
        val modelJson = """{"bubbles":[
            {"id":0,"original":"HELLO THERE","translated":"Nazdar","bubble_size_tag":"MEDIUM","is_sfx":false,"syllable_breaks":"Nazdar"},
            {"id":1,"original":"GOODBYE NOW","translated":"Na shledanou","bubble_size_tag":"MEDIUM","is_sfx":false,"syllable_breaks":"Na shledanou"}
        ]}"""
        val proxyJson = JSONObject().put("text", modelJson).toString()
        val fakeHttp = OkHttpClient.Builder().addInterceptor { chain ->
            chain.request().body?.let { body ->
                val buf = Buffer()
                body.writeTo(buf)
                capturedBody.set(buf.readUtf8())
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(proxyJson.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val providerHealth = mockk<ProviderHealth>(relaxed = true).also {
            every { it.isAvailable(any()) } returns true
        }
        val client = GeminiTranslateClient(fakeHttp, providerHealth)

        val bubbles = listOf(
            ClassifiedBubble(
                raw = speechBlock("GLUG GLUG", 0.10f),
                sizeTag = SizeTag.SFX, bubbleType = BubbleType.SFX, isSfx = true, lineCount = 1,
            ),
            ClassifiedBubble(
                raw = speechBlock("HELLO THERE", 0.40f),
                sizeTag = SizeTag.MEDIUM, bubbleType = BubbleType.SPEECH, isSfx = false, lineCount = 1,
            ),
            ClassifiedBubble(
                raw = speechBlock("GOODBYE NOW", 0.70f),
                sizeTag = SizeTag.MEDIUM, bubbleType = BubbleType.SPEECH, isSfx = false, lineCount = 1,
            ),
        )
        val response = client.translateBubbles(bubbles, emptyMap())

        // SFX text nesmí být v promptu vůbec - dřív se posílal a spotřebovával tokeny.
        val userPrompt = JSONObject(capturedBody.get()).getString("user")
        assertFalse("SFX text se nesmí dostat do promptu", userPrompt.contains("GLUG"))
        assertTrue(userPrompt.contains("HELLO THERE"))
        assertTrue(userPrompt.contains("GOODBYE NOW"))

        // Ids z odpovědi (indexy do filtrovaného seznamu) se přemapují na původní.
        assertNotNull(response)
        assertEquals(listOf(1, 2), response!!.bubbles.map { it.id })
    }

    @Test
    fun `TR-13 - jednopismenny untranslated blok nedrzi stranku retryable`() = runTest {
        val repo = repository(mockk(relaxed = true), mockk(relaxed = true))
        val oneLetter = TranslatedBlock(
            originalText = "I", translatedText = "I",
            leftF = 0f, topF = 0f, rightF = 0.1f, bottomF = 0.05f,
            isUntranslated = true,
        )
        val word = TranslatedBlock(
            originalText = "OK", translatedText = "OK",
            leftF = 0f, topF = 0.5f, rightF = 0.1f, bottomF = 0.55f,
            isUntranslated = true,
        )
        // Jednopísmenný blok repair cesta neopraví (fillUntranslatedBlocks >=2 písmen)
        // - nesmí držet stránku za retryable. Dvoupísmenný ano.
        assertFalse(repo.hasRetryableUntrans(listOf(oneLetter)))
        assertTrue(repo.hasRetryableUntrans(listOf(word)))
    }

    @Test
    fun `TR-14 - drivejsi untranslated vyskyt frazy se doplni z phrase cache`() = runTest {
        val repo = repository(mockk(relaxed = true), mockk(relaxed = true))
        val registry = ChapterNameRegistry(emptySet(), emptySet())
        fun cb(text: String) = ClassifiedBubble(
            raw = speechBlock(text, 0.1f),
            sizeTag = SizeTag.MEDIUM, bubbleType = BubbleType.SPEECH, isSfx = false, lineCount = 1,
        )
        fun tb(text: String, translated: String, untranslated: Boolean) = TranslatedBlock(
            originalText = text, translatedText = translated, displayText = translated,
            leftF = 0f, topF = 0f, rightF = 0.5f, bottomF = 0.05f,
            isUntranslated = untranslated,
        )
        // První výskyt fráze zůstal untranslated, druhý se přeložil - dřív první
        // zůstal anglicky navěky (jednosměrný phrase cache zápis).
        val blocks = listOf(
            tb("THE SAME PHRASE", "THE SAME PHRASE", untranslated = true),
            tb("THE SAME PHRASE", "TA SAMÁ FRÁZE", untranslated = false),
        )
        val result = repo.applyChapterConsistency(
            blocks, listOf(cb("THE SAME PHRASE"), cb("THE SAME PHRASE")), registry,
        )
        assertEquals("TA SAMÁ FRÁZE", result[0].translatedText)
        assertFalse(result[0].isUntranslated)
        assertEquals("TA SAMÁ FRÁZE", result[1].translatedText)
    }
}
