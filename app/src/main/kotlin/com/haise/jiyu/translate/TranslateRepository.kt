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
     * Vrátí přeložené bloky pro stránku. Cache-first:
     * pokud jsou v Room, vrátí je okamžitě bez volání API.
     */
    suspend fun translatePage(
        pageUrl: String,
        chapterId: String,
        pageIndex: Int,
        targetLanguage: String = "Czech",
    ): List<TranslatedBlock> {
        val cacheId = "$chapterId::$pageIndex::$targetLanguage"
        dao.getById(cacheId)?.let { return it.deserialize() }

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

        dao.upsert(TranslatedPageEntity(id = cacheId, blocksJson = blocks.serialize()))
        return blocks
    }

    // ── JSON (de)serialization pomocí org.json, bez extra dep ───────────────

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
