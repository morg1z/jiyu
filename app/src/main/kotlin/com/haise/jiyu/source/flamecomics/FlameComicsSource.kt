package com.haise.jiyu.source.flamecomics

import com.haise.jiyu.source.bodyOrThrow

import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FlameComics prešel z Madara sablony na kompletne prepsanou Next.js appku
 * (zjisteno auditem 2026-07-27) - stare Madara URL (/manga/?...) uz vubec
 * neexistuji. Archiv (/browse) i detail (/series/{id}) i ctecka
 * (/series/{id}/{token}) jsou staticky generovane (__N_SSG__) stranky
 * s celym datovym modelem vlozenym primo do <script id="__NEXT_DATA__">
 * jako JSON - zdroj tedy vubec nepotrebuje HTML/CSS selektory, jen JSON
 * parsing. Obrazky jsou na cdn.flamecomics.xyz/uploads/images/series/{id}/{token}/{name}.
 */
@Singleton
class FlameComicsSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "flamecomics"
    override val name = "Flame Comics"
    override val homepageUrl get() = base
    private val base = "https://flamecomics.xyz"
    private val cdn = "https://cdn.flamecomics.xyz/uploads/images/series"
    private val pageSize = 30

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    /** Vytahne JSON z <script id="__NEXT_DATA__" type="application/json">...</script>. */
    private fun nextDataPageProps(html: String): JSONObject? {
        val json = Regex(
            """__NEXT_DATA__"\s+type="application/json">(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(html)?.groupValues?.get(1) ?: return null
        return JSONObject(json).optJSONObject("props")?.optJSONObject("pageProps")
    }

    private fun coverUrl(seriesId: Int, cover: String?) =
        if (cover.isNullOrBlank()) null else "$cdn/$seriesId/$cover"

    private fun seriesToManga(s: JSONObject): SManga? {
        val seriesId = s.optInt("series_id", -1).takeIf { it > 0 } ?: return null
        val title = s.optString("title").ifBlank { return null }
        return SManga(
            sourceId = id,
            url = "/series/$seriesId",
            title = title,
            coverUrl = coverUrl(seriesId, s.optString("cover").ifBlank { null }),
        )
    }

    private fun allSeries(): List<JSONObject> {
        val props = nextDataPageProps(get("$base/browse")) ?: return emptyList()
        val arr = props.optJSONArray("series") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            // Cely katalog uz je v jedne JSON odpovedi (allSeries) - razeni Popularni/
            // Nejnovejsi jde udelat klientsky bez dalsiho pozadavku pres "popularityRank"
            // (1 = nejpopularnejsi) a "last_edit" (unix cas posledni upravy kapitoly),
            // oboje pole overena zive v datech, davaji viditelne odlisne poradi.
            val all = allSeries()
            val sorted = if (filter.sortBy == "latest") {
                all.sortedByDescending { it.optLong("last_edit", 0L) }
            } else {
                all.sortedBy { s -> s.optInt("popularityRank", -1).let { if (it < 0) Int.MAX_VALUE else it } }
            }
            val from = (page - 1) * pageSize
            if (from >= sorted.size) emptyList() else sorted.subList(from, minOf(from + pageSize, sorted.size)).mapNotNull(::seriesToManga)
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = query.trim().lowercase()
            val matches = allSeries().filter { it.optString("title").lowercase().contains(q) }
            val from = (page - 1) * pageSize
            if (from >= matches.size) emptyList() else matches.subList(from, minOf(from + pageSize, matches.size)).mapNotNull(::seriesToManga)
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val seriesId = manga.url.substringAfterLast("/")
            val props = nextDataPageProps(get("$base${manga.url}")) ?: return@withContext manga
            val s = props.optJSONObject("series") ?: return@withContext manga
            val genres = s.optJSONArray("tags")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList()
            val authors = s.optJSONArray("author")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList()
            manga.copy(
                title = s.optString("title").ifBlank { manga.title },
                coverUrl = coverUrl(seriesId.toIntOrNull() ?: -1, s.optString("cover").ifBlank { null }) ?: manga.coverUrl,
                description = org.jsoup.Jsoup.parse(s.optString("description")).text().ifBlank { null },
                genres = genres,
                author = authors.joinToString(", ").ifBlank { null },
                status = s.optString("status").ifBlank { null },
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val seriesId = manga.url.substringAfterLast("/")
            val props = nextDataPageProps(get("$base${manga.url}")) ?: return@withContext emptyList()
            val chapters = props.optJSONArray("chapters") ?: return@withContext emptyList()
            (0 until chapters.length()).mapNotNull { i ->
                val c = chapters.optJSONObject(i) ?: return@mapNotNull null
                val token = c.optString("token").ifBlank { return@mapNotNull null }
                val num = c.optString("chapter").toFloatOrNull() ?: 0f
                val title = c.optString("title").ifBlank { null }
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = "/series/$seriesId/$token",
                    name = buildString { append("Ch.").append(c.optString("chapter")); if (title != null) append(" – ").append(title) },
                    chapterNumber = num,
                    dateUpload = c.optLong("release_date", 0L) * 1000L,
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val parts = chapter.url.split("/").filter { it.isNotBlank() }
            val seriesId = parts.getOrNull(1) ?: return@withContext emptyList()
            val token = parts.getOrNull(2) ?: return@withContext emptyList()
            val props = nextDataPageProps(get("$base${chapter.url}")) ?: return@withContext emptyList()
            val images = props.optJSONObject("chapter")?.optJSONObject("images") ?: return@withContext emptyList()
            images.keys().asSequence()
                .mapNotNull { key -> key.toIntOrNull()?.let { it to images.optJSONObject(key) } }
                .sortedBy { it.first }
                .mapIndexedNotNull { i, (_, img) ->
                    val name = img?.optString("name")?.ifBlank { null } ?: return@mapIndexedNotNull null
                    val url = "$cdn/$seriesId/$token/$name"
                    Page(i, url, url)
                }
                .toList()
        } catch (_: Exception) { emptyList() }
    }
}
