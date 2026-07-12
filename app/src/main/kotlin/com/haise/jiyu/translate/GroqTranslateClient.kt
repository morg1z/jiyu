package com.haise.jiyu.translate

import com.haise.jiyu.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Volá Supabase Edge Function "translate-proxy", která teprve server-side volá Groq.
 * Groq API klíč NENÍ nikdy součástí appky (dřív byl v BuildConfig a šel triviálně
 * vytáhnout z veřejně distribuovaného APK) - žije jen jako Supabase secret.
 */
@Singleton
class GroqTranslateClient @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    /** false = SUPABASE_URL není nakonfigurované v local.properties, překlad nemá šanci fungovat. */
    val isConfigured: Boolean get() = BuildConfig.SUPABASE_URL.isNotBlank() &&
        !BuildConfig.SUPABASE_URL.contains("placeholder")

    /**
     * Přeloží seznam textů v jednom volání proxy.
     * Vrátí seznam překladů ve stejném pořadí; při chybě vrátí prázdný list.
     *
     * @param glossary páry pojem→překlad (jména, techniky, přezdívky...), které musí model
     *   dodržet přesně - zajišťuje konzistenci napříč kapitolami místo toho, aby si model
     *   "vymýšlel" jiný překlad stejného jména pokaždé znovu.
     */
    suspend fun translateBatch(
        texts: List<String>,
        targetLanguage: String = "Czech",
        sourceLanguage: String = "Auto",
        glossary: Map<String, String> = emptyMap(),
    ): List<String> = translateViaProxy(
        texts = texts,
        targetLanguage = targetLanguage,
        sourceLanguage = sourceLanguage,
        glossary = glossary,
        mode = "manga",
    )

    /**
     * Překlad odstavců light novel kapitoly - proxy použije odlišný prompt od manga
     * bublin (zachovává tón a odstavcovou strukturu prózy).
     */
    suspend fun translateNovelBatch(
        paragraphs: List<String>,
        targetLanguage: String = "Czech",
        sourceLanguage: String = "Auto",
        glossary: Map<String, String> = emptyMap(),
    ): List<String> = translateViaProxy(
        texts = paragraphs,
        targetLanguage = targetLanguage,
        sourceLanguage = sourceLanguage,
        glossary = glossary,
        mode = "novel",
    )

    private suspend fun translateViaProxy(
        texts: List<String>,
        targetLanguage: String,
        sourceLanguage: String,
        glossary: Map<String, String>,
        mode: String,
    ): List<String> = withContext(Dispatchers.IO) {
        if (!isConfigured || texts.isEmpty()) return@withContext emptyList()

        val body = JSONObject().apply {
            put("mode", mode)
            put("texts", JSONArray(texts))
            put("targetLanguage", targetLanguage)
            put("sourceLanguage", sourceLanguage)
            put("glossary", JSONObject(glossary))
        }

        val request = Request.Builder()
            .url("${BuildConfig.SUPABASE_URL}/functions/v1/translate-proxy")
            .header("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val responseText = httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                resp.body?.string() ?: return@withContext emptyList()
            }
            val arr = JSONObject(responseText).getJSONArray("translations")
            List(arr.length()) { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
