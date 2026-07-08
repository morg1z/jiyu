package com.haise.jiyu.data.tracking

import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.SecureRandom
import android.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MalAuthManager @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val client: OkHttpClient,
) {
    companion object {
        private val KEY_ACCESS_TOKEN  = stringPreferencesKey("mal_access_token")
        private val KEY_REFRESH_TOKEN = stringPreferencesKey("mal_refresh_token")
        private val KEY_CODE_VERIFIER = stringPreferencesKey("mal_code_verifier")
        const val REDIRECT_URI = "jiyu://mal-auth"
        private const val TOKEN_URL = "https://myanimelist.net/v1/oauth2/token"
        private const val AUTH_URL   = "https://myanimelist.net/v1/oauth2/authorize"
    }

    val accessToken: Flow<String?> = dataStore.data.map { it[KEY_ACCESS_TOKEN] }
    val isLoggedIn:  Flow<Boolean> = accessToken.map { !it.isNullOrBlank() }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    suspend fun startOAuthFlow(clientId: String): Uri {
        val verifier = generateCodeVerifier()
        dataStore.edit { it[KEY_CODE_VERIFIER] = verifier }
        return Uri.parse(
            "$AUTH_URL?response_type=code&client_id=$clientId" +
            "&code_challenge=$verifier&code_challenge_method=plain" +
            "&redirect_uri=${Uri.encode(REDIRECT_URI)}"
        )
    }

    suspend fun handleCallback(code: String, clientId: String): Boolean {
        val verifier = dataStore.data.first()[KEY_CODE_VERIFIER] ?: return false
        return try {
            val body = FormBody.Builder()
                .add("client_id", clientId)
                .add("code", code)
                .add("code_verifier", verifier)
                .add("grant_type", "authorization_code")
                .add("redirect_uri", REDIRECT_URI)
                .build()
            val req = Request.Builder().url(TOKEN_URL).post(body).build()
            val response = client.newCall(req).execute().use { it.body?.string() ?: return false }
            val json = JSONObject(response)
            val accessToken = json.optString("access_token").takeIf { it.isNotBlank() } ?: return false
            val refreshToken = json.optString("refresh_token")
            dataStore.edit {
                it[KEY_ACCESS_TOKEN] = accessToken
                if (refreshToken.isNotBlank()) it[KEY_REFRESH_TOKEN] = refreshToken
            }
            true
        } catch (_: Exception) { false }
    }

    suspend fun refreshAccessToken(clientId: String): Boolean {
        val refresh = dataStore.data.first()[KEY_REFRESH_TOKEN] ?: return false
        return try {
            val body = FormBody.Builder()
                .add("client_id", clientId)
                .add("grant_type", "refresh_token")
                .add("refresh_token", refresh)
                .build()
            val req = Request.Builder().url(TOKEN_URL).post(body).build()
            val response = client.newCall(req).execute().use { it.body?.string() ?: return false }
            val json = JSONObject(response)
            val accessToken = json.optString("access_token").takeIf { it.isNotBlank() } ?: return false
            dataStore.edit {
                it[KEY_ACCESS_TOKEN] = accessToken
                json.optString("refresh_token").takeIf { it.isNotBlank() }?.let { rt ->
                    it[KEY_REFRESH_TOKEN] = rt
                }
            }
            true
        } catch (_: Exception) { false }
    }

    suspend fun logout() {
        dataStore.edit {
            it.remove(KEY_ACCESS_TOKEN)
            it.remove(KEY_REFRESH_TOKEN)
            it.remove(KEY_CODE_VERIFIER)
        }
    }
}
