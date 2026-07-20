package com.haise.jiyu.source.scribblehub

import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScribbleHubSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "scribblehub"
    override val name = "ScribbleHub"
    override val contentType = "NOVEL"
    override val homepageUrl get() = base
    private val base = "https://www.scribblehub.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html, base)
        return doc.select(".search_main_box, .novel-item").mapNotNull { el ->
            val link = el.selectFirst(".search_title a, .novel-title a, h3 a, h2 a") ?: return@mapNotNull null
            val href = link.attr("href").let {
                if (it.startsWith("http")) it else "$base$it"
            }
            SManga(
                sourceId = id,
                url = href.removePrefix(base),
                title = link.text().trim(),
                coverUrl = el.selectFirst(".search_img img, .novel-cover img, img")?.let { img ->
                    img.attr("src").takeIf { s -> s.startsWith("http") }
                        ?: img.attr("data-src").takeIf { s -> s.startsWith("http") }
                },
            )
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            parseList(get("$base/series-ranking/?sort=toprated&page=$page"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseList(get("$base/?s=$q&post_type=fictionposts&paged=$page"))
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"), base)
            manga.copy(
                title = doc.selectFirst(".fic_title, h1.title")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst(".novel-cover img, .fic_image img")?.let { img ->
                    img.attr("src").takeIf { s -> s.startsWith("http") }
                } ?: manga.coverUrl,
                description = doc.selectFirst(".wi_fic_desc, .description-summary")
                    ?.text()?.trim(),
                genres = doc.select(".wi_fic_genre a, .novel-genre a, .wi_fic_tag a")
                    .map { it.text().trim() }.filter { it.isNotBlank() },
                author = doc.selectFirst(".auth_name_fic a, .author-name a")?.text()?.trim(),
                contentType = "NOVEL",
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            // Scribble Hub načítá seznam kapitol přes AJAX
            val postId = Regex("""series/(\d+)/""").find(manga.url)?.groupValues?.get(1)
                ?: return@withContext emptyList()

            val body = FormBody.Builder()
                .add("action", "wi_gettocchps")
                .add("action_order", "DESC")
                .add("pagenum", "1")
                .add("mypostid", postId)
                .build()
            val req = Request.Builder()
                .url("$base/wp-admin/admin-ajax.php")
                .post(body)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "$base${manga.url}")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            val html = client.newCall(req).execute().use { it.body?.string() ?: "" }

            Jsoup.parse(html, base).select("li.toc_w a").mapIndexed { i, a ->
                val href = a.attr("href").let { if (it.startsWith("http")) it.removePrefix(base) else it }
                val name = a.text().trim().ifBlank { "Chapter ${i + 1}" }
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = href,
                    name = name,
                    chapterNumber = (i + 1).toFloat(),
                    dateUpload = 0L,
                )
            }.reversed()
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val url = if (chapter.url.startsWith("http")) chapter.url else "$base${chapter.url}"
            val doc = Jsoup.parse(get(url), base)
            val content = doc.selectFirst(".chapter-inner .chp-raw, .chp-raw, .chapter-content")
                ?: return@withContext emptyList()
            content.select("script, style, .ads-holder, ins").remove()
            val text = content.text().trim()
            if (text.isBlank()) emptyList()
            else listOf(Page(0, text, "novel://text"))
        } catch (_: Exception) { emptyList() }
    }
}
