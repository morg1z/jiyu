package com.haise.jiyu.anilist

import com.haise.jiyu.BuildConfig
import com.haise.jiyu.security.SecureCredentialStore
import com.haise.jiyu.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val secureStore: SecureCredentialStore,
) {
    companion object {
        private const val API_URL = "https://graphql.anilist.co"
        private const val KEY_TOKEN = "anilist_access_token"
        private const val REDIRECT_URI = "jiyu://anilist/callback"
    }

    /** Chybí-li, appka nikdy nebyla zaregistrovaná na anilist.co/settings/developer - viz README. */
    private val clientId get() = BuildConfig.ANILIST_CLIENT_ID
    val hasClientId get() = clientId.isNotBlank()

    val authUrl: String get() =
        "https://anilist.co/api/v2/oauth/authorize?client_id=$clientId" +
        "&redirect_uri=${URLEncoder.encode(REDIRECT_URI, "UTF-8")}&response_type=token"

    private val mapMutex = Mutex()

    private val _token = MutableStateFlow(secureStore.get(KEY_TOKEN))
    val isAuthenticated: Flow<Boolean> = _token.map { !it.isNullOrBlank() }

    suspend fun handleCallback(token: String) = withContext(Dispatchers.IO) {
        secureStore.set(KEY_TOKEN, token)
        _token.value = token
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        secureStore.remove(KEY_TOKEN)
        _token.value = null
        settings.saveAniListIdMap("{}")
    }

    /** Vyhledá mangu na AniList a vrátí její ID. Cachuje výsledek. Mutex brání race condition. */
    suspend fun resolveAniListId(mangaId: String, title: String): Int? = mapMutex.withLock {
        val mapJson = settings.aniListIdMap.first()
        val map = try { JSONObject(mapJson) } catch (_: Exception) { JSONObject() }
        if (map.has(mangaId)) {
            val cached = map.optInt(mangaId, 0)
            if (cached > 0) return@withLock cached
        }

        val token = _token.value ?: return@withLock null
        val query = """query(${'$'}s:String){Media(search:${'$'}s,type:MANGA){id title{romaji}}}"""
        val body = JSONObject().apply {
            put("query", query)
            put("variables", JSONObject().apply { put("s", title) })
        }
        val aniId = graphql(body, token)
            ?.optJSONObject("data")
            ?.optJSONObject("Media")
            ?.optInt("id", 0)
            ?.takeIf { it > 0 } ?: return@withLock null

        map.put(mangaId, aniId)
        settings.saveAniListIdMap(map.toString())
        aniId
    }

    /** Zruší si lokálně zapamatovaný odkaz mangy na AniList - příští synchronizace ho znovu dohledá vyhledáním. */
    suspend fun unlink(mangaId: String) = mapMutex.withLock {
        val mapJson = settings.aniListIdMap.first()
        val map = try { JSONObject(mapJson) } catch (_: Exception) { JSONObject() }
        map.remove(mangaId)
        settings.saveAniListIdMap(map.toString())
    }

    data class AniListManga(val id: Int, val title: String, val coverUrl: String?)

    /** Vyhledá mangy na AniList podle názvu - pro ruční výběr správné shody, ne jen auto-resolve. */
    suspend fun searchManga(query: String): List<AniListManga> {
        val token = _token.value ?: return emptyList()
        val gql = """query(${'$'}s:String){Page(perPage:10){media(search:${'$'}s,type:MANGA){id title{romaji english}coverImage{medium}}}}"""
        val body = JSONObject().apply {
            put("query", gql)
            put("variables", JSONObject().apply { put("s", query) })
        }
        val results = graphql(body, token)
            ?.optJSONObject("data")
            ?.optJSONObject("Page")
            ?.optJSONArray("media") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val m = results.getJSONObject(i)
            val titleObj = m.optJSONObject("title")
            val title = titleObj?.optString("english")?.takeIf { it.isNotBlank() }
                ?: titleObj?.optString("romaji") ?: return@mapNotNull null
            AniListManga(
                id = m.getInt("id"),
                title = title,
                coverUrl = m.optJSONObject("coverImage")?.optString("medium"),
            )
        }
    }

    /** Ručně propojí mangu s konkrétním AniList ID (přes výsledek [searchManga]). */
    suspend fun linkManually(mangaId: String, aniListId: Int) = mapMutex.withLock {
        val mapJson = settings.aniListIdMap.first()
        val map = try { JSONObject(mapJson) } catch (_: Exception) { JSONObject() }
        map.put(mangaId, aniListId)
        settings.saveAniListIdMap(map.toString())
    }

    /** Aktualizuje počet přečtených kapitol na AniList. */
    suspend fun updateProgress(mangaId: String, mangaTitle: String, chapterNumber: Float) {
        val token = _token.value ?: return
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
        val token = _token.value ?: return
        val aniId = resolveAniListId(mangaId, mangaTitle) ?: return
        val body = JSONObject().apply {
            put("query", "mutation(\$id:Int,\$s:Int){SaveMediaListEntry(mediaId:\$id,score:\$s){id score}}")
            put("variables", JSONObject().apply { put("id", aniId); put("s", score) })
        }
        graphql(body, token)
    }

    data class AniListUserStatus(val status: String?, val progress: Int?, val score: Int?)

    /** Stáhne uživatelův status/progress/skóre uložený přímo na AniListu (obousměrná synchronizace). */
    suspend fun getMyStatus(mangaId: String, mangaTitle: String): AniListUserStatus? {
        val token = _token.value ?: return null
        val aniId = resolveAniListId(mangaId, mangaTitle) ?: return null
        val query = """query(${'$'}id:Int){Media(id:${'$'}id,type:MANGA){mediaListEntry{status progress score(format:POINT_100)}}}"""
        val body = JSONObject().apply {
            put("query", query)
            put("variables", JSONObject().apply { put("id", aniId) })
        }
        val entry = graphql(body, token)
            ?.optJSONObject("data")
            ?.optJSONObject("Media")
            ?.optJSONObject("mediaListEntry") ?: return null
        return AniListUserStatus(
            status = entry.optString("status").takeIf { it.isNotBlank() },
            progress = entry.optInt("progress", -1).takeIf { it >= 0 },
            score = entry.optInt("score", -1).takeIf { it >= 0 },
        )
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
