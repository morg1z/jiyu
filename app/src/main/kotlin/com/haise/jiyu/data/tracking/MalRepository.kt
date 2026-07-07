package com.haise.jiyu.data.tracking

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.haise.jiyu.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

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

    fun openMalPage(context: Context, malId: Int) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://myanimelist.net/manga/$malId")))
    }
}
