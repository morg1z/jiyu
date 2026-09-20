package com.haise.jiyu.data.tracking

import com.haise.jiyu.security.SecureCredentialStore
import com.haise.jiyu.util.report
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KitsuAuthManager @Inject constructor(
    private val secureStore: SecureCredentialStore,
    private val client: OkHttpClient,
) {
    companion object {
        private const val KEY_TOKEN    = "kitsu_access_token"
        private const val KEY_REFRESH  = "kitsu_refresh_token"
        private const val KEY_USERNAME = "kitsu_username"
        private const val KEY_USER_ID  = "kitsu_user_id"
        // Kitsu public OAuth client (documented, used by community apps)
        private const val CLIENT_ID     = "dd031b32d2f56c990b1425efe6c42ad847e7fe3ab46bf1299f05ecd856bdb7dd"
        private const val CLIENT_SECRET = "54d7307928f63414defd96399fc31ba847961ceaecef3a5fd93144e960c0e151"
        private const val TOKEN_URL     = "https://kitsu.app/api/oauth/token"
    }

    private val _token = MutableStateFlow(secureStore.get(KEY_TOKEN))
    private val _username = MutableStateFlow(secureStore.get(KEY_USERNAME) ?: "")

    val isLoggedIn: Flow<Boolean> = _token.map { !it.isNullOrBlank() }
    val username:   Flow<String>  = _username.asStateFlow()

    suspend fun getToken(): String? = withContext(Dispatchers.IO) { secureStore.get(KEY_TOKEN) }
    suspend fun getUserId(): String? = withContext(Dispatchers.IO) { secureStore.get(KEY_USER_ID) }

    suspend fun login(email: String, password: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = FormBody.Builder()
                .add("grant_type", "password")
                .add("username", email)
                .add("password", password)
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .build()
            val req = Request.Builder().url(TOKEN_URL).post(body).build()
            // .use{} je tu POVINNÉ - uvnitř jsou tři brzké returny (neúspěšný status,
            // prázdné tělo, chybějící token) a bez něj každý z nich nechal viset otevřené
            // spojení; nezdařené přihlášení tak pokaždé uniklo jeden socket z poolu.
            val token = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val json = JSONObject(resp.body?.string() ?: return@withContext false)
                val access = json.optString("access_token").takeIf { it.isNotBlank() }
                    ?: return@withContext false
                json.optString("refresh_token").takeIf { it.isNotBlank() }
                    ?.let { secureStore.set(KEY_REFRESH, it) }
                access
            }
            secureStore.set(KEY_TOKEN, token)
            secureStore.set(KEY_USERNAME, email)
            _token.value = token
            _username.value = email
            true
        } catch (e: Exception) {
            e.report("tracking:kitsu:login")
            false
        }
    }

    /**
     * Obnoví access token z uloženého refresh tokenu (dřív se refresh token ukládal, ale nikdy nepoužil,
     * takže po vypršení access tokenu sync do Kitsu tiše přestal - audit nález JIYU-ARCH-1). Mutex +
     * [staleToken] mají stejný smysl jako u [MalAuthManager.refreshAccessToken]: souběžné požadavky
     * neobnovují dvakrát. Odmítnutý refresh token (400/401) znamená nutnost nového přihlášení.
     */
    suspend fun refresh(staleToken: String? = null): Boolean = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            val current = secureStore.get(KEY_TOKEN)
            if (staleToken != null && !current.isNullOrBlank() && current != staleToken) return@withContext true
            val refreshToken = secureStore.get(KEY_REFRESH) ?: return@withContext false
            try {
                val body = FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .add("client_id", CLIENT_ID)
                    .add("client_secret", CLIENT_SECRET)
                    .build()
                val req = Request.Builder().url(TOKEN_URL).post(body).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        if (resp.code == 400 || resp.code == 401) clearSession()
                        return@withContext false
                    }
                    val json = JSONObject(resp.body?.string() ?: return@withContext false)
                    val access = json.optString("access_token").takeIf { it.isNotBlank() } ?: return@withContext false
                    json.optString("refresh_token").takeIf { it.isNotBlank() }?.let { secureStore.set(KEY_REFRESH, it) }
                    secureStore.set(KEY_TOKEN, access)
                    _token.value = access
                    true
                }
            } catch (e: Exception) {
                e.report("tracking:kitsu:refresh")
                false
            }
        }
    }

    private val refreshMutex = Mutex()

    private fun clearSession() {
        secureStore.remove(KEY_TOKEN, KEY_REFRESH, KEY_USERNAME, KEY_USER_ID)
        _token.value = null
        _username.value = ""
    }

    suspend fun saveUserId(id: String) = withContext(Dispatchers.IO) { secureStore.set(KEY_USER_ID, id) }

    suspend fun logout() = withContext(Dispatchers.IO) {
        secureStore.remove(KEY_TOKEN, KEY_REFRESH, KEY_USERNAME, KEY_USER_ID)
        _token.value = null
        _username.value = ""
    }
}
