package com.haise.jiyu.translate

import android.graphics.Bitmap
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regresní testy pro audit TR-6: překladové vstupní body ([TranslateRepository.translatePage],
 * [TranslateRepository.translateChapter], [TranslateRepository.translateNovelChapter]) se
 * serializují per-chapter mutexem - souběžné běhy nad STEJNOU kapitolou se nesmí prolnout
 * (sdílí translated_page řádky a seam-dedup přepisuje sousední stránky), ale různé kapitoly
 * musí běžet paralelně dál (jinak by preload například zbytečně čekal na aktuální kapitolu).
 *
 * Pozorovatelnost: instrumentovaný [PageBitmapLoader.load] / [GroqTranslateClient.translateNovelBatch]
 * zapisují start/end události do sdíleného logu - serializace = události dvou běhů stejné
 * kapitoly se neprolnou; paralelismus = události cizí kapitoly se do okna prvního běhu vejdou.
 *
 * Konstrukce [TranslateRepository] bez Hiltu - stejný vzor jako [OcrResilienceTest].
 */
class TranslateChapterSerializationTest {

    private val rawBlock = RawTextBlock(
        text = "Hello there, friend.",
        leftF = 0f, topF = 0f, rightF = 0.2f, bottomF = 0.1f,
    )

    private val context = mockk<android.content.Context>(relaxed = true).also {
        every { it.getExternalFilesDir(any()) } returns null
        every { it.getSystemService(android.app.ActivityManager::class.java) } returns null
        every { it.getSystemService(android.content.Context.ACTIVITY_SERVICE) } returns null
    }

    private fun repository(
        ocrEngine: OcrEngine,
        pageBitmapLoader: PageBitmapLoader,
        groqClient: GroqTranslateClient,
    ): TranslateRepository {
        val dao = mockk<com.haise.jiyu.data.db.TranslatedPageDao>(relaxed = true).also {
            coEvery { it.getById(any()) } returns null
        }
        val novelDao = mockk<com.haise.jiyu.data.db.TranslatedNovelDao>(relaxed = true).also {
            coEvery { it.getById(any()) } returns null
        }
        val mangaDao = mockk<com.haise.jiyu.data.db.MangaDao>(relaxed = true).also {
            coEvery { it.getById(any()) } returns null
        }
        val glossaryRepository = mockk<GlossaryRepository>().also {
            coEvery { it.getMap(any(), any()) } returns emptyMap()
            coEvery { it.getProtectedEntries(any(), any()) } returns emptyList()
        }
        return TranslateRepository(
            context = context,
            ocrEngine = ocrEngine,
            pageBitmapLoader = pageBitmapLoader,
            groqClient = groqClient,
            geminiClient = mockk(relaxed = true),
            glossaryRepository = glossaryRepository,
            providerHealth = mockk(relaxed = true),
            mangaDao = mangaDao,
            dao = dao,
            novelDao = novelDao,
            manualDao = mockk(relaxed = true),
            byokClient = mockk(relaxed = true),
        )
    }

    /** OCR vrátí vždy jednu bublinu; translateBatch odpoví krátkým textem (v JVM netlačí na ML Kit). */
    private fun groqClient(): GroqTranslateClient = mockk<GroqTranslateClient>().also { client ->
        every { client.isConfigured } returns true
        coEvery {
            client.translateBatch(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            arg<(String?) -> Unit>(7)("openai/gpt-oss-120b")
            listOf("Krátká odpověď.")
        }
    }

    private fun ocrEngine() = mockk<OcrEngine>().also {
        coEvery { it.recognize(any(), any(), any(), any()) } returns listOf(rawBlock)
    }

    /** Bitmap loader zapisující "load-start:<url>"/"load-end:<url>" do [events]. */
    private fun loader(events: ConcurrentLinkedQueue<String>): PageBitmapLoader =
        mockk<PageBitmapLoader>().also {
            coEvery { it.load(any()) } coAnswers {
                val url = arg<String>(0)
                events += "load-start:$url"
                delay(50)
                events += "load-end:$url"
                mockk<Bitmap>()
            }
        }

    /** Indexy událostí daného tagu v logu (tag = prefix URL, např. "a"). */
    private fun indicesOf(events: List<String>, tag: String): List<Int> =
        events.mapIndexedNotNull { i, e -> if (e.substringAfter(':').startsWith(tag)) i else null }

    /** Události dvou běhů nad stejnou kapitolou se nesmí prolnout. */
    private fun assertSerialized(events: List<String>, tagA: String, tagB: String) {
        val a = indicesOf(events, tagA)
        val b = indicesOf(events, tagB)
        assertTrue("žádné události pro $tagA v $events", a.isNotEmpty())
        assertTrue("žádné události pro $tagB v $events", b.isNotEmpty())
        assertTrue(
            "běhy $tagA/$tagB se prolnuly (mutex neserializoval): $events",
            a.last() < b.first() || b.last() < a.first(),
        )
    }

    @Test
    fun `TR-6 - dva translateChapter nad stejnou kapitolou se serializuji, jina kapitola bezi paralelne`() = runTest {
        val events = ConcurrentLinkedQueue<String>()
        val repo = repository(ocrEngine(), loader(events), groqClient())
        repo.ocrDispatcher = StandardTestDispatcher(testScheduler)

        val a = async { repo.translateChapter(listOf("a0", "a1"), "ch1", "m1") { _, _ -> } }
        val b = async { repo.translateChapter(listOf("b0", "b1"), "ch1", "m1") { _, _ -> } }
        val c = async { repo.translateChapter(listOf("c0"), "ch2", "m1") { _, _ -> } }
        awaitAll(a, b, c)

        val log = events.toList()
        assertSerialized(log, "a", "b")
        // Kontrola, že serializace nezabila paralelismus mezi kapitolami: c0 musí
        // spadnout do okna běžící kapitoly ch1 (start c před koncem a-událostí;
        // jinak bychom testovali jen pořadí, ne souběh).
        assertTrue(
            "cizí kapitola ch2 se nevešla do běhu ch1 - paralelismus mrtvý: $log",
            indicesOf(log, "c").first() < indicesOf(log, "a").last(),
        )
    }

    @Test
    fun `TR-6 - retranslatePage (translatePage) pocka na bezici translateChapter stejne kapitoly`() = runTest {
        val events = ConcurrentLinkedQueue<String>()
        val repo = repository(ocrEngine(), loader(events), groqClient())
        repo.ocrDispatcher = StandardTestDispatcher(testScheduler)

        val a = async { repo.translateChapter(listOf("a0", "a1"), "ch1", "m1") { _, _ -> } }
        // forceRefresh = simuluje uživatelovo "Přeložit stránku znovu" během běžícího
        // auto-překladu kapitoly - před fixem se četl/upsertoval previousRaw souběžně.
        val b = async {
            repo.translatePage(
                pageUrl = "b0", chapterId = "ch1", mangaId = "m1",
                pageIndex = 5, forceRefresh = true,
            )
        }
        awaitAll(a, b)

        assertSerialized(events.toList(), "a", "b")
    }

    @Test
    fun `TR-6 - translateNovelChapter nad stejnou kapitolou je serializovany, jina bezi paralelne`() = runTest {
        // translateNovelBatch zapisuje start/end tagovany prvnim odstavcem dávky.
        val events = ConcurrentLinkedQueue<String>()
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val groq = groqClient().also { client ->
            coEvery {
                client.translateNovelBatch(any(), any(), any(), any(), any(), any(), any(), any())
            } coAnswers {
                val texts = arg<List<String>>(0)
                val tag = texts.first()
                val now = inFlight.incrementAndGet()
                maxInFlight.accumulateAndGet(now) { m, n -> maxOf(m, n) }
                events += "novel-start:$tag"
                delay(50)
                events += "novel-end:$tag"
                inFlight.decrementAndGet()
                // Odpověď musí mít stejný počet položek jako chunk (jinak isBadBatch).
                texts.map { "Překlad." }
            }
        }
        // Žádné bitmapy - novely jdou textovou cestou; loader/OCR mocky nejsou volané.
        val repo = repository(ocrEngine(), loader(events), groq)

        val a = async { repo.translateNovelChapter("ch1", "m1", "a-first\na-second", forceRefresh = true) }
        val b = async { repo.translateNovelChapter("ch1", "m1", "b-first\nb-second", forceRefresh = true) }
        val c = async { repo.translateNovelChapter("ch2", "m1", "c-first\nc-second", forceRefresh = true) }
        awaitAll(a, b, c)

        val log = events.toList()
        assertSerialized(log, "a", "b")
        // c-běh (jiná kapitola) se prolne s a/b -> max souběžných dávek je 2.
        assertEquals("očekávaný paralelismus cizí kapitoly chybí: $log", 2, maxInFlight.get())
    }
}
