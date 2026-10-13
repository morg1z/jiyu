package com.haise.jiyu.data.tracking

import android.net.Uri
import com.haise.jiyu.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

// region Modely - Comick Library API v1.1.0 (comick.dev/docs/library)

data class ComickProfile(val id: String, val username: String?, val avatarUrl: String?)

data class ComickAltTitle(val title: String, val lang: String?, val isDefault: Boolean)

/** `progress` objekt - `number`/`volume` jsou STRINGY (muze byt "12.5" i neciselny label). */
data class ComickProgress(
    val unit: String?,
    /** Chapter/episode HID - odlisny od title HID. */
    val chapterHid: String?,
    val number: String?,
    val volume: String?,
)

data class ComickLibraryEntry(
    /** Opaque string HID - NIKDY neparsovat jako cislo (docs: ^[A-Za-z0-9_-]{1,64}$). */
    val hid: String,
    val slug: String?,
    val title: String?,
    /** "manga" (pokryva manga/manhwa/manhua) nebo "anime". */
    val mediaType: String?,
    /** 1 reading, 2 completed, 3 on_hold, 4 dropped, 5 plan_to_read. */
    val status: Int?,
    val progress: ComickProgress?,
    /** Pri cteni muze byt desetinne (importovane hodnoceni); zapis jen int 1-10. */
    val rating: Double?,
    val coverUrl: String?,
    val country: String?,
    val titles: List<ComickAltTitle>,
    val followedAt: Long?,
    val updatedAt: Long?,
    val progressedAt: Long?,
    val publicationStatus: Int?,
    /** true = web ukazuje titul jako completed bez ohledu na publicationStatus. */
    val translationCompleted: Boolean?,
    val translationStatus: String?,
    /** Posledni vydane cislo - NENI spolehlivy celkovy pocet kapitol (docs). */
    val lastReleasedNumber: String?,
    val continueEntry: JSONObject? = null,
)

data class ComickTitle(
    val hid: String,
    val slug: String?,
    val title: String?,
    val year: Int?,
    val country: String?,
    val titles: List<ComickAltTitle>,
    val coverUrl: String?,
)

data class ComickLibraryPage(val entries: List<ComickLibraryEntry>, val nextCursor: String?)

/** Non-2xx po jednom 401-retry. [retryAfterMs] z Retry-After (429) nebo null. */
class ComickApiException(val code: Int, val retryAfterMs: Long? = null) : Exception("Comick API $code")

/** Prefix url comick-source kapitol - za nim je chapter HID (odlisny od title HID).
 *  Sdili ho tracker push (presny progress zaznam) i resolver/repository lookup. */
internal const val COMICK_CHAPTER_URL_PREFIX = "https://api.comick.dev/chapter/"

// endregion

// region Parsery - ciste funkce, testovatelne bez OkHttp

private fun String?.toEpochMsOrNull(): Long? =
    this?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

private fun parseAltTitles(arr: JSONArray?): List<ComickAltTitle> = buildList {
    if (arr == null) return@buildList
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        add(ComickAltTitle(o.optString("title"), o.optString("lang").takeIf { it.isNotBlank() }, o.optBoolean("is_default")))
    }
}

internal fun parseComickProgress(o: JSONObject?): ComickProgress? = o?.let {
    ComickProgress(
        unit = it.optString("unit").takeIf { u -> u.isNotBlank() },
        chapterHid = it.optString("hid").takeIf { h -> h.isNotBlank() },
        number = it.optString("number").takeIf { n -> n.isNotBlank() },
        volume = it.optString("volume").takeIf { v -> v.isNotBlank() },
    )
}

internal fun parseComickEntry(o: JSONObject): ComickLibraryEntry = ComickLibraryEntry(
    hid = o.getString("hid"),
    slug = o.optString("slug").takeIf { it.isNotBlank() },
    title = o.optString("title").takeIf { it.isNotBlank() },
    mediaType = o.optString("media_type").takeIf { it.isNotBlank() },
    status = o.optInt("status", 0).takeIf { it in 1..5 },
    progress = parseComickProgress(o.optJSONObject("progress")),
    rating = if (o.isNull("rating")) null else o.optDouble("rating").takeIf { it > 0 },
    coverUrl = o.optString("cover_url").takeIf { it.isNotBlank() },
    country = o.optString("country").takeIf { it.isNotBlank() },
    titles = parseAltTitles(o.optJSONArray("titles")),
    followedAt = o.optString("followed_at").toEpochMsOrNull(),
    updatedAt = o.optString("updated_at").toEpochMsOrNull(),
    progressedAt = o.optString("progressed_at").toEpochMsOrNull(),
    publicationStatus = o.optInt("publication_status", 0).takeIf { it > 0 },
    translationCompleted = if (o.isNull("translation_completed")) null else o.optBoolean("translation_completed"),
    translationStatus = o.optString("translation_status").takeIf { it.isNotBlank() },
    lastReleasedNumber = o.optString("last_released_number").takeIf { it.isNotBlank() },
    continueEntry = o.optJSONObject("continue_entry"),
)

internal fun parseComickLibraryPage(body: String): ComickLibraryPage {
    val root = JSONObject(body)
    val arr = root.optJSONArray("data")
    val entries = buildList {
        if (arr == null) return@buildList
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { add(parseComickEntry(it)) }
        }
    }
    return ComickLibraryPage(entries, root.optString("next_cursor").takeIf { it.isNotBlank() && it != "null" })
}

internal fun parseComickMe(body: String): ComickProfile? = try {
    val j = JSONObject(body)
    ComickProfile(
        id = j.getString("id"),
        username = j.optString("username").takeIf { it.isNotBlank() },
        avatarUrl = j.optString("avatar_url").takeIf { it.isNotBlank() },
    )
} catch (_: Exception) { null }

internal fun parseComickTitles(body: String): List<ComickTitle> = try {
    val arr = JSONObject(body).optJSONArray("data") ?: return emptyList()
    buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(
                ComickTitle(
                    hid = o.getString("hid"),
                    slug = o.optString("slug").takeIf { it.isNotBlank() },
                    title = o.optString("title").takeIf { it.isNotBlank() },
                    year = o.optInt("year", 0).takeIf { it > 0 },
                    country = o.optString("country").takeIf { it.isNotBlank() },
                    titles = parseAltTitles(o.optJSONArray("titles")),
                    coverUrl = o.optString("cover_url").takeIf { it.isNotBlank() },
                )
            )
        }
    }
} catch (_: Exception) { emptyList() }

// endregion

@Singleton
class ComickRepository @Inject constructor(
    private val httpClient: OkHttpClient,
    private val authManager: ComickAuthManager,
) {
    private val clientId get() = BuildConfig.COMICK_CLIENT_ID
    val hasClientId get() = clientId.isNotBlank()

    private val base = "https://api.comick.dev/integrations/v1"
    private val jsonMedia = "application/json".toMediaType()

    /**
     * Request s Bearer tokenem: proaktivne obnovi prosly access token (margin viz
     * ComickAuthManager.EXPIRY_MARGIN_MS), pri 401
     * obnovi jednou a zopakuje. Na non-2xx haze [ComickApiException] (429 nese
     * retryAfterMs z Retry-After - importer ji cte a spi). Neuspesny refresh -> null
     * a necha rozhodnout volajiciho.
     */
    private suspend fun <T> authed(build: (token: String) -> Request, handle: (Response) -> T): T? {
        var token = authManager.getAccessToken(clientId) ?: return null
        httpClient.newCall(build(token)).execute().use { resp ->
            if (resp.code == 401) {
                if (!authManager.refreshTokens(clientId, staleToken = token)) return null
                token = authManager.getAccessToken(clientId) ?: return null
                httpClient.newCall(build(token)).execute().use { retry ->
                    if (!retry.isSuccessful) throw ComickApiException(retry.code, retry.retryAfterMs())
                    return handle(retry)
                }
            }
            if (!resp.isSuccessful) throw ComickApiException(resp.code, resp.retryAfterMs())
            return handle(resp)
        }
    }

    private fun Response.retryAfterMs(): Long? =
        header("Retry-After")?.toLongOrNull()?.times(1000L)

    suspend fun getMe(): ComickProfile? = withContext(Dispatchers.IO) {
        try {
            authed(
                build = { token -> Request.Builder().url("$base/me").header("Authorization", "Bearer $token").build() },
                handle = { it.body?.string()?.let(::parseComickMe) },
            )
        } catch (_: Exception) { null }
    }

    /**
     * Jedna stranka knihovny. [cursor] predat z predchozi odpovedi BEZE ZMENY (opaque,
     * encrypted, vazany na usera+filtry); null = prvni stranka. 429 vyjimku necha propadnout
     * volajicimu (importer resi Retry-After).
     */
    suspend fun listLibraryPage(cursor: String? = null, mediaType: String = "manga", limit: Int = 100): ComickLibraryPage? =
        withContext(Dispatchers.IO) {
            authed(
                build = { token ->
                    val url = "$base/me/library?media_type=$mediaType&limit=$limit" +
                        (cursor?.let { "&cursor=${Uri.encode(it)}" } ?: "")
                    Request.Builder().url(url).header("Authorization", "Bearer $token").build()
                },
                handle = { it.body?.string()?.let(::parseComickLibraryPage) },
            )
        }

    suspend fun getLibraryEntry(hid: String): ComickLibraryEntry? = withContext(Dispatchers.IO) {
        try {
            authed(
                build = { token -> Request.Builder().url("$base/me/library/${Uri.encode(hid)}").header("Authorization", "Bearer $token").build() },
                handle = { it.body?.string()?.let { b -> parseComickEntry(JSONObject(b).getJSONObject("data")) } },
            )
        } catch (_: Exception) { null }
    }

    /** Tracker matching search - vyzaduje library:read, q 2-100 znaku, limit max 20. */
    suspend fun searchTitles(query: String, mediaType: String = "manga", limit: Int = 10): List<ComickTitle> =
        withContext(Dispatchers.IO) {
            try {
                authed(
                    build = { token ->
                        Request.Builder()
                            .url("$base/titles?q=${Uri.encode(query)}&media_type=$mediaType&limit=$limit")
                            .header("Authorization", "Bearer $token")
                            .build()
                    },
                    handle = { it.body?.string()?.let(::parseComickTitles) ?: emptyList() },
                ) ?: emptyList()
            } catch (_: Exception) { emptyList() }
        }

    // region Zapis - vyzaduje scope library:write (Phase 7)

    /** Body sdilene pro PUT i PATCH: vynechana pole se zachovaji, `progress: null` maze progress, `rating: null` maze hodnoceni. */
    private fun writeBody(
        status: Int?,
        progressNumber: String?,
        progressChapterHid: String?,
        progressVolume: String?,
        rating: Int?,
        clearProgress: Boolean,
        clearRating: Boolean,
    ): JSONObject {
        val body = JSONObject()
        status?.let { body.put("status", it) }
        when {
            clearProgress -> body.put("progress", JSONObject.NULL)
            progressChapterHid != null -> body.put("progress", JSONObject().put("hid", progressChapterHid))
            progressNumber != null || progressVolume != null -> {
                val p = JSONObject()
                progressNumber?.let { p.put("number", it) }
                progressVolume?.let { p.put("volume", it) }
                body.put("progress", p)
            }
        }
        when {
            clearRating -> body.put("rating", JSONObject.NULL)
            rating != null -> body.put("rating", rating.coerceIn(1, 10))
        }
        return body
    }

    private suspend fun writeEntry(method: String, hid: String, body: JSONObject): ComickLibraryEntry? =
        withContext(Dispatchers.IO) {
            authed(
                build = { token ->
                    Request.Builder()
                        .url("$base/me/library/${Uri.encode(hid)}")
                        .header("Authorization", "Bearer $token")
                        .method(method, body.toString().toRequestBody(jsonMedia))
                        .build()
                },
                handle = { it.body?.string()?.let { b -> parseComickEntry(JSONObject(b).getJSONObject("data")) } },
            )
        }

    /** PUT - prida/updatuje entry (status povinny pri pridani), idempotentni. */
    suspend fun putEntry(
        hid: String,
        status: Int? = null,
        progressNumber: String? = null,
        progressChapterHid: String? = null,
        progressVolume: String? = null,
        rating: Int? = null,
    ): ComickLibraryEntry? = writeEntry("PUT", hid, writeBody(status, progressNumber, progressChapterHid, progressVolume, rating, false, false))

    /** PATCH - castecny update entry (404 na unfollowed title). */
    suspend fun patchEntry(
        hid: String,
        status: Int? = null,
        progressNumber: String? = null,
        progressChapterHid: String? = null,
        progressVolume: String? = null,
        rating: Int? = null,
        clearProgress: Boolean = false,
        clearRating: Boolean = false,
    ): ComickLibraryEntry? = writeEntry("PATCH", hid, writeBody(status, progressNumber, progressChapterHid, progressVolume, rating, clearProgress, clearRating))

    /**
     * Push postupu cteni - preferuje presny chapter HID (comick-sourced kapitoly ho maji
     * v url), fallback na cislo zformatovane bez zbytecneho ".0" ("12.5" zustane "12.5").
     */
    suspend fun updateProgress(hid: String, chapterNumber: Float, chapterHid: String? = null): Boolean {
        val formatted = if (chapterNumber == chapterNumber.toLong().toFloat()) {
            chapterNumber.toLong().toString()
        } else {
            chapterNumber.toString()
        }
        return patchEntry(hid = hid, progressNumber = if (chapterHid == null) formatted else null, progressChapterHid = chapterHid) != null
    }

    /** DELETE - unfollow; 204/404 = ok (idempotentni). VYHRADNE na explicitni akci uzivatele. */
    suspend fun deleteEntry(hid: String): Boolean = withContext(Dispatchers.IO) {
        try {
            authed(
                build = { token ->
                    Request.Builder()
                        .url("$base/me/library/${Uri.encode(hid)}")
                        .header("Authorization", "Bearer $token")
                        .delete()
                        .build()
                },
                handle = { true },
            ) ?: false
        } catch (e: ComickApiException) { e.code == 404 } catch (_: Exception) { false }
    }

    // endregion
}
