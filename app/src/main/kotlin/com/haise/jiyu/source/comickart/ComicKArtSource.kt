package com.haise.jiyu.source.comickart

import com.haise.jiyu.source.FilterTag
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SGroup
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.bodyOrThrow
import com.haise.jiyu.source.fetchPagesParallel
import com.haise.jiyu.source.interceptor.CloudflareInterceptor
import com.haise.jiyu.util.rethrowIfControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Zdroj pro zrcadlo ComicK na comick.art (stejný přístup jako Kotatsu-Redo's
 * ComickFunParser - doména comick.live tam vrací 403, comick.art funguje).
 *
 * Na rozdíl od oficiálního api.comick.dev ([com.haise.jiyu.source.comick.ComicKSource],
 * který je jen metadatový katalog - /chapter/{hid} vrací 404) tahle doména
 * reálné stránky kapitol SERVÍRUJE: HTML stránka kapitoly obsahuje
 * `<script id="sv-data">` s JSON `chapter.images[].url` (CDN cdn1.comicknew.pictures).
 * Ověřeno živě 2026-09-23: search/detail/kapitoly/stránky všechny vrací data
 * bez Cloudflare výzvy (postačí desktopový User-Agent).
 *
 * Pozor: jde o neoficiální mirror s VLASTNÍ databází - `hid` kapitol se
 * NESDÍLÍ s api.comick.dev (jiná hodnota u stejné kapitoly), sdílený je jen
 * `slug` titulu. Proto se ComicK knihovní kapitoly na tento zdroj resolvují
 * standardně přes cross-source resolver (title match), ne přímým hid.
 *
 * Endpointy (všechny ověřené):
 *  - GET /api/search?q=&order_by=&genres=&country=&status=&type=comic&cursor=
 *  - GET /api/comics/top?days=7|30|90&type=follow|most_follow_new   (populární)
 *  - GET /api/chapters/latest?order=new&page=N                    (neustále)
 *  - GET /api/comics/{slug}/chapter-list?page=N                   (60/str., všechny jazyky+skupiny)
 *  - GET /api/metadata                                          (žánry/tagy/demografie)
 *  - GET /comic/{slug}              → <script id="comic-data"> JSON (detail)
 *  - GET /comic/{slug}/{hid}-chapter-{chap}-{lang} → <script id="sv-data"> JSON (stránky)
 */
class ComicKArtSource(
    private val client: OkHttpClient,
    private val base: String = DEFAULT_BASE,
) : MangaSource {

    override val id = SOURCE_ID
    override val name = "ComicK Art"
    override val homepageUrl = base
    override val supportsTagFilter = true
    // /api/search?status=&from=&to= - overeno zive (status=1 jen ongoing,
    // from=2020&to=2021 jen rok 2021).
    override val supportsStatusFilter: Boolean get() = true
    override val supportsYearFilter: Boolean get() = true
    // Web filtruje i podle techto parametru /api/search (vse overeno zive 2026-09-27):
    // excludes[]/excluded_tags[], demographic[], country[], minimum, time,
    // order_direction. Hodnoty z /api/metadata (demographics/comic_type/comic_status/
    // created_ranges).
    override val supportsExcludeTags: Boolean get() = true
    override val supportsMinChaptersFilter: Boolean get() = true
    override val supportsSortDirection: Boolean get() = true
    // comic_status z /api/metadata - web nabizi i "Cancelled" (status=3).
    override val availableStatuses = listOf("ongoing", "completed", "hiatus", "cancelled")
    override val availableDemographics = listOf(
        FilterTag("1", "Shounen"), FilterTag("2", "Josei"),
        FilterTag("3", "Seinen"), FilterTag("4", "Shoujo"),
        FilterTag("0", "None"),
    )
    override val availableComicTypes = listOf(
        FilterTag("jp", "Manga"), FilterTag("kr", "Manhwa"),
        FilterTag("cn", "Manhua"), FilterTag("others", "Others"),
    )
    override val availableCreatedRanges = listOf(
        FilterTag("3", "3 days ago"), FilterTag("7", "7 days ago"),
        FilterTag("30", "30 days ago"), FilterTag("90", "3 months ago"),
        FilterTag("180", "6 months ago"), FilterTag("365", "1 year ago"),
        FilterTag("730", "2 years ago"),
    )
    // API podporuje jen order_by=rating a order_by=uploaded (audit 2026-10:
    // title/view/follow vraci HTTP 302 -> HTML misto JSON). "popular" listing
    // ma vlastni /api/comics/top feed, takze zbyva jen rating navic.
    override val availableSorts = setOf("popular", "latest", "rating")

    /** Cursor stránkování /api/search - cursor z předchozí stránky, resetuje se při page==1. */
    @Volatile private var nextCursor: String? = null

    @Volatile private var cachedTags: List<FilterTag>? = null

    // ─── Browse / hledání ────────────────────────────────────────────────────

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            apiSearch(page, filter.copy(), query)
        }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            // Status/rok a dalsi filtry podporuje jen /api/search - specialni feedy
            // (latestUpdates, topComics) zadne filtry neumi, proto se pri
            // aktivnim filtru vzdy routuje do apiSearch (stejne jako genres).
            val hasAdvancedFilter = filter.genres.isNotEmpty() ||
                filter.excludeGenres.isNotEmpty() || filter.tags.isNotEmpty() ||
                filter.excludeTags.isNotEmpty() || filter.demographic.isNotEmpty() ||
                filter.comicTypes.isNotEmpty() || filter.minChapters != null ||
                filter.createdRangeDays != null ||
                filter.status != null || filter.year != null ||
                filter.sortAscending
            when {
                // "Nejnovější aktualizace" - feed posledních kapitol, deduplikace podle slugu.
                filter.sortBy == "latest" && !hasAdvancedFilter -> latestUpdates(page)
                // Čisté "populární" bez filtrů - top feed (7/30/90 dní × follow/most_follow_new),
                // stejné mapování stránek jako Kotatsu-Redo (6 stránek, pak konec).
                filter.sortBy == "popular" && !hasAdvancedFilter -> topComics(page)
                else -> apiSearch(page, filter, null)
            }
        }

    /** /api/comics/top - API od 2026-10 parametr "days" ignoruje (7/30/90 vraci
     * identicka data - audit DUP), odlisny je jen "type": stranka 1 = follow,
     * stranka 2 = most_follow_new, dalsi stranky neexistuji. */
    private fun topComics(page: Int): List<SManga> {
        if (page > 2) return emptyList()
        val type = if (page == 1) "follow" else "most_follow_new"
        val json = getObject("$base/api/comics/top?days=7&type=$type")
        return parseComicList(json.optJSONArray("data") ?: JSONArray())
    }

    /** /api/chapters/latest - kapitoly napříč tituly; jedna položka = titul (dedupe podle slugu).
     *  "page" parametr endpoint ignoruje - kazda stranka vraci identicky feed
     *  (audit DUP, overeno zive), proto jen prvni stranka. */
    private fun latestUpdates(page: Int): List<SManga> {
        if (page > 1) return emptyList()
        val json = getObject("$base/api/chapters/latest?order=new&page=$page")
        val arr = json.optJSONArray("data") ?: return emptyList()
        val seen = HashSet<String>()
        return (0 until arr.length()).mapNotNull { i ->
            val jo = arr.getJSONObject(i)
            val slug = jo.optString("slug").ifBlank { return@mapNotNull null }
            if (!seen.add(slug)) return@mapNotNull null
            val title = jo.optString("title").ifBlank { return@mapNotNull null }
            SManga(
                sourceId = id,
                url = "$base/comic/$slug",
                title = title,
                coverUrl = jo.optString("default_thumbnail").ifBlank { null },
                contentType = contentTypeFromCountry(jo.optString("country")),
            )
        }
    }

    /** /api/search - cursor stránkování (cursor z předchozí odpovědi, ne číslo stránky). */
    private fun apiSearch(page: Int, filter: MangaFilter, query: String?): List<SManga> {
        if (page == 1) nextCursor = null
        val url = buildString {
            append("$base/api/search?type=comic&showAll=false&exclude_mylist=false")
            // Jedinne platne hodnoty jsou rating/uploaded - "view"/"title"/"follow"
            // vraci 302 redirect na HTML, coz rozbije JSON parsing (audit 2026-10
            // "9327tagu -> 0t" byl zpusobeny prave timhle, ne zanrovym filtrem).
            when (filter.sortBy) {
                "latest" -> append("&order_by=uploaded")
                "rating" -> append("&order_by=rating")
                else     -> Unit // bez order_by = default relevance webu
            }
            append(if (filter.sortAscending) "&order_direction=asc" else "&order_direction=desc")
            fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
            filter.genres.forEach { append("&genres=${enc(it)}") }
            filter.excludeGenres.forEach { append("&excludes=${enc(it)}") }
            filter.tags.forEach { append("&tags=${enc(it)}") }
            filter.excludeTags.forEach { append("&excluded_tags=${enc(it)}") }
            filter.demographic.forEach { append("&demographic=${enc(it)}") }
            filter.comicTypes.forEach { append("&country=${enc(it)}") }
            filter.minChapters?.takeIf { it > 0 }?.let { append("&minimum=$it") }
            filter.createdRangeDays?.takeIf { it > 0 }?.let { append("&time=$it") }
            // Stejne int hodnoty jako hlavni ComicK API (overeno zive:
            // status=1 vraci jen status:1 vysledky). Rok = interval from=to.
            when (filter.status) {
                "ongoing" -> append("&status=1")
                "completed" -> append("&status=2")
                "cancelled" -> append("&status=3")
                "hiatus" -> append("&status=4")
            }
            filter.year?.takeIf { it > 0 }?.let { append("&from=$it&to=$it") }
            query?.trim()?.takeIf { it.length >= 3 }
                ?.let { append("&q=${URLEncoder.encode(it, "UTF-8")}") }
            if (page > 1) nextCursor?.let { append("&cursor=${URLEncoder.encode(it, "UTF-8")}") }
        }
        val json = getObject(url)
        nextCursor = (json.optString("next_cursor").ifBlank { null }
            ?: json.optString("cursor").ifBlank { null })
            ?.takeIf { it != "null" }
        val result = parseComicList(json.optJSONArray("data") ?: JSONArray())
        // Málo výsledků na stránce = pravděpodobně konec (stejná heuristika jako Kotatsu).
        if (result.size < 10) nextCursor = null
        return result
    }

    /** /api/metadata - žánry (kind "genre", ~84) a volné tagy (kind "tag", ~9k) jako
     *  FilterTag (slug = hodnota pro genres=/excludes=/tags=/excluded_tags= parametry).
     *  Web je taky odděluje - Genres multi-select vs. Tags fulltext; picker je routuje
     *  podle `kind` do [MangaFilter.genres]/[MangaFilter.tags]. */
    override suspend fun getAvailableTags(): List<FilterTag> = withContext(Dispatchers.IO) {
        cachedTags?.let { return@withContext it }
        val json = getObject("$base/api/metadata")
        val out = mutableListOf<FilterTag>()
        for ((key, kind) in listOf("genres" to KIND_GENRE, "tags" to KIND_TAG)) {
            val arr = json.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val slug = o.optString("slug").ifBlank { null } ?: continue
                val name = o.optString("name").ifBlank { null } ?: continue
                out.add(FilterTag(id = slug, label = name, kind = kind))
            }
        }
        cachedTags = out.distinctBy { it.kind to it.id }.sortedWith(
            compareBy({ if (it.kind == KIND_GENRE) 0 else 1 }, { it.label }))
        cachedTags!!
    }

    // ─── Detail ──────────────────────────────────────────────────────────────

    /**
     * Detail titulu = HTML stránka `/comic/{slug}` s JSON v `<script id="comic-data">`.
     * API pod `api.comick.dev` tvar (`{comic: {...}}`) zde neexistuje - comick.art
     * servíruje plná metadata přímo v SSR stránce (title, desc, authors, artists,
     * md_titles, md_comic_md_genres, country, status, year, bayesian_rating...).
     */
    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        val slug = manga.url.substringAfterLast("/")
        val html = getHtml("$base/comic/$slug")
        val dataEl = html.selectFirst("#comic-data")
            ?: throw java.io.IOException("ComicK Art: chybí #comic-data u $slug")
        val comic = JSONObject(dataEl.data())

        val desc = if (comic.isNull("desc")) null
            else Jsoup.parseBodyFragment(comic.optString("desc")).wholeText().ifBlank { null }
        val author = comic.optJSONArray("authors")?.optJSONObject(0)
            ?.optString("name")?.ifBlank { null }
        val genres = mutableListOf<String>()
        comic.optJSONArray("md_comic_md_genres")?.let { arr ->
            for (i in 0 until arr.length()) {
                val g = arr.optJSONObject(i)?.optJSONObject("md_genres") ?: continue
                if (!g.isNull("name")) g.optString("name").ifBlank { null }?.let { genres.add(it) }
            }
        }
        val alternateTitles = mutableListOf<String>()
        comic.optJSONArray("md_titles")?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val title = if (t.isNull("title")) null else t.optString("title").ifBlank { null }
                if (title != null && title != manga.title) alternateTitles.add(title)
            }
        }

        val statusInt = if (comic.isNull("status")) -1 else comic.optInt("status", -1)
        val status = when (statusInt) {
            1 -> "Vychází"; 2 -> "Dokončeno"; 3 -> "Zrušeno"; 4 -> "Přerušeno"; else -> null
        }
        val rating = if (comic.isNull("bayesian_rating")) null
            else comic.optString("bayesian_rating").toDoubleOrNull()
        val followCount = comic.optInt("user_follow_count", 0).takeIf { it > 0 }
            ?: comic.optInt("follow_count", 0).takeIf { it > 0 }
        val rank = comic.optInt("follow_rank", 0).takeIf { it > 0 }
        val year = comic.optInt("year", 0).takeIf { it > 0 }
        val finalChapterRaw = if (comic.isNull("final_chapter")) null
            else comic.optString("final_chapter").ifBlank { null }
        val finalVolumeRaw = if (comic.isNull("final_volume")) null
            else comic.optString("final_volume").ifBlank { null }
        val finalChapter = finalChapterRaw?.let { ch ->
            if (finalVolumeRaw != null) "Svazek $finalVolumeRaw, kapitola $ch" else "Kapitola $ch"
        }

        manga.copy(
            title = comic.optString("title").ifBlank { manga.title },
            description = desc,
            status = status,
            author = author,
            genres = genres,
            year = year,
            coverUrl = comic.optString("default_thumbnail").ifBlank { manga.coverUrl },
            contentType = contentTypeFromCountry(comic.optString("country")),
            demographic = if (comic.isNull("demographic_name")) null
                else comic.optString("demographic_name").ifBlank { null },
            translationCompleted = if (comic.has("translation_completed") && !comic.isNull("translation_completed"))
                comic.optBoolean("translation_completed") else null,
            hasAnime = if (comic.has("has_anime") && !comic.isNull("has_anime"))
                comic.optBoolean("has_anime") else null,
            finalChapter = finalChapter,
            rating = rating,
            followCount = followCount,
            rank = rank,
            alternateTitles = alternateTitles.distinct().take(8),
            lastChapter = comic.optDouble("last_chapter").takeIf { !it.isNaN() }?.toFloat(),
        )
    }

    // ─── Kapitoly ────────────────────────────────────────────────────────────

    /**
     * /api/comics/{slug}/chapter-list?page=N - stránkované (60/str.), všechny jazyky
     * a všechny skupinové verze (jedna logická kapitola × N skupin = N záznamů;
     * každá má vlastní hid → vlastní stránky, proto se NEoddeduplikují - uživatel
     * si vybere skupinu, kterou chce číst, přes scanlationGroup ve výpisu).
     */
    override suspend fun getChapterList(manga: SManga): List<SChapter> =
        withContext(Dispatchers.IO) {
            val slug = manga.url.substringAfterLast("/")
            val collected = mutableListOf<Pair<JSONObject, Int>>() // json, up_count
            withTimeoutOrNull(CHAPTER_LIST_TIMEOUT_MS) {
                // Stránka 1 zjistí last_page, zbytek se stáhne souběžně - API stránkuje
                // fixně po 60 a limit= ignoruje (ověřeno živě), takže velký titul
                // (Solo Leveling = 70 stránek) sekvenciálně trvá desítky sekund.
                val first = fetchChapterPage(slug, 1) ?: return@withTimeoutOrNull
                collected.addAll(first.items)
                val last = first.lastPage.coerceAtMost(MAX_CHAPTER_PAGES)
                if (last > 1) {
                    collected.addAll(fetchPagesParallel(2, last) { page ->
                        fetchChapterPage(slug, page)?.items ?: emptyList()
                    })
                }
            }
            // Nejnovější napřed; u více verzí stejné kapitoly dřív ta s více hlasy.
            collected
                .sortedWith(compareByDescending<Pair<JSONObject, Int>> { (jo, _) ->
                    jo.optString("chap").toFloatOrNull() ?: 0f
                }.thenByDescending { (_, up) -> up })
                .mapNotNull { (jo, _) -> chapterFromJson(jo, manga.url, slug) }
        }

    // ─── Stránky ─────────────────────────────────────────────────────────────

    /**
     * Stránky kapitoly = HTML `/comic/{slug}/{hid}-chapter-{chap}-{lang}` se
     * `<script id="sv-data">` JSON → `chapter.images[].url` (přímé URL na CDN).
     * chapter.url už je celá absolutní adresa sestavená v getChapterList.
     */
    override suspend fun getPageList(chapter: SChapter): List<Page> =
        withContext(Dispatchers.IO) {
            val html = getHtml(chapter.url)
            val dataEl = html.selectFirst("#sv-data")
                ?: throw java.io.IOException("ComicK Art: chybí #sv-data u ${chapter.url}")
            val images = JSONObject(dataEl.data())
                .getJSONObject("chapter")
                .getJSONArray("images")
            (0 until images.length()).mapNotNull { i ->
                val url = images.optJSONObject(i)?.optString("url")?.ifBlank { null }
                    ?: return@mapNotNull null
                Page(index = i, url = url, imageUrl = url)
            }
        }

    override suspend fun getImageUrl(page: Page): String = page.imageUrl ?: page.url

    // ─── Privátní pomocné ────────────────────────────────────────────────────

    private fun parseComicList(arr: JSONArray): List<SManga> =
        (0 until arr.length()).mapNotNull { i -> comicFromJson(arr.getJSONObject(i)) }

    /** Položka z /api/search, /api/comics/top, /group #sv-data - `default_thumbnail` je už plná URL. */
    private fun comicFromJson(comic: JSONObject): SManga? {
        val title = comic.optString("title").ifBlank { return null }
        val slug = comic.optString("slug").ifBlank { return null }
        val lastChapter = if (comic.isNull("last_chapter")) null
            else comic.optDouble("last_chapter").takeIf { !it.isNaN() }?.toFloat()
        return SManga(
            sourceId = id,
            url = "$base/comic/$slug",
            title = title,
            coverUrl = comic.optString("default_thumbnail").ifBlank { null },
            contentType = contentTypeFromCountry(comic.optString("country")),
            lastChapter = lastChapter,
        )
    }

    /** Jedna stránka chapter-list: položky (json, up_count) + last_page z pagination. */
    private class ChapterPage(val items: List<Pair<JSONObject, Int>>, val lastPage: Int)

    private fun fetchChapterPage(slug: String, page: Int): ChapterPage? {
        val json = getObject("$base/api/comics/$slug/chapter-list?page=$page")
        val arr = json.optJSONArray("data") ?: return null
        val items = (0 until arr.length()).map { i ->
            arr.getJSONObject(i) to arr.getJSONObject(i).optInt("up_count", 0)
        }
        val lastPage = json.optJSONObject("pagination")?.optInt("last_page", 1) ?: 1
        return ChapterPage(items, lastPage)
    }

    /** Jedna kapitola z chapter-list - url rovnou ve tvaru stránky kapitoly. */
    private fun chapterFromJson(json: JSONObject, mangaUrl: String, slug: String): SChapter? {
        val hid = json.optString("hid").ifBlank { return null }
        val chap = json.optString("chap").ifBlank { "0" }
        val lang = json.optString("lang", "en").ifBlank { "en" }
        // isNull() guardy - stejný zavedený org.json bug jako v ComicKSource
        // (optString() na JSONObject.NULL vrací doslovný "null", ne "").
        val vol = if (json.isNull("vol")) null else json.optString("vol").ifBlank { null }
        val title = if (json.isNull("title")) null else json.optString("title").ifBlank { null }
        val name = buildString {
            if (vol != null) append("Vol.$vol ")
            append("Ch.$chap")
            if (!title.isNullOrBlank()) append(" – $title")
        }
        val groups = parseGroups(json)
        return SChapter(
            sourceId = id,
            mangaUrl = mangaUrl,
            url = "$base/comic/$slug/$hid-chapter-$chap-$lang",
            name = name,
            chapterNumber = chap.toFloatOrNull() ?: 0f,
            dateUpload = parseIso(json.optString("created_at")),
            volume = vol,
            scanlationGroup = groups.joinToString(", ") { it.name }.ifBlank { null },
            groups = groups,
            // Mirror drzi u kapitoly jazyk primo v hid URL - bez zachovani se
            // vicejazycne verze tehoz cisla michaly jako nerozlisitelne duplicity.
            language = lang,
        )
    }

    /**
     * group_name[] + md_chapters_groups[].md_groups - stejná logika jako
     * [com.haise.jiyu.source.comick.ComicKSource.parseGroups] (pole se párují
     * podle indexu, md_* může být kratší/chybět).
     */
    private fun parseGroups(json: JSONObject): List<SGroup> {
        val names = json.optJSONArray("group_name") ?: return emptyList()
        val mdGroups = json.optJSONArray("md_chapters_groups")
        return (0 until names.length()).map { i ->
            val rawName = if (names.isNull(i)) "" else names.optString(i)
            val mdGroup = mdGroups?.optJSONObject(i)?.optJSONObject("md_groups")
            SGroup(
                name = mdGroup?.takeIf { !it.isNull("title") }?.optString("title")?.ifBlank { null } ?: rawName,
                slug = mdGroup?.takeIf { !it.isNull("slug") }?.optString("slug")?.ifBlank { null },
            )
        }
    }

    /** country je tu uppercase ("KR"), jinde v appce lowercase - sjednotit. */
    private fun contentTypeFromCountry(country: String): String = when (country.lowercase()) {
        "jp" -> "MANGA"; "kr" -> "MANHWA"; "cn" -> "MANHUA"; else -> "MANGA"
    }

    private fun parseIso(iso: String): Long = try {
        java.time.Instant.parse(iso).toEpochMilli()
    } catch (e: Exception) { e.rethrowIfControl(); 0L }

    private fun requestBuilder(url: String) = Request.Builder().url(url)
        .header("User-Agent", CloudflareInterceptor.CHROME_UA)

    private fun getObject(url: String): JSONObject {
        client.newCall(requestBuilder(url).build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw java.io.IOException("ComicK Art API chyba ${response.code}: $url")
            return JSONObject(body)
        }
    }

    private fun getHtml(url: String): org.jsoup.nodes.Document {
        val body = client.newCall(requestBuilder(url).build()).execute()
            .use { it.bodyOrThrow(url) }
        return Jsoup.parse(body, url)
    }

    companion object {
        /** ID zdroje - ComicKChapterResolver podle nej pozna mirror pro fázi 0 (slug probe). */
        const val SOURCE_ID = "comickart"
        const val DEFAULT_BASE = "https://comick.art"

        private const val CHAPTER_LIST_TIMEOUT_MS = 60_000L
        /** Strop stránkování chapter-list (60/str. → 12 000 kapitol max). */
        private const val MAX_CHAPTER_PAGES = 200

        /** `FilterTag.kind` hodnoty z /api/metadata - picker podle nich dělí sekce. */
        const val KIND_GENRE = "genre"
        const val KIND_TAG = "tag"
    }
}
