package com.haise.jiyu.data.repository

import com.haise.jiyu.source.comick.ResolvedCandidate
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lehká perzistovaná podoba [ResolvedCandidate] pro `source_resolution.candidatesJson` -
 * stačí na znovuotevření zdroje přes `openPreview(SManga)` a na zobrazení v přepínači
 * zdrojů (rozsah/počet kapitol). MangaSource instance se perzistovat nedá, rekonstruuje
 * se přes SourceManager podle [sourceId].
 */
data class CachedSourceCandidate(
    val sourceId: String,
    val mangaUrl: String,
    val mangaTitle: String,
    val coverUrl: String?,
    val minChapter: Float?,
    val maxChapter: Float?,
    val chapterCount: Int,
)

object SourceResolutionCodec {

    /**
     * Sentinel pro "kandidatsky sweep probehl a nic nenasel". Cisty "[]" je
     * dvojznacny - starsi verze ho psaly i pro resoluce, kde sweep kandidatu
     * vubec nedobehl (fast-path), takze by se s nim nedalo poznat, jestli se ma
     * pri otevreni sheetu jeste doskenovat. decode("none") -> emptyList stejne.
     */
    const val EMPTY_RESULT = "none"

    fun encode(candidates: List<CachedSourceCandidate>): String =
        JSONArray(candidates.map { c ->
            JSONObject().apply {
                put("sourceId", c.sourceId)
                put("mangaUrl", c.mangaUrl)
                put("mangaTitle", c.mangaTitle)
                put("coverUrl", c.coverUrl)
                put("minChapter", c.minChapter?.toDouble())
                put("maxChapter", c.maxChapter?.toDouble())
                put("chapterCount", c.chapterCount)
            }
        }).toString()

    fun decode(json: String): List<CachedSourceCandidate> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            CachedSourceCandidate(
                sourceId = o.optString("sourceId"),
                mangaUrl = o.optString("mangaUrl"),
                mangaTitle = o.optString("mangaTitle"),
                coverUrl = o.optString("coverUrl").takeIf { it.isNotEmpty() && it != "null" },
                minChapter = if (o.isNull("minChapter")) null else o.optDouble("minChapter").toFloat(),
                maxChapter = if (o.isNull("maxChapter")) null else o.optDouble("maxChapter").toFloat(),
                chapterCount = o.optInt("chapterCount"),
            )
        }
    }.getOrDefault(emptyList())

    fun ResolvedCandidate.toCached() = CachedSourceCandidate(
        sourceId = source.id,
        mangaUrl = manga.url,
        mangaTitle = manga.title,
        coverUrl = manga.coverUrl,
        minChapter = minChapterNumber,
        maxChapter = maxChapterNumber,
        chapterCount = matchedChapterCount,
    )
}
