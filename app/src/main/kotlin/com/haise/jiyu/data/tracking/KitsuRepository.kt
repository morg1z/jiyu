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
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class KitsuUserEntry(val status: String?, val ratingTwenty: Int?, val progress: Int?)

data class KitsuManga(
    val id: String,
    val title: String,
    val coverUrl: String?,
    val score: Float?,
    val synopsis: String?,
)

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
            val arr = JSONObject(body).getJSONArray("data")
            (0 until arr.length()).map { i ->
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
        } catch (_: Exception) { emptyList() }
    }

    suspend fun getLibraryEntryId(kitsuMangaId: String): String? = withContext(Dispatchers.IO) {
        val userId = authManager.getUserId() ?: return@withContext null
        val token  = authManager.getToken()  ?: return@withContext null
        try {
            val url = "https://kitsu.app/api/edge/library-entries?filter[userId]=$userId&filter[mediaType]=manga&filter[mediaId]=$kitsuMangaId&fields[libraryEntries]=id"
            val req = Request.Builder().url(url)
                .header("Accept", "application/vnd.api+json")
                .header("Authorization", "Bearer $token")
                .build()
            val body = httpClient.newCall(req).execute().use { it.body?.string() } ?: return@withContext null
            val arr = JSONObject(body).optJSONArray("data")
            if (arr != null && arr.length() > 0) arr.getJSONObject(0).getString("id") else null
        } catch (_: Exception) { null }
    }

    suspend fun fetchUserId(): String? = withContext(Dispatchers.IO) {
        val token = authManager.getToken() ?: return@withContext null
        try {
            val req = Request.Builder()
                .url("https://kitsu.app/api/edge/users?filter[self]=true&fields[users]=id")
                .header("Accept", "application/vnd.api+json")
                .header("Authorization", "Bearer $token")
                .build()
            val body = httpClient.newCall(req).execute().use { it.body?.string() } ?: return@withContext null
            val arr = JSONObject(body).optJSONArray("data")
            arr?.getJSONObject(0)?.getString("id")
        } catch (_: Exception) { null }
    }

    /**
     * Uloží počet přečtených kapitol do Kitsu library entry.
     * Pokud entry ještě neexistuje, vytvoří ji. Jinak ji aktualizuje.
     */
    suspend fun updateProgress(kitsuMangaId: String, chaptersRead: Int) = withContext(Dispatchers.IO) {
        val token = authManager.getToken() ?: return@withContext
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
                Request.Builder()
                    .url("https://kitsu.app/api/edge/library-entries")
                    .header("Accept", "application/vnd.api+json")
                    .header("Authorization", "Bearer $token")
                    .post(body.toRequestBody(jsonType))
                    .build()
                    .let { httpClient.newCall(it).execute().close() }
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
                Request.Builder()
                    .url("https://kitsu.app/api/edge/library-entries/$existingId")
                    .header("Accept", "application/vnd.api+json")
                    .header("Authorization", "Bearer $token")
                    .patch(body.toRequestBody(jsonType))
                    .build()
                    .let { httpClient.newCall(it).execute().close() }
            }
        } catch (_: Exception) {}
    }

    /** Stáhne uživatelův status/skóre uložený přímo na Kitsu (pro obousměrnou synchronizaci). */
    suspend fun getMyLibraryEntry(kitsuMangaId: String): KitsuUserEntry? = withContext(Dispatchers.IO) {
        val userId = authManager.getUserId() ?: return@withContext null
        val token  = authManager.getToken()  ?: return@withContext null
        try {
            val url = "https://kitsu.app/api/edge/library-entries?filter[userId]=$userId&filter[mediaType]=manga&filter[mediaId]=$kitsuMangaId&fields[libraryEntries]=status,ratingTwenty,progress"
            val req = Request.Builder().url(url)
                .header("Accept", "application/vnd.api+json")
                .header("Authorization", "Bearer $token")
                .build()
            val body = httpClient.newCall(req).execute().use { it.body?.string() } ?: return@withContext null
            val arr = JSONObject(body).optJSONArray("data") ?: return@withContext null
            if (arr.length() == 0) return@withContext null
            val attrs = arr.getJSONObject(0).getJSONObject("attributes")
            KitsuUserEntry(
                status = attrs.optString("status").takeIf { it.isNotBlank() },
                ratingTwenty = attrs.optInt("ratingTwenty", 0).takeIf { it > 0 },
                progress = attrs.optInt("progress", 0).takeIf { it > 0 },
            )
        } catch (_: Exception) { null }
    }

    fun openKitsuPage(context: Context, kitsuId: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://kitsu.app/manga/$kitsuId")))
    }
}
