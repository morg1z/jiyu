package com.haise.jiyu.translate

import com.haise.jiyu.data.db.TranslatedPageDao
import com.haise.jiyu.data.db.entity.TranslatedPageEntity
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TranslateRepository @Inject constructor(
    private val ocrEngine: OcrEngine,
    private val groqClient: GroqTranslateClient,
    private val dao: TranslatedPageDao,
) {
    /**
     * Vrátí přeložené bloky pro jednu stránku.
     * Cache-first: pokud jsou v Room, vrátí okamžitě.
     * @return bloky nebo emptyList() pokud OCR/API selže
     */
    suspend fun translatePage(
        pageUrl: String,
        chapterId: String,
        pageIndex: Int,
        targetLanguage: String = "Czech",
    ): List<TranslatedBlock> {
        getCachedPage(chapterId, pageIndex, targetLanguage)?.let { return it }

        val rawBlocks = ocrEngine.recognize(pageUrl)
        if (rawBlocks.isEmpty()) return emptyList()

        val translations = groqClient.translateBatch(
            texts = rawBlocks.map { it.text },
            targetLanguage = targetLanguage,
        )
        if (translations.isEmpty()) return emptyList()

        val blocks = rawBlocks.mapIndexed { i, raw ->
            TranslatedBlock(
                originalText = raw.text,
                translatedText = translations.getOrElse(i) { raw.text },
                leftF = raw.leftF,
                topF = raw.topF,
                rightF = raw.rightF,
                bottomF = raw.bottomF,
            )
        }

        dao.upsert(TranslatedPageEntity(id = cacheId(chapterId, pageIndex, targetLanguage), blocksJson = blocks.serialize()))
        return blocks
    }

    /** Vrátí výsledek z Room cache bez volání API; null = není v cache */
    suspend fun getCachedPage(
        chapterId: String,
        pageIndex: Int,
        targetLanguage: String,
    ): List<TranslatedBlock>? =
        dao.getById(cacheId(chapterId, pageIndex, targetLanguage))?.deserialize()

    private fun cacheId(chapterId: String, pageIndex: Int, targetLanguage: String) =
        "$chapterId::$pageIndex::$targetLanguage"

    // ── JSON (de)serialization ───────────────────────────────────────────────

    private fun List<TranslatedBlock>.serialize(): String = JSONArray().also { arr ->
        forEach { b ->
            arr.put(JSONObject().apply {
                put("orig", b.originalText)
                put("trans", b.translatedText)
                put("l", b.leftF)
                put("t", b.topF)
                put("r", b.rightF)
                put("b", b.bottomF)
            })
        }
    }.toString()

    private fun TranslatedPageEntity.deserialize(): List<TranslatedBlock> = try {
        val arr = JSONArray(blocksJson)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            TranslatedBlock(
                originalText = o.getString("orig"),
                translatedText = o.getString("trans"),
                leftF = o.getDouble("l").toFloat(),
                topF = o.getDouble("t").toFloat(),
                rightF = o.getDouble("r").toFloat(),
                bottomF = o.getDouble("b").toFloat(),
            )
        }
    } catch (e: Exception) { emptyList() }
}
