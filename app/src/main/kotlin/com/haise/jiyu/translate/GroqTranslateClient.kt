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
import java.io.IOException
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
 * Tělo požadavku na translate-proxy. Čistá funkce (a ne pár řádků uvnitř
 * [GroqTranslateClient.translateViaProxy]) proto, aby šlo otestovat, co se na proxy
 * doopravdy posílá - bez HTTP a bez Androidu.
 *
 * [mangaContext] a [previousLines] jsou tu od chvíle, kdy se ukázalo, že záložní cesta
 * překládá naslepo: posílala jen holý seznam vět, přestože appka zná i dílo (název, typ,
 * žánry - viz [GeminiUltraPrompt.buildMangaContext]) i to, co padlo v předchozích bublinách.
 * Gemini cesta obojí dostávala, tahle ne, takže jakmile Gemini vypadl na kvótě, kvalita
 * spadla na doslovný překlad izolovaných vět (viz uživatelská zpětná vazba -
 * "JUST LEAVE ME HERE." přeložené jako "ZŮSTAŇTE MĚ TADY").
 *
 * Prázdný kontext se do těla nepřidává vůbec - proxy pak staví prompt jako dřív.
 */
internal fun buildProxyRequestBody(
    texts: List<String>,
    targetLanguage: String,
    sourceLanguage: String,
    glossary: Map<String, String>,
    mode: String,
    provider: String,
    mangaContext: String,
    previousLines: List<String>,
): String = JSONObject().apply {
    put("mode", mode)
    put("texts", JSONArray(texts))
    put("targetLanguage", targetLanguage)
    put("sourceLanguage", sourceLanguage)
    put("glossary", JSONObject(glossary))
    put("provider", provider)
    mangaContext.trim().takeIf { it.isNotEmpty() }?.let { put("context", it) }
    // Stejný rozpočet jako u Gemini cesty - kontext je krátký ocásek, ne druhá dávka.
    GeminiUltraPrompt.recentContextLines(previousLines).takeIf { it.isNotEmpty() }
        ?.let { put("recent", JSONArray(it)) }
}.toString()

/**
 * Volá Supabase Edge Function "translate-proxy", která teprve server-side volá Groq.
 * Groq API klíč NENÍ nikdy součástí appky (dřív byl v BuildConfig a šel triviálně
 * vytáhnout z veřejně distribuovaného APK) - žije jen jako Supabase secret.
 */
@Singleton
class GroqTranslateClient @Inject constructor(
    private val httpClient: OkHttpClient,
    private val providerHealth: ProviderHealth,
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
     * @param provider "groq" (výchozí) nebo "openrouter" - pro necílové jazyky jiné než
     *   čeština (viz [TranslateRepository.translatePage]) je tohle jediná záložní síť,
     *   protože [GeminiUltraPrompt] je psaný natvrdo pro češtinu.
     */
    suspend fun translateBatch(
        texts: List<String>,
        targetLanguage: String = "Czech",
        sourceLanguage: String = "Auto",
        glossary: Map<String, String> = emptyMap(),
        provider: String = "groq",
        mangaContext: String = "",
        previousLines: List<String> = emptyList(),
    ): List<String> = translateViaProxy(
        texts = texts,
        targetLanguage = targetLanguage,
        sourceLanguage = sourceLanguage,
        glossary = glossary,
        mode = "manga",
        provider = provider,
        mangaContext = mangaContext,
        previousLines = previousLines,
    )

    /**
     * Překlad odstavců light novel kapitoly - proxy použije odlišný prompt od manga
     * bublin (zachovává tón a odstavcovou strukturu prózy).
     *
     * @param provider "groq" (výchozí) nebo "openrouter" - viz [translateBatch], stejný
     *   parametr, jen dřív u novel cesty chyběl a volalo se natvrdo jen "groq" (viz
     *   [TranslateRepository.translateNovelChapter], kde teď při selhání zkusí i tenhle).
     */
    suspend fun translateNovelBatch(
        paragraphs: List<String>,
        targetLanguage: String = "Czech",
        sourceLanguage: String = "Auto",
        glossary: Map<String, String> = emptyMap(),
        provider: String = "groq",
    ): List<String> = translateViaProxy(
        texts = paragraphs,
        targetLanguage = targetLanguage,
        sourceLanguage = sourceLanguage,
        glossary = glossary,
        mode = "novel",
        provider = provider,
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
        provider: String = "groq",
        mangaContext: String = "",
        previousLines: List<String> = emptyList(),
    ): List<String> = withContext(Dispatchers.IO) {
        if (!isConfigured || texts.isEmpty()) return@withContext emptyList()
        // Provider odstavený z předchozí dávky se přeskočí bez requestu - viz [ProviderHealth].
        if (!providerHealth.isAvailable(provider)) return@withContext emptyList()

        val body = buildProxyRequestBody(
            texts = texts,
            targetLanguage = targetLanguage,
            sourceLanguage = sourceLanguage,
            glossary = glossary,
            mode = mode,
            provider = provider,
            mangaContext = mangaContext,
            previousLines = previousLines,
        )

        val request = Request.Builder()
            .url("${BuildConfig.SUPABASE_URL}/functions/v1/translate-proxy")
            .header("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        // Jednotlivé stránky/dávky občas selžou na přechodné síťové chybě nebo timeoutu
        // proxy/Groq - bez retry to dřív znamenalo natrvalo nepřeloženou bublinu, i když
        // druhý pokus o pár set ms později běžně projde. Opakuje se ale JEN takové selhání:
        // odmítnutí ze strany upstreamu (vyčerpaná kvóta, výpadek modelu) pozná proxy a
        // vrátí ho v poli "error" (viz UpstreamErrorCode v translate-proxy/index.ts), takže
        // se na jistě marný požadavek už nepálí další pokusy ani čekání. Rate limit samotné
        // proxy (429) se propaguje okamžitě jako RateLimitedException.
        repeat(MAX_ATTEMPTS) { attempt ->
            var retryable = false
            try {
                val result = httpClient.newCall(request).execute().use { resp ->
                    if (resp.code == 429) {
                        // Limit hlásí proxy, ne upstream - přes ni vedou všichni provideři stejně.
                        providerHealth.markAllUnavailable()
                        throw RateLimitedException()
                    }
                    if (!resp.isSuccessful) {
                        retryable = true
                        return@use null
                    }
                    val responseText = resp.body?.string()
                    if (responseText == null) {
                        retryable = true
                        return@use null
                    }
                    val json = JSONObject(responseText)
                    val error = json.optString("error").takeIf { it.isNotBlank() }
                    if (error != null && error != UPSTREAM_EMPTY) {
                        providerHealth.markUnavailable(provider)
                        return@use null
                    }
                    val arr = json.optJSONArray("translations") ?: return@use null
                    List(arr.length()) { arr.getString(it) }.also {
                        if (it.isNotEmpty()) providerHealth.markHealthy(provider)
                    }
                }
                if (result != null) return@withContext result
            } catch (e: RateLimitedException) {
                throw e
            } catch (_: IOException) {
                retryable = true // síť/timeout - druhý pokus o chvíli později běžně projde
            } catch (_: Exception) {
                // neparsovatelné tělo odpovědi - opakování to nespraví
            }
            if (!retryable) return@withContext emptyList()
            if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MILLIS)
        }
        emptyList()
    }

    private companion object {
        /**
         * Nižší než dřívější 3, protože OkHttp klient má navíc vlastní RetryInterceptor
         * (viz AppModule) - ten na IOException opakuje okamžitě, tenhle s prodlevou.
         */
        const val MAX_ATTEMPTS = 2
        const val RETRY_DELAY_MILLIS = 800L

        /** Viz UpstreamErrorCode v translate-proxy/index.ts - vlastnost dávky, ne providera. */
        const val UPSTREAM_EMPTY = "upstream_empty"
    }
}
