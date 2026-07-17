package com.haise.jiyu.source.comicskingdom

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
 * Comics Kingdom (comicskingdom.com) - oficialni King Features Syndicate
 * archiv denne aktualizovanych americkych novinovych komiksovych pasku
 * (Blondie, Popeye, Hagar the Horrible, Zits, Mutts...). Frontend je
 * Next.js SSR, ale __NEXT_DATA__ na kazde strance prozradi, ze cely obsah
 * jede pres verejne headless WordPress REST API na wp.comicskingdom.com -
 * scrapujeme proto primo to, misto Next.js HTML.
 *
 * Datovy model: jeden "feature" (pásek/série, WP taxonomie
 * ck_feature_taxonomy) = jedna SManga. Kazdy denni pásek je vlastni
 * ck_comic post filtrovatelny pres ?ck_feature_taxonomy={termId} = jedna
 * SChapter (chapterNumber roste chronologicky, 1 = nejstarsi dostupny
 * pásek, ne 1 = nejnovejsi).
 */
@Singleton
class ComicsKingdomSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "comicskingdom"
    override val name = "Comics Kingdom"
    override val contentType = "COMIC"
    private val api = "https://wp.comicskingdom.com/wp-json/wp/v2"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseFeatures(raw: String): List<SManga> {
        val arr = JSONArray(raw)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val termId = o.optInt("id", -1).takeIf { it > 0 } ?: return@mapNotNull null
            val name = o.optString("name").ifBlank { return@mapNotNull null }
            SManga(sourceId = id, url = "/$termId", title = name, coverUrl = null, contentType = "COMIC")
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            parseFeatures(get("$api/ck_feature_taxonomy?per_page=24&page=$page&orderby=count&order=desc"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseFeatures(get("$api/ck_feature_taxonomy?search=$q&per_page=24&page=$page"))
        } catch (_: Exception) { emptyList() }
    }

    private fun assetUrl(post: JSONObject): String? {
        val assets = post.optJSONObject("assets") ?: return null
        return assets.optJSONObject("single")?.optString("url")?.ifBlank { null }
            ?: assets.optJSONObject("featured")?.optString("url")?.ifBlank { null }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val termId = manga.url.removePrefix("/")
            val arr = JSONArray(get("$api/ck_comic?ck_feature_taxonomy=$termId&per_page=1&orderby=date&order=desc"))
            if (arr.length() == 0) return@withContext manga
            val post = arr.getJSONObject(0)
            manga.copy(
                coverUrl = assetUrl(post) ?: manga.coverUrl,
                author = post.optString("ck_comic_byline").ifBlank { null },
                description = "Denní komiksový pásek ${manga.title} (King Features Syndicate).",
                status = "Ongoing",
                contentType = "COMIC",
            )
        } catch (_: Exception) { manga }
    }

    // WP REST vraci max. 100 polozek na stranku - u dlouho bezicich pasku
    // (Blondie ma pres 13000 dennich stripu) je proto potreba strankovat.
    // Strop 40 stranek (4000 pasku) je bezpecnostni pojistka proti
    // nekonecne smycce, ne realne omezeni (nejdelsi ověřene série maji
    // pod 3000 zaznamu).
    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val termId = manga.url.removePrefix("/")
            val posts = mutableListOf<JSONObject>()
            var page = 1
            while (page <= 40) {
                val arr = JSONArray(
                    get("$api/ck_comic?ck_feature_taxonomy=$termId&per_page=100&page=$page&orderby=date&order=desc&_fields=id,date,ck_formatted_date")
                )
                if (arr.length() == 0) break
                (0 until arr.length()).forEach { posts.add(arr.getJSONObject(it)) }
                if (arr.length() < 100) break
                page++
            }
            // API vraci nejnovejsi prvni - otocime, aby kapitola 1 byla
            // nejstarsi dostupny pásek (konvence stejna jako u ostatnich zdroju).
            posts.reverse()
            posts.mapIndexed { i, post ->
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = post.getInt("id").toString(),
                    name = post.optString("ck_formatted_date").ifBlank { "Strip ${i + 1}" },
                    chapterNumber = (i + 1).toFloat(),
                    dateUpload = parseDate(post.optString("date")),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun parseDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        return try {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ENGLISH)
            fmt.parse(text)?.time ?: 0L
        } catch (_: Exception) { 0L }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val post = JSONObject(get("$api/ck_comic/${chapter.url}?_fields=assets"))
            val url = assetUrl(post) ?: return@withContext emptyList()
            listOf(Page(0, url))
        } catch (_: Exception) { emptyList() }
    }
}
