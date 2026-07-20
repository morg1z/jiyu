package com.haise.jiyu.translate

import com.haise.jiyu.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * Proxy vrátila 429 (denní/uživatelský limit počtu požadavků na translate-proxy) -
 * na rozdíl od běžné síťové chyby nemá smysl to zkoušet znovu (viz [GroqTranslateClient]),
 * volající (ReaderViewModel) by měl místo tichého selhání ukázat uživateli konkrétní
 * hlášku a přestat rozjíždět další stránky dávky.
 */
class RateLimitedException : Exception("Translation rate limit exceeded")

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

    /**
     * @throws RateLimitedException pokud proxy vrátí 429 - volající by to nemel tise
     *   spolknout jako "žádný text na stránce", ale ukázat konkrétní hlášku.
     */
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

        // Jednotlivé stránky/dávky občas selžou na přechodné síťové chybě nebo timeoutu
        // proxy/Groq - bez retry to dřív znamenalo natrvalo nepřeloženou bublinu, i když
        // druhý pokus o pár set ms později běžně projde. Rate limit (429) naopak retry
        // nezachrání, tam se propaguje okamžitě jako RateLimitedException.
        repeat(3) { attempt ->
            try {
                val result = httpClient.newCall(request).execute().use { resp ->
                    if (resp.code == 429) throw RateLimitedException()
                    if (!resp.isSuccessful) return@use null
                    val responseText = resp.body?.string() ?: return@use null
                    val arr = JSONObject(responseText).getJSONArray("translations")
                    List(arr.length()) { arr.getString(it) }
                }
                if (result != null) return@withContext result
            } catch (e: RateLimitedException) {
                throw e
            } catch (_: Exception) {
                // zkusime to znovu, viz delay nize; po vycerpani pokusu spadneme na emptyList()
            }
            if (attempt < 2) delay(800L * (attempt + 1))
        }
        emptyList()
    }
}
