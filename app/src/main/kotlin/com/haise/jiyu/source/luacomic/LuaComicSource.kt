package com.haise.jiyu.source.luacomic

import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.parseChapterNumber
import com.haise.jiyu.util.rethrowIfControl
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
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * luacomic.org - Next.js frontend, ale API (`api.luacomic.org`) je cisty JSON
 * a funguje bez auth pro zdarma dostupne kapitoly - staci Referer/Origin
 * hlavicka (jinak WAF vraci 403). `SManga.url` je `"{numericId}/{slug}"`,
 * protoze `/chapter/query` potrebuje numericke `series_id`, ale ctecka
 * stranka pouziva slug.
 */
@Singleton
class LuaComicSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "luacomic"
    override val name = "Lua Comic"
    override val supportsSortOrder: Boolean get() = false
    override val homepageUrl get() = base
    private val base = "https://luacomic.org"
    private val apiBase = "https://api.luacomic.org"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP_124)
            .header("Referer", "$base/")
            .header("Origin", base)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseManga(o: JSONObject): SManga {
        val slug = o.optString("series_slug")
        val seriesId = o.optInt("id")
        val type = o.optString("series_type")
        val contentType = when (type.lowercase()) {
            "manhwa" -> "MANHWA"
            "manhua" -> "MANHUA"
            else -> "MANGA"
        }
        return SManga(
            sourceId = id, url = "$seriesId/$slug", title = o.optString("title"),
            coverUrl = o.optString("thumbnail").takeIf { it.isNotBlank() },
            description = o.optString("description").takeIf { it.isNotBlank() },
            status = o.optString("status").takeIf { it.isNotBlank() },
            contentType = contentType,
        )
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(get("$apiBase/query?adult=true&query_string=&page=$page"))
            val data = json.optJSONArray("data") ?: return@withContext emptyList()
            (0 until data.length()).mapNotNull { data.optJSONObject(it)?.let(::parseManga) }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val json = JSONObject(get("$apiBase/query?adult=true&query_string=$q&page=$page"))
            val data = json.optJSONArray("data") ?: return@withContext emptyList()
            (0 until data.length()).mapNotNull { data.optJSONObject(it)?.let(::parseManga) }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = manga

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val seriesId = manga.url.substringBefore('/')
            val json = JSONObject(get("$apiBase/chapter/query?page=1&perPage=999&query=&order=asc&series_id=$seriesId"))
            val data = json.optJSONArray("data") ?: return@withContext emptyList()
            (0 until data.length()).mapNotNull { i ->
                val c = data.optJSONObject(i) ?: return@mapNotNull null
                val slug = c.optString("chapter_slug").ifBlank { return@mapNotNull null }
                val name = c.optString("chapter_name").ifBlank { slug }
                val num = parseChapterNumber(name) ?: 0f
                SChapter(sourceId = id, mangaUrl = manga.url, url = slug, name = name, chapterNumber = num, dateUpload = 0L)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val mangaSlug = chapter.mangaUrl.substringAfter('/')
            val html = get("$base/series/$mangaSlug/${chapter.url}")
            Regex("""https://media\.luacomic\.org/[^"'\\]+\.(?:webp|jpg|jpeg|png)\.jpg""")
                .findAll(html)
                .map { it.value }
                .distinct()
                .toList()
                .mapIndexed { i, url -> Page(i, url, url) }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}
