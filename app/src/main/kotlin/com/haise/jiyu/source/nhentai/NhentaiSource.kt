package com.haise.jiyu.source.nhentai

import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.rethrowIfControl
import com.haise.jiyu.source.FilterTag
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * nhentai vyřadilo starou API (`/api/galleries/...`, `/api/gallery/{id}`) ve
 * prospěch v2 (`/api/v2/...`, viz https://nhentai.net/api/v2/docs). Rozdíl
 * oproti staré verzi:
 *  - listing endpointy (popular/search) vrací "ploché" pole bez title objektu
 *    (jen english_title/japanese_title) a jen tag_ids (ne celé tag objekty)
 *  - detail endpoint (/galleries/{id}) pořád vrací bohatou strukturu
 *    (title.english/japanese/pretty, plné tag objekty, "pages" pole s
 *    hotovou cestou k souboru včetně přípony - žádné mapování "t" typu na
 *    příponu jako dřív)
 *  - obálky/thumbnaily fungují jen na t.nhentai.net, i.nhentai.net je jen
 *    pro plné stránky galerie
 */
@Singleton
class NhentaiSource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "nhentai"
    override val name = "nhentai"
    override val isAdult = true
    override val homepageUrl get() = "https://nhentai.net"

    private val apiBase   = "https://nhentai.net/api/v2"
    private val imgBase   = "https://i.nhentai.net"
    private val thumbBase = "https://t.nhentai.net"

    // /api/v2/tags/{tag_type}: "category" (3 polozky), "language" (~desitka)
    // a "tag" - plna tagova taxonomie ~4700 polozek, strankovana po 120 a
    // razena podle poctu pouziti (overeno zive: strana 1 = big breasts,
    // sole female, group...). Tahame jen TAG_TOP_PAGES nejuzitecnejsich stranek
    // - dlouhy ocas tagu s pouzitim <10k stejne nikoho nezajima a kazda dalsi
    // stranka je dalsi request. Query syntax "tag:slug" funguje v /api/v2/search
    // stejne jako "category:manga"/"language:english" (overeno zive).
    override val supportsTagFilter: Boolean get() = true

    @Volatile private var cachedTags: List<FilterTag>? = null

    private fun fetchTagType(tagType: String, pages: Int = 1): List<FilterTag> {
        val out = mutableListOf<FilterTag>()
        for (page in 1..pages) {
            val json = fetch("$apiBase/tags/$tagType?page=$page")
            val result = json.optJSONArray("result") ?: break
            for (i in 0 until result.length()) {
                val obj = result.getJSONObject(i)
                val slug = obj.optString("slug").ifBlank { continue }
                val name = obj.optString("name").ifBlank { continue }
                // "tag" je vychozi typ - suffix jen u vedlejsich, at seznam ctu jako na webu.
                val label = if (tagType == "tag") name else "$name (${tagType})"
                out += FilterTag(id = "$tagType:$slug", label = label)
            }
            if (result.length() == 0) break
        }
        return out
    }

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val tags = (fetchTagType("category") + fetchTagType("language") +
                fetchTagType("tag", TAG_TOP_PAGES)).distinctBy { it.id }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    // Obecny browse "/galleries?page=N" razeni neumi (vzdy chronologicky) -
    // sort parametr se uplatni jen v zanrovych dotazech (sortParam), takze
    // prepinac popular/latest by na hlavnim vypisu nic nedelal (audit).
    override val supportsSortOrder: Boolean get() = false

    private fun sortParam(sortBy: String) = if (sortBy == "latest") "date" else "popular"

    private fun fetch(url: String): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .header("Referer", "https://nhentai.net")
            .header("Accept", "application/json")
            .build()
        val body = client.newCall(req).execute().use { it.body?.string() ?: "{}" }
        // popular/search vraci bud primo pole, nebo {"result": [...]}; zabalime pole do objektu.
        return if (body.trimStart().startsWith("[")) JSONObject().put("result", JSONArray(body))
        else JSONObject(body)
    }

    /** Listing (popular/search) - jen ploche pole, bez plnych tag objektu. */
    private fun listItemToSManga(obj: JSONObject): SManga {
        val thumb = obj.optString("thumbnail").ifBlank { null }
        val title = obj.optString("english_title").ifBlank { null }
            ?: obj.optString("japanese_title").ifBlank { null }
            ?: "ID: ${obj.optInt("id")}"
        return SManga(
            sourceId    = id,
            url         = "/gallery/${obj.optInt("id")}",
            title       = title,
            coverUrl    = thumb?.let { "$thumbBase/$it" },
            contentType = "MANGA",
        )
    }

    private fun parseList(json: JSONObject): List<SManga> {
        val result = json.optJSONArray("result") ?: return emptyList()
        return (0 until result.length()).map { listItemToSManga(result.getJSONObject(it)) }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        // nhentai query syntax umi vic tagu najednou ("tag:a tag:b" = AND) - drive
        // se aplikoval jen prvni vybrany, coz filtr znevaznoval.
        if (filter.genres.isNotEmpty()) {
            return@withContext try {
                val q = URLEncoder.encode(filter.genres.joinToString(" "), "UTF-8")
                parseList(fetch("$apiBase/search?query=$q&sort=${sortParam(filter.sortBy)}&page=$page"))
            } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }
        // "/galleries/popular" NENÍ stránkovaný výpis - podle OpenAPI schématu appky
        // (/api/v2/openapi.json) je to "Get today's popular galleries" bez jakéhokoli
        // parametru, vrací vždy stejnou pevnou pětici bez ohledu na "page" (ověřeno
        // živě: page=1/2/3 vrací identických 5 položek). Obecný výpis "/galleries" má
        // v OpenAPI parametry page + per_page a živě vrací 25 různých položek na
        // stránku, proto se browse teď opírá o ten.
        try { parseList(fetch("$apiBase/galleries?page=$page")) }
        catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try {
                val tagQuery = filter.genres.joinToString(" ")
                val combined = if (query.isNotBlank()) "${query.trim()} $tagQuery" else tagQuery
                val q = URLEncoder.encode(combined, "UTF-8")
                parseList(fetch("$apiBase/search?query=$q&sort=${sortParam(filter.sortBy)}&page=$page"))
            } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            parseList(fetch("$apiBase/search?query=$q&page=$page"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val galleryId = manga.url.substringAfterLast("/")
            val json = fetch("$apiBase/galleries/$galleryId")

            val titleObj = json.optJSONObject("title")
            val title = titleObj?.optString("english")?.takeIf { it.isNotBlank() }
                ?: titleObj?.optString("pretty")?.takeIf { it.isNotBlank() }
                ?: titleObj?.optString("japanese")?.takeIf { it.isNotBlank() }
                ?: manga.title
            val cover = json.optJSONObject("cover")?.optString("path")?.takeIf { it.isNotBlank() }
                ?.let { "$thumbBase/$it" } ?: manga.coverUrl

            val tagsArr = json.optJSONArray("tags") ?: JSONArray()
            val tagObjs = (0 until tagsArr.length()).map { tagsArr.getJSONObject(it) }
            val byType  = tagObjs.groupBy { it.optString("type") }
            val artist  = byType["artist"]?.firstOrNull()?.optString("name")
            val genres  = byType["tag"]?.map { it.optString("name") }?.filter { it.isNotBlank() } ?: emptyList()

            val desc = buildString {
                byType["parody"]?.let    { append("Parody: ${it.joinToString { t -> t.optString("name") }}\n") }
                byType["character"]?.let { append("Characters: ${it.joinToString { t -> t.optString("name") }}\n") }
                byType["language"]?.let  { append("Language: ${it.joinToString { t -> t.optString("name") }}\n") }
                byType["category"]?.let  { append("Category: ${it.joinToString { t -> t.optString("name") }}\n") }
                append("Pages: ${json.optInt("num_pages")}")
            }.trim()

            manga.copy(title = title, coverUrl = cover, description = desc, author = artist, genres = genres.take(15))
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        listOf(
            SChapter(
                sourceId        = id,
                mangaUrl        = manga.url,
                url             = manga.url,
                name            = manga.title,
                chapterNumber   = 1f,
                dateUpload      = 0L,
            )
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val galleryId = chapter.url.substringAfterLast("/")
            val json = fetch("$apiBase/galleries/$galleryId")
            val pages = json.optJSONArray("pages") ?: return@withContext emptyList()
            (0 until pages.length()).map { i ->
                val path = pages.getJSONObject(i).optString("path")
                val url = "$imgBase/$path"
                Page(i, url, url)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private companion object {
        /** Kolik stranek /api/v2/tags/tag tahat do pickru (120 tagu/strana, razeno
         * podle poctu pouziti) - 3 stranky = 360 nejcastejsich tagu, zbytek je
         * long-tail, ktery v chipovem pickeru nikoho nezajima. */
        const val TAG_TOP_PAGES = 3
    }
}
