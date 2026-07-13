package com.haise.jiyu.source.comick

import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.source.LanguageMap
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
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

    private val apiBase   = "https://api.comick.dev"
    private val coverBase = "https://meo.comick.pictures"

    // ─── Vyhledávání & browse ────────────────────────────────────────────────

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            val q = URLEncoder.encode(query, "UTF-8")
            parseComicList(getArray("$apiBase/v1.0/search?q=$q&limit=20&page=$page"))
        }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> =
        withContext(Dispatchers.IO) {
            val sort = when (filter.sortBy) {
                "latest" -> "date"
                "rating" -> "rating"
                "title"  -> "title"
                else     -> "follow"
            }
            parseComicList(getArray("$apiBase/v1.0/search?sort=$sort&limit=20&page=$page"))
        }

    // ─── Detail mangy ────────────────────────────────────────────────────────

    /**
     * Doplní popis a stav vydávání.
     * manga.url je ve formátu "$apiBase/manga/{slug}".
     */
    override suspend fun getMangaDetails(manga: SManga): SManga =
        withContext(Dispatchers.IO) {
            val slug = manga.url.substringAfterLast("/")
            val json = getObject("$apiBase/comic/$slug")
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

            manga.copy(description = desc, status = status, author = author, genres = genres, year = year)
        }

    // ─── Kapitoly ────────────────────────────────────────────────────────────

    /**
     * Stáhne kompletní seznam kapitol v angličtině.
     * API vyžaduje hid (ne slug) pro endpoint /manga/{hid}/chapters,
     * proto nejdřív načteme detail mangy abychom hid získali.
     * Prochází stránky po 60 dokud API nevrátí méně výsledků.
     */
    override suspend fun getChapterList(manga: SManga): List<SChapter> =
        withContext(Dispatchers.IO) {
            // Krok 1: získat hid z detailu mangy
            val slug = manga.url.substringAfterLast("/")
            val detailJson = getObject("$apiBase/comic/$slug")
            val hid = detailJson.getJSONObject("comic").getString("hid")

            // Krok 2: stránkovat přes všechny kapitoly
            val chapters = mutableListOf<SChapter>()
            var page = 1
            val pageSize = 60

            val langCode = LanguageMap.toMangaDexCode(settings.sourceLanguage.first())
            while (true) {
                val url = "$apiBase/comic/$hid/chapters?lang=$langCode&page=$page&limit=$pageSize"
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

    private fun getArray(url: String): JSONArray {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) { "ComicK API chyba ${response.code}: $url" }
            return JSONArray(body)
        }
    }

    private fun getObject(url: String): JSONObject {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) { "ComicK API chyba ${response.code}: $url" }
            return JSONObject(body)
        }
    }

    /** Převede jeden objekt z výsledků hledání na SManga. */
    private fun parseComicList(arr: JSONArray): List<SManga> =
        (0 until arr.length()).mapNotNull { i ->
            val comic = arr.getJSONObject(i)
            val title = comic.optString("title").ifBlank { return@mapNotNull null }
            val slug  = comic.optString("slug").ifBlank { return@mapNotNull null }

            // Titulní obrázek: první položka md_covers s neprázdným b2key
            val coverUrl = comic.optJSONArray("md_covers")
                ?.let { covers ->
                    (0 until covers.length()).firstNotNullOfOrNull { j ->
                        covers.getJSONObject(j).optString("b2key").ifBlank { null }
                    }
                }
                ?.let { b2key -> "$coverBase/$b2key" }

            SManga(
                sourceId = id,
                url      = "$apiBase/comic/$slug",
                title    = title,
                coverUrl = coverUrl,
            )
        }

    /** Převede jeden objekt kapitoly na SChapter, nebo null pokud chybí hid. */
    private fun chapterFromJson(json: JSONObject, mangaUrl: String): SChapter? {
        val chHid = json.optString("hid").ifBlank { return null }
        val chap  = json.optString("chap", "0")
        val vol   = json.optString("vol").ifBlank { null }
        val title = json.optString("title").ifBlank { null }

        val chapterNum = chap.toFloatOrNull() ?: 0f
        val name = buildString {
            if (vol != null) append("Vol.$vol ")
            append("Ch.$chap")
            if (!title.isNullOrBlank()) append(" – $title")
        }

        return SChapter(
            sourceId      = id,
            mangaUrl      = mangaUrl,
            url           = "$apiBase/chapter/$chHid",
            name          = name,
            chapterNumber = chapterNum,
            dateUpload    = parseIso(json.optString("created_at")),
        )
    }

    private fun parseIso(iso: String): Long = try {
        java.time.Instant.parse(iso).toEpochMilli()
    } catch (_: Exception) {
        System.currentTimeMillis()
    }
}
