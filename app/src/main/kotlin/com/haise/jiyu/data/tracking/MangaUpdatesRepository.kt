package com.haise.jiyu.data.tracking

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class MuUserStatus(val listId: Int?, val rating: Float?)

data class MuManga(
    val id: Long,
    val title: String,
    val coverUrl: String?,
    val year: Int?,
    val description: String?,
)

@Singleton
class MangaUpdatesRepository @Inject constructor(
    private val httpClient: OkHttpClient,
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        private val KEY_SESSION = stringPreferencesKey("mu_session_token")
        private val KEY_USER    = stringPreferencesKey("mu_username")
        private const val BASE  = "https://api.mangaupdates.com/v1"
    }

    val isLoggedIn: Flow<Boolean> = dataStore.data.map { !it[KEY_SESSION].isNullOrBlank() }
    val username:   Flow<String>  = dataStore.data.map { it[KEY_USER] ?: "" }

    private suspend fun sessionToken(): String? = dataStore.data.first()[KEY_SESSION]

    suspend fun login(user: String, pass: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().put("username", user).put("password", pass).toString()
            val req = Request.Builder()
                .url("$BASE/account/login")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext false
            val body = JSONObject(resp.body?.string() ?: return@withContext false)
            val ctx = body.optJSONObject("context")
            val token = ctx?.optString("session_token")?.takeIf { it.isNotBlank() } ?: return@withContext false
            dataStore.edit {
                it[KEY_SESSION] = token
                it[KEY_USER]    = user
            }
            true
        } catch (_: Exception) { false }
    }

    suspend fun logout() = dataStore.edit {
        it.remove(KEY_SESSION)
        it.remove(KEY_USER)
    }

    suspend fun searchManga(query: String): List<MuManga> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().put("search", query).put("perpage", 10).toString()
            val req = Request.Builder()
                .url("$BASE/series/search")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            val body = httpClient.newCall(req).execute().use { it.body?.string() } ?: return@withContext emptyList()
            val results = JSONObject(body).optJSONArray("results") ?: return@withContext emptyList()
            (0 until results.length()).map { i ->
                val rec = results.getJSONObject(i).getJSONObject("record")
                MuManga(
                    id = rec.getLong("series_id"),
                    title = rec.optString("title"),
                    coverUrl = rec.optJSONObject("image")?.optJSONObject("url")?.optString("thumb"),
                    year = rec.optString("year").toIntOrNull(),
                    description = rec.optString("description").take(200).takeIf { it.isNotBlank() },
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Přidá/aktualizuje sérii ve čtenářském listu a nastaví počet přečtených kapitol.
     * MangaUpdates API: PUT /v1/lists/series přidá do listu "Reading" (list_id = 0),
     * pak PUT /v1/lists/series/{seriesId}/chapters označí počet přečtených.
     */
    suspend fun updateProgress(seriesId: Long, chaptersRead: Int) = withContext(Dispatchers.IO) {
        val token = sessionToken() ?: return@withContext
        val jsonType = "application/json".toMediaType()
        try {
            // Přidá do listu "Reading" (list_id = 0 = Reading list na MU)
            val addBody = JSONObject()
                .put("series", org.json.JSONArray().put(JSONObject().put("id", seriesId).put("list_id", 0)))
                .toString()
            Request.Builder()
                .url("$BASE/lists/series")
                .header("Authorization", "Bearer $token")
                .put(addBody.toRequestBody(jsonType))
                .build()
                .let { httpClient.newCall(it).execute().close() }

            // Nastaví počet přečtených kapitol
            val chapBody = JSONObject().put("chapter", chaptersRead).toString()
            Request.Builder()
                .url("$BASE/lists/series/$seriesId/chapters/$chaptersRead")
                .header("Authorization", "Bearer $token")
                .post(chapBody.toRequestBody(jsonType))
                .build()
                .let { httpClient.newCall(it).execute().close() }
        } catch (_: Exception) {}
    }

    /** Stáhne uživatelův list_id (status) a hodnocení uložené přímo na MangaUpdates. */
    suspend fun getMyStatus(seriesId: Long): MuUserStatus? = withContext(Dispatchers.IO) {
        val token = sessionToken() ?: return@withContext null
        var listId: Int? = null
        var rating: Float? = null
        try {
            Request.Builder()
                .url("$BASE/lists/series/$seriesId")
                .header("Authorization", "Bearer $token")
                .build()
                .let { req ->
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val json = JSONObject(resp.body?.string() ?: "{}")
                            listId = json.optInt("list_id", -1).takeIf { it >= 0 }
                        }
                    }
                }
            Request.Builder()
                .url("$BASE/series/$seriesId/rating")
                .header("Authorization", "Bearer $token")
                .build()
                .let { req ->
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val json = JSONObject(resp.body?.string() ?: "{}")
                            rating = json.optDouble("rating", 0.0).takeIf { it > 0.0 }?.toFloat()
                        }
                    }
                }
        } catch (_: Exception) {}
        if (listId == null && rating == null) null else MuUserStatus(listId, rating)
    }

    fun openMuPage(context: Context, seriesId: Long) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.mangaupdates.com/series/$seriesId")))
    }
}
