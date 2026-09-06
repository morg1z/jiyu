package com.haise.jiyu.source.teamshadowi

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
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * team-shadowi.com - bespoke server-rendered Next.js aplikace (NE klientska SPA -
 * overeno zive, "/series" i "/series/{slug}" vraci plny HTML obsah bez JS).
 * Maly katalog (8 serii, overeno zive - "/series?page=2" vraci identickou sadu
 * jako strana 1, zadne dalsi strankovani), server-side "?search=" parametr
 * appka tise ignoruje (overeno zive - identicka sada pro ruzne dotazy), proto
 * vypis "/series" jen scrapuje karty `a[href^=/series/]` (titulek z `img[alt]`,
 * obalka primo z `img[src]`, zadny proxy trik) a hledani filtruje lokalne.
 *
 * Detail stranky "/series/{slug}" ukazuje jen poslednich 10 kapitol staticky a
 * zbytek dotahuje tlacitkem "Show More" - misto scrapovani HTML appka pouziva
 * primo JSON REST endpoint GET "/api/series/{slug}" nalezeny v JS bundlu detailu
 * (`page-*.js`, retezec "/api/series/"), overeno zive na vsech testovanych
 * seriich. Tenhle JEDEN endpoint vraci VSECHNY metadata (title, popis, genres,
 * origination=typ obsahu, status, authors/artists) A KOMPLETNI seznam kapitol
 * vcetne `image_paths` (uz hotove absolutni URL vsech stranek) v jednom
 * requestu - zadne dalsi API pro kapitoly/stranky netreba. Overeno zive na
 * serii se 224 kapitolami (~1MB JSON, staci ~0.3s). Kazda kapitola ma i vlastni
 * `is_locked`/`unlock_at` (predplacene predbezne kapitoly) - pokud je zamcena,
 * `image_paths` appka nezkousi cist a vrati prazdny seznam stranek misto pádu
 * (na vsech aktualne testovanych 224+67+104 kapitolach ale `is_locked` vzdy
 * false, zadna zamcena kapitola zatim nenalezena).
 *
 * Zadny zdrojovy seznam zanru pro tag-filter nenalezen (filtr na "/series" je
 * skryty React combobox bez options v initial HTML) - supportsTagFilter proto
 * zustava vypnuty, zanry se ale poradne naplni v getMangaDetails z API.
 */
@Singleton
class TeamShadowiSource @Inject constructor(private val client: OkHttpClient) : MangaSource {
    override val id = "teamshadowi"
    override val name = "TeamShadowi"
    override val contentType = "MANHWA"
    override val homepageUrl get() = base
    private val base = "https://www.team-shadowi.com"

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    private fun parseList(html: String): List<SManga> {
        val doc = Jsoup.parse(html)
        return doc.select("a[href^=/series/]").mapNotNull { a ->
            val href = a.attr("href").ifBlank { return@mapNotNull null }
            val slug = href.removePrefix("/series/").trim('/')
            if (slug.isBlank() || slug.contains('/')) return@mapNotNull null
            val img = a.selectFirst("img") ?: return@mapNotNull null
            val title = img.attr("alt").trim().ifBlank { return@mapNotNull null }
            val cover = img.attr("src").trim().ifBlank { null }
            SManga(sourceId = id, url = slug, title = title, coverUrl = cover)
        }.distinctBy { it.url }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (page > 1) return@withContext emptyList()
            parseList(get("$base/series"))
        } catch (_: Exception) { emptyList() }
    }

    // Server-side "?search=" parametr nefunguje (viz komentar u tridy) - hledani
    // proto stahne cely (maly, 8 titulu) katalog a filtruje podle titulku lokalne.
    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPopular(page, filter)
        if (page > 1) return@withContext emptyList()
        try {
            val q = query.trim()
            parseList(get("$base/series")).filter { it.title.contains(q, ignoreCase = true) }
        } catch (_: Exception) { emptyList() }
    }

    private fun fetchSeriesJson(slug: String): JSONObject =
        JSONObject(get("$base/api/series/$slug")).getJSONObject("series")

    private fun normalizeContentType(text: String?): String = when (text?.trim()?.lowercase()) {
        "manga" -> "MANGA"
        "manhua" -> "MANHUA"
        "novel", "light novel" -> "NOVEL"
        else -> "MANHWA"
    }

    private fun JSONArray.toStringList(): List<String> = (0 until length()).mapNotNull { i ->
        optString(i).trim().ifBlank { null }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val s = fetchSeriesJson(manga.url)
            manga.copy(
                title = s.optString("title").trim().ifBlank { manga.title },
                coverUrl = s.optString("thumbnail_url").trim().ifBlank { null } ?: manga.coverUrl,
                description = s.optString("description").trim().ifBlank { null },
                genres = s.optJSONArray("genres")?.toStringList() ?: manga.genres,
                alternateTitles = s.optJSONArray("alt_titles")?.toStringList() ?: manga.alternateTitles,
                status = s.optString("status").trim().lowercase().ifBlank { null },
                contentType = normalizeContentType(s.optString("origination")),
                author = s.optJSONArray("authors")?.toStringList()?.joinToString(", ")?.ifBlank { null },
                artist = s.optJSONArray("artists")?.toStringList()?.joinToString(", ")?.ifBlank { null },
                rating = if (s.has("average_rating")) s.optDouble("average_rating") else null,
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val chapters = fetchSeriesJson(manga.url).optJSONArray("chapters") ?: JSONArray()
            (0 until chapters.length()).mapNotNull { i ->
                val c = chapters.optJSONObject(i) ?: return@mapNotNull null
                val num = c.optString("number").toFloatOrNull() ?: return@mapNotNull null
                val title = c.optString("title").trim().ifBlank { null }
                // "url" musi byt globalne unikatni napric CELYM zdrojem (pouziva se jako
                // "$sourceId::$url" DB klic v MangaRepository.chapterId) - samotne cislo
                // kapitoly by kolidovalo mezi ruznymi seriemi, proto prefix slugem serie.
                SChapter(
                    sourceId = id, mangaUrl = manga.url, url = "${manga.url}/$num",
                    name = title?.let { "Chapter $num: $it" } ?: "Chapter $num",
                    chapterNumber = num, dateUpload = parseIsoDate(c.optString("created_at")),
                )
            }.sortedByDescending { it.chapterNumber }
        } catch (_: Exception) { emptyList() }
    }

    private fun parseIsoDate(iso: String): Long = try {
        java.time.Instant.parse(iso).toEpochMilli()
    } catch (_: Exception) { 0L }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val chapters = fetchSeriesJson(chapter.mangaUrl).optJSONArray("chapters") ?: JSONArray()
            val match = (0 until chapters.length()).map { chapters.optJSONObject(it) }
                .firstOrNull { it?.optString("number")?.toFloatOrNull() == chapter.chapterNumber }
                ?: return@withContext emptyList()
            if (match.optBoolean("is_locked")) return@withContext emptyList()
            val paths = match.optJSONArray("image_paths") ?: return@withContext emptyList()
            (0 until paths.length()).mapNotNull { i ->
                val url = paths.optString(i).trim().ifBlank { return@mapNotNull null }
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}
