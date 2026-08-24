package com.haise.jiyu.source.likemanga

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
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * likemanga.ink - vlastni sablona (ne Madara), sdili nekolik JS/ID konvenci
 * ("nt_listchapter", cookie "..._session") se starym MangaNato/NatoManga
 * rodinou sablon, ale nejde o stejny web. Ceka obrazky kapitol hostuje na
 * samostatne domene like.mgread.io.
 */
@Singleton
class LikeMangaSource @Inject constructor(private val client: OkHttpClient) : MangaSource {

    override val id = "likemanga"
    override val name = "LikeManga"
    override val homepageUrl get() = base
    private val base = "https://likemanga.ink"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    /** Karty maji stejnou strukturu na "top-all"/genre/AJAX vyhledavani - .card obal, a.jtip odkaz, img.card-img-top obalka. */
    private fun parseCard(el: Element): SManga? {
        val link = el.selectFirst("p.title-manga a, a.jtip.card-img-top") ?: return null
        val href = link.attr("href").ifBlank { return null }
        val title = el.selectFirst("p.title-manga a")?.text()?.trim()
            ?: link.attr("title").trim().takeIf { it.isNotBlank() }
            ?: return null
        // data-src prednostne pred src (lazy-loading placeholder) - stejny vzor jako u
        // MangaMikan/Raw1001, kde "src" bylo prazdne/placeholder a skutecna URL byla v data-src.
        val coverEl = el.selectFirst("img")
        val coverRaw = coverEl?.attr("data-src")?.trim()?.ifBlank { null }
            ?: coverEl?.attr("src")?.trim().orEmpty()
        return SManga(
            sourceId = id,
            url = href,
            title = title,
            coverUrl = coverRaw.takeIf { it.isNotBlank() }?.let { if (it.startsWith("http")) it else "$base/$it" },
            contentType = "MANGA",
        )
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base/search/top-all/$page/"))
            doc.select("div.card-body.list-left-8-manga").mapNotNull { it.parent()?.let(::parseCard) }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext emptyList()
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.parse(get("$base/?act=ajax&code=search_manga&keyword=$q"))
            doc.select("li > a[href]").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val title = a.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null
                val coverRaw = a.selectFirst("img")?.attr("src")?.trim().orEmpty()
                SManga(
                    sourceId = id,
                    url = href,
                    title = title,
                    coverUrl = coverRaw.takeIf { it.isNotBlank() }?.let { if (it.startsWith("http")) it else "$base/$it" },
                    contentType = "MANGA",
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val genres = doc.select("li.kind p.col-8 a").map { it.text().trim() }
            manga.copy(
                title = doc.selectFirst("h1.title-detail")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst(".col-image img")?.attr("src")?.trim()
                    ?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("http")) it else "$base/$it" }
                    ?: manga.coverUrl,
                description = doc.selectFirst("#summary_shortened")?.text()?.trim()?.takeIf { it.isNotBlank() },
                author = doc.selectFirst("li.author p.col-8")?.text()?.trim()?.takeIf { it.isNotBlank() && !it.equals("Updating", ignoreCase = true) },
                genres = genres,
                status = doc.selectFirst("li.status p.col-8")?.text()?.trim()?.takeIf { it.isNotBlank() },
                // Web sam typ (manga/manhwa/manhua) nikde primo neuvadi - odvozeno ze zanroveho stitku,
                // stejny vzor jako u MadaraSource - viz project_jiyu_contenttype_default past.
                contentType = genres.firstNotNullOfOrNull {
                    when (it.lowercase()) {
                        "manhwa" -> "MANHWA"
                        "manhua" -> "MANHUA"
                        else -> null
                    }
                } ?: "MANGA",
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val mangaId = Regex("""-(\d+)/?$""").find(manga.url)?.groupValues?.get(1)
                ?: return@withContext emptyList()
            val chapters = mutableListOf<SChapter>()
            var pageNum = 1
            // Seznam kapitol na detailu je strankovany po ~50 pres AJAX (viz
            // load_list_chapter v custom_new.js) - prazdny list_chap = konec.
            while (true) {
                val json = JSONObject(get("$base/?act=ajax&code=load_list_chapter&manga_id=$mangaId&page_num=$pageNum&chap_id=0&keyword="))
                val listHtml = json.optString("list_chap").ifBlank { break }
                val items = Jsoup.parse(listHtml).select("li.wp-manga-chapter a")
                if (items.isEmpty()) break
                items.forEach { a ->
                    val name = a.text().trim()
                    chapters += SChapter(
                        sourceId = id,
                        mangaUrl = manga.url,
                        url = a.attr("href"),
                        name = name,
                        chapterNumber = Regex("""[Cc]hapter\s*([\d.]+)""").find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f,
                        dateUpload = 0L,
                    )
                }
                pageNum++
            }
            chapters
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${chapter.url}"))
            doc.select("div.page-chapter img").mapIndexedNotNull { i, img ->
                val url = img.attr("src").takeIf { it.startsWith("http") } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}
