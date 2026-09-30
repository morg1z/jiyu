package com.haise.jiyu.translate

import com.haise.jiyu.util.executeCancellable
import android.util.Log
import com.haise.jiyu.BuildConfig
import com.haise.jiyu.di.TranslateProxyHttpClient
import com.haise.jiyu.util.report
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Volá stejnou Supabase Edge Function "translate-proxy" jako [GroqTranslateClient], ale
 * novým "gemini" módem - ten na rozdíl od "manga"/"novel" módu neposílá jen holé texty,
 * posílá HOTOVÝ system+user prompt postavený v [GeminiUltraPrompt] (kompresní pravidla,
 * glosář, JSON schema - vše je verzovatelné v Kotlinu, ne skryté server-side).
 *
 * Od verze 10 proxy funkce umí ten samý "gemini" mód obsloužit i přes Groq (parametr
 * [provider] = "groq") - stejný system+user prompt, jen jiný upstream model. Díky tomu
 * komprese/sylabické dělení z [GeminiUltraPrompt] fungují i když samotné Gemini selže
 * (deprekovaný model, jeho vlastní výpadek), místo aby appka spadla na holý Groq překlad
 * bez těchhle pravidel - viz [TranslateRepository.translateWithGemini].
 *
 * Od verze 12 (2026-07-26) umí proxy stejný "gemini" mód obsloužit i přes OpenRouter
 * free-tier model (parametr [provider] = "openrouter") jako čtvrtou úroveň zálohy, než
 * appka klesne na holý Groq bez komprese - viz [TranslateRepository.translatePage].
 *
 * Google AI Studio / Groq / OpenRouter API klíč NENÍ nikde v appce - proxy je vloží
 * server-side ze Supabase secretů. Přímé volání z appky s klíčem v hlavičce by šlo
 * dekompilací APK triviálně ukrást a zneužít na cizí free-tier kvótu.
 */
@Singleton
class GeminiTranslateClient @Inject constructor(
    @TranslateProxyHttpClient private val httpClient: OkHttpClient,
    private val providerHealth: ProviderHealth,
) {
    val isConfigured: Boolean get() = BuildConfig.SUPABASE_URL.isNotBlank() &&
        !BuildConfig.SUPABASE_URL.contains("placeholder")

    /**
     * Přeloží dávku bublin jedné stránky. SFX bubliny (viz [ClassifiedBubble.isSfx]) se
     * do requestu vůbec nezahrnují - filtruje se `bubbles` před
     * [GeminiUltraPrompt.buildUserPrompt] a id odpovědí se přemapují zpátky na
     * pozice v nefiltrovaném seznamu (viz [remapToOriginalIndices]).
     *
     * @param provider "gemini" (výchozí), "groq" nebo "openrouter" - viz komentář u třídy. Groq i
     *   OpenRouter model se nastavují server-side (Groq: "openai/gpt-oss-120b" jako
     *   [GroqTranslateClient] - dřív "llama-3.3-70b-versatile", to Groq k 16.8.2026 vyřadil;
     *   OpenRouter: free-tier model, viz OPENROUTER_MODEL v translate-proxy), appka je nemusí
     *   posílat.
     * @return null při selhání (síť, vyčerpaná kvóta upstreamu, neparsovatelná odpověď) i tehdy,
     *   když je provider zrovna odstavený v [ProviderHealth] - v tom případě se neposílá vůbec
     *   žádný požadavek a volající rovnou pokračuje dalším krokem řetězce.
     * @throws RateLimitedException když je vyčerpaná sdílená denní kvóta SAMOTNÉ proxy - viz
     *   [GroqTranslateClient]. Na rozdíl od kvóty upstreamu tohle znamená, že přes proxy
     *   neprojde ani jeden další provider, proto se odstaví všichni najednou.
     */
    suspend fun translateBubbles(
        bubbles: List<ClassifiedBubble>,
        glossary: Map<String, String>,
        provider: String = "gemini",
        mangaContext: String = "",
        previousLines: List<String> = emptyList(),
    ): GeminiTranslationResponse? = withContext(Dispatchers.IO) {
        // TR-8: do promptu jdou jen skutečně překládané bubliny. Dřív se posílal celý
        // `bubbles` včetně SFX - model je "překládal", odpověď se pak zahazovala přes
        // sfxBlock, takže se plýtvalo tokeny i id sloty (docstring výš uváděl opak).
        // Ids v promptu/odpovědi jsou teď pozice ve FILTROVANÉM seznamu - po parse se
        // přemapují zpátky na indexy v původním `bubbles`, jak volající přes byId[i]
        // očekává, ať repository cesta zůstane beze změny.
        val toTranslate = bubbles.filter { !it.isSfx }
        if (!isConfigured || toTranslate.isEmpty()) return@withContext null
        // Provider, o kterém z předchozí dávky víme, že odmítá obsluhu, se přeskočí bez
        // jediného requestu - tohle je hlavní úspora u dlouhé kapitoly, viz ProviderHealth.
        if (!providerHealth.isAvailable(provider)) return@withContext null
        val origIndex = bubbles.mapIndexedNotNull { i, b -> if (!b.isSfx) i else null }

        val raw = sendPrompt(
            system = GeminiUltraPrompt.buildSystemPrompt(glossary, mangaContext),
            user = GeminiUltraPrompt.buildUserPrompt(toTranslate, previousLines),
            provider = provider,
        ) ?: return@withContext null

        return@withContext try {
            GeminiUltraPrompt.parseResponse(raw.text)
                .remapToOriginalIndices(origIndex)
                .copy(model = raw.model)
        } catch (e: Exception) {
            // Useknutá odpověď (model narazil na output limit) - kompletní bubliny z
            // validního prefixu zachráníme, ocásek dořekne opravný dotaz v repository.
            // Bez tohohle se zahodila celá dávka i když jí byla většina čitelná.
            val salvaged = GeminiUltraPrompt.parseTruncatedResponse(raw.text)
            if (salvaged != null && salvaged.bubbles.isNotEmpty()) {
                Log.w(LOG_TAG, "translate:$provider: useknutá odpověď - zachráněno ${salvaged.bubbles.size} bublin z prefixu")
                e.report("translate:gemini:parseResponse:provider=$provider:salvaged")
                return@withContext salvaged.remapToOriginalIndices(origIndex).copy(model = raw.model)
            }
            // Neparsovatelná odpověď - nemá smysl retryovat, model to znovu nespraví.
            // Hlásíme ale ven: tohle je přesně ten druh tiché chyby, kdy se překlad
            // "prostě neudělá" a bez hlášení není podle čeho zjistit proč.
            e.report("translate:gemini:parseResponse:provider=$provider")
            null
        }
    }

    /**
     * Self-review dávka ([TranslationReview]) - posílá dvojice zdroj->překlad zpět
     * přes stejný proxy řetězec a vrací SUROVÝ text odpovědi (JSON s verdicty).
     * Výsledek se nikdy neparsuje na GeminiTranslationResponse - má jiné schéma.
     */
    suspend fun reviewPairs(
        pairs: List<Pair<String, String>>,
        provider: String = "gemini",
        targetLanguage: String = "Czech",
    ): String? = withContext(Dispatchers.IO) {
        if (!isConfigured || pairs.isEmpty()) return@withContext null
        if (!providerHealth.isAvailable(provider)) return@withContext null
        sendPrompt(
            system = TranslationReview.buildSystemPrompt(targetLanguage),
            user = TranslationReview.buildUserPrompt(pairs),
            provider = provider,
        )?.text
    }

    /**
     * Přemapuje `id` v odpovědi z pozic ve filtrovaném seznamu (bez SFX - ten dostal
     * [GeminiUltraPrompt.buildUserPrompt]) zpátky na pozice v původním seznamu
     * bublin, jak je repository páruje přes `byId[i]`. Id mimo rozsah (halucinace
     * modelu) zůstane jak je - prostě se nepáruje a skončí jako chybějící.
     */
    private fun GeminiTranslationResponse.remapToOriginalIndices(origIndex: List<Int>): GeminiTranslationResponse =
        copy(bubbles = bubbles.map { b -> b.copy(id = origIndex.getOrElse(b.id) { b.id }) })

    private data class RawReply(val text: String, val model: String?)

    /**
     * Sdílené poslání system+user promptu přes proxy - používá jak překladová dávka,
     * tak review dávka. Opakuje se JEN přechodné selhání (viz ProxyOutcome.Retryable).
     * Dřív se opakovala i odpověď proxy s prázdným textem - jenže tak vypadalo i
     * natvrdo vyčerpané Gemini, takže se na jistě marný požadavek pálily pokusy
     * a prodleva, a to na každém providerovi každé dávky kapitoly.
     */
    private suspend fun sendPrompt(system: String, user: String, provider: String): RawReply? {
        val requestBody = JSONObject().apply {
            put("mode", "gemini")
            put("provider", provider)
            if (provider == "gemini") put("model", GeminiUltraPrompt.MODEL)
            put("system", system)
            put("user", user)
        }

        val request = Request.Builder()
            .url("${BuildConfig.SUPABASE_URL}/functions/v1/translate-proxy")
            .header("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .header("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        repeat(MAX_ATTEMPTS) { attempt ->
            when (val outcome = executeOnce(request, provider)) {
                is ProxyOutcome.Text -> return RawReply(outcome.value, outcome.model)
                ProxyOutcome.ProviderDown, ProxyOutcome.BatchFailed -> return null
                ProxyOutcome.Retryable -> if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MILLIS)
            }
        }
        return null
    }

    /**
     * Jeden pokus o zavolání proxy. Vyhodnocuje jak HTTP status, tak pole "error" v těle
     * odpovědi - proxy totiž selhání upstreamu vrací se statusem 200 (viz UpstreamErrorCode
     * v translate-proxy/index.ts), aby starší verze appky, které to pole neznají, dál
     * fungovaly beze změny.
     */
    private suspend fun executeOnce(request: Request, provider: String): ProxyOutcome = try {
        httpClient.newCall(request).executeCancellable { resp ->
            if (resp.code == 429) {
                // Limit hlásí sama proxy, ne upstream - přes ni vedou všichni provideři stejně,
                // takže zkoušet zbytek řetězce je jen ztráta času.
                providerHealth.markAllUnavailable()
                throw RateLimitedException()
            }
            // Jen 5xx (prechodne selhani proxy) je hodne opakovat - trvala 4xx chyba (spatne
            // sestaveny request...) by druhy pokus stejne nikdy nespravil, jen by zbytecne
            // ztratil cas na RETRY_DELAY_MILLIS pred padem na dalsiho providera v retezci
            // (stejna oprava jako GroqTranslateClient, ktery volá stejnou proxy).
            if (!resp.isSuccessful) return@executeCancellable if (resp.code in 500..599) ProxyOutcome.Retryable else ProxyOutcome.BatchFailed
            val body = resp.body?.string() ?: return@executeCancellable ProxyOutcome.Retryable
            val jsonBody = JSONObject(body)
            when (val error = jsonBody.optString("error").takeIf { it.isNotBlank() }) {
                null -> jsonBody.optString("text").takeIf { it.isNotBlank() }
                    ?.let { text ->
                        providerHealth.markHealthy(provider)
                        // "model" hlásí proxy od té verze, co umí interní fallback na
                        // slabší model - starší proxy ho nemá, pak je null a appka se
                        // chová jako dřív (výsledek se cachuje).
                        ProxyOutcome.Text(text, jsonBody.optString("model").takeIf { it.isNotBlank() })
                    }
                    ?: ProxyOutcome.BatchFailed
                UPSTREAM_EMPTY -> ProxyOutcome.BatchFailed
                else -> {
                    // upstream_rate_limited / upstream_error - provider odmítá obsluhu.
                    // Proxy sem posílá KONKRÉTNÍ důvod (viz UpstreamErrorCode v
                    // translate-proxy/index.ts) a ten se dřív beze stopy zahodil - přitom je
                    // to jediné, podle čeho jde poznat "došla kvóta" od "upstream je rozbitý".
                    Log.w(LOG_TAG, "proxy odmítla providera $provider: $error")
                    // retryAfterSeconds = skutečné navržené čekání ze samotné odpovědi
                    // providera (Retry-After/RetryInfo), viz ProviderHealth.markUnavailable -
                    // chybí (NaN), když ho provider tentokrát nedal, appka pak hádá jako dřív.
                    val retryAfterSeconds = jsonBody.optDouble("retryAfterSeconds", Double.NaN).takeIf { !it.isNaN() }
                    providerHealth.markUnavailable(provider, retryAfterSeconds)
                    ProxyOutcome.ProviderDown
                }
            }
        }
    } catch (e: RateLimitedException) {
        throw e
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: IOException) {
        ProxyOutcome.Retryable // síť/timeout - druhý pokus o chvíli později běžně projde
    } catch (e: Exception) {
        e.report("translate:gemini:executeOnce:provider=$provider")
        ProxyOutcome.BatchFailed // neparsovatelné tělo odpovědi - opakování to nespraví
    }

    /** Jak dopadlo jedno volání proxy - viz [executeOnce]. */
    private sealed interface ProxyOutcome {
        data class Text(val value: String, val model: String? = null) : ProxyOutcome

        /** Upstream odmítá obsluhu (kvóta, výpadek) - provider je odstavený, neopakovat. */
        data object ProviderDown : ProxyOutcome

        /** Nepovedla se tahle konkrétní dávka, provider je v pořádku - neopakovat, neodstavovat. */
        data object BatchFailed : ProxyOutcome

        /** Přechodné selhání (síť, timeout, 5xx) - má smysl zkusit znovu. */
        data object Retryable : ProxyOutcome
    }

    private companion object {
        /**
         * Nižší než dřívější 3, protože OkHttp klient má navíc vlastní RetryInterceptor
         * (viz AppModule) - ten na IOException opakuje okamžitě, tenhle s prodlevou.
         */
        const val MAX_ATTEMPTS = 2
        const val RETRY_DELAY_MILLIS = 800L

        const val LOG_TAG = "Jiyu"

        /** Viz UpstreamErrorCode v translate-proxy/index.ts. */
        const val UPSTREAM_EMPTY = "upstream_empty"
    }
}
