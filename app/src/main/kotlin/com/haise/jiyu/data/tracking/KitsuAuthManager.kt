package com.haise.jiyu.data.tracking

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KitsuAuthManager @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val client: OkHttpClient,
) {
    companion object {
        private val KEY_TOKEN    = stringPreferencesKey("kitsu_access_token")
        private val KEY_REFRESH  = stringPreferencesKey("kitsu_refresh_token")
        private val KEY_USERNAME = stringPreferencesKey("kitsu_username")
        private val KEY_USER_ID  = stringPreferencesKey("kitsu_user_id")
        // Kitsu public OAuth client (documented, used by community apps)
        private const val CLIENT_ID     = "dd031b32d2f56c990b1425efe6c42ad847e7fe3ab46bf1299f05ecd856bdb7dd"
        private const val CLIENT_SECRET = "54d7307928f63414defd96399fc31ba847961ceaecef3a5fd93144e960c0e151"
        private const val TOKEN_URL     = "https://kitsu.app/api/oauth/token"
    }

    val isLoggedIn: Flow<Boolean> = dataStore.data.map { !it[KEY_TOKEN].isNullOrBlank() }
    val username:   Flow<String>  = dataStore.data.map { it[KEY_USERNAME] ?: "" }

    suspend fun getToken(): String? = dataStore.data.first()[KEY_TOKEN]
    suspend fun getUserId(): String? = dataStore.data.first()[KEY_USER_ID]

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
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext false
            val json = JSONObject(resp.body?.string() ?: return@withContext false)
            val token = json.optString("access_token").takeIf { it.isNotBlank() } ?: return@withContext false
            val refresh = json.optString("refresh_token")
            dataStore.edit { prefs ->
                prefs[KEY_TOKEN]    = token
                prefs[KEY_USERNAME] = email
                if (refresh.isNotBlank()) prefs[KEY_REFRESH] = refresh
            }
            true
        } catch (_: Exception) { false }
    }

    suspend fun saveUserId(id: String) = dataStore.edit { it[KEY_USER_ID] = id }

    suspend fun logout() = dataStore.edit {
        it.remove(KEY_TOKEN)
        it.remove(KEY_REFRESH)
        it.remove(KEY_USERNAME)
        it.remove(KEY_USER_ID)
    }
}
