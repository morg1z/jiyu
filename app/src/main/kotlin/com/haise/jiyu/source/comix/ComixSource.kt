package com.haise.jiyu.source.comix

import com.haise.jiyu.source.FilterTag
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SGroup
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.source.bodyOrThrow
import com.haise.jiyu.util.parseChapterDate
import com.haise.jiyu.util.rethrowIfControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import javax.inject.Inject
import javax.inject.Singleton

/**
 * comix.to - SPA (Nuxt/Vue) s podepsanĂ˝m API `/api/v1`. NepodepsanĂ˝ dotaz vracĂ­
 * `{"message":"Missing token."}`. Zdroj mĂˇ tĹ™i cesty k datĹŻm, od nejlevnÄ›jĹˇĂ­:
 *
 * 1. **SSR `script#initial-data`** - detail titulu (a vyjĂ­meÄŤnÄ› listing) je v HTML
 *    jako JSON, staÄŤĂ­ Jsoup.
 * 2. **NativnĂ­ podepsanĂ˝ dotaz** - jakmile WebView bÄ›h jednou zachytĂ­ crypto
 *    materiĂˇl (hook na `atob`, viz [ComixScripts.bootstrap]), podepisujeme sami
 *    pĹ™es [ComixCipher] (3-kolovĂˇ substituce + `_` token, `{e:...}` obĂˇlka odpovÄ›di).
 * 3. **WebView capture** - strĂˇnka se naÄŤte se skriptem, kterĂ˝ zachytĂ­
 *    deĹˇifrovanĂ© payloady (listing/strĂˇnky) nebo pĹ™Ă­mo zavolĂˇ `mangaApi.chapters()`
 *    z env bundlu strĂˇnky (kapitoly). PomalĂ©, ale vĹľdy funkÄŤnĂ­ - pouĹľĂ­vĂˇ se jako
 *    fallback a k prvnĂ­mu zachycenĂ­ materiĂˇlu.
 *
 * ObrĂˇzky strĂˇnek deĹˇifruje `ComixImageInterceptor` na image klientovi podle
 * `x-enc-*`/`x-scramble-*` hlaviÄŤek; zdroj jen oznaÄŤĂ­ strĂˇnky fragmenty
 * `#scrambled` (v3 dlaĹľdice, `?v3` flag) a `#enc-scrambled` (kaĹľdĂˇ 4. strĂˇnka,
 * legacy XOR).
 */
@Singleton
class ComixSource @Inject constructor(
    private val client: OkHttpClient,
    private val runner: ComixPageRunner,
) : MangaSource {

    override val id = "comix"
    override val name = "Comix"
    override val homepageUrl get() = BASE
    override val supportsTagFilter get() = true
    // Web ma jediny "score" sort (Popular = Highest rated) - "rating" by byl jen
    // duplikat "popular" se stejnym vysledkem, proto ho nenabizime.
    override val availableSorts get() = setOf("popular", "latest", "title")

    @Volatile private var cipher: ComixCipher? = null

    /** env-*.js bundle se mÄ›nĂ­ jen pĹ™i redeployi webu - reuk pouĹľitĂ­ ĹˇetĹ™Ă­ staĹľenĂ­
     * celĂ©ho main bundlu pĹ™i kaĹľdĂ©m naÄŤtenĂ­ kapitol. */
    @Volatile private var cachedEnvUrl: String? = null

    // ---------- HTTP ----------

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
            .header("Referer", "$BASE/")
            .header("Accept", "*/*")
            .build()
        return client.newCall(req).execute().use { it.bodyOrThrow(url) }
    }

    /**
     * NativnĂ­ podepsanĂ˝ GET na `/api/v1`. VracĂ­ deĹˇifrovanĂ˝ JSON nebo `null`
     * (cipher jeĹˇtÄ› nenĂ­ zachycenĂ˝ / request selhal - pak se cipher zahodĂ­ a
     * pĹ™Ă­ĹˇtĂ­ WebView bÄ›h ho zachytĂ­ znovu, ÄŤerstvĂ˝).
     */
    private fun getSigned(path: String, params: Map<String, List<String>>): JSONObject? {
        val current = cipher ?: return null
        return runCatching {
            val entries = canonicalEntries(params)
            val query = entries.joinToString("&") { (name, value) -> "$name=${value.trim()}" }
            val url = BASE.toHttpUrlOrNull()!!.newBuilder()
                .addPathSegments("api/v1/${path.trimStart('/')}")
                .apply {
                    entries.forEach { (name, value) -> addQueryParameter(name, value) }
                    addQueryParameter("_", current.sign("/api/v1/${path.trimStart('/')}", query))
                }
                .build()
            val root = JSONObject(get(url.toString()))
            if (root.has("e")) JSONObject(current.decrypt(root.getString("e"))) else root
        }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (cipher === current) cipher = null
            null
        }
    }

    /** Query parametry v kanonickĂ© podobÄ› pro podpis: seĹ™azenĂ© klĂ­ÄŤe, opakovanĂ©
     * hodnoty jako `name[i]`, `[]` suffix se odstranĂ­ (schĂ©ma webu). */
    internal fun canonicalEntries(params: Map<String, List<String>>): List<Pair<String, String>> = buildList {
        params.toSortedMap().forEach { (rawName, values) ->
            val name = rawName.removeSuffix("[]")
            if (values.size == 1 && !rawName.endsWith("[]")) {
                add(name to values.single())
            } else {
                values.forEachIndexed { i, v -> add("$name[$i]" to v) }
            }
        }
    }

    private fun adoptMaterial(material: ComixCipherMaterial?) {
        if (material != null && material.isValid()) {
            cipher = ComixCipher(material)
        }
    }

    // ---------- listing ----------

    internal fun browseUrl(page: Int, filter: MangaFilter, query: String?): String =
        "https://comix.to".toHttpUrlOrNull()!!.newBuilder()
            .addPathSegment("browse")
            .apply {
                if (query.isNullOrBlank()) {
                    when (filter.sortBy) {
                        "latest" -> addQueryParameter("order[chapter_updated_at]", "desc")
                        "title" -> addQueryParameter("order[title]", "asc")
                        else -> addQueryParameter("order[score]", "desc")
                    }
                } else {
                    // Webove routovani hledani: `q` + `sort` cte stranka; `keyword` cte API.
                    addQueryParameter("q", query)
                    addQueryParameter("sort", "relevance:desc")
                    addQueryParameter("keyword", query)
                }
                filter.genres.forEach { addQueryParameter("genres_in[]", it) }
                addQueryParameter("content_rating", CONTENT_RATINGS)
                addQueryParameter("page", page.toString())
            }
            .build().toString()

    /** Parametry browse URL prevedene na `/api/v1/manga` dotaz (stejne jmena,
     * `content_rating` se rozdeli carkou, doplni se limit). */
    internal fun mangaParamsFrom(browseUrl: String): Map<String, List<String>> {
        val url = browseUrl.toHttpUrlOrNull() ?: return emptyMap()
        return buildMap {
            for (name in url.queryParameterNames) {
                val values = url.queryParameterValues(name).filterNotNull()
                if (values.isEmpty()) continue
                put(name, if (name == "content_rating") values.flatMap { it.split(',') } else values)
            }
            putIfAbsent("limit", listOf("28"))
        }
    }

    private suspend fun loadList(browseUrl: String): List<SManga> {
        try {
            // 1. Nativni podepsany dotaz - nejrychlejsi, kdyz uz je cipher zachyceny.
            getSigned("manga", mangaParamsFrom(browseUrl))?.let { root ->
                val items = root.optJSONObject("result")?.optJSONArray("items")
                    ?: root.optJSONArray("items")
                if (items != null && items.length() > 0) return items.toMangaList()
            }

            // 2. SSR initial-data v HTML (detail a nektere routy ho server plni).
            val html = runCatching { get(browseUrl) }.getOrNull()
            if (html != null) {
                extractInitialDataItems(Jsoup.parse(html, browseUrl))
                    ?.takeIf { it.length() > 0 }
                    ?.let { return it.toMangaList() }
            }

            // 3. WebView capture - stranka si sama dotahne podepsanym XHR.
            if (html != null) {
                val capture = runCatching {
                    runner.capture(browseUrl, html, ComixScripts.browseCapture(), LAST_RATING)
                }.getOrElse { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    null
                } ?: return emptyList()
                adoptMaterial(capture.material)
                val root = JSONObject(capture.payload)
                val items = root.optJSONObject("result")?.optJSONArray("items")
                    ?: root.optJSONArray("items")
                if (items != null && items.length() > 0) return items.toMangaList()
            }
            return emptyList()
        } catch (e: Exception) {
            e.rethrowIfControl()
            return emptyList()
        }
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) { loadList(browseUrl(page, filter, null)) }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) loadList(browseUrl(page, filter, null))
            else loadList(browseUrl(page, filter, query))
        }

    // ---------- initial-data parsing ----------

    /** `script#initial-data` â†’ `queries` â†’ prvni hodnota s `result.items` / `items`. */
    internal fun extractInitialDataItems(document: Document): JSONArray? {
        val raw = document.selectFirst("script#initial-data")?.data()?.takeIf { it.isNotBlank() } ?: return null
        val queries = runCatching { JSONObject(raw).optJSONObject("queries") }.getOrNull() ?: return null
        for (key in queries.keys()) {
            val value = queries.optJSONObject(key) ?: continue
            val items = value.optJSONObject("result")?.optJSONArray("items")
                ?: value.optJSONArray("items")
            if (items != null && items.length() > 0) return items
        }
        return null
    }

    /** Detail query klic obsahuje "detail"; hodnota je objekt mangy (nekdy pod `result`). */
    internal fun extractInitialDataDetail(document: Document): JSONObject? {
        val raw = document.selectFirst("script#initial-data")?.data()?.takeIf { it.isNotBlank() } ?: return null
        val queries = runCatching { JSONObject(raw).optJSONObject("queries") }.getOrNull() ?: return null
        for (key in queries.keys()) {
            if (!key.contains("detail")) continue
            val value = queries.optJSONObject(key) ?: continue
            val candidate = value.optJSONObject("result") ?: value
            if (candidate.has("hid") || candidate.has("hash_id") || candidate.has("title")) return candidate
        }
        return null
    }

    /** Reader query: hodnota s `result.pages` nebo primo `pages`. */
    internal fun extractInitialDataPages(document: Document): JSONObject? {
        val raw = document.selectFirst("script#initial-data")?.data()?.takeIf { it.isNotBlank() } ?: return null
        val queries = runCatching { JSONObject(raw).optJSONObject("queries") }.getOrNull() ?: return null
        for (key in queries.keys()) {
            val value = queries.optJSONObject(key) ?: continue
            if (value.optJSONObject("result")?.has("pages") == true) return value
            if (value.has("pages")) return JSONObject().put("result", value)
        }
        return null
    }

    // ---------- mapping ----------

    internal fun JSONArray.toMangaList(): List<SManga> =
        (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(::itemToManga) }

    internal fun itemToManga(o: JSONObject): SManga {
        val hid = o.optString("hid").ifBlank { o.optString("hash_id") }
        val poster = o.optJSONObject("poster")
        val cover = poster?.optString("large")?.takeIf { it.isNotBlank() }
            ?: poster?.optString("medium")?.takeIf { it.isNotBlank() }
            ?: poster?.optString("small")?.takeIf { it.isNotBlank() }
        return SManga(
            sourceId = id,
            url = "/title/$hid",
            title = o.optString("title"),
            coverUrl = cover,
            contentType = when (o.optString("type")) {
                "manhwa" -> "MANHWA"
                "manhua" -> "MANHUA"
                "novel" -> "NOVEL"
                else -> "MANGA"
            },
            lastChapter = o.optDouble("latestChapter", Double.NaN)
                .takeUnless { it.isNaN() }?.toFloat(),
            rating = o.optDouble("ratedAvg", Double.NaN).takeUnless { it.isNaN() }
                ?: o.optDouble("rated_avg", Double.NaN).takeUnless { it.isNaN() },
            followCount = o.optInt("followsTotal", 0).takeIf { it > 0 },
            rank = o.optInt("rank", 0).takeIf { it > 0 },
            year = o.optInt("year", 0).takeIf { it > 0 },
        )
    }

    private fun namedList(o: JSONObject, vararg keys: String): List<String> {
        for (key in keys) {
            val arr = o.optJSONArray(key) ?: continue
            val names = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { it.optString("title").ifBlank { it.optString("name") } }
                    ?: arr.optString(i).takeIf { it.isNotBlank() }
            }.filter { it.isNotBlank() }
            if (names.isNotEmpty()) return names
        }
        return emptyList()
    }

    private fun mapStatus(status: String): String? = when (status) {
        "releasing" -> "VychĂˇzĂ­"
        "finished" -> "DokonÄŤeno"
        "discontinued" -> "ZruĹˇeno"
        "on_hiatus" -> "PĹ™eruĹˇeno"
        else -> null
    }

    /** hid je kratky kod pred prvni pomlckou slugu: "/title/lly3j-x" -> "lly3j". */
    private fun hidOf(manga: SManga) = manga.url
        .substringAfter("/title/")
        .substringBefore("/")
        .substringBefore("-")

    // ---------- detail ----------

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val html = get("$BASE/title/${hidOf(manga)}")
            val detail = extractInitialDataDetail(Jsoup.parse(html, BASE)) ?: return@withContext manga
            val poster = detail.optJSONObject("poster")
            val cover = poster?.optString("large")?.takeIf { it.isNotBlank() }
                ?: poster?.optString("medium")?.takeIf { it.isNotBlank() }
                ?: manga.coverUrl
            val synopsis = detail.optString("synopsis").takeIf { it.isNotBlank() }
                ?.let { if ('<' in it) Jsoup.parse(it).text() else it }
            val alternateTitles = detail.optJSONArray("altTitles")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.optString("title")?.takeIf { it.isNotBlank() }
                        ?: arr.optString(i).takeIf { it.isNotBlank() }
                }
            }?.filter { it != manga.title } ?: emptyList()

            manga.copy(
                title = detail.optString("title").takeIf { it.isNotBlank() } ?: manga.title,
                coverUrl = cover,
                description = synopsis ?: manga.description,
                status = mapStatus(detail.optString("status")) ?: manga.status,
                author = namedList(detail, "authors", "author").joinToString(", ").ifBlank { null }
                    ?: manga.author,
                artist = namedList(detail, "artists", "artist").joinToString(", ").ifBlank { null }
                    ?: manga.artist,
                genres = namedList(detail, "genres", "genre").ifEmpty { manga.genres },
                demographic = namedList(detail, "demographics", "demographic").firstOrNull()
                    ?: manga.demographic,
                year = detail.optInt("year", 0).takeIf { it > 0 } ?: manga.year,
                rating = detail.optDouble("ratedAvg", Double.NaN).takeUnless { it.isNaN() }
                    ?: detail.optDouble("rated_avg", Double.NaN).takeUnless { it.isNaN() }
                    ?: manga.rating,
                followCount = detail.optInt("followsTotal", 0).takeIf { it > 0 } ?: manga.followCount,
                rank = detail.optInt("rank", 0).takeIf { it > 0 } ?: manga.rank,
                alternateTitles = alternateTitles.ifEmpty { manga.alternateTitles },
                lastChapter = detail.optDouble("latestChapter", Double.NaN)
                    .takeUnless { it.isNaN() }?.toFloat() ?: manga.lastChapter,
                contentType = when (detail.optString("type")) {
                    "manhwa" -> "MANHWA"; "manhua" -> "MANHUA"; "novel" -> "NOVEL"
                    else -> manga.contentType
                },
            )
        } catch (e: Exception) { e.rethrowIfControl(); manga }
    }

    // ---------- chapters ----------

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val hid = hidOf(manga)
            getSigned("manga/$hid/chapters", chapterParams(1))?.let { first ->
                collectNativeChapters(first, hid, manga.url)?.let { return@withContext it }
            }
            val titleUrl = "$BASE/title/$hid"
            val html = runCatching { get(titleUrl) }.getOrNull() ?: return@withContext emptyList()
            val capture = runCatching {
                runner.capture(
                    titleUrl, html,
                    ComixScripts.chapterFetch(hid, cachedEnvUrl),
                    timeoutMs = CHAPTER_TIMEOUT_MS,
                )
            }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                return@withContext emptyList()
            }
            adoptMaterial(capture.material)
            val payload = JSONObject(capture.payload)
            payload.optString("env").takeIf { it.isNotBlank() }?.let { cachedEnvUrl = it }
            parseChaptersCompact(payload, hid, manga.url)
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    private fun chapterParams(page: Int) = mapOf(
        "limit" to listOf("100"),
        "order[number]" to listOf("desc"),
        "page" to listOf(page.toString()),
    )

    /** Nativni podepsane strankovani kapitol - pokracuje, dokud API hlasi dalsi stranku. */
    private fun collectNativeChapters(first: JSONObject, hid: String, mangaUrl: String): List<SChapter>? {
        val all = mutableListOf<JSONObject>()
        var response = first
        var page = 1
        while (true) {
            val result = response.optJSONObject("result") ?: response
            val items = result.optJSONArray("items") ?: return null
            (0 until items.length()).mapNotNullTo(all) { items.optJSONObject(it) }
            val meta = result.optJSONObject("meta") ?: result.optJSONObject("pagination")
            val lastPage = meta?.optInt("lastPage", 0)?.takeIf { it > 0 }
                ?: meta?.optInt("last_page", 0)?.takeIf { it > 0 }
                ?: page
            val hasNext = meta?.optBoolean("hasNext", false) == true || page < lastPage
            if (!hasNext || items.length() == 0 || page >= 200) break
            page++
            response = getSigned("manga/$hid/chapters", chapterParams(page)) ?: return null
        }
        return all.mapNotNull { chapterToSChapter(it, hid, mangaUrl) }
            .sortedByDescending { it.chapterNumber }
            .takeIf { it.isNotEmpty() }
    }

    internal fun chapterToSChapter(o: JSONObject, hid: String, mangaUrl: String): SChapter? {
        val chapterId = o.optLong("id", 0L).takeIf { it > 0 } ?: return null
        val number = o.optDouble("number", 0.0).toFloat()
        val name = o.optString("name").takeIf { it.isNotBlank() }
        val path = o.optString("url").takeIf { it.isNotBlank() }
            ?: "/title/$hid/$chapterId-chapter-${formatNumber(number)}"
        val group = o.optJSONObject("group")?.optString("name")?.takeIf { it.isNotBlank() }
            ?: if (o.optBoolean("isOfficial")) "Official" else null
        val createdAt = o.optLong("createdAt", 0L)
        val date = when {
            createdAt > 0 -> if (createdAt < SECONDS_LIMIT) createdAt * 1000L else createdAt
            else -> parseChapterDate(o.optString("createdAtFormatted"))
        }
        return SChapter(
            sourceId = id,
            mangaUrl = mangaUrl,
            url = "$BASE$path",
            name = if (name != null) "Chapter ${formatNumber(number)}: $name" else "Chapter ${formatNumber(number)}",
            chapterNumber = number,
            dateUpload = date,
            scanlationGroup = group,
            volume = o.optString("volume").takeIf { it.isNotBlank() },
        )
    }

    /** Kompaktni format z [ComixScripts.chapterFetch]: {prefix, groups, items:[{i,n,u,g,v,t,c,d}]}. */
    internal fun parseChaptersCompact(payload: JSONObject, hid: String, mangaUrl: String): List<SChapter> {
        val items = payload.optJSONArray("items") ?: return emptyList()
        val prefix = payload.optString("prefix")
        val groups = payload.optJSONArray("groups")
        return (0 until items.length()).mapNotNull { i ->
            val c = items.optJSONObject(i) ?: return@mapNotNull null
            val chapterId = c.optLong("i", 0L)
            val number = c.optDouble("n", 0.0).toFloat()
            val suffix = c.optString("u")
            val path = if (prefix.isNotBlank() || suffix.isNotBlank()) prefix + suffix
                else "/title/$hid/$chapterId-chapter-${formatNumber(number)}"
            val group = groups?.optJSONObject(c.optInt("g", -1).takeIf { it >= 0 } ?: -1)
            val groupName = group?.optString("name")?.takeIf { it.isNotBlank() }
                ?: if (group?.optInt("o") == 1) "Official" else null
            val epoch = c.optLong("c", 0L)
            val date = when {
                epoch > 0 -> if (epoch < SECONDS_LIMIT) epoch * 1000L else epoch
                else -> parseChapterDate(c.optString("d"))
            }
            val name = c.optString("t").takeIf { it.isNotBlank() }
            SChapter(
                sourceId = id,
                mangaUrl = mangaUrl,
                url = "$BASE$path",
                name = if (name != null) "Chapter ${formatNumber(number)}: $name" else "Chapter ${formatNumber(number)}",
                chapterNumber = number,
                dateUpload = date,
                scanlationGroup = groupName,
                volume = c.optString("v").takeIf { it.isNotBlank() },
                groups = groupName?.let { listOf(SGroup(it)) } ?: emptyList(),
            )
        }.sortedByDescending { it.chapterNumber }
    }

    // ---------- pages ----------

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val chapterId = chapter.url.substringAfterLast("/").substringBefore("-chapter-")
            // 1. Nativni podepsany dotaz.
            if (chapterId.isNotBlank()) {
                getSigned("chapters/$chapterId", emptyMap())?.let { root ->
                    parsePages(root)?.let { return@withContext it }
                }
            }
            // 2. SSR initial-data reader stranky.
            val html = runCatching { get(chapter.url) }.getOrNull() ?: return@withContext emptyList()
            extractInitialDataPages(Jsoup.parse(html, chapter.url))
                ?.let { parsePages(it) }
                ?.let { return@withContext it }
            // 3. WebView capture.
            val capture = runCatching {
                runner.capture(chapter.url, html, ComixScripts.PAGES_CAPTURE)
            }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                return@withContext emptyList()
            }
            adoptMaterial(capture.material)
            parsePages(JSONObject(capture.payload)) ?: emptyList()
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    /**
     * `result.pages = {baseUrl, items:[{url, s}]}`. `s==1` (nebo `v3` flag na URL)
     * oznacuje 5Ă—5 tile-scramble - server pak hlaviÄŤky vracĂ­ jen s `v3` query flagem.
     * Ostatni muze byt legacy byte-XOR na kazde ctvrte strance - ta potrebuje `Origin`
     * hlavicku (presny opak), coz zaridi fragment `#enc-scrambled` v interceptoru.
     */
    internal fun parsePages(root: JSONObject): List<Page>? {
        val result = root.optJSONObject("result") ?: root
        val pagesObj = result.optJSONObject("pages") ?: return null
        val baseUrl = pagesObj.optString("baseUrl").trimEnd('/')
        val items = pagesObj.optJSONArray("items") ?: return null
        return (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i)
            val rawUrl = item?.optString("url") ?: items.optString(i).takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val full = if (rawUrl.startsWith("http", ignoreCase = true) || baseUrl.isBlank()) rawUrl
                else "$baseUrl/${rawUrl.trimStart('/')}"
            val parsed = full.toHttpUrlOrNull()
            val isV3 = item?.optInt("s", 0) == 1 ||
                parsed?.queryParameterNames?.contains("v3") == true
            val isLegacy = !isV3 && (i + 1) % 4 == 0
            val imageUrl = when {
                isV3 -> {
                    val flagged = if (parsed == null || parsed.queryParameterNames.contains("v3")) full
                        else parsed.newBuilder().addQueryParameter("v3", null).build().toString()
                    "$flagged#${ComixScramble.SCRAMBLED_FRAGMENT}"
                }
                isLegacy -> "$full#${ComixScramble.LEGACY_FRAGMENT}"
                else -> full
            }
            Page(index = i, url = full, imageUrl = imageUrl)
        }
    }

    // ---------- tags ----------

    /** Kuratovane zanry comix.to, klicovane numericke id pro `genres_in[]`
     * (overeno proti /api/v1/tags/search?type=genre). */
    override suspend fun getAvailableTags(): List<FilterTag> = GENRE_TAGS

    internal fun formatNumber(n: Float): String =
        if (n == n.toInt().toFloat()) n.toInt().toString() else n.toString()

    companion object {
        private const val BASE = "https://comix.to"

        /** Bezpecne + sugestivni (appka zadny per-source rating filtr neresiduuje). */
        private const val CONTENT_RATINGS = "safe,suggestive"
        private const val LAST_RATING = "suggestive"

        /** Kapitol byva i tisice - WebView bÄ›h neni casove omezen standardnim limitem. */
        private const val CHAPTER_TIMEOUT_MS = 600_000L
        private const val SECONDS_LIMIT = 10_000_000_000L

        private val GENRE_TAGS = listOf(
            FilterTag("6", "Action"), FilterTag("87264", "Adult"), FilterTag("7", "Adventure"),
            FilterTag("8", "Boys Love"), FilterTag("9", "Comedy"), FilterTag("10", "Crime"),
            FilterTag("11", "Drama"), FilterTag("87265", "Ecchi"), FilterTag("12", "Fantasy"),
            FilterTag("13", "Girls Love"), FilterTag("40", "Harem"), FilterTag("87266", "Hentai"),
            FilterTag("14", "Historical"), FilterTag("15", "Horror"), FilterTag("16", "Isekai"),
            FilterTag("17", "Magical Girls"), FilterTag("87267", "Mature"), FilterTag("18", "Mecha"),
            FilterTag("19", "Medical"), FilterTag("20", "Mystery"), FilterTag("21", "Philosophical"),
            FilterTag("22", "Psychological"), FilterTag("23", "Romance"), FilterTag("24", "Sci-Fi"),
            FilterTag("25", "Slice of Life"), FilterTag("87268", "Smut"), FilterTag("26", "Sports"),
            FilterTag("27", "Superhero"), FilterTag("28", "Thriller"), FilterTag("29", "Tragedy"),
            FilterTag("30", "Wuxia"),
        )
    }
}
