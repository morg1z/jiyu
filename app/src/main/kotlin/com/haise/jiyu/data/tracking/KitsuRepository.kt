package com.haise.jiyu.data.tracking

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import com.haise.jiyu.util.report

data class KitsuUserEntry(val status: String?, val ratingTwenty: Int?, val progress: Int?)

data class KitsuManga(
    val id: String,
    val title: String,
    val coverUrl: String?,
    val score: Float?,
    val synopsis: String?,
)

/** Vytaženo z [KitsuRepository.searchManga] jako čistá funkce, aby šlo otestovat bez OkHttp. */
internal fun parseKitsuSearchResults(body: String): List<KitsuManga> {
    val arr = JSONObject(body).getJSONArray("data")
    return (0 until arr.length()).map { i ->
        val item = arr.getJSONObject(i)
        val attrs = item.getJSONObject("attributes")
        val titles = attrs.optJSONObject("titles")
        val title = titles?.optString("en")?.takeIf { it.isNotBlank() }
            ?: titles?.optString("en_jp")?.takeIf { it.isNotBlank() }
            ?: titles?.optString("ja_jp") ?: ""
        val cover = attrs.optJSONObject("posterImage")?.optString("small")
        val rating = attrs.optString("averageRating").toFloatOrNull()?.div(20f)
        KitsuManga(
            id = item.getString("id"),
            title = title,
            coverUrl = cover,
            score = rating,
            synopsis = attrs.optString("synopsis").take(200).takeIf { it.isNotBlank() },
        )
    }
}

/** Vytaženo z [KitsuRepository.getMyLibraryEntry] jako čistá funkce, aby šlo otestovat bez OkHttp. */
internal fun parseKitsuLibraryEntry(body: String): KitsuUserEntry? {
    val arr = JSONObject(body).optJSONArray("data") ?: return null
    if (arr.length() == 0) return null
    val attrs = arr.getJSONObject(0).getJSONObject("attributes")
    return KitsuUserEntry(
        status = attrs.optString("status").takeIf { it.isNotBlank() },
        ratingTwenty = attrs.optInt("ratingTwenty", 0).takeIf { it > 0 },
        progress = attrs.optInt("progress", 0).takeIf { it > 0 },
    )
}

@Singleton
class KitsuRepository @Inject constructor(
    private val httpClient: OkHttpClient,
    private val authManager: KitsuAuthManager,
) {
    suspend fun searchManga(query: String): List<KitsuManga> = withContext(Dispatchers.IO) {
        try {
            val url = "https://kitsu.app/api/edge/manga?filter[text]=${Uri.encode(query)}&page[limit]=10&fields[manga]=id,titles,posterImage,averageRating,synopsis"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.api+json")
                .build()
            val body = httpClient.newCall(req).execute().use { it.body?.string() } ?: return@withContext emptyList()
            parseKitsuSearchResults(body)
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Požadavek s Bearer tokenem: při 401 jednou obnoví token přes [KitsuAuthManager.refresh] a zopakuje
     * ho (audit nález JIYU-ARCH-1 - refresh token se dřív ukládal, ale nikdy nepoužil). `null` = bez
     * přihlášení nebo obnova selhala.
     */
    private suspend fun <T> authed(build: (token: String) -> Request, handle: (Response) -> T?): T? {
        val token = authManager.getToken() ?: return null
        val first = httpClient.newCall(build(token)).execute()
        if (first.code != 401) return first.use(handle)
        first.close()
        if (!authManager.refresh(staleToken = token)) return null
        val fresh = authManager.getToken() ?: return null
        return httpClient.newCall(build(fresh)).execute().use(handle)
    }

    suspend fun getLibraryEntryId(kitsuMangaId: String): String? = withContext(Dispatchers.IO) {
        val userId = authManager.getUserId() ?: return@withContext null
        try {
            val url = "https://kitsu.app/api/edge/library-entries?filter[userId]=$userId&filter[mediaType]=manga&filter[mediaId]=$kitsuMangaId&fields[libraryEntries]=id"
            val body = authed(
                build = { token ->
                    Request.Builder().url(url)
                        .header("Accept", "application/vnd.api+json")
                        .header("Authorization", "Bearer $token")
                        .build()
                },
            ) { resp -> if (!resp.isSuccessful) null else resp.body?.string() } ?: return@withContext null
            val arr = JSONObject(body).optJSONArray("data")
            if (arr != null && arr.length() > 0) arr.getJSONObject(0).getString("id") else null
        } catch (_: Exception) { null }
    }

    suspend fun fetchUserId(): String? = withContext(Dispatchers.IO) {
        try {
            val body = authed(
                build = { token ->
                    Request.Builder()
                        .url("https://kitsu.app/api/edge/users?filter[self]=true&fields[users]=id")
                        .header("Accept", "application/vnd.api+json")
                        .header("Authorization", "Bearer $token")
                        .build()
                },
            ) { resp -> if (!resp.isSuccessful) null else resp.body?.string() } ?: return@withContext null
            val arr = JSONObject(body).optJSONArray("data")
            arr?.getJSONObject(0)?.getString("id")
        } catch (_: Exception) { null }
    }

    /**
     * Uloží počet přečtených kapitol do Kitsu library entry.
     * Pokud entry ještě neexistuje, vytvoří ji. Jinak ji aktualizuje.
     */
    suspend fun updateProgress(kitsuMangaId: String, chaptersRead: Int) = withContext(Dispatchers.IO) {
        if (authManager.getToken() == null) return@withContext
        try {
            val existingId = getLibraryEntryId(kitsuMangaId)
            val jsonType = "application/vnd.api+json".toMediaType()
            if (existingId == null) {
                val userId = authManager.getUserId() ?: return@withContext
                val body = JSONObject().apply {
                    put("data", JSONObject().apply {
                        put("type", "libraryEntries")
                        put("attributes", JSONObject().apply {
                            put("status", "current")
                            put("chaptersRead", chaptersRead)
                        })
                        put("relationships", JSONObject().apply {
                            put("user", JSONObject().put("data", JSONObject().put("type", "users").put("id", userId)))
                            put("media", JSONObject().put("data", JSONObject().put("type", "manga").put("id", kitsuMangaId)))
                        })
                    })
                }.toString()
                authed(
                    build = { token ->
                        Request.Builder()
                            .url("https://kitsu.app/api/edge/library-entries")
                            .header("Accept", "application/vnd.api+json")
                            .header("Authorization", "Bearer $token")
                            .post(body.toRequestBody(jsonType))
                            .build()
                    },
                ) { it.isSuccessful }
            } else {
                val body = JSONObject().apply {
                    put("data", JSONObject().apply {
                        put("type", "libraryEntries")
                        put("id", existingId)
                        put("attributes", JSONObject().apply {
                            put("status", "current")
                            put("chaptersRead", chaptersRead)
                        })
                    })
                }.toString()
                authed(
                    build = { token ->
                        Request.Builder()
                            .url("https://kitsu.app/api/edge/library-entries/$existingId")
                            .header("Accept", "application/vnd.api+json")
                            .header("Authorization", "Bearer $token")
                            .patch(body.toRequestBody(jsonType))
                            .build()
                    },
                ) { it.isSuccessful }
            }
        } catch (e: Exception) {
            e.report("tracking:kitsu:updateProgress")
        }
    }

    /** Stáhne uživatelův status/skóre uložený přímo na Kitsu (pro obousměrnou synchronizaci). */
    suspend fun getMyLibraryEntry(kitsuMangaId: String): KitsuUserEntry? = withContext(Dispatchers.IO) {
        val userId = authManager.getUserId() ?: return@withContext null
        try {
            val url = "https://kitsu.app/api/edge/library-entries?filter[userId]=$userId&filter[mediaType]=manga&filter[mediaId]=$kitsuMangaId&fields[libraryEntries]=status,ratingTwenty,progress"
            val body = authed(
                build = { token ->
                    Request.Builder().url(url)
                        .header("Accept", "application/vnd.api+json")
                        .header("Authorization", "Bearer $token")
                        .build()
                },
            ) { resp -> if (!resp.isSuccessful) null else resp.body?.string() } ?: return@withContext null
            parseKitsuLibraryEntry(body)
        } catch (_: Exception) { null }
    }

    fun openKitsuPage(context: Context, kitsuId: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://kitsu.app/manga/$kitsuId")))
    }
}
