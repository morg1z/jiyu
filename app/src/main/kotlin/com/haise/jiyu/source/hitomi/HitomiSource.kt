package com.haise.jiyu.source.hitomi

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
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HitomiSource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "hitomi"
    override val name = "Hitomi.La"

    private val baseUrl = "https://hitomi.la"
    private val ltnUrl = "https://ltn.hitomi.la"

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", baseUrl)
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseGalleryList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select(".gallery-content > .gallery, div.gallery").mapNotNull { el ->
            val link = el.selectFirst("a[href*='/galleries/']") ?: return@mapNotNull null
            val href = link.attr("href")
            val title = el.selectFirst(".gallery-name, h1 a, h1")?.text()?.trim()
                ?: link.text().trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val cover = el.selectFirst("img")?.let {
                val src = it.attr("data-src").takeIf { s -> s.isNotBlank() } ?: it.attr("src")
                if (src.startsWith("//")) "https:$src" else src.takeIf { s -> s.isNotBlank() }
            }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try { parseGalleryList(get("$baseUrl/index-all-$page.html")) } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            parseGalleryList(get("$baseUrl/search.html?$encoded&page=$page"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        val html = try { get("$baseUrl${manga.url}") } catch (_: Exception) { return@withContext manga }
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst(".gallery-info h1 a, h1.lillie")?.text()?.trim() ?: manga.title
        val cover = doc.selectFirst(".cover img, img.content-preview")?.let {
            val src = it.attr("data-src").takeIf { s -> s.isNotBlank() } ?: it.attr("src")
            if (src.startsWith("//")) "https:$src" else src.takeIf { s -> s.isNotBlank() }
        } ?: manga.coverUrl
        val tags = doc.select(".gallery-info .simpleTags a, .gallery-info td a")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct().take(20)
        val artist = doc.selectFirst(".gallery-info td:contains(Artists) ~ td a, .artist-list a")?.text()?.trim()
        val type = doc.selectFirst(".gallery-info td:contains(Type) ~ td")?.text()?.trim()
        val language = doc.selectFirst(".gallery-info td:contains(Language) ~ td")?.text()?.trim()
        val description = buildString {
            if (!type.isNullOrBlank()) append("Type: $type\n")
            if (!language.isNullOrBlank()) append("Language: $language\n")
            if (tags.isNotEmpty()) append("Tags: ${tags.joinToString(", ")}")
        }
        manga.copy(
            title = title,
            coverUrl = cover,
            author = artist,
            description = description.takeIf { it.isNotBlank() },
            genres = tags,
        )
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        val galleryId = manga.url.substringAfterLast("/").removeSuffix(".html")
        listOf(
            SChapter(
                sourceId = id,
                mangaUrl = manga.url,
                url = "/galleries/$galleryId",
                name = manga.title,
                chapterNumber = 1f,
                dateUpload = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        val galleryId = chapter.url.substringAfterLast("/")
        val js = try { get("$ltnUrl/galleries/$galleryId.js") } catch (_: Exception) { return@withContext emptyList() }
        val jsonStr = js.removePrefix("var galleryinfo = ").trimEnd(';', '\n', '\r').trim()
        val json = try { JSONObject(jsonStr) } catch (_: Exception) { return@withContext emptyList() }
        val files = json.optJSONArray("files") ?: return@withContext emptyList()
        (0 until files.length()).map { i ->
            val file = files.getJSONObject(i)
            val hash = file.optString("hash")
            val hasWebp = file.optInt("haswebp", 0)
            val fileName = file.optString("name", "")
            val imageUrl = buildImageUrl(hash, hasWebp, fileName)
            Page(index = i, url = imageUrl, imageUrl = imageUrl)
        }
    }

    private fun buildImageUrl(hash: String, hasWebp: Int, fileName: String): String {
        if (hash.isBlank()) return ""
        val lastChar = hash.last()
        val penultimate = if (hash.length >= 3) hash.substring(hash.length - 3, hash.length - 1) else hash
        val fullPath = "$lastChar/$penultimate/$hash"
        return if (hasWebp == 1) {
            "https://a.hitomi.la/webp/$fullPath.webp"
        } else {
            val ext = fileName.substringAfterLast('.').takeIf { it.isNotBlank() } ?: "jpg"
            "https://b.hitomi.la/images/$fullPath.$ext"
        }
    }
}
