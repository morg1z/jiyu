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
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class MalUserStatus(val status: String?, val score: Int?, val numChaptersRead: Int?)

data class MalManga(
    val id: Int,
    val title: String,
    val coverUrl: String?,
    val score: Float?,
    val status: String?,
    val synopsis: String?,
)

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
            val arr = JSONObject(body).getJSONArray("data")
            (0 until arr.length()).map { i ->
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
        } catch (_: Exception) { emptyList() }
    }

    suspend fun getUserProfile(): JSONObject? = withContext(Dispatchers.IO) {
        val token = authManager.accessToken.first() ?: return@withContext null
        try {
            val req = Request.Builder()
                .url("https://api.myanimelist.net/v2/users/@me")
                .header("Authorization", "Bearer $token")
                .build()
            httpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                JSONObject(response.body?.string() ?: return@withContext null)
            }
        } catch (_: Exception) { null }
    }

    suspend fun updateMangaStatus(
        malId: Int,
        status: String,
        score: Int? = null,
        numChaptersRead: Int? = null,
    ) = withContext(Dispatchers.IO) {
        val token = authManager.accessToken.first() ?: return@withContext
        try {
            val formBuilder = FormBody.Builder().add("status", status)
            if (score != null) formBuilder.add("score", score.toString())
            if (numChaptersRead != null) formBuilder.add("num_chapters_read", numChaptersRead.toString())
            val req = Request.Builder()
                .url("https://api.myanimelist.net/v2/manga/$malId/my_list_status")
                .header("Authorization", "Bearer $token")
                .patch(formBuilder.build())
                .build()
            httpClient.newCall(req).execute().close()
        } catch (_: Exception) { }
    }

    /** Stáhne uživatelův status/skóre uložený přímo na MAL (pro obousměrnou synchronizaci). */
    suspend fun getMyStatus(malId: Int): MalUserStatus? = withContext(Dispatchers.IO) {
        val token = authManager.accessToken.first() ?: return@withContext null
        try {
            val req = Request.Builder()
                .url("https://api.myanimelist.net/v2/manga/$malId?fields=my_list_status")
                .header("Authorization", "Bearer $token")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val json = JSONObject(resp.body?.string() ?: return@withContext null)
                val status = json.optJSONObject("my_list_status") ?: return@withContext null
                MalUserStatus(
                    status = status.optString("status").takeIf { it.isNotBlank() },
                    score = status.optInt("score", 0).takeIf { it > 0 },
                    numChaptersRead = status.optInt("num_chapters_read", 0).takeIf { it > 0 },
                )
            }
        } catch (_: Exception) { null }
    }

    fun openMalPage(context: Context, malId: Int) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://myanimelist.net/manga/$malId")))
    }
}
