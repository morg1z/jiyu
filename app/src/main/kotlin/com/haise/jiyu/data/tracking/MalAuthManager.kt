package com.haise.jiyu.data.tracking

import android.net.Uri
import com.haise.jiyu.security.SecureCredentialStore
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
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MalAuthManager @Inject constructor(
    private val secureStore: SecureCredentialStore,
    private val client: OkHttpClient,
) {
    companion object {
        private const val KEY_ACCESS_TOKEN  = "mal_access_token"
        private const val KEY_REFRESH_TOKEN = "mal_refresh_token"
        private const val KEY_CODE_VERIFIER = "mal_code_verifier"
        private const val KEY_OAUTH_STATE   = "mal_oauth_state"
        const val REDIRECT_URI = "jiyu://mal-auth"
        private const val TOKEN_URL = "https://myanimelist.net/v1/oauth2/token"
        private const val AUTH_URL   = "https://myanimelist.net/v1/oauth2/authorize"
    }

    private val _accessToken = MutableStateFlow(secureStore.get(KEY_ACCESS_TOKEN))
    val accessToken: Flow<String?> = _accessToken.asStateFlow()
    val isLoggedIn:  Flow<Boolean> = accessToken.map { !it.isNullOrBlank() }

    private fun randomUrlSafeToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun codeChallengeS256(verifier: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    suspend fun startOAuthFlow(clientId: String): Uri = withContext(Dispatchers.IO) {
        val verifier = randomUrlSafeToken()
        val state = randomUrlSafeToken()
        secureStore.set(KEY_CODE_VERIFIER, verifier)
        secureStore.set(KEY_OAUTH_STATE, state)
        Uri.parse(
            "$AUTH_URL?response_type=code&client_id=$clientId" +
            "&code_challenge=${codeChallengeS256(verifier)}&code_challenge_method=S256" +
            "&state=${Uri.encode(state)}" +
            "&redirect_uri=${Uri.encode(REDIRECT_URI)}"
        )
    }

    /**
     * [state] musí přesně odpovídat hodnotě vygenerované v [startOAuthFlow] a uložené přes
     * [SecureCredentialStore] - jinak jde o CSRF pokus (útočník podstrčí vlastní autorizační
     * kód přes cizí redirect) a `code` se vůbec nesmí vyměnit za token. `null` (chybějící
     * parametr v callbacku) se bere stejně jako neshoda.
     */
    suspend fun handleCallback(code: String, clientId: String, state: String?): Boolean = withContext(Dispatchers.IO) {
        val verifier = secureStore.get(KEY_CODE_VERIFIER) ?: return@withContext false
        val expectedState = secureStore.get(KEY_OAUTH_STATE)
        if (state.isNullOrBlank() || expectedState.isNullOrBlank() || state != expectedState) return@withContext false
        try {
            val body = FormBody.Builder()
                .add("client_id", clientId)
                .add("code", code)
                .add("code_verifier", verifier)
                .add("grant_type", "authorization_code")
                .add("redirect_uri", REDIRECT_URI)
                .build()
            val req = Request.Builder().url(TOKEN_URL).post(body).build()
            val response = client.newCall(req).execute().use { it.body?.string() ?: return@withContext false }
            val json = JSONObject(response)
            val accessToken = json.optString("access_token").takeIf { it.isNotBlank() } ?: return@withContext false
            val refreshToken = json.optString("refresh_token")
            secureStore.set(KEY_ACCESS_TOKEN, accessToken)
            if (refreshToken.isNotBlank()) secureStore.set(KEY_REFRESH_TOKEN, refreshToken)
            _accessToken.value = accessToken
            secureStore.remove(KEY_CODE_VERIFIER, KEY_OAUTH_STATE)
            true
        } catch (_: Exception) { false }
    }

    /**
     * Mutex - MAL při obnově REFRESH token rotuje, takže dvě souběžné obnovy by druhou zneplatnily
     * (druhá by použila už spotřebovaný refresh token). [staleToken] = access token, se kterým selhal
     * požadavek, který obnovu vyvolal; pokud už je uložený jiný, obnovil ho mezitím souběžný požadavek.
     *
     * @return true = platný access token je uložený (obnovený teď nebo souběžně), false = obnova selhala.
     *   Odmítnutý refresh token (HTTP 400/401, "invalid_grant") = uživatel se musí přihlásit znovu, takže
     *   se lokální přihlášení zruší; síťová chyba tokeny nechává.
     */
    suspend fun refreshAccessToken(clientId: String, staleToken: String? = null): Boolean = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            val current = secureStore.get(KEY_ACCESS_TOKEN)
            if (staleToken != null && !current.isNullOrBlank() && current != staleToken) return@withContext true
            val refresh = secureStore.get(KEY_REFRESH_TOKEN) ?: return@withContext false
            try {
                val body = FormBody.Builder()
                    .add("client_id", clientId)
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refresh)
                    .build()
                val req = Request.Builder().url(TOKEN_URL).post(body).build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        if (resp.code == 400 || resp.code == 401) clearTokens()
                        return@withContext false
                    }
                    val json = JSONObject(text)
                    val accessToken = json.optString("access_token").takeIf { it.isNotBlank() } ?: return@withContext false
                    secureStore.set(KEY_ACCESS_TOKEN, accessToken)
                    json.optString("refresh_token").takeIf { it.isNotBlank() }?.let { rt -> secureStore.set(KEY_REFRESH_TOKEN, rt) }
                    _accessToken.value = accessToken
                    true
                }
            } catch (_: Exception) { false }
        }
    }

    private val refreshMutex = Mutex()

    private fun clearTokens() {
        secureStore.remove(KEY_ACCESS_TOKEN, KEY_REFRESH_TOKEN, KEY_CODE_VERIFIER, KEY_OAUTH_STATE)
        _accessToken.value = null
    }

    suspend fun logout() = withContext(Dispatchers.IO) { clearTokens() }
}
