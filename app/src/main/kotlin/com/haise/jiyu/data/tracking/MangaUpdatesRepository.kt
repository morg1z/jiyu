package com.haise.jiyu.data.tracking

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.haise.jiyu.security.SecureCredentialStore
import com.haise.jiyu.util.report
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/** Vytaženo z [MangaUpdatesRepository.searchManga] jako čistá funkce, aby šlo otestovat bez OkHttp. */
internal fun parseMuSearchResults(body: String): List<MuManga> {
    val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
    return (0 until results.length()).map { i ->
        val rec = results.getJSONObject(i).getJSONObject("record")
        MuManga(
            id = rec.getLong("series_id"),
            title = rec.optString("title"),
            coverUrl = rec.optJSONObject("image")?.optJSONObject("url")?.optString("thumb"),
            year = rec.optString("year").toIntOrNull(),
            description = rec.optString("description").take(200).takeIf { it.isNotBlank() },
        )
    }
}

/** Vytaženo z [MangaUpdatesRepository.getMyStatus] jako čistá funkce, aby šlo otestovat bez OkHttp. */
internal fun parseMuListId(body: String): Int? =
    JSONObject(body).optInt("list_id", -1).takeIf { it >= 0 }

/** Vytaženo z [MangaUpdatesRepository.getMyStatus] jako čistá funkce, aby šlo otestovat bez OkHttp. */
internal fun parseMuRating(body: String): Float? =
    JSONObject(body).optDouble("rating", 0.0).takeIf { it > 0.0 }?.toFloat()

/**
 * Kombinuje výsledky obou volání [MangaUpdatesRepository.getMyStatus] - `null` jen když
 * SELHALA OBĚ (žádná z API odpovědí nepřinesla nic užitečného), jinak vrátí status i s
 * jedním chybějícím polem (např. list_id se podařilo, rating endpoint zrovna spadl).
 */
internal fun combineMuStatus(listId: Int?, rating: Float?): MuUserStatus? =
    if (listId == null && rating == null) null else MuUserStatus(listId, rating)

@Singleton
class MangaUpdatesRepository @Inject constructor(
    private val httpClient: OkHttpClient,
    private val secureStore: SecureCredentialStore,
) {
    companion object {
        private const val KEY_SESSION = "mu_session_token"
        private const val KEY_USER    = "mu_username"
        private const val BASE  = "https://api.mangaupdates.com/v1"
    }

    private val _session = MutableStateFlow(secureStore.get(KEY_SESSION))
    private val _username = MutableStateFlow(secureStore.get(KEY_USER) ?: "")

    val isLoggedIn: Flow<Boolean> = _session.map { !it.isNullOrBlank() }
    val username:   Flow<String>  = _username.asStateFlow()

    private suspend fun sessionToken(): String? = withContext(Dispatchers.IO) { secureStore.get(KEY_SESSION) }

    suspend fun login(user: String, pass: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().put("username", user).put("password", pass).toString()
            val req = Request.Builder()
                .url("$BASE/account/login")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            // .use{} je tu POVINNÉ - uvnitř jsou tři brzké returny a bez něj každý z nich
            // nechal viset otevřené spojení (viz stejná oprava v KitsuAuthManager).
            val token = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val body = JSONObject(resp.body?.string() ?: return@withContext false)
                body.optJSONObject("context")?.optString("session_token")?.takeIf { it.isNotBlank() }
                    ?: return@withContext false
            }
            secureStore.set(KEY_SESSION, token)
            secureStore.set(KEY_USER, user)
            _session.value = token
            _username.value = user
            true
        } catch (e: Exception) {
            e.report("tracking:mangaupdates:login")
            false
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        secureStore.remove(KEY_SESSION, KEY_USER)
        _session.value = null
        _username.value = ""
    }

    suspend fun searchManga(query: String): List<MuManga> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().put("search", query).put("perpage", 10).toString()
            val req = Request.Builder()
                .url("$BASE/series/search")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            val body = httpClient.newCall(req).execute().use { it.body?.string() } ?: return@withContext emptyList()
            parseMuSearchResults(body)
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
        } catch (e: Exception) {
            e.report("tracking:mangaupdates:updateProgress")
        }
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
                            listId = parseMuListId(resp.body?.string() ?: "{}")
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
                            rating = parseMuRating(resp.body?.string() ?: "{}")
                        }
                    }
                }
        } catch (e: Exception) {
            e.report("tracking:mangaupdates:rating")
        }
        combineMuStatus(listId, rating)
    }

    fun openMuPage(context: Context, seriesId: Long) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.mangaupdates.com/series/$seriesId")))
    }
}
