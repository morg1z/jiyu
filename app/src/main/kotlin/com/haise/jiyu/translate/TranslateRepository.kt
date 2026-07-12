package com.haise.jiyu.translate

import com.haise.jiyu.data.db.GlossaryDao
import com.haise.jiyu.data.db.TranslatedNovelDao
import com.haise.jiyu.data.db.TranslatedPageDao
import com.haise.jiyu.data.db.entity.GlossaryEntity
import com.haise.jiyu.data.db.entity.TranslatedNovelEntity
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
    private val novelDao: TranslatedNovelDao,
    private val glossaryDao: GlossaryDao,
) {
    val isApiKeyConfigured: Boolean get() = groqClient.isConfigured

    private suspend fun glossaryFor(mangaId: String, targetLanguage: String): Map<String, String> =
        glossaryDao.getForMangaAndLanguage(mangaId, targetLanguage).associate { it.sourceTerm to it.targetTerm }

    /**
     * Vrátí přeložené bloky pro jednu stránku.
     * Cache-first: pokud jsou v Room, vrátí okamžitě.
     * @return bloky nebo emptyList() pokud OCR/API selže
     */
    suspend fun translatePage(
        pageUrl: String,
        chapterId: String,
        mangaId: String,
        pageIndex: Int,
        targetLanguage: String = "Czech",
        sourceLanguage: String = "Auto",
    ): List<TranslatedBlock> {
        getCachedPage(chapterId, pageIndex, targetLanguage, sourceLanguage)?.let { return it }

        val rawBlocks = ocrEngine.recognize(pageUrl, sourceLanguage)
        if (rawBlocks.isEmpty()) return emptyList()

        val translations = groqClient.translateBatch(
            texts = rawBlocks.map { it.text },
            targetLanguage = targetLanguage,
            sourceLanguage = sourceLanguage,
            glossary = glossaryFor(mangaId, targetLanguage),
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

        dao.upsert(TranslatedPageEntity(id = cacheId(chapterId, pageIndex, targetLanguage, sourceLanguage), blocksJson = blocks.serialize()))
        return blocks
    }

    /** Vrátí výsledek z Room cache bez volání API; null = není v cache */
    suspend fun getCachedPage(
        chapterId: String,
        pageIndex: Int,
        targetLanguage: String,
        sourceLanguage: String = "Auto",
    ): List<TranslatedBlock>? =
        dao.getById(cacheId(chapterId, pageIndex, targetLanguage, sourceLanguage))?.deserialize()

    private fun cacheId(chapterId: String, pageIndex: Int, targetLanguage: String, sourceLanguage: String) =
        "$chapterId::$pageIndex::$sourceLanguage::$targetLanguage"

    companion object {
        /** Maximální počet znaků originálu na jedno API volání - drží výstup pod limitem max_tokens. */
        private const val NOVEL_CHUNK_CHAR_LIMIT = 2500
    }

    // ── Light novel překlad (prostý text, ne obrázek) ────────────────────────

    /**
     * Přeloží celou kapitolu light novel (odstavce oddělené \n). Rozdělí dlouhý text
     * do více dávek, aby výstup nepřekročil limit tokenů jednoho API volání.
     * @return přeložený text (odstavce spojené \n) nebo null při selhání
     */
    suspend fun translateNovelChapter(
        chapterId: String,
        mangaId: String,
        text: String,
        targetLanguage: String = "Czech",
        sourceLanguage: String = "Auto",
    ): String? {
        getCachedNovel(chapterId, targetLanguage, sourceLanguage)?.let { return it }
        if (!groqClient.isConfigured) return null

        val paragraphs = text.split("\n").filter { it.isNotBlank() }
        if (paragraphs.isEmpty()) return null

        val glossary = glossaryFor(mangaId, targetLanguage)
        val chunks = chunkParagraphs(paragraphs)
        val translatedParagraphs = mutableListOf<String>()
        for (chunk in chunks) {
            val translated = groqClient.translateNovelBatch(chunk, targetLanguage, sourceLanguage, glossary)
            if (translated.size != chunk.size) return null // dávka selhala nebo neúplná -> necachovat polovičatý výsledek
            translatedParagraphs += translated
        }

        val result = translatedParagraphs.joinToString("\n")
        novelDao.upsert(TranslatedNovelEntity(id = novelCacheId(chapterId, sourceLanguage, targetLanguage), translatedText = result))
        return result
    }

    suspend fun getCachedNovel(chapterId: String, targetLanguage: String, sourceLanguage: String = "Auto"): String? =
        novelDao.getById(novelCacheId(chapterId, sourceLanguage, targetLanguage))?.translatedText

    private fun novelCacheId(chapterId: String, sourceLanguage: String, targetLanguage: String) =
        "$chapterId::$sourceLanguage::$targetLanguage"

    private fun chunkParagraphs(paragraphs: List<String>): List<List<String>> {
        val chunks = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var currentLen = 0
        for (p in paragraphs) {
            if (current.isNotEmpty() && currentLen + p.length > NOVEL_CHUNK_CHAR_LIMIT) {
                chunks += current
                current = mutableListOf()
                currentLen = 0
            }
            current += p
            currentLen += p.length
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }

    // ── JSON (de)serialization ───────────────────────────────────────────────

    private fun List<TranslatedBlock>.serialize(): String = JSONArray().also { arr ->
        forEach { b ->
            arr.put(JSONObject().apply {
                put("orig", b.originalText)
                put("trans", b.translatedText)
                // put(String, float) na Android org.json.JSONObject neexistuje (jen desktopová
                // verze knihovny) -> NoSuchMethodError za běhu. Double overload existuje vždy.
                put("l", b.leftF.toDouble())
                put("t", b.topF.toDouble())
                put("r", b.rightF.toDouble())
                put("b", b.bottomF.toDouble())
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
