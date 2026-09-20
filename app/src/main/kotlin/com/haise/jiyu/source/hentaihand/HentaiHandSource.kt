package com.haise.jiyu.source.hentaihand

import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.rethrowIfControl
import com.haise.jiyu.source.FilterTag
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
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HentaiHand (hentaihand.com) - anglicka hentai doujinshi/comic galerie. Na
 * rozdil od vetsiny ostatnich zdroju v tomhle balicku NENI postavena na
 * server-renderovanem HTML - homepage/detail stranky vraci jen staticky
 * "ssr-seo" skelet pro crawlery (bez obrazku stranek), skutecny obsah
 * dotahuje az klientsky JS z JSON API. Naopak to API je verejne dostupne
 * primo (Laravel-style paginace `{current_page, data:[...], last_page}`):
 *  - `/api/comics?page=N` a `/api/comics?q=X&page=N` pro listing/hledani
 *  - `/api/comics/{slug}/images` pro seznam stranek konkretni galerie
 *    (POZOR: cesta bere slug, ne ciselne "id" z listing odpovedi - "id" tam
 *    je jen interni DB klic jine tabulky a s timhle endpointem nesouvisi)
 *
 * Cela galerie = jedna "kapitola" (viz NhentaiSource).
 */
@Singleton
class HentaiHandSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "hentaihand"
    override val name = "HentaiHand"
    override val supportsSortOrder: Boolean get() = false
    override val isAdult = true
    override val homepageUrl get() = base

    private val base = "https://hentaihand.com"

    private fun fetchJson(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP_124)
            .header("Accept", "application/json")
            .build()
        return JSONObject(client.newCall(request).execute().use { it.bodyOrThrow(url) })
    }

    private fun listItemToSManga(obj: JSONObject): SManga {
        val slug = obj.optString("slug")
        val genres = obj.optJSONArray("tags")?.let { tags ->
            (0 until tags.length()).mapNotNull { tags.optJSONObject(it)?.optString("name")?.trim()?.ifBlank { null } }
        } ?: emptyList()
        return SManga(
            sourceId    = id,
            url         = slug,
            title       = obj.optString("title").ifBlank { obj.optString("alternative_title") }.ifBlank { slug },
            coverUrl    = obj.optString("thumb_url").ifBlank { null },
            description = obj.optString("description").ifBlank { null },
            genres      = genres.take(15),
            contentType = "MANGA",
        )
    }

    private fun parseList(json: JSONObject): List<SManga> {
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.let(::listItemToSManga) }
    }

    // /api/tags ma pres 8000 polozek (prilis mnoho na hardcode/zive stahovani celeho
    // seznamu), ale web ma i mnohem hrubsi taxonomii "Categories" (stejnych 7 hodnot
    // jako HentaiFox - Doujinshi/Manga/Non-H/Western/Imageset/Artistcg/Misc, zbytek
    // stranky /api/categories je spam od uzivatelu s prazdnym "name" a comics_count=0,
    // proto se filtruji pryc). Live overeno: `?categories[]={id}` skutecne filtruje
    // vypis (napr. id=3 "Non-H" vraci total=16 misto celkovych stovek tisic).
    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val json = fetchJson("$base/api/categories?page=1")
            val data = json.optJSONArray("data") ?: return@withContext emptyList()
            val tags = (0 until data.length()).mapNotNull { i ->
                val obj = data.optJSONObject(i) ?: return@mapNotNull null
                val name = obj.optString("name").trim().ifBlank { null } ?: return@mapNotNull null
                if (obj.optInt("comics_count", 0) <= 0) return@mapNotNull null
                FilterTag(id = obj.optInt("id", -1).takeIf { it > 0 }?.toString() ?: return@mapNotNull null, label = name)
            }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try { parseList(fetchJson("$base/api/comics?categories[]=${filter.genres.first()}&page=$page")) }
            catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }
        try { parseList(fetchJson("$base/api/comics?page=$page")) }
        catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try { parseList(fetchJson("$base/api/comics?categories[]=${filter.genres.first()}&page=$page")) }
            catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            parseList(fetchJson("$base/api/comics?q=$q&page=$page"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val json = fetchJson("$base/api/comics/${manga.url}/images")
            val comic = json.optJSONObject("comic") ?: return@withContext manga
            val title = comic.optString("title").ifBlank { null } ?: manga.title
            val description = comic.optString("description").ifBlank { null } ?: manga.description
            val genres = if (manga.genres.isNotEmpty()) manga.genres else {
                comic.optJSONArray("tags")?.let { tags ->
                    (0 until tags.length()).mapNotNull { i ->
                        tags.optJSONObject(i)?.optString("slug")?.trim()?.ifBlank { null }
                            ?.split("-")?.joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
                    }
                } ?: emptyList()
            }
            manga.copy(title = title, description = description, genres = genres)
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        listOf(
            SChapter(
                sourceId = id,
                mangaUrl = manga.url,
                url = manga.url,
                name = manga.title,
                chapterNumber = 1f,
                dateUpload = 0L,
            )
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val json = fetchJson("$base/api/comics/${chapter.url}/images")
            val images = json.optJSONArray("images") ?: return@withContext emptyList()
            (0 until images.length()).mapNotNull { i ->
                val url = images.optJSONObject(i)?.optString("source_url")?.ifBlank { null } ?: return@mapNotNull null
                Page(index = i, url = url, imageUrl = url)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}
