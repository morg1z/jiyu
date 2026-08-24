package com.haise.jiyu.source.dankemoe

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
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * danke.moe (Danke fuers Lesen) - vlastni sablona, ALE ma jeden cisty JSON
 * endpoint `/api/series/{slug}/`, ktery vrati uplne vsechno najednou (title,
 * description, author, cover, kompletni seznam kapitol i s nazvy souboru
 * stranek). Zadne dalsi requesty netreba. Homepage nema pagination ani
 * funkcni hledani (`?search=` param je ticha no-op) - search() vraci
 * prazdny seznam.
 */
@Singleton
class DankeMoeSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "dankemoe"
    override val name = "Danke fürs Lesen"
    override val homepageUrl get() = base
    private val base = "https://danke.moe"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Referer", "$base/")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext emptyList()
        try {
            val doc = Jsoup.parse(get("$base/"))
            doc.select("div.card a[href^=/read/manga/]:has(img)").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val img = a.selectFirst("img") ?: return@mapNotNull null
                val title = img.attr("alt").trim().removePrefix("Cover for ").ifBlank { return@mapNotNull null }
                val cover = img.attr("data-src").trim().takeIf { it.isNotBlank() }?.let { if (it.startsWith("http")) it else "$base$it" }
                SManga(sourceId = id, url = href, title = title, coverUrl = cover, contentType = "MANGA")
            }.distinctBy { it.url }
        } catch (_: Exception) { emptyList() }
    }

    // Web nema funkcni server-side hledani (?search= je ticha no-op), ale homepage
    // ma kompletni seznam (100 titulu, jedna stranka bez strankovani) - misto
    // natvrdo prazdneho vysledku ("zadne vysledky" i pro titul, ktery web
    // evidentne ma) se filtruje lokalne, stejny vzor jako AnimeSamaSource/
    // HachirumiSource.
    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext emptyList()
        getPopular(1, filter).filter { it.title.contains(query, ignoreCase = true) }
    }

    private fun slugOf(mangaUrl: String) = mangaUrl.trim('/').substringAfterLast('/')

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(get("$base/api/series/${slugOf(manga.url)}/"))
            manga.copy(
                title = json.optString("title").ifBlank { manga.title },
                description = json.optString("description").takeIf { it.isNotBlank() },
                author = json.optString("author").takeIf { it.isNotBlank() },
                artist = json.optString("artist").takeIf { it.isNotBlank() },
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val slug = slugOf(manga.url)
            val json = JSONObject(get("$base/api/series/$slug/"))
            val chapters = json.optJSONObject("chapters") ?: return@withContext emptyList()
            chapters.keys().asSequence().mapNotNull { volumeKey ->
                val c = chapters.optJSONObject(volumeKey) ?: return@mapNotNull null
                val groups = c.optJSONObject("groups") ?: return@mapNotNull null
                val groupId = groups.keys().asSequence().firstOrNull() ?: return@mapNotNull null
                val title = c.optString("title").takeIf { it.isNotBlank() }
                val name = title ?: "Chapter $volumeKey"
                val num = volumeKey.toFloatOrNull() ?: 0f
                // url nese vse potrebne pro getPageList: slug, folder a groupId oddelene "|"
                val folder = c.optString("folder")
                SChapter(sourceId = id, mangaUrl = manga.url, url = "$slug|$folder|$groupId", name = name, chapterNumber = num, dateUpload = 0L)
            }.toList()
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val parts = chapter.url.split("|")
            if (parts.size != 3) return@withContext emptyList()
            val (slug, folder, groupId) = parts
            val json = JSONObject(get("$base/api/series/$slug/"))
            val chapters = json.optJSONObject("chapters") ?: return@withContext emptyList()
            val target = chapters.keys().asSequence()
                .map { chapters.optJSONObject(it) }
                .firstOrNull { it?.optString("folder") == folder }
                ?: return@withContext emptyList()
            val filenames = target.optJSONObject("groups")?.optJSONArray(groupId) ?: return@withContext emptyList()
            (0 until filenames.length()).mapNotNull { i ->
                val filename = filenames.optString(i).ifBlank { return@mapNotNull null }
                val url = "$base/media/manga/$slug/chapters/$folder/$groupId/$filename"
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}
