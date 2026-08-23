package com.haise.jiyu.source.comick

import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.source.LanguageMap
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SGroup
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.interceptor.CloudflareInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zdroj napojený na veřejné REST API ComicK (https://api.comick.dev - dřívější
 * api.comick.fun je mrtvá doména, endpoint pro detail/kapitoly je teď pod
 * "/comic/" místo "/manga/").
 *
 * ComicK poskytuje veřejné API bez nutnosti klíče a explicitně povoluje
 * jeho využití třetími stranami. Pokrývá přes 100 000 titulů (manga,
 * manhwa, manhua) s překlady do desítek jazyků.
 *
 * Klíčové entity v API:
 *  - slug  = URL-friendly název ("one-piece"), používá se v adrese mangy
 *  - hid   = hash ID ("abc123"), používá se pro kapitoly a stránky
 */
@Singleton
class ComicKSource @Inject constructor(
    private val client: OkHttpClient,
    private val settings: SettingsRepository,
) : MangaSource {

    override val id   = "comick"
    override val name = "ComicK"
    override val homepageUrl get() = "https://comick.io"

    private val apiBase   = "https://api.comick.dev"
    private val coverBase = "https://meo.comick.pictures"

    // In-memory cache pro /genre - stejny seznam pro celou appku po celou dobu behu,
    // nema smysl ho stahovat znovu pri kazdem otevreni filtru (stejny vzor jako
    // ComicKChapterResolver).
    private var cachedGenres: List<ComicKGenreOption>? = null

    // ─── Vyhledávání & browse ────────────────────────────────────────────────

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            val q = URLEncoder.encode(query, "UTF-8")
            parseComicList(getArray("$apiBase/v1.0/search?q=$q&limit=20&page=$page"))
        }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            val sort = when (filter.sortBy) {
                "latest" -> "uploaded"
                "rating" -> "rating"
                "title"  -> "title"
                else     -> "follow"
            }
            parseComicList(getArray("$apiBase/v1.0/search?sort=$sort&limit=20&page=$page"))
        }

    /**
     * Kompletni taxonomie zanru/tagu z `/genre` - "group" pole rozlisuje "Genre"
     * (zanry v uzsim smyslu) od ostatnich skupin (Format/Theme/Content Warning/...),
     * ktere appka zobrazuje jako "Tagy" - stejne rozdeleni, jake ma ComicKuv vlastni
     * filtr. Overeno zive 2026-08-14, viz [searchAdvanced].
     */
    suspend fun getGenreList(): List<ComicKGenreOption> = withContext(Dispatchers.IO) {
        cachedGenres?.let { return@withContext it }
        val arr = getArray("$apiBase/genre")
        val result = (0 until arr.length()).mapNotNull { i ->
            val obj = arr.getJSONObject(i)
            val name = obj.optString("name").ifBlank { return@mapNotNull null }
            val slug = obj.optString("slug").ifBlank { return@mapNotNull null }
            ComicKGenreOption(name = name, slug = slug, group = obj.optString("group").ifBlank { "Genre" })
        }
        cachedGenres = result
        result
    }

    /**
     * Rozsirene hledani se vsemi filtry, ktere `/v1.0/search` podporuje - overeno
     * zive proti kazdemu parametru zvlast (uzivatelsky pozadavek "vsechny filtry
     * co ma ComicK"): genres/excludes/tags/excluded_tags (slug retezce, opakovatelne
     * pro AND), demographic (1-4, opakovatelne), country (jp/kr/cn/others,
     * opakovatelne), status (1-4), content_rating (safe/suggestive/erotica),
     * minimum (min. pocet kapitol), from/to (rok vydani).
     */
    suspend fun searchAdvanced(page: Int, filters: ComicKSearchFilters): List<SManga> =
        withContext(Dispatchers.IO) {
            val sort = when (filters.sortBy) {
                "latest"         -> "uploaded"
                "rating"         -> "rating"
                "average_rating" -> "average_rating"
                "title"          -> "title"
                else             -> "follow"
            }
            val url = buildString {
                append("$apiBase/v1.0/search?sort=$sort&limit=20&page=$page")
                if (filters.query.isNotBlank()) append("&q=${URLEncoder.encode(filters.query, "UTF-8")}")
                filters.genres.forEach { append("&genres=${URLEncoder.encode(it, "UTF-8")}") }
                filters.tags.forEach { append("&tags=${URLEncoder.encode(it, "UTF-8")}") }
                filters.demographics.forEach { append("&demographic=$it") }
                filters.countries.forEach { append("&country=${URLEncoder.encode(it, "UTF-8")}") }
                filters.status?.let { append("&status=$it") }
                filters.contentRating?.let { append("&content_rating=${URLEncoder.encode(it, "UTF-8")}") }
                filters.minChapters?.let { append("&minimum=$it") }
                filters.yearFrom?.let { append("&from=$it") }
                filters.yearTo?.let { append("&to=$it") }
            }
            parseComicList(getArray(url))
        }

    // ─── Detail mangy ────────────────────────────────────────────────────────

    /**
     * Doplní popis a stav vydávání.
     * manga.url je ve formátu "$apiBase/manga/{slug}".
     */
    override suspend fun getMangaDetails(manga: SManga): SManga =
        withContext(Dispatchers.IO) {
            val slug = manga.url.substringAfterLast("/")
            val json = getObject(
                "$apiBase/comic/$slug",
                notFoundMessage = "ComicK tenhle titul přes veřejné API neposkytuje (časté u 18+ obsahu)",
            )
            val comic = json.getJSONObject("comic")

            val desc = comic.optString("desc").ifBlank { null }
            val status = when (comic.optInt("status", -1)) {
                1    -> "Vychází"
                2    -> "Dokončeno"
                3    -> "Zrušeno"
                4    -> "Přerušeno"
                else -> null
            }
            val year = comic.optInt("year", 0).takeIf { it > 0 }

            val authors = json.optJSONArray("authors")
            val author = if (authors != null && authors.length() > 0)
                authors.getJSONObject(0).optString("name").ifBlank { null }
            else null

            val genres = mutableListOf<String>()
            val tagsArr = json.optJSONArray("genres") ?: json.optJSONArray("tags")
            if (tagsArr != null) {
                for (i in 0 until tagsArr.length()) {
                    val name = tagsArr.optJSONObject(i)?.optString("name")
                        ?: tagsArr.optString(i)
                    if (!name.isNullOrBlank()) genres.add(name)
                }
            }

            // final_chapter/final_volume/demographic/translation_completed/has_anime umi ComicK
            // vracet jako JSON null (ne jako chybejici klic) - stejny bug jako u vol/title vyse,
            // proto isNull() guardy misto primeho optString()/optBoolean() s defaultem.
            val demographic = if (json.isNull("demographic")) null else json.optString("demographic").ifBlank { null }
            val translationCompleted = if (comic.has("translation_completed") && !comic.isNull("translation_completed")) comic.optBoolean("translation_completed") else null
            val hasAnime = if (comic.has("has_anime") && !comic.isNull("has_anime")) comic.optBoolean("has_anime") else null
            val finalChapterRaw = if (comic.isNull("final_chapter")) null else comic.optString("final_chapter").ifBlank { null }
            val finalVolumeRaw = if (comic.isNull("final_volume")) null else comic.optString("final_volume").ifBlank { null }
            val finalChapter = finalChapterRaw?.let { chap ->
                if (finalVolumeRaw != null) "Svazek $finalVolumeRaw, kapitola $chap" else "Kapitola $chap"
            }

            // bayesian_rating je na rozdil od user_follow_count/follow_rank STRING v JSON
            // ("9.19"), proto isNull() guard + optString misto optDouble (viz zavedeny
            // org.json optString-na-JSON-null bug - stejny pattern jako u demographic vyse).
            val rating = if (comic.isNull("bayesian_rating")) null else comic.optString("bayesian_rating").toDoubleOrNull()
            val followCount = comic.optInt("user_follow_count", 0).takeIf { it > 0 }
            val rank = comic.optInt("follow_rank", 0).takeIf { it > 0 }

            // md_titles - stejne pole, ktere getTitleInfo() pouziva pro cross-source parovani,
            // tady jen bereme vsechny nazvy krome toho, co uz appka pouziva jako hlavni titul.
            val altTitlesArr = comic.optJSONArray("md_titles")
            val alternateTitles = mutableListOf<String>()
            if (altTitlesArr != null) {
                for (i in 0 until altTitlesArr.length()) {
                    val t = altTitlesArr.optJSONObject(i) ?: continue
                    val titleText = if (t.isNull("title")) null else t.optString("title").ifBlank { null }
                    if (titleText != null && titleText != manga.title) alternateTitles.add(titleText)
                }
            }

            manga.copy(
                description = desc,
                status      = status,
                author      = author,
                genres      = genres,
                year        = year,
                contentType = contentTypeFromCountry(comic.optString("country")),
                demographic = demographic,
                translationCompleted = translationCompleted,
                hasAnime = hasAnime,
                finalChapter = finalChapter,
                rating = rating,
                followCount = followCount,
                rank = rank,
                alternateTitles = alternateTitles.distinct().take(8),
            )
        }

    /**
     * ComicK titulu vrátí seznam alternativních (anglických/přepsaných) názvů, nejdřív
     * ten "is_default" - viz [ComicKChapterResolver], který jinak proti ostatním zdrojům
     * hledá jen podle `comic.title`, což u řady titulů (např. Solo Leveling → ComicK
     * primárně eviduje pod "I am the only the one who levels up", "Solo Leveling" je jen
     * jeden z `md_titles`, ale s `is_default: true`) selže úplně - žádný zdroj nenajde,
     * i když ho reálně máme.
     *
     * Vrací i `content_rating` ("safe"/"suggestive"/"erotica"/"pornographic" - stejná
     * škála, jakou MangaDex/MangaFire zdroje používají pro svůj vlastní safe/suggestive
     * filtr) ze STEJNÉ odpovědi, aby [ComicKChapterResolver] mohl rozhodnout o zahrnutí
     * isAdult zdrojů per titul bez dalšího requestu navíc.
     */
    suspend fun getTitleInfo(mangaUrl: String): ComicKTitleInfo =
        withContext(Dispatchers.IO) {
            val slug = mangaUrl.substringAfterLast("/")
            val json = getObject("$apiBase/comic/$slug")
            val comic = json.getJSONObject("comic")

            val fallbackTitle = if (comic.isNull("title")) null else comic.optString("title").ifBlank { null }
            val titlesArr = comic.optJSONArray("md_titles")
            val alternates = mutableListOf<Pair<String, Boolean>>()
            if (titlesArr != null) {
                for (i in 0 until titlesArr.length()) {
                    val t = titlesArr.optJSONObject(i) ?: continue
                    if (t.optString("lang") !in ROMANIZED_LANGS) continue
                    val title = if (t.isNull("title")) null else t.optString("title").ifBlank { null }
                    if (title != null) alternates.add(title to t.optBoolean("is_default", false))
                }
            }
            val ordered = alternates.sortedByDescending { it.second }.map { it.first }
            val contentRating = if (comic.isNull("content_rating")) null else comic.optString("content_rating").ifBlank { null }
            ComicKTitleInfo(
                alternateTitles = (ordered + listOfNotNull(fallbackTitle)).distinct(),
                contentRating = contentRating,
            )
        }

    /**
     * Vrátí metadata překladatelské skupiny + seznam titulů, které přeložila
     * (viz [Sub-projekt 4 v design docu]). `comics[]` v odpovědi má stejný
     * tvar jako položky `/v1.0/search`, proto se parsuje stejnou [parseComicList].
     */
    suspend fun getGroup(slug: String): GroupInfo =
        withContext(Dispatchers.IO) {
            val json = getObject("$apiBase/group/$slug")
            val group = json.optJSONObject("group") ?: JSONObject()
            GroupInfo(
                title = group.optString("title").ifBlank { slug },
                followCount = group.optInt("follow_count", 0),
                chapterCount = group.optInt("chapter_count", 0),
                comics = parseComicList(json.optJSONArray("comics") ?: JSONArray()),
            )
        }

    /**
     * Vsechny historicke obalky titulu (vsechny svazky, vcetne starsich/fanouskovskych
     * uploadu) - viz uzivatelsky pozadavek "galerie obalek jako na ComicK webu".
     *
     * ComicKovo vlastni API (`apiBase`) tohle NEMA - "md_covers" v odpovedi `/comic/{slug}`
     * vraci jen JEDNU aktualni obalku na svazek. Overeno zive: cela galerie je ve
     * skutecnosti data z MangaDexu, ktere ComicK jen zobrazuje na strance
     * `comick.io/comic/{slug}/cover` (jednotne cislo - "/covers" 404uje). ComicKovo API
     * navic nikde neuvadi MangaDex ID titulu primo (proverovano v poli `links`), takze se
     * musi vytahnout regexem z te HTML stranky - az pak jde zavolat MangaDexi VEREJNE,
     * dokumentovane Cover Art API pro cisty strukturovany seznam.
     *
     * Zamerne jen pro ComicK: pro jine zdroje by se MangaDex ID muselo hledat fuzzy
     * shodou nazvu, coz nese realne riziko spatneho parovani u podobnych/obecnych nazvu.
     */
    suspend fun getCoverGallery(mangaUrl: String): List<SCover> = withContext(Dispatchers.IO) {
        try {
            val slug = mangaUrl.substringAfterLast("/")
            val html = requestBuilder("$homepageUrl/comic/$slug/cover").build().let { req ->
                client.newCall(req).execute().use { it.body?.string().orEmpty() }
            }
            val mangaDexId = Regex("""uploads\.mangadex\.org/covers/([a-f0-9-]{36})/""")
                .find(html)?.groupValues?.get(1) ?: return@withContext emptyList()

            val json = getObject("https://api.mangadex.org/cover?manga[]=$mangaDexId&limit=100")
            val data = json.optJSONArray("data") ?: return@withContext emptyList()
            (0 until data.length()).mapNotNull { i ->
                val attrs = data.getJSONObject(i).optJSONObject("attributes") ?: return@mapNotNull null
                val fileName = attrs.optString("fileName").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SCover(
                    // isNull() guard: org.json.optString() na poli s JSON hodnotou null (ne
                    // chybejicim poli) vraci doslovny retezec "null", ne prazdny string.
                    volume = if (attrs.isNull("volume")) null else attrs.optString("volume").ifBlank { null },
                    imageUrl = "https://uploads.mangadex.org/covers/$mangaDexId/$fileName",
                )
            }.sortedByDescending { it.volume?.toFloatOrNull() ?: -1f }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Komentáře pod titulem (uživatelský požadavek "stáhnout k sobě ComicK komentáře") -
     * jen ke čtení, appka nemá napojený ComicK účet potřebný pro psaní nových. Ověřeno
     * živě: `GET /comment/comic/{hid}?page=N` vrací `{comments, total, remaining}`, kde
     * `remaining > 0` = jsou další stránky. Odpovědi (`content`/`parsed`) obsahují syrové
     * HTML (`<br>`, `<p>`, entity jako `&#x26;`) - Jsoup ho převádí na čistý text, appka
     * nikde jinde HTML nevykresluje. Odpovědi jsou navíc jen jednu úroveň vnořené
     * (`other_comments` na kořenovém komentáři) - stejně jako to ukazuje ComicK web.
     */
    suspend fun getComments(mangaUrl: String, page: Int): ComicKCommentsPage =
        withContext(Dispatchers.IO) {
            val slug = mangaUrl.substringAfterLast("/")
            val detailJson = getObject(
                "$apiBase/comic/$slug",
                notFoundMessage = "ComicK tenhle titul přes veřejné API neposkytuje (časté u 18+ obsahu)",
            )
            val hid = detailJson.getJSONObject("comic").getString("hid")
            val json = getObject("$apiBase/comment/comic/$hid?page=$page")
            val arr = json.optJSONArray("comments") ?: JSONArray()
            ComicKCommentsPage(
                comments = (0 until arr.length()).mapNotNull { i -> commentFromJson(arr.getJSONObject(i)) },
                total = json.optInt("total", 0),
                hasMore = json.optInt("remaining", 0) > 0,
            )
        }

    private fun commentFromJson(json: JSONObject): ComicKComment? {
        val id = json.optLong("id", -1L).takeIf { it >= 0 } ?: return null
        val rawText = (json.optString("parsed").ifBlank { json.optString("content") })
        val identities = json.optJSONObject("identities")
        val traits = identities?.optJSONObject("traits")
        val repliesArr = json.optJSONArray("other_comments") ?: JSONArray()
        return ComicKComment(
            id = id,
            content = Jsoup.parse(rawText).text(),
            username = traits?.optString("username")?.trim()?.ifBlank { null } ?: "?",
            avatarUrl = traits?.optString("gravatar")?.ifBlank { null },
            upCount = json.optInt("up_count", 0),
            downCount = json.optInt("down_count", 0),
            createdAt = parseIso(json.optString("created_at")),
            replies = (0 until repliesArr.length()).mapNotNull { i -> commentFromJson(repliesArr.getJSONObject(i)) },
        )
    }

    /**
     * "Reader-picked" doporučení podobných titulů (uživatelský požadavek "stáhnout k sobě
     * ComicK Recommendations"). Ověřeno živě: `GET /comic/{hid}/recommendations` vrací ploché
     * pole `[{up, down, total, relates: {...}}]`, kde `relates` má stejný tvar jako položka
     * z `/v1.0/search` - proto jde znovupoužít [comicFromJson]. Řazeno podle `up` sestupně,
     * stejně jako to ukazuje ComicK web (počet palců nahoru u každé karty).
     */
    suspend fun getRecommendations(mangaUrl: String): List<ComicKRecommendation> =
        withContext(Dispatchers.IO) {
            val slug = mangaUrl.substringAfterLast("/")
            val detailJson = getObject(
                "$apiBase/comic/$slug",
                notFoundMessage = "ComicK tenhle titul přes veřejné API neposkytuje (časté u 18+ obsahu)",
            )
            val hid = detailJson.getJSONObject("comic").getString("hid")
            val arr = getArray("$apiBase/comic/$hid/recommendations")
            (0 until arr.length()).mapNotNull { i ->
                val item = arr.getJSONObject(i)
                val relates = item.optJSONObject("relates") ?: return@mapNotNull null
                val manga = comicFromJson(relates) ?: return@mapNotNull null
                ComicKRecommendation(manga = manga, upCount = item.optInt("up", 0))
            }.sortedByDescending { it.upCount }
        }

    private var cachedTop: TopFeed? = null

    /**
     * ComicK domovská data (Sub-projekt: Home Feed) - jeden request vrátí data
     * pro všech 5 sekcí naráz (~3 MB), proto se cachuje po dobu běhu appky
     * (stejný vzor jako [ComicKChapterResolver]'s cache) - Home i "zobrazit vše"
     * obrazovky sdílí jedno stažení, ne request na sekci.
     */
    suspend fun getTop(): TopFeed =
        withContext(Dispatchers.IO) {
            cachedTop?.let { return@withContext it }
            val json = getObject("$apiBase/top")
            val windows = listOf("7", "30", "90")
            fun windowMap(key: String): Map<String, List<SManga>> {
                val obj = json.optJSONObject(key) ?: JSONObject()
                return windows.associateWith { w -> parseComicList(obj.optJSONArray(w) ?: JSONArray()) }
            }
            val feed = TopFeed(
                recentlyAdded     = parseComicList(json.optJSONArray("news") ?: JSONArray()),
                completed         = parseComicList(json.optJSONArray("completions") ?: JSONArray()),
                popularNew        = windowMap("topFollowNewComics"),
                mostRecentPopular = windowMap("topFollowComics"),
                recentReviews     = parseReviewList(json.optJSONArray("recentReviews") ?: JSONArray()),
            )
            cachedTop = feed
            feed
        }

    /**
     * Feed posledních nahraných kapitol napříč VŠEMI tituly (Updates tab) -
     * na rozdíl od [getTop] se nekešuje, každé přepnutí `order` nebo scroll
     * dolů je nový request. `limit` parametr API spolehlivě neomezuje počet
     * položek (ověřeno živě) - konec seznamu pozná appka jen podle prázdné
     * odpovědi, ne podle magického čísla.
     */
    /**
     * "Preferences" filtr z uzivatelskeho pozadavku (Type/Demographic/Mature Content,
     * presne jako ma ComicK vlastni Preferences stranka) - overeno zive, ze /chapter
     * tyhle parametry v query stringu tise IGNORUJE (jedina vyjimka je content_rating,
     * ale ten filtrujeme tady stejne kvuli jednotne logice pro vsechny 3 sekce), takze
     * se filtruje az tady nad uz stazenou strankou dat, ktera md_comics uz obsahuji.
     */
    suspend fun getUpdates(order: String, page: Int): List<ChapterUpdate> =
        withContext(Dispatchers.IO) {
            val langCode = LanguageMap.toMangaDexCode(settings.sourceLanguage.first())
            val allowedCountries = settings.comickUpdatesCountries.first()
            val allowedDemographics = settings.comickUpdatesDemographics.first()
            val matureFlags = settings.comickUpdatesMatureFlags.first()
            val arr = getArray("$apiBase/chapter?lang=$langCode&order=$order&page=$page")
            (0 until arr.length()).mapNotNull { i ->
                val json = arr.getJSONObject(i)
                val comicJson = json.optJSONObject("md_comics") ?: return@mapNotNull null

                val countryKey = comicJson.optString("country").let { if (it in setOf("jp", "kr", "cn")) it else "others" }
                if (countryKey !in allowedCountries) return@mapNotNull null
                val demographicKey = comicJson.optInt("demographic", 0).toString()
                if (demographicKey !in allowedDemographics) return@mapNotNull null
                val contentRating = comicJson.optString("content_rating", "safe")
                val violenceRating = comicJson.optString("violence_rating", "none")
                val isBlockedMature =
                    (contentRating == "suggestive" && "suggestive" !in matureFlags) ||
                        (contentRating == "erotica" && "adult" !in matureFlags) ||
                        (violenceRating == "graphic" && "violence" !in matureFlags)
                if (isBlockedMature) return@mapNotNull null

                val comic = comicFromJson(comicJson) ?: return@mapNotNull null
                val chapter = chapterFromJson(json, comic.url) ?: return@mapNotNull null
                ChapterUpdate(
                    chapter = chapter,
                    comic = comic,
                    upCount = json.optInt("up_count", 0),
                    commentCount = json.optInt("comment_count", 0),
                )
            }
        }

    private fun parseReviewList(arr: JSONArray): List<ReviewItem> =
        (0 until arr.length()).mapNotNull { i -> reviewFromJson(arr.getJSONObject(i)) }

    private fun reviewFromJson(json: JSONObject): ReviewItem? {
        val content = json.optString("content").ifBlank { return null }
        val comicJson = json.optJSONObject("md_comics") ?: return null
        val comic = comicFromJson(comicJson) ?: return null
        val title = if (json.isNull("title")) null else json.optString("title").ifBlank { null }
        val authorName = json.optJSONObject("identities")
            ?.optJSONObject("traits")
            ?.optString("username")
            ?.ifBlank { null }
        return ReviewItem(title = title, content = content, authorName = authorName, comic = comic)
    }

    // ─── Kapitoly ────────────────────────────────────────────────────────────

    /**
     * Stáhne kompletní seznam kapitol NAPŘÍČ VŠEMI JAZYKY (ne jen jedním).
     * API vyžaduje hid (ne slug) pro endpoint /manga/{hid}/chapters,
     * proto nejdřív načteme detail mangy abychom hid získali.
     * Prochází stránky po 60 dokud API nevrátí méně výsledků.
     *
     * Dřív se filtrovalo přes `lang=` podle SettingsRepository.sourceLanguage - to je ale
     * nastavení PŘEKLADOVÉ funkce (z jakého jazyka OCR čte text), s katalogem kapitol ComicK
     * nemá nic společného. Efekt: titul, jehož překlad do zvoleného jazyka (výchozí "en")
     * zaostává za originálem, měl v appce synchronizovaný jen zlomek svých kapitol - "celkem
     * kapitol" (viz LibraryScreen.progressPercentFor - počítá UNIKÁTNÍ čísla kapitol napříč
     * všemi jazyky, duplicity už řeší SQL GROUP BY) tak vycházelo směšně nízké/špatné (např.
     * "100 % přečteno" u kapitoly 1 z titulu, který má ve skutečnosti 150+ kapitol - nahlásil
     * uživatel). Bez filtru appka vidí VŠECHNA existující čísla kapitol bez ohledu na jazyk
     * překladu (skutečné čtení stejně vždy jde přes zvlášť vyhledaný reálný zdroj, ne přímo
     * přes ComicK - viz ReaderViewModel.loadChapter, blokuje sourceId=="comick").
     */
    override suspend fun getChapterList(manga: SManga): List<SChapter> =
        withContext(Dispatchers.IO) {
            // Krok 1: získat hid z detailu mangy
            val slug = manga.url.substringAfterLast("/")
            val detailJson = getObject(
                "$apiBase/comic/$slug",
                notFoundMessage = "ComicK tenhle titul přes veřejné API neposkytuje (časté u 18+ obsahu)",
            )
            val hid = detailJson.getJSONObject("comic").getString("hid")

            // Krok 2: stránkovat přes všechny kapitoly
            val chapters = mutableListOf<SChapter>()
            var page = 1
            val pageSize = 60

            while (true) {
                val url = "$apiBase/comic/$hid/chapters?page=$page&limit=$pageSize"
                val json = getObject(url)
                val arr = json.optJSONArray("chapters") ?: break

                for (i in 0 until arr.length()) {
                    chapterFromJson(arr.getJSONObject(i), manga.url)
                        ?.let { chapters.add(it) }
                }

                // Méně výsledků než pageSize = poslední stránka
                if (arr.length() < pageSize) break
                page++
            }

            chapters
        }

    // ─── Stránky kapitoly ────────────────────────────────────────────────────

    /**
     * Stáhne seznam stránek kapitoly.
     * chapter.url je ve formátu "$apiBase/chapter/{hid}".
     * Obrázky jsou hostované na meo.comick.pictures/{b2key}.
     */
    override suspend fun getPageList(chapter: SChapter): List<Page> =
        withContext(Dispatchers.IO) {
            val chHid = chapter.url.substringAfterLast("/")
            val json = getObject("$apiBase/chapter/$chHid")
            val images = json.getJSONObject("chapter").getJSONArray("md_images")

            (0 until images.length()).map { i ->
                val img = images.getJSONObject(i)
                val b2key = img.getString("b2key")
                val imageUrl = "$coverBase/$b2key"
                Page(index = i, url = imageUrl, imageUrl = imageUrl)
            }
        }

    // ─── Privátní pomocné funkce ─────────────────────────────────────────────

    /**
     * api.comick.dev je za Cloudflare a bez prohlížečového User-Agentu vrací 403
     * "Just a moment..." (WAF pravidlo na základě UA, ne skutečná JS výzva) - zjištěno
     * auditem 2026-07-27. S touhle hlavičkou requesty prochází bez CloudflareInterceptor
     * (rychlejší a spolehlivější než čekat na jeho WebView-based řešení výzvy).
     */
    private fun requestBuilder(url: String) = Request.Builder().url(url)
        .header("User-Agent", CloudflareInterceptor.CHROME_UA)

    private fun getArray(url: String): JSONArray {
        val request = requestBuilder(url).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) { "ComicK API chyba ${response.code}: $url" }
            return JSONArray(body)
        }
    }

    private fun getObject(url: String, notFoundMessage: String? = null): JSONObject {
        val request = requestBuilder(url).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 404 && notFoundMessage != null) {
                check(response.isSuccessful) { notFoundMessage }
            } else {
                check(response.isSuccessful) { "ComicK API chyba ${response.code}: $url" }
            }
            return JSONObject(body)
        }
    }

    /** Převede jeden objekt z výsledků hledání na SManga. */
    private fun parseComicList(arr: JSONArray): List<SManga> =
        (0 until arr.length()).mapNotNull { i -> comicFromJson(arr.getJSONObject(i)) }

    /**
     * Jeden komiks z `/v1.0/search`, `/group/{slug}`'s `comics[]`, i `/top`'s
     * `news`/`completions`/`topFollowNewComics`/`topFollowComics` - všechny mají
     * stejný tvar položky, proto jedna sdílená funkce.
     */
    private fun comicFromJson(comic: JSONObject): SManga? {
        val title = comic.optString("title").ifBlank { return null }
        val slug  = comic.optString("slug").ifBlank { return null }

        // Titulní obrázek: první položka md_covers s neprázdným b2key
        val coverUrl = comic.optJSONArray("md_covers")
            ?.let { covers ->
                (0 until covers.length()).firstNotNullOfOrNull { j ->
                    covers.getJSONObject(j).optString("b2key").ifBlank { null }
                }
            }
            ?.let { b2key -> "$coverBase/$b2key" }

        // isNull() guard: stejny zavedeny bug jako u demographic/final_chapter jinde v tomhle
        // souboru - org.json.optString() na poli s JSON hodnotou null vraci doslovny "null".
        val lastChapter = if (comic.isNull("last_chapter")) null else comic.optDouble("last_chapter").takeIf { !it.isNaN() }?.toFloat()

        return SManga(
            sourceId    = id,
            url         = "$apiBase/comic/$slug",
            title       = title,
            coverUrl    = coverUrl,
            contentType = contentTypeFromCountry(comic.optString("country")),
            lastChapter = lastChapter,
        )
    }

    /** ComicK nema vlastni "contentType" pole - odvozujeme ho z puvodu (jp/kr/cn). internal kvuli testu. */
    internal fun contentTypeFromCountry(country: String): String = when (country) {
        "jp"  -> "MANGA"
        "kr"  -> "MANHWA"
        "cn"  -> "MANHUA"
        else  -> "MANGA"
    }

    /** Převede jeden objekt kapitoly na SChapter, nebo null pokud chybí hid. */
    private fun chapterFromJson(json: JSONObject, mangaUrl: String): SChapter? {
        val chHid = json.optString("hid").ifBlank { return null }
        val chap  = json.optString("chap", "0")
        // ComicK API vraci "vol"/"title" jako JSON null (ne jako chybejici klic) -
        // org.json.optString() na JSONObject.NULL vraci doslovny retezec "null",
        // proto je nutne nejdriv zkontrolovat isNull(), ne az .ifBlank {}.
        val vol   = if (json.isNull("vol")) null else json.optString("vol").ifBlank { null }
        val title = if (json.isNull("title")) null else json.optString("title").ifBlank { null }

        val chapterNum = chap.toFloatOrNull() ?: 0f
        val name = buildString {
            if (vol != null) append("Vol.$vol ")
            append("Ch.$chap")
            if (!title.isNullOrBlank()) append(" – $title")
        }

        val groups = parseGroups(json)

        return SChapter(
            sourceId        = id,
            mangaUrl        = mangaUrl,
            url             = "$apiBase/chapter/$chHid",
            name            = name,
            chapterNumber   = chapterNum,
            dateUpload      = parseIso(json.optString("created_at")),
            volume          = vol,
            scanlationGroup = groups.joinToString(", ") { it.name }.ifBlank { null },
            groups          = groups,
        )
    }

    /**
     * "group_name" je autoritativní seznam jmen/pořadí skupin u kapitoly.
     * "md_chapters_groups" ho jen doplňuje o slug a hezčí zobrazovací jméno, ale
     * ověřeno živě na API: může být kratší, nebo úplně `[]`, i když group_name
     * prázdné není (např. smazaná/anonymizovaná skupina u starší kapitoly).
     * Párujeme podle indexu; chybějící index = jen jméno z group_name bez slugu.
     *
     * Stejný bug jako u vol/title v [chapterFromJson]: "group_name[i]" i
     * "md_groups.title"/"md_groups.slug" umí ComicK vracet jako JSON null, proto
     * isNull() guard před optString() i tady. internal kvůli testu.
     */
    internal fun parseGroups(json: JSONObject): List<SGroup> {
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

    private fun parseIso(iso: String): Long = try {
        java.time.Instant.parse(iso).toEpochMilli()
    } catch (_: Exception) {
        System.currentTimeMillis()
    }

    private companion object {
        /** Přepsané/anglické varianty md_titles - jiné skripty (ar, bn, ...) k porovnání s ostatními zdroji nejsou k ničemu. */
        val ROMANIZED_LANGS = setOf("en", "ja-ro", "ko-ro", "zh-ro", "zh-hk-ro")
    }
}

/** Výsledek [ComicKSource.getTitleInfo] - alternativní názvy pro cross-source hledání + content_rating pro adult filtr. */
data class ComicKTitleInfo(
    val alternateTitles: List<String>,
    val contentRating: String?,
)

/** Výsledek [ComicKSource.getGroup] - metadata skupiny + tituly, které přeložila. */
data class GroupInfo(
    val title: String,
    val followCount: Int,
    val chapterCount: Int,
    val comics: List<SManga>,
)

/** Jedna obálka ze seznamu [ComicKSource.getCoverGallery] - svazek může být neznámý (staré/fan uploady). */
data class SCover(
    val volume: String?,
    val imageUrl: String,
)

/** Jedna položka z [ComicKSource.getGenreList] - `group` "Genre" = žánr, cokoliv jiného = tag. */
data class ComicKGenreOption(
    val name: String,
    val slug: String,
    val group: String,
)

/** Vstup pro [ComicKSource.searchAdvanced] - viz komentář u funkce pro ověřené hodnoty parametrů. */
data class ComicKSearchFilters(
    val query: String = "",
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val demographics: List<Int> = emptyList(),
    val countries: List<String> = emptyList(),
    val status: Int? = null,
    val contentRating: String? = null,
    val minChapters: Int? = null,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val sortBy: String = "follow",
) {
    val isActive: Boolean
        get() = genres.isNotEmpty() || tags.isNotEmpty() || demographics.isNotEmpty() ||
            countries.isNotEmpty() || status != null || contentRating != null ||
            minChapters != null || yearFrom != null || yearTo != null
}

/** Výsledek [ComicKSource.getTop] - data pro ComicK domovskou obrazovku (5 sekcí). Klíče map jsou "7"/"30"/"90" (dny). */
data class TopFeed(
    val recentlyAdded: List<SManga>,
    val completed: List<SManga>,
    val popularNew: Map<String, List<SManga>>,
    val mostRecentPopular: Map<String, List<SManga>>,
    val recentReviews: List<ReviewItem>,
)

/** Jedna recenze z `/top`'s `recentReviews[]` - `title` může chybět (recenze bez nadpisu). */
data class ReviewItem(
    val title: String?,
    val content: String,
    val authorName: String?,
    val comic: SManga,
)

/** Jedna položka z [ComicKSource.getUpdates] - kapitola + komiks, kterému patří, + počty lajků/komentářů. */
data class ChapterUpdate(
    val chapter: SChapter,
    val comic: SManga,
    val upCount: Int,
    val commentCount: Int,
)

/** Jedna stránka výsledku [ComicKSource.getComments] - `hasMore` řídí "Načíst další". */
data class ComicKCommentsPage(
    val comments: List<ComicKComment>,
    val total: Int,
    val hasMore: Boolean,
)

/** Jedno doporučení z [ComicKSource.getRecommendations] - `upCount` je počet palců nahoru, co má na ComicK webu. */
data class ComicKRecommendation(
    val manga: SManga,
    val upCount: Int,
)

/** Jeden komentář pod titulem - `replies` jsou jen jednu úroveň vnořené, stejně jako na ComicK webu. */
data class ComicKComment(
    val id: Long,
    val content: String,
    val username: String,
    val avatarUrl: String?,
    val upCount: Int,
    val downCount: Int,
    val createdAt: Long,
    val replies: List<ComicKComment>,
)
