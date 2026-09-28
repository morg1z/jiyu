package com.haise.jiyu.source.scribblehub

import com.haise.jiyu.util.toSourcePath
import com.haise.jiyu.util.resolveSourceUrl
import com.haise.jiyu.util.absoluteMediaUrl
import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.util.rethrowIfControl
import com.haise.jiyu.source.bodyOrThrow

import com.haise.jiyu.source.FilterTag
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
    override val supportsSortOrder: Boolean get() = false
    override val contentType = "NOVEL"
    override val homepageUrl get() = base
    private val base = "https://www.scribblehub.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html, base)
        return doc.select(".search_main_box, .novel-item").mapNotNull { el ->
            val link = el.selectFirst(".search_title a, .novel-title a, h3 a, h2 a") ?: return@mapNotNull null
            val href = link.attr("href").let {
                resolveSourceUrl(base, it)
            }
            SManga(
                sourceId = id,
                url = toSourcePath(base, href),
                title = link.text().trim(),
                coverUrl = el.selectFirst(".search_img img, .novel-cover img, img")?.let { img ->
                    img.attr("src").let { s -> absoluteMediaUrl(base, s) }
                        ?: img.attr("data-src").let { s -> absoluteMediaUrl(base, s) }
                },
                contentType = "NOVEL",
            )
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        // Genre archiv /genre/{slug}/ vraci standardni .search_main_box listing.
        val genre = filter.genres.firstOrNull()
        if (genre != null && page > 1) return@withContext emptyList() // strankovani archivu neoverene
        try {
            val url = if (genre != null) "$base/genre/$genre/"
            else "$base/series-ranking/?sort=toprated&page=$page"
            parseList(get(url))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        val genre = filter.genres.firstOrNull()
        if (genre != null) {
            // ?s= nelze kombinovat s genre archivem - text dofilitrujeme lokalne.
            val base = getPopular(page, filter)
            return@withContext if (query.isBlank()) base
            else base.filter { it.title.contains(query.trim(), ignoreCase = true) }
        }
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseList(get("$base/?s=$q&post_type=fictionposts&paged=$page"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    /**
     * Genre index web nenabizi na jedinem endpointu - kazda detail stranka
     * ale nese sve genre linky (.wi_fic_genre a -> /genre/{slug}/). Sklizime
     * je z nekolika top-hodnocenych detailu; taxonomie je fixni (~40 zanru),
     * takze par detailu staci na temer kompletni pokryti.
     */
    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        val acc = linkedMapOf<String, String>() // slug -> label
        try {
            val rankDoc = Jsoup.parse(get("$base/series-ranking/?sort=toprated"), base)
            val detailUrls = rankDoc.select(".search_main_box .search_title a, .novel-item .novel-title a")
                .mapNotNull { it.attr("href").takeIf(String::isNotBlank) }
                .distinct().take(6)
            for (u in detailUrls) {
                try {
                    Jsoup.parse(get(resolveSourceUrl(base, u)), base)
                        .select(".wi_fic_genre a")
                        .forEach { a ->
                            val slug = Regex("""/genre/([^/?#]+)""").find(a.attr("href"))?.groupValues?.get(1)
                                ?: return@forEach
                            acc.putIfAbsent(slug, a.text().trim().ifBlank { slug })
                        }
                } catch (e: Exception) { e.rethrowIfControl() }
                if (acc.size >= 30) break
            }
        } catch (e: Exception) { e.rethrowIfControl() }
        acc.map { (slug, label) -> FilterTag(id = slug, label = label) }
            .sortedBy { it.label.lowercase() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)), base)
            manga.copy(
                title = doc.selectFirst(".fic_title, h1.title")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst(".novel-cover img, .fic_image img")?.let { img ->
                    img.attr("src").let { s -> absoluteMediaUrl(base, s) }
                } ?: manga.coverUrl,
                description = doc.selectFirst(".wi_fic_desc, .description-summary")
                    ?.text()?.trim(),
                genres = doc.select(".wi_fic_genre a, .novel-genre a, .wi_fic_tag a")
                    .map { it.text().trim() }.filter { it.isNotBlank() },
                author = doc.selectFirst(".auth_name_fic a, .author-name a")?.text()?.trim(),
                contentType = "NOVEL",
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
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
                .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
                .header("Referer", resolveSourceUrl(base, manga.url))
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            val html = client.newCall(req).execute().use { it.bodyOrThrow("$base/wp-admin/admin-ajax.php") }

            Jsoup.parse(html, base).select("li.toc_w a").mapIndexed { i, a ->
                val href = a.attr("href").let { toSourcePath(base, it) }
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
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val url = if (chapter.url.startsWith("http")) chapter.url else resolveSourceUrl(base, chapter.url)
            val doc = Jsoup.parse(get(url), base)
            val content = doc.selectFirst(".chapter-inner .chp-raw, .chp-raw, .chapter-content")
                ?: return@withContext emptyList()
            content.select("script, style, .ads-holder, ins").remove()
            val text = content.text().trim()
            if (text.isBlank()) emptyList()
            else listOf(Page(0, text, "novel://text"))
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}
