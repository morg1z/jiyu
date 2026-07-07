package com.haise.jiyu.anilist

import com.haise.jiyu.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AniListRepository @Inject constructor(
    private val client: OkHttpClient,
    private val settings: SettingsRepository,
) {
    companion object {
        private const val API_URL = "https://graphql.anilist.co"
        // Zaregistruj app na anilist.co/settings/developer s redirect_uri = jiyu://anilist/callback
        const val CLIENT_ID = "YOUR_ANILIST_CLIENT_ID"
        private const val REDIRECT_URI = "jiyu://anilist/callback"
        val AUTH_URL: String get() =
            "https://anilist.co/api/v2/oauth/authorize?client_id=$CLIENT_ID" +
            "&redirect_uri=${URLEncoder.encode(REDIRECT_URI, "UTF-8")}&response_type=token"
    }

    val isAuthenticated: Flow<Boolean> = settings.aniListToken.map { !it.isNullOrBlank() }

    suspend fun handleCallback(token: String) = settings.saveAniListToken(token)

    suspend fun signOut() {
        settings.saveAniListToken(null)
        settings.saveAniListIdMap("{}")
    }

    /** Vyhledá mangu na AniList a vrátí její ID. Cachuje výsledek. */
    suspend fun resolveAniListId(mangaId: String, title: String): Int? {
        val mapJson = settings.aniListIdMap.first()
        val map = try { JSONObject(mapJson) } catch (_: Exception) { JSONObject() }
        if (map.has(mangaId)) {
            val cached = map.optInt(mangaId, 0)
            if (cached > 0) return cached
        }

        val token = settings.aniListToken.first() ?: return null
        val query = """query(${'$'}s:String){Media(search:${'$'}s,type:MANGA){id title{romaji}}}"""
        val body = JSONObject().apply {
            put("query", query)
            put("variables", JSONObject().apply { put("s", title) })
        }
        val aniId = graphql(body, token)
            ?.optJSONObject("data")
            ?.optJSONObject("Media")
            ?.optInt("id", 0)
            ?.takeIf { it > 0 } ?: return null

        map.put(mangaId, aniId)
        settings.saveAniListIdMap(map.toString())
        return aniId
    }

    /** Aktualizuje počet přečtených kapitol na AniList. */
    suspend fun updateProgress(mangaId: String, mangaTitle: String, chapterNumber: Float) {
        val token = settings.aniListToken.first() ?: return
        val aniId = resolveAniListId(mangaId, mangaTitle) ?: return
        val progress = chapterNumber.toInt()
        val mutation = """mutation(${'$'}id:Int,${'$'}p:Int){SaveMediaListEntry(mediaId:${'$'}id,progress:${'$'}p,status:CURRENT){id progress}}"""
        val body = JSONObject().apply {
            put("query", mutation)
            put("variables", JSONObject().apply { put("id", aniId); put("p", progress) })
        }
        graphql(body, token)
    }

    /** Nastaví skóre mangy na AniList (score 0-100). */
    suspend fun updateScore(mangaId: String, mangaTitle: String, score: Int) {
        val token = settings.aniListToken.first() ?: return
        val aniId = resolveAniListId(mangaId, mangaTitle) ?: return
        val body = JSONObject().apply {
            put("query", "mutation(\$id:Int,\$s:Int){SaveMediaListEntry(mediaId:\$id,score:\$s){id score}}")
            put("variables", JSONObject().apply { put("id", aniId); put("s", score) })
        }
        graphql(body, token)
    }

    private suspend fun graphql(body: JSONObject, token: String): JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val requestBody = body.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val request = Request.Builder()
                    .url(API_URL)
                    .post(requestBody)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .build()
                client.newCall(request).execute().use { resp ->
                    val str = resp.body?.string() ?: return@withContext null
                    JSONObject(str)
                }
            } catch (_: Exception) { null }
        }
}
