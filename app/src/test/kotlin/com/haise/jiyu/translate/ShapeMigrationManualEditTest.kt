package com.haise.jiyu.translate

import android.graphics.Bitmap
import com.haise.jiyu.data.db.entity.ManualTranslationEntity
import com.haise.jiyu.data.db.entity.TranslatedPageEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray

/**
 * Regresní test TR-3: shape-migrace v [TranslateRepository.getCachedPage] běžela
 * nad bloky s už napařenými manuálními editacemi a výsledek se upsertoval zpátky
 * do strojové cache - uživatelova oprava se trvale vpálila do strojového překladu
 * a po smazání editu se strojový text nevrátil.
 *
 * Migrace musí číst i psát RAW bloky; manuální edity se napařují až na hodnotu,
 * která se vrací čtečce.
 */
class ShapeMigrationManualEditTest {

    private val context = mockk<android.content.Context>(relaxed = true).also {
        every { it.getExternalFilesDir(any()) } returns null
        every { it.getSystemService(android.app.ActivityManager::class.java) } returns null
        every { it.getSystemService(android.content.Context.ACTIVITY_SERVICE) } returns null
    }

    private val machineBlock = TranslatedBlock(
        originalText = "HELLO THERE",
        translatedText = "STROJOVÝ PŘEKLAD",
        leftF = 0.1f, topF = 0.1f, rightF = 0.5f, bottomF = 0.2f,
        // shape=null -> starý cache formát -> getCachedPage spustí migraci tvarů.
        shape = null,
    )

    private val manualEdit = ManualTranslationEntity(
        id = "ch1::0::hello there",
        chapterId = "ch1",
        pageIndex = 0,
        originalText = "HELLO THERE",
        text = "RUČNÍ OPRAVA",
        updatedAt = 1L,
    )

    private fun repository(
        dao: com.haise.jiyu.data.db.TranslatedPageDao,
        ocrEngine: OcrEngine,
    ): TranslateRepository {
        val mangaDao = mockk<com.haise.jiyu.data.db.MangaDao>(relaxed = true).also {
            coEvery { it.getById(any()) } returns null
        }
        val manualDao = mockk<com.haise.jiyu.data.db.ManualTranslationDao>(relaxed = true).also {
            coEvery { it.forPage("ch1", 0) } returns listOf(manualEdit)
        }
        val glossaryRepository = mockk<GlossaryRepository>().also {
            coEvery { it.getMap(any(), any()) } returns emptyMap()
            coEvery { it.getProtectedEntries(any(), any()) } returns emptyList()
        }
        return TranslateRepository(
            context = context,
            ocrEngine = ocrEngine,
            pageBitmapLoader = mockk<PageBitmapLoader>().also {
                coEvery { it.load(any()) } returns mockk<Bitmap>()
            },
            groqClient = mockk(relaxed = true),
            geminiClient = mockk(relaxed = true),
            glossaryRepository = glossaryRepository,
            providerHealth = mockk(relaxed = true),
            mangaDao = mangaDao,
            dao = dao,
            novelDao = mockk(relaxed = true),
            manualDao = manualDao,
            byokClient = mockk(relaxed = true),
        )
    }

    @Test
    fun `TR-3 - shape migration persists raw machine text, manual edit stays on top`() = runTest {
        val cachedEntity = TranslatedPageEntity(
            id = pageCacheKey("ch1", 0, "Czech", "Auto"),
            blocksJson = listOf(machineBlock).toCacheJson(),
        )
        val upserted = slot<TranslatedPageEntity>()
        val dao = mockk<com.haise.jiyu.data.db.TranslatedPageDao>(relaxed = true).also {
            coEvery { it.getById(cachedEntity.id) } returns cachedEntity
            coEvery { it.upsert(capture(upserted)) } returns Unit
        }
        // Migrace tvarů: vrátí bloky s dopočítaným shape - obsah textu zůstane,
        // co dostane na vstupu (u fixu RAW text, před fixem manuální edit).
        val ocrEngine = mockk<OcrEngine>().also {
            coEvery { it.detectShapesOnly(any(), any()) } coAnswers {
                arg<List<TranslatedBlock>>(1).map { b ->
                    b.copy(shape = listOf(BubbleShapePoint(0.5f, 0.1f, 0.5f)))
                }
            }
        }

        val result = repository(dao, ocrEngine)
            .getCachedPage("ch1", 0, "Czech", "Auto", pageUrl = "page-url")

        // Cache záznam musí obsahovat STROJOVÝ překlad, ne ruční opravu.
        assertTrue("migrace měla cache upsertnout", upserted.isCaptured)
        val persisted = JSONArray(upserted.captured.blocksJson).getJSONObject(0)
        assertEquals("STROJOVÝ PŘEKLAD", persisted.getString("trans"))
        assertFalse(persisted.getString("trans").contains("RUČNÍ"))

        // Čtečka ale vidí ruční opravu - napařuje se až na výstup.
        assertEquals("RUČNÍ OPRAVA", result!!.single().displayText)
    }

    @Test
    fun `TR-3 - without pageUrl cached page returns manual edit without migration`() = runTest {
        val cachedEntity = TranslatedPageEntity(
            id = pageCacheKey("ch1", 0, "Czech", "Auto"),
            blocksJson = listOf(machineBlock).toCacheJson(),
        )
        val dao = mockk<com.haise.jiyu.data.db.TranslatedPageDao>(relaxed = true).also {
            coEvery { it.getById(cachedEntity.id) } returns cachedEntity
        }
        val ocrEngine = mockk<OcrEngine>(relaxed = true)

        val result = repository(dao, ocrEngine)
            .getCachedPage("ch1", 0, "Czech", "Auto", pageUrl = null)

        assertEquals("RUČNÍ OPRAVA", result!!.single().displayText)
        // Bez pageUrl se migrace nespouští - žádný zápis do cache.
        coVerify(exactly = 0) { dao.upsert(any()) }
        coVerify(exactly = 0) { ocrEngine.detectShapesOnly(any(), any()) }
    }
}
