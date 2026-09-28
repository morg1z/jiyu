package com.haise.jiyu.source.dynasty

import com.haise.jiyu.util.lazySrc
import com.haise.jiyu.util.toSourcePath
import com.haise.jiyu.util.resolveSourceUrl
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DynastySource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "dynasty"
    override val name = "Dynasty Scans"
    override val supportsSortOrder: Boolean get() = false
    override val homepageUrl get() = base

    private val base = "https://dynasty-scans.com"

    // Dynasty rate-limituje na ~10 requestu za kratke okno (~1 s) - pres limit
    // vraci 503, ale resetuje po ~1.5 s. Retry po pauze proto vetsinou projde.
    // Bez retry cover-burst v getPopular saturuje limit a nasledny detail fetch
    // (klik na titul hned po vypsani) vraci 503 -> "zadne kapitoly" u titulu,
    // co kapitoly normalne ma (overeno zive).
    private fun get(url: String): String {
        var lastError: java.io.IOException? = null
        repeat(2) { attempt ->
            try {
                if (attempt > 0) Thread.sleep(1_200)
                val req = Request.Builder().url(url)
                    .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
                    .build()
                return client.newCall(req).execute().use { it.bodyOrThrow(url) }
            } catch (e: java.io.IOException) {
                if (!e.message.orEmpty().contains("503") && !e.message.orEmpty().contains("429")) throw e
                lastError = e
            }
        }
        throw lastError!!
    }

    // series.json vraci strankovane vysledky, zabalene do "tags" pole objektu
    // s jedinym klicem "#" (ne skutecne razeni podle pismene) - viz odpoved
    // {"tags":[{"#":[{"name":...,"permalink":...}, ...]}, ...],"current_page":1,"total_pages":17}.
    // Cover uz v listingu neni, doplni se az v getMangaDetails.
    // ─── Filtrování podle tagu ───────────────────────────────────────────────

    override val supportsTagFilter: Boolean get() = true

    // "/tags" je staticky seznam (~120 polozek, overeno zive 2026-09-04) - stejny
    // vzor kesovani jako u ostatnich zdroju s vlastnim seznamem tagu.
    @Volatile private var cachedTags: List<FilterTag>? = null

    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        try {
            val doc = Jsoup.parse(get("$base/tags"))
            val tags = doc.select("dl.tag-list dd a[href^=/tags/]").mapNotNull { a ->
                val slug = a.attr("href").removePrefix("/tags/").ifBlank { null } ?: return@mapNotNull null
                val label = a.text().trim().ifBlank { null } ?: return@mapNotNull null
                FilterTag(id = slug, label = label)
            }
            cachedTags = tags
            tags
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    /**
     * "/tags/{slug}?view=groupings" - na rozdil od vychozi "Chapters" zalozky
     * (jednorazove kapitoly/anthology prispevky) vraci SERIALY se stejnym
     * "/series/{slug}" tvarem URL jako [getPopular]/[search] - overeno zive
     * 2026-09-04. Kombinace vice tagu web nepodporuje - pri vice vybranych se
     * pouzije prvni (stejny vzor jako u MadaraSource).
     */
    private fun parseTagArchive(slug: String, page: Int): List<SManga> {
        val doc = Jsoup.parse(get("$base/tags/$slug?page=$page&view=groupings"))
        return doc.select("ul.thumbnails.cover-list li a.thumbnail").mapNotNull { a ->
            val href = a.attr("href").ifBlank { return@mapNotNull null }
            val title = a.selectFirst("div.caption b")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
            val cover = a.selectFirst("img")?.attr("src")?.let { if (it.startsWith("//")) "https:$it" else it }
            SManga(sourceId = id, url = href, title = title, coverUrl = cover)
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try { parseTagArchive(filter.genres.first(), page) } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }
        try {
            val json = JSONObject(get("$base/series.json?page=$page"))
            val groups = json.optJSONArray("tags") ?: return@withContext emptyList()
            val items = mutableListOf<SManga>()
            for (g in 0 until groups.length()) {
                val arr = groups.getJSONObject(g).optJSONArray("#") ?: continue
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val slug = obj.optString("permalink", "")
                    items.add(
                        SManga(
                            sourceId = id,
                            url = "/series/$slug",
                            title = obj.optString("name", slug),
                            coverUrl = null,
                        )
                    )
                }
            }
            // JSON listing obalky nenosi - dotahnou se paralelne z detailnich
            // stranek (img.thumbnail = tag_contents_covers obrazek). Bez toho
            // by browse grid byl cisty text (audit 2026-10 "21t/0c").
            // Paralelismus 2 + rozestup startu - plny burst (i jen 21/4) prekroci
            // ~10 req/okno rate-limit webu a nasledne dotazy (tagy/search/kapitoly)
            // koncily 503 -> prazdno (re-audit + live mereni: 11 OK pak 503).
            val io2 = Dispatchers.IO.limitedParallelism(2)
            coroutineScope {
                items.mapIndexed { i, m ->
                    async(io2) {
                        if (i > 0) kotlinx.coroutines.delay(120L * i)
                        m.copy(coverUrl = fetchSeriesCover(m.url) ?: m.coverUrl)
                    }
                }.map { it.await() }
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    /** Obalka serie z detailni stranky ("/series/{slug}" -> img.thumbnail). */
    private fun fetchSeriesCover(seriesPath: String): String? =
        try {
            val doc = Jsoup.parse(get("$base$seriesPath"), base)
            doc.selectFirst("img.thumbnail")?.attr("src")
                ?.let { if (it.startsWith("//")) "https:$it" else resolveSourceUrl(base, it) }
        } catch (e: Exception) { null }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (filter.genres.isNotEmpty()) {
            return@withContext try { parseTagArchive(filter.genres.first(), page) } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
        }
        if (query.isBlank()) return@withContext getPopular(page, filter)
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val html = get("$base/search?q=$q&classes[]=Series")
            val doc = Jsoup.parse(html)
            doc.select(".chapter-list dd").mapNotNull { el ->
                val link = el.selectFirst("a") ?: return@mapNotNull null
                val href = link.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = link.text().trim()
                val cover = el.selectFirst("img")?.attr("src")?.let { if (it.startsWith("//")) "https:$it" else it }
                SManga(sourceId = id, url = href, title = title, coverUrl = cover)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            val title = doc.selectFirst("h2.tag-title b")?.text()?.trim() ?: manga.title
            val cover = doc.selectFirst(".thumbnail img, .cover img")?.let {
                val src = it.lazySrc().orEmpty()
                if (src.startsWith("//")) "https:$src"
                else if (src.startsWith("/")) "$base$src"
                else src
            } ?: manga.coverUrl
            val desc = doc.selectFirst(".description")?.text()?.trim()
            val genres = doc.select(".tags a[href*='/tags/']").map { it.text().trim() }.filter { it.isNotBlank() }
            manga.copy(title = title, coverUrl = cover, description = desc, genres = genres)
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.parse(get(resolveSourceUrl(base, manga.url)))
            // Web vypisuje kapitoly od nejnovejsi - cislo se puvodne pocitalo primo z
            // DOM poradi (i+1) a AZ POTOM se seznam otocil (.reversed()), coz obratilo
            // jen poradi ZOBRAZENI, ne uz priradena cisla (nejnovejsi kapitola dostala
            // cislo 1). Radici prepinac Nejnovejsi/Nejstarsi v MangaDetailViewModel radi
            // podle chapterNumber, takze s takhle obracenymi cisly vypadalo rozbite.
            doc.select(".chapter-list dd a[href*='/chapters/']").reversed().mapIndexed { i, el ->
                val href = toSourcePath(base, el.attr("href"))
                val name = el.text().trim().ifBlank { "Chapter ${i + 1}" }
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = href,
                    name = name,
                    chapterNumber = (i + 1).toFloat(),
                    dateUpload = 0L,
                )
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val html = get(resolveSourceUrl(base, chapter.url))
            val match = Regex("""var\s+pages\s*=\s*(\[.*?]);""", RegexOption.DOT_MATCHES_ALL).find(html)
            val json = match?.groupValues?.get(1) ?: return@withContext emptyList()
            val arr = runCatching { org.json.JSONArray(json) }.getOrNull() ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val url = obj.optString("image").takeIf { it.isNotBlank() }
                    ?.let { if (it.startsWith("//")) "https:$it" else if (it.startsWith("/")) "$base$it" else it }
                    ?: return@mapNotNull null
                Page(i, url, url)
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }
}
