package com.haise.jiyu.source.mangago

import com.haise.jiyu.source.bodyOrThrow

import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.interceptor.CloudflareInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangagoSource @Inject constructor(
    baseClient: OkHttpClient,
    cloudflareInterceptor: CloudflareInterceptor,
) : MangaSource {
    override val id = "mangago"
    override val name = "Mangago"
    override val homepageUrl get() = base
    private val base = "https://www.mangago.me"

    private val client: OkHttpClient = baseClient.newBuilder()
        .addInterceptor(cloudflareInterceptor)
        .build()

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", CloudflareInterceptor.CHROME_UA)
            .header("Referer", base)
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            // Puvodni "/list/allmanga/page/N/?o=views" je mrtve (Total: 0, zadna karta) -
            // aktualni vypis zije na "/list/?page=N" (Total: 20000+), overeno zivym
            // stazenim. Karta = ".listitem", obalka je v "data-src" (v "src" je jen
            // sdileny base64 placeholder pro lazy-load).
            val doc = Jsoup.parse(get("$base/list/?page=$page"))
            doc.select(".listitem").mapNotNull { li ->
                val link = li.selectFirst("div.left a[href]") ?: return@mapNotNull null
                SManga(
                    sourceId = id,
                    url = link.attr("href").removePrefix(base),
                    title = li.selectFirst("span.title a")?.text()?.trim()?.ifBlank { null }
                        ?: link.attr("title").trim().takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null,
                    coverUrl = li.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() },
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            // Puvodni "/r/search.php" vraci 404 - skutecny hledaci formular na hlavni
            // strance vede na "/r/l_search/" (overeno zivym stazenim, funguje i strankovani
            // pres &page=N). Vysledky maji jinou strukturu nez popularni vypis - obalka je
            // v primem "src" (bez lazy-load placeholderu) a nadpis obsahuje vnoreny
            // <span class="hilight"> se shodou hledaneho vyrazu, .text() ho spoji do ciste
            // podoby.
            val q = URLEncoder.encode(query, "UTF-8")
            val doc = Jsoup.parse(get("$base/r/l_search/?name=$q&page=$page"))
            doc.select("#search_list li").mapNotNull { li ->
                val link = li.selectFirst("div.left a[href*='/read-manga/']") ?: return@mapNotNull null
                SManga(
                    sourceId = id,
                    url = link.attr("href").removePrefix(base),
                    title = li.selectFirst("span.tit h2 a")?.text()?.trim()?.ifBlank { null }
                        ?: return@mapNotNull null,
                    coverUrl = li.selectFirst("div.left img")?.attr("src"),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            manga.copy(
                title = doc.selectFirst(".w-title h1, h1.title")?.text()?.trim() ?: manga.title,
                coverUrl = doc.selectFirst(".cover img, .w-cover img")?.attr("src") ?: manga.coverUrl,
                description = doc.selectFirst("#content p, .manga-info p")?.text(),
                genres = doc.select(".tag-links a, .genre a").map { it.text() },
                author = doc.selectFirst(".table-ellipsis td:contains(Author) + td")?.text()?.trim(),
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get("$base${manga.url}"))
            val links = doc.select("#chapter_table tr td a, .chapter_list li a")
            // Web vypisuje kapitoly od nejnovejsi (DOM poradi), puvodne se ale cislo
            // kapitoly pocitalo primo z tohohle poradi (i+1) a AZ POTOM se cely seznam
            // otocil (.reversed()) - to obratilo poradi ZOBRAZENI, ale ne uz priradena
            // CISLA (nejnovejsi kapitola tak dostala cislo 1, nejstarsi nejvyssi cislo).
            // MangaDetailViewModel radi podle chapterNumber (prepinac Nejnovejsi/
            // Nejstarsi) - s obracenymi cisly vypadalo razeni rozbite. Oprava: napred
            // otocit poradi prvku (na chronologicke, nejstarsi->nejnovejsi), az pak
            // cislovat indexem.
            links.reversed().mapIndexed { i, a ->
                SChapter(
                    sourceId = id, mangaUrl = manga.url,
                    url = a.attr("href").removePrefix(base),
                    name = a.text().trim().takeIf { it.isNotBlank() } ?: "Chapter ${i + 1}",
                    chapterNumber = (i + 1).toFloat(),
                    dateUpload = 0L,
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val html = get("$base${chapter.url}")
            val jsMatch = Regex("""var\s+newImglist\s*=\s*\[([^\]]+)\]""").find(html)
                ?: Regex("""imgsrcs\s*=\s*\[([^\]]+)\]""").find(html)
            if (jsMatch != null) {
                val urls = jsMatch.groupValues[1]
                    .split(",")
                    .map { it.trim().trim('"', '\'') }
                    .filter { it.startsWith("http") }
                return@withContext urls.mapIndexed { i, url -> Page(i, url, url) }
            }
            Jsoup.parse(html).select(".pic_box img, #comicpic img").mapIndexedNotNull { i, img ->
                val url = img.attr("src").takeIf { it.startsWith("http") } ?: return@mapIndexedNotNull null
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}
