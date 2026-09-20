package com.haise.jiyu.source.yaoimangaonline

import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.rethrowIfControl
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.bodyOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * yaoimangaonline.com - WordPress web na šabloně "Herald" (NENÍ Madara). Každý
 * "titul" je jeden WP post: úvodní strana postu (page 1, WP `<!--nextpage-->`
 * dělení) nese popis/autora, další stránky postu = jednotlivé kapitoly, jejich
 * odkazy jsou v `nav.mpp-toc a` na detailu. Frontendové HTML hledání (`/?s=`)
 * je za Cloudflare výzvou (403), ALE WP REST API (`/wp-json/wp/v2/posts`) je
 * volně dostupné a blokované není - přes něj jde search, listing i detail v
 * jednom čistém JSON volání (`content.rendered` obsahuje celý článek se všemi
 * `<!--nextpage-->` značkami, takže z jednoho requestu jde vytáhnout jak popis,
 * tak obrázky všech kapitol). Zdroj proto vůbec neparsuje HTML, jen JSON.
 *
 * Do obsahu kapitol se občas vloží reklamní/doporučovací obrázek z jiné složky
 * uploadů (název bez vzoru "NN-XX.pripona") - filtrujeme podle jména souboru.
 */
@Singleton
class YaoiMangaOnlineSource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "yaoimangaonline"
    override val name = "Yaoi Manga Online"
    override val supportsSortOrder: Boolean get() = false
    override val isAdult = true
    override val homepageUrl get() = base
    private val base = "https://yaoimangaonline.com"
    private val apiBase = "$base/wp-json/wp/v2/posts"

    /** Skutečné stránky mají v souboru vzor "01-90.webp"/"12-345.jpg" - reklamy/doporučení ne. */
    private val pageFilenameRegex = Regex("""^\d{1,3}-\d+\.\w+$""", RegexOption.IGNORE_CASE)

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP_124)
            .header("Referer", "$base/")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun decode(html: String) = Parser.unescapeEntities(html, false)

    private fun slugOf(mangaUrl: String) = mangaUrl.trim('/').substringAfterLast('/')

    private fun termsByTaxonomy(post: JSONObject, taxonomy: String): List<String> {
        val embedded = post.optJSONObject("_embedded") ?: return emptyList()
        val groups = embedded.optJSONArray("wp:term") ?: return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until groups.length()) {
            val group = groups.optJSONArray(i) ?: continue
            for (j in 0 until group.length()) {
                val term = group.optJSONObject(j) ?: continue
                if (term.optString("taxonomy") == taxonomy) {
                    term.optString("name").takeIf { it.isNotBlank() }?.let(result::add)
                }
            }
        }
        return result
    }

    private fun coverOf(post: JSONObject): String? {
        val media = post.optJSONObject("_embedded")?.optJSONArray("wp:featuredmedia") ?: return null
        return media.optJSONObject(0)?.optString("source_url")?.takeIf { it.isNotBlank() }
    }

    private fun postToSManga(post: JSONObject): SManga {
        val slug = post.optString("slug")
        val title = decode(post.optJSONObject("title")?.optString("rendered").orEmpty()).ifBlank { slug }
        val categories = termsByTaxonomy(post, "category")
        val contentType = if (categories.any { it.contains("webtoon", ignoreCase = true) }) "MANHWA" else "MANGA"
        return SManga(
            sourceId = id,
            url = "/$slug/",
            title = title,
            coverUrl = coverOf(post),
            contentType = contentType,
        )
    }

    private fun fetchPostList(url: String): List<SManga> {
        val arr = JSONArray(get(url))
        return (0 until arr.length()).map { postToSManga(arr.getJSONObject(it)) }.distinctBy { it.url }
    }

    private fun fetchPostBySlug(slug: String): JSONObject? {
        val url = "$apiBase?slug=${URLEncoder.encode(slug, "UTF-8")}&_embed=wp:featuredmedia,wp:term"
        val arr = JSONArray(get(url))
        return if (arr.length() > 0) arr.getJSONObject(0) else null
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            fetchPostList("$apiBase?per_page=20&page=$page&_embed=wp:featuredmedia,wp:term")
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            fetchPostList("$apiBase?search=$q&per_page=20&page=$page&_embed=wp:featuredmedia,wp:term")
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val post = fetchPostBySlug(slugOf(manga.url)) ?: return@withContext manga
            val content = post.optJSONObject("content")?.optString("rendered").orEmpty()
            val intro = content.substringBefore("<!--nextpage-->")
            val description = Jsoup.parse(intro).text().trim().takeIf { it.isNotBlank() }
            val categories = termsByTaxonomy(post, "category")
            val status = categories.firstOrNull { it.contains("ongoing", true) || it.contains("completed", true) || it.contains("hiatus", true) || it.contains("dropped", true) }
            val author = termsByTaxonomy(post, "mangaka").takeIf { it.isNotEmpty() }?.joinToString(", ")
            val genres = termsByTaxonomy(post, "post_tag").take(20)
            val contentType = if (categories.any { it.contains("webtoon", ignoreCase = true) }) "MANHWA" else "MANGA"
            manga.copy(
                title = decode(post.optJSONObject("title")?.optString("rendered").orEmpty()).ifBlank { manga.title },
                coverUrl = coverOf(post) ?: manga.coverUrl,
                description = description,
                author = author,
                status = status,
                genres = genres,
                contentType = contentType,
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    private fun dateOf(post: JSONObject): Long = try {
        java.time.Instant.parse(post.optString("modified_gmt") + "Z").toEpochMilli()
    } catch (e: Exception) { e.rethrowIfControl(); 0L }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val slug = slugOf(manga.url)
            val post = fetchPostBySlug(slug) ?: return@withContext emptyList()
            val content = post.optJSONObject("content")?.optString("rendered").orEmpty()
            val segments = content.split("<!--nextpage-->")
            val date = dateOf(post)
            // segments[0] je uvod/popis, kapitoly zacinaji od segments[1]
            (1 until segments.size).map { idx ->
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = "$slug|$idx",
                    name = "Chapter $idx",
                    chapterNumber = idx.toFloat(),
                    dateUpload = date,
                )
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val parts = chapter.url.split("|")
            if (parts.size != 2) return@withContext emptyList()
            val (slug, idxStr) = parts
            val idx = idxStr.toIntOrNull() ?: return@withContext emptyList()
            val post = fetchPostBySlug(slug) ?: return@withContext emptyList()
            val content = post.optJSONObject("content")?.optString("rendered").orEmpty()
            val segments = content.split("<!--nextpage-->")
            if (idx !in segments.indices) return@withContext emptyList()
            val imgSrcRegex = Regex("""<img[^>]+src="([^"]+)"""")
            imgSrcRegex.findAll(segments[idx]).mapNotNull { it.groupValues[1] }
                .filter { it.substringAfterLast('/').let(pageFilenameRegex::matches) }
                .distinct()
                .mapIndexed { i, url -> Page(i, url, url) }
                .toList()
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}
