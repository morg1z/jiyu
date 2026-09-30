package com.haise.jiyu.translate

import android.graphics.Bitmap
import com.haise.jiyu.util.DeviceResourcePolicy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regresní testy audit fáze 5 (OCR paměť/odolnost):
 *
 * - OCR-2: `OutOfMemoryError` z recognize() je Error, ne Exception - bez explicitní větve
 *   by proletěl catch (e: Exception) a shodil viewModelScope / celou sourozeneckou dávku.
 * - OCR-3: částečné výsledky přežijí stránkový timeout - recognize() hlásí už dokončené
 *   bubliny přes onPartial sink, takže 40 s odvedené práce nepropadne jako "bez textu".
 * - OCR-1: bitmap load se děje POD OCR permitem - bez toho se u "Přeložit vše" nakupí
 *   až pageCount plnohodnotných bitmape najednou (webtoon = desítky MB každá).
 * - OCR-6: čekání ve frontě na OCR permit má strop 90 s - trvale zaseklý nativní úkol
 *   držící permit nesmí zmrazit celou kapitolu na 0/N.
 *
 * Konstrukce [TranslateRepository] bez Hiltu - stejný vzor jako [DegradedModelCacheTest];
 * `ocrDispatcher` seam přepíná per-stránkové korutiny na virtuální test scheduler, aby
 * timeouty neběžely reálným časem.
 */
class OcrResilienceTest {

    private val rawBlock = RawTextBlock(
        text = "Hello there, friend.",
        leftF = 0f, topF = 0f, rightF = 0.2f, bottomF = 0.1f,
    )

    private val context = mockk<android.content.Context>(relaxed = true).also {
        // Diagnostika zapisuje do getExternalFilesDir - null = zápis se přeskočí.
        every { it.getExternalFilesDir(any()) } returns null
        // Relaxed child-mock z getSystemService se neda castnout na ActivityManager
        // (ClassCastException v DeviceResourcePolicy) - null = fallback na výchozí
        // 512MB odhad, stejně jako na zařízení bez ActivityManagera. ContextCompat
        // může volat String i Class overload, stubuju oba.
        every { it.getSystemService(android.app.ActivityManager::class.java) } returns null
        every { it.getSystemService(android.content.Context.ACTIVITY_SERVICE) } returns null
    }

    private fun repository(
        ocrEngine: OcrEngine,
        pageBitmapLoader: PageBitmapLoader,
    ): TranslateRepository {
        val dao = mockk<com.haise.jiyu.data.db.TranslatedPageDao>(relaxed = true).also {
            // Relaxed DAO by vrátilo MOCK entity, ne null - getCachedPage by pak našlo
            // "prázdnou" stránku a pipeline by skončila před jakýmkoli OCR.
            coEvery { it.getById(any()) } returns null
        }
        val mangaDao = mockk<com.haise.jiyu.data.db.MangaDao>(relaxed = true).also {
            coEvery { it.getById(any()) } returns null
        }
        val glossaryRepository = mockk<GlossaryRepository>().also {
            coEvery { it.getMap(any(), any()) } returns emptyMap()
            coEvery { it.getProtectedEntries(any(), any()) } returns emptyList()
        }
        val groqClient = mockk<GroqTranslateClient>().also { client ->
            every { client.isConfigured } returns true
            coEvery {
                client.translateBatch(any(), any(), any(), any(), any(), any(), any(), any())
            } coAnswers {
                // Krátká odpověď pod 40 znaky -> isWrongTargetLanguage skončí předčasně
                // a nikdy nespustí ML Kit LanguageIdentification (v JVM testu neexistuje).
                arg<(String?) -> Unit>(7)("openai/gpt-oss-120b")
                // TR-7: poziční řetězec odmítá odpověď s jiným počtem položek než
                // vstup - mock musí vrátit tolik překladů, kolik se poslalo.
                List(arg<List<String>>(0).size) { "Krátká odpověď." }
            }
        }
        return TranslateRepository(
            context = context,
            ocrEngine = ocrEngine,
            pageBitmapLoader = pageBitmapLoader,
            groqClient = groqClient,
            geminiClient = mockk(relaxed = true),
            glossaryRepository = glossaryRepository,
            providerHealth = mockk(relaxed = true), // allUnavailable() -> false
            mangaDao = mangaDao,
            dao = dao,
            novelDao = mockk(relaxed = true),
            manualDao = mockk(relaxed = true),
            byokClient = mockk(relaxed = true), // isConfigured() -> false -> žádný vision rozpočet
        )
    }

    private fun loader(): PageBitmapLoader = mockk<PageBitmapLoader>().also {
        coEvery { it.load(any()) } returns mockk<Bitmap>()
    }

    @Test
    fun `OCR-2 - OutOfMemoryError z recognize vrati prazdnou stranku misto crashu`() = runTest {
        val ocrEngine = mockk<OcrEngine>().also {
            coEvery { it.recognize(any(), any(), any(), any()) } throws OutOfMemoryError("test OOM")
        }
        // Bez catch (e: OutOfMemoryError) by Error proletěl přes catch (e: Exception)
        // až do korutiny volajícího - tady se má chovat jako tiše selhavší stránka.
        val result = repository(ocrEngine, loader())
            .translatePage("page-url", "ch1", "m1", pageIndex = 0)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `OCR-2 - OutOfMemoryError v translateChapter nezabije sourozeneckou stranku`() = runTest {
        val oomThrown = AtomicBoolean(false)
        val ocrEngine = mockk<OcrEngine>().also {
            coEvery { it.recognize(any(), any(), any(), any()) } coAnswers {
                // První stránka hodí OOM; ostatní stránky musí doběhnout - dřív by Error
                // přes coroutineScope zabil celou sourozeneckou dávku.
                if (oomThrown.getAndSet(true)) listOf(rawBlock) else throw OutOfMemoryError("test OOM")
            }
        }
        val pages = List(4) { "page-$it" }
        val ready = mutableMapOf<Int, List<TranslatedBlock>>()
        val repo = repository(ocrEngine, loader())
        repo.ocrDispatcher = StandardTestDispatcher(testScheduler)
        repo.translateChapter(pages, "ch1", "m1", onPageReady = { i, b -> ready[i] = b })

        assertEquals(4, ready.size)
    }

    @Test
    fun `OCR-3 - castecny vysledek prezije strankovy timeout`() = runTest {
        val ocrEngine = mockk<OcrEngine>().also {
            coEvery { it.recognize(any(), any(), any(), any()) } coAnswers {
                // Nejprv hlásí dokončenou bublinu, pak "nativní práce" visí donekonečna -
                // stránkový timeout (virtuálně 40 s) ji zruší, ale blok musí zůstat.
                arg<(List<RawTextBlock>) -> Unit>(3).invoke(listOf(rawBlock))
                awaitCancellation()
            }
        }
        val result = repository(ocrEngine, loader())
            .translatePage("page-url", "ch1", "m1", pageIndex = 0)

        // Před fixem timeout zahazoval VŠE - vracela by se prázdná stránka, i když
        // recognize() už měl hotový text.
        assertEquals(1, result.size)
        assertEquals("Krátká odpověď.", result.single().translatedText)
    }

    @Test
    fun `OCR-1 - bitmapy se nenakupi pred OCR permitem`() = runTest {
        val ocrConcurrency = DeviceResourcePolicy.recommendedOcrConcurrency(context)
        val gate = CompletableDeferred<Unit>()
        val loadCalls = AtomicInteger(0)
        val loader = mockk<PageBitmapLoader>().also {
            coEvery { it.load(any()) } coAnswers {
                loadCalls.incrementAndGet()
                mockk<Bitmap>()
            }
        }
        val ocrEngine = mockk<OcrEngine>().also {
            coEvery { it.recognize(any(), any(), any(), any()) } coAnswers {
                gate.await() // visí, dokud test neskočí - simuluje pomalé OCR
                listOf(rawBlock)
            }
        }
        val pages = List(10) { "page-$it" }
        val ready = mutableMapOf<Int, List<TranslatedBlock>>()
        val repo = repository(ocrEngine, loader)
        repo.ocrDispatcher = StandardTestDispatcher(testScheduler)
        val job = async {
            repo.translateChapter(pages, "ch1", "m1", onPageReady = { i, b -> ready[i] = b })
        }
        runCurrent()

        // Všechny stránky čekají na OCR permit - načtených bitmape smí být nejvíc
        // tolik, kolik je permitů (permit se drží UŽ před loadem). Před fixem by
        // bitmapLoadSemaphore pustil ~8 loadů najednou a všechny držely bitmapu
        // ve frontě na OCR.
        assertTrue(
            "načteno ${loadCalls.get()} bitmape při ocrConcurrency=$ocrConcurrency",
            loadCalls.get() <= ocrConcurrency,
        )

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(10, ready.size)
    }

    @Test
    fun `OCR-6 - zasekly permit nezablokuje celou kapitolu`() = runTest {
        // recognize() se suspenduje bez resume - jako zaseklý nativní ML Kit task.
        // Stránkový timeout 40 s ho zruší, permit se uvolní, fronta jede dál; stránky,
        // které by čekaly déle než 90 s, skončí jako "permit_timeout" - ale kapitola
        // se MUSÍ dokončit, ne viset na 0/N.
        val ocrEngine = mockk<OcrEngine>().also {
            coEvery { it.recognize(any(), any(), any(), any()) } coAnswers {
                awaitCancellation()
            }
        }
        // 20 stránek > 3 vlny za jakékoli realistické ocrConcurrency (max 6) - poslední
        // vlna čeká na permit déle než 90 s a dostane "permit_timeout" místo visení.
        val pages = List(20) { "page-$it" }
        val ready = mutableMapOf<Int, List<TranslatedBlock>>()
        val repo = repository(ocrEngine, loader())
        repo.ocrDispatcher = StandardTestDispatcher(testScheduler)
        val job = async {
            repo.translateChapter(pages, "ch1", "m1", onPageReady = { i, b -> ready[i] = b })
        }
        advanceUntilIdle()

        assertTrue("translateChapter se nesmí zaseknout", job.isCompleted)
        assertEquals(20, ready.size)
    }
}
