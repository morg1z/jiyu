package com.haise.jiyu.data.tracking

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.haise.jiyu.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import com.haise.jiyu.util.report

data class MalUserStatus(val status: String?, val score: Int?, val numChaptersRead: Int?)

data class MalManga(
    val id: Int,
    val title: String,
    val coverUrl: String?,
    val score: Float?,
    val status: String?,
    val synopsis: String?,
)

/** Vytaženo z [MalRepository.searchManga] jako čistá funkce, aby šlo otestovat bez OkHttp. */
internal fun parseMalSearchResults(body: String): List<MalManga> {
    val arr = JSONObject(body).getJSONArray("data")
    return (0 until arr.length()).map { i ->
        val node = arr.getJSONObject(i).getJSONObject("node")
        MalManga(
            id = node.getInt("id"),
            title = node.optString("title"),
            coverUrl = node.optJSONObject("main_picture")?.optString("medium"),
            score = node.optDouble("mean", 0.0).takeIf { it > 0.0 }?.toFloat(),
            status = node.optString("status").takeIf { it.isNotBlank() },
            synopsis = node.optString("synopsis").take(200).takeIf { it.isNotBlank() },
        )
    }
}

/** Vytaženo z [MalRepository.getMyStatus] jako čistá funkce, aby šlo otestovat bez OkHttp. */
internal fun parseMalUserStatus(body: String): MalUserStatus? {
    val status = JSONObject(body).optJSONObject("my_list_status") ?: return null
    return MalUserStatus(
        status = status.optString("status").takeIf { it.isNotBlank() },
        score = status.optInt("score", 0).takeIf { it > 0 },
        numChaptersRead = status.optInt("num_chapters_read", 0).takeIf { it > 0 },
    )
}

@Singleton
class MalRepository @Inject constructor(
    private val httpClient: OkHttpClient,
    private val authManager: MalAuthManager,
) {
    private val clientId get() = BuildConfig.MAL_CLIENT_ID
    val hasClientId get() = clientId.isNotBlank()

    suspend fun searchManga(query: String): List<MalManga> = withContext(Dispatchers.IO) {
        if (!hasClientId) return@withContext emptyList()
        try {
            val url = "https://api.myanimelist.net/v2/manga?q=${Uri.encode(query)}&limit=10&fields=id,title,main_picture,mean,status,synopsis"
            val req = Request.Builder().url(url).header("X-MAL-CLIENT-ID", clientId).build()
            val body = httpClient.newCall(req).execute().use { it.body?.string() } ?: return@withContext emptyList()
            parseMalSearchResults(body)
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Požadavek s Bearer tokenem: při 401 (access token MAL vyprší zhruba po hodině) jednou obnoví token
     * přes [MalAuthManager.refreshAccessToken] a zopakuje ho. Dřív se refresh nevolal nikde, takže se
     * po vypršení tokenu progres čtení do MAL tiše přestal synchronizovat (audit nález JIYU-ARCH-1).
     * Selhání obnovy nebo chybějící přihlášení = `null`.
     */
    private suspend fun <T> authed(build: (token: String) -> Request, handle: (Response) -> T?): T? {
        val token = authManager.accessToken.first() ?: return null
        val first = httpClient.newCall(build(token)).execute()
        if (first.code != 401) return first.use(handle)
        first.close()
        if (!hasClientId || !authManager.refreshAccessToken(clientId, staleToken = token)) return null
        val fresh = authManager.accessToken.first() ?: return null
        return httpClient.newCall(build(fresh)).execute().use(handle)
    }

    suspend fun getUserProfile(): JSONObject? = withContext(Dispatchers.IO) {
        try {
            authed(
                build = { token ->
                    Request.Builder()
                        .url("https://api.myanimelist.net/v2/users/@me")
                        .header("Authorization", "Bearer $token")
                        .build()
                },
            ) { response ->
                if (!response.isSuccessful) return@authed null
                JSONObject(response.body?.string() ?: return@authed null)
            }
        } catch (_: Exception) { null }
    }

    suspend fun updateMangaStatus(
        malId: Int,
        status: String,
        score: Int? = null,
        numChaptersRead: Int? = null,
    ) = withContext(Dispatchers.IO) {
        try {
            authed(
                build = { token ->
                    val formBuilder = FormBody.Builder().add("status", status)
                    if (score != null) formBuilder.add("score", score.toString())
                    if (numChaptersRead != null) formBuilder.add("num_chapters_read", numChaptersRead.toString())
                    Request.Builder()
                        .url("https://api.myanimelist.net/v2/manga/$malId/my_list_status")
                        .header("Authorization", "Bearer $token")
                        .patch(formBuilder.build())
                        .build()
                },
            ) { it.isSuccessful }
            Unit
        } catch (e: Exception) {
            e.report("tracking:mal:updateMangaStatus")
        }
    }

    /** Stáhne uživatelův status/skóre uložený přímo na MAL (pro obousměrnou synchronizaci). */
    suspend fun getMyStatus(malId: Int): MalUserStatus? = withContext(Dispatchers.IO) {
        try {
            authed(
                build = { token ->
                    Request.Builder()
                        .url("https://api.myanimelist.net/v2/manga/$malId?fields=my_list_status")
                        .header("Authorization", "Bearer $token")
                        .build()
                },
            ) { resp ->
                if (!resp.isSuccessful) return@authed null
                parseMalUserStatus(resp.body?.string() ?: return@authed null)
            }
        } catch (_: Exception) { null }
    }

    fun openMalPage(context: Context, malId: Int) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://myanimelist.net/manga/$malId")))
    }
}
