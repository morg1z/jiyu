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

/**
 * OAuth klient pro oficialni Comick Library API (comick.dev/docs/oauth).
 *
 * Rozdily oproti [MalAuthManager], ktere Comick vyzaduje:
 * - `resource=https://api.comick.dev/integrations/v1` v authorize i token/refresh requestech,
 * - callback je reverse-domain scheme `com.haise.jiyu:/oauth/comick` (jiyu:// registrace odmitla),
 * - credentials se ukladaji jako JEDEN JSON blob pod klicem [KEY_AUTH] - refresh token se
 *   rotuje pri kazdem refreshi a Comick vyzaduje atomickou vymenu cele dvojice (zapis do
 *   EncryptedSharedPreferences pod jednim klicem je atomicky per commit),
 * - refresh token po uspesnem refreshi okamzite umira (bez grace periodu); po nejednoznacnem
 *   sitovem selhani refresh nesmime automaticky opakovat - misto toho se pozada o reconnect,
 *   jinak hrozi detekce reuse a zruseni cele token family.
 */
@Singleton
class ComickAuthManager @Inject constructor(
    private val secureStore: SecureCredentialStore,
    private val client: OkHttpClient,
) {
    companion object {
        private const val KEY_AUTH          = "comick_auth"            // JSON blob viz StoredAuth
        private const val KEY_CODE_VERIFIER = "comick_code_verifier"
        private const val KEY_OAUTH_STATE   = "comick_oauth_state"
        const val REDIRECT_URI = "com.haise.jiyu:/oauth/comick"
        const val SCOPE_WRITE = "library:write"
        private const val SCOPE_BASE  = "library:read offline_access"
        private const val AUTH_URL    = "https://comick.dev/api/auth/oauth2/authorize"
        private const val TOKEN_URL   = "https://comick.dev/api/auth/oauth2/token"
        private const val REVOKE_URL  = "https://comick.dev/api/auth/oauth2/revoke"
        private const val RESOURCE    = "https://api.comick.dev/integrations/v1"
        /** Rezerva - access token povazujeme za prosly driv, nez skutecne vyprsi (900 s). */
        private const val EXPIRY_MARGIN_MS = 30_000L
    }

    /** Ulozene credentials jako jeden JSON - atomicka vymena pri rotaci refresh tokenu. */
    private data class StoredAuth(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAt: Long,
        val scope: String,
    ) {
        fun toJson(): String = JSONObject()
            .put("access_token", accessToken)
            .put("refresh_token", refreshToken ?: JSONObject.NULL)
            .put("expires_at", expiresAt)
            .put("scope", scope)
            .toString()

        companion object {
            fun fromJson(raw: String?): StoredAuth? = try {
                val j = JSONObject(raw ?: return null)
                StoredAuth(
                    accessToken = j.getString("access_token"),
                    refreshToken = if (j.isNull("refresh_token")) null else j.getString("refresh_token"),
                    expiresAt = j.getLong("expires_at"),
                    scope = j.optString("scope"),
                )
            } catch (_: Exception) { null }
        }
    }

    private fun loadAuth(): StoredAuth? = StoredAuth.fromJson(secureStore.get(KEY_AUTH))

    private val _auth = MutableStateFlow(loadAuth())
    val isConnected: Flow<Boolean> = _auth.map { it != null }
    /** Skutecne udelene scope z token response - UI podle nej zapina write funkce. */
    val grantedScope: Flow<String> = _auth.map { it?.scope.orEmpty() }
    val hasWriteScope: Flow<Boolean> = _auth.map { it?.scope?.contains(SCOPE_WRITE) == true }

    /** true po selhani auth, ktere vyzaduje rucni reconnect (ne jen sitovy vypadek). */
    private val _needsReconnect = MutableStateFlow(false)
    val needsReconnect: Flow<Boolean> = _needsReconnect.asStateFlow()

    private fun randomUrlSafeToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun codeChallengeS256(verifier: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /**
     * URL pro systemovy browser. [includeWrite]=true se pouziva jen pri re-auth po zapnuti
     * obousmerne sync - zakladni connect zada `library:read offline_access`.
     */
    suspend fun startOAuthFlow(clientId: String, includeWrite: Boolean = false): Uri = withContext(Dispatchers.IO) {
        val verifier = randomUrlSafeToken()
        val state = randomUrlSafeToken()
        secureStore.set(KEY_CODE_VERIFIER, verifier)
        secureStore.set(KEY_OAUTH_STATE, state)
        val scope = if (includeWrite) "$SCOPE_BASE $SCOPE_WRITE" else SCOPE_BASE
        Uri.parse(
            "$AUTH_URL?response_type=code&client_id=${Uri.encode(clientId)}" +
            "&redirect_uri=${Uri.encode(REDIRECT_URI)}" +
            "&scope=${Uri.encode(scope)}" +
            "&resource=${Uri.encode(RESOURCE)}" +
            "&state=${Uri.encode(state)}" +
            "&code_challenge=${codeChallengeS256(verifier)}&code_challenge_method=S256"
        )
    }

    /** viz [MalAuthManager.handleCallback] - state musi presne sedet, jinak CSRF. */
    suspend fun handleCallback(code: String, clientId: String, state: String?): Boolean = withContext(Dispatchers.IO) {
        val verifier = secureStore.get(KEY_CODE_VERIFIER) ?: return@withContext false
        val expectedState = secureStore.get(KEY_OAUTH_STATE)
        if (state.isNullOrBlank() || expectedState.isNullOrBlank() || state != expectedState) return@withContext false
        try {
            val body = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("code_verifier", verifier)
                .add("resource", RESOURCE)
                .add("client_id", clientId)
                .build()
            val req = Request.Builder().url(TOKEN_URL).post(body).build()
            val response = client.newCall(req).execute().use { it.body?.string() ?: return@withContext false }
            val json = JSONObject(response)
            val accessToken = json.optString("access_token").takeIf { it.isNotBlank() } ?: return@withContext false
            saveAuth(
                StoredAuth(
                    accessToken = accessToken,
                    refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
                    expiresAt = System.currentTimeMillis() + json.optLong("expires_in", 900L) * 1000L,
                    scope = json.optString("scope"),
                )
            )
            _needsReconnect.value = false
            secureStore.remove(KEY_CODE_VERIFIER, KEY_OAUTH_STATE)
            true
        } catch (_: Exception) {
            // Neponechat polotovar PKCE par - pristi startOAuthFlow ho stejne prepise,
            // ale ocistene je to deterministictejsi (replay selze hned na verifieru).
            secureStore.remove(KEY_CODE_VERIFIER, KEY_OAUTH_STATE)
            false
        }
    }

    /**
     * Platny access token: cerstvy rovnou, prosly/tesne pred expiraci nejdriv obnovi pres
     * [refreshTokens]. null = nepripojeno nebo obnova selhala (viz [needsReconnect]).
     */
    suspend fun getAccessToken(clientId: String): String? {
        val auth = _auth.value ?: return null
        if (auth.expiresAt - EXPIRY_MARGIN_MS > System.currentTimeMillis()) return auth.accessToken
        return if (refreshTokens(clientId, staleToken = auth.accessToken)) _auth.value?.accessToken else null
    }

    /**
     * Rotujici refresh: Comick po uspesnem refreshi okamzite zneplatni stary refresh token,
     * takze dvojice se musi ulozit atomicky (jeden JSON blob) a nesmi bezet dvakrat soubezne
     * (Mutex + stale-token guard - viz MalAuthManager).
     *
     * Chyby: HTTP 400/401 = invalid_grant -> odhlasit a chtit reconnect. Sitova chyba je
     * NEJEDNOZNACNA (server mohl refresh zpracovat a odpoved se ztratila). Dokumentace zakazuje
     * automaticky retry - proto tokeny NECHAME a rovnou vratime false; dalsi prirozeny pokus
     * o [getAccessToken] refresh zopakuje: pri offline vypadku projde, pri skutecne rotaci
     * dostane 400/401 -> markDisconnected. Horsi scenar (reuse -> revoke rodiny) konci stejne
     * jako okamzity reconnect, lepsi scenar uzivateli usetri prihlaseni.
     */
    suspend fun refreshTokens(clientId: String, staleToken: String? = null): Boolean = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            val current = _auth.value
            if (staleToken != null && current != null && current.accessToken != staleToken) return@withContext true
            val refresh = current?.refreshToken ?: return@withContext false
            try {
                val body = FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refresh)
                    .add("resource", RESOURCE)
                    .add("client_id", clientId)
                    .build()
                val req = Request.Builder().url(TOKEN_URL).post(body).build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        if (resp.code == 400 || resp.code == 401) markDisconnected()
                        return@withContext false
                    }
                    val json = JSONObject(text)
                    val accessToken = json.optString("access_token").takeIf { it.isNotBlank() } ?: return@withContext false
                    saveAuth(
                        StoredAuth(
                            accessToken = accessToken,
                            refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: refresh,
                            expiresAt = System.currentTimeMillis() + json.optLong("expires_in", 900L) * 1000L,
                            scope = json.optString("scope").takeIf { it.isNotBlank() } ?: current.scope,
                        )
                    )
                    _needsReconnect.value = false
                    true
                }
            } catch (_: Exception) {
                // Ambiguous failure - zadny automaticky retry (viz KDoc vyse). Dalsi
                // getAccessToken to zkusí znovu a pripadne 400/401 udela reconnect.
                false
            }
        }
    }

    private val refreshMutex = Mutex()

    private fun saveAuth(auth: StoredAuth) {
        secureStore.set(KEY_AUTH, auth.toJson())
        _auth.value = auth
    }

    /** Smaze lokalni credentials bez nastaveni [needsReconnect] - pro vedomy logout. */
    private fun clearAuth() {
        secureStore.remove(KEY_AUTH, KEY_CODE_VERIFIER, KEY_OAUTH_STATE)
        _auth.value = null
    }

    /** Nedobrovolne odpojeni (revokovany/expirovany refresh) - UI pozada o reconnect. */
    private fun markDisconnected() {
        clearAuth()
        _needsReconnect.value = true
    }

    /**
     * Odpojeni: zrusi na Comicku refresh i access token (docs: "revoke each credential you
     * hold"), pak smaze lokalni credentials. Selhani revoke lokalni odpojeni neblokuje.
     * Nikdy nesahá na lokalni knihovnu - ta zustava nedotcena.
     *
     * Bezi pod refreshMutex: soubezne probabilajici refreshTokens by jinak dokoncil
     * saveAuth az PO vymazu a uzivatel by zustal "pripojeny" navzdory logoutu.
     * Vedome odpojeni flag reconnect nevysvi - markDisconnected je jen pro nedobrovolne
     * ztraty spojeni (400/401 pri refreshi), jinak settings ukazuje cervene upozorneni
     * i uzivateli, co se zamerne odhlasil.
     */
    suspend fun disconnect(clientId: String) = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            val auth = _auth.value
            if (auth != null) {
                for ((token, hint) in listOfNotNull(
                    auth.refreshToken?.let { it to "refresh_token" },
                    auth.accessToken to "access_token",
                )) {
                    runCatching {
                        val body = FormBody.Builder()
                            .add("token", token)
                            .add("token_type_hint", hint)
                            .add("client_id", clientId)
                            .build()
                        client.newCall(Request.Builder().url(REVOKE_URL).post(body).build()).execute().close()
                    }
                }
            }
            clearAuth()
            _needsReconnect.value = false
        }
    }
}
