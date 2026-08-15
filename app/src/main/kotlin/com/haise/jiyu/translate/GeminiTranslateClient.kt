package com.haise.jiyu.translate

import android.util.Log
import com.haise.jiyu.BuildConfig
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
    private val httpClient: OkHttpClient,
    private val providerHealth: ProviderHealth,
) {
    val isConfigured: Boolean get() = BuildConfig.SUPABASE_URL.isNotBlank() &&
        !BuildConfig.SUPABASE_URL.contains("placeholder")

    /**
     * Přeloží dávku bublin jedné stránky. SFX bubliny (viz [ClassifiedBubble.isSfx]) se
     * do requestu vůbec nezahrnují - filtruje [GeminiUltraPrompt.buildUserPrompt].
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
        val toTranslate = bubbles.filterIndexed { _, b -> !b.isSfx }
        if (!isConfigured || toTranslate.isEmpty()) return@withContext null
        // Provider, o kterém z předchozí dávky víme, že odmítá obsluhu, se přeskočí bez
        // jediného requestu - tohle je hlavní úspora u dlouhé kapitoly, viz ProviderHealth.
        if (!providerHealth.isAvailable(provider)) return@withContext null

        val requestBody = JSONObject().apply {
            put("mode", "gemini")
            put("provider", provider)
            if (provider == "gemini") put("model", GeminiUltraPrompt.MODEL)
            put("system", GeminiUltraPrompt.buildSystemPrompt(glossary, mangaContext))
            put("user", GeminiUltraPrompt.buildUserPrompt(bubbles, previousLines))
        }

        val request = Request.Builder()
            .url("${BuildConfig.SUPABASE_URL}/functions/v1/translate-proxy")
            .header("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .header("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        // Opakuje se JEN přechodné selhání (viz ProxyOutcome.Retryable). Dřív se opakovala
        // i odpověď proxy s prázdným textem - jenže tak vypadalo i natvrdo vyčerpané Gemini,
        // takže se na jistě marný požadavek pálily tři pokusy a přes dvě vteřiny čekání,
        // a to na každém providerovi každé dávky kapitoly.
        repeat(MAX_ATTEMPTS) { attempt ->
            when (val outcome = executeOnce(request, provider)) {
                is ProxyOutcome.Text -> return@withContext try {
                    GeminiUltraPrompt.parseResponse(outcome.value)
                } catch (e: Exception) {
                    // Neparsovatelná odpověď - nemá smysl retryovat, model to znovu nespraví.
                    // Hlásíme ale ven: tohle je přesně ten druh tiché chyby, kdy se překlad
                    // "prostě neudělá" a bez hlášení není podle čeho zjistit proč.
                    e.report("translate:gemini:parseResponse:provider=$provider")
                    null
                }
                ProxyOutcome.ProviderDown, ProxyOutcome.BatchFailed -> return@withContext null
                ProxyOutcome.Retryable -> if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MILLIS)
            }
        }
        null
    }

    /**
     * Jeden pokus o zavolání proxy. Vyhodnocuje jak HTTP status, tak pole "error" v těle
     * odpovědi - proxy totiž selhání upstreamu vrací se statusem 200 (viz UpstreamErrorCode
     * v translate-proxy/index.ts), aby starší verze appky, které to pole neznají, dál
     * fungovaly beze změny.
     */
    private fun executeOnce(request: Request, provider: String): ProxyOutcome = try {
        httpClient.newCall(request).execute().use { resp ->
            if (resp.code == 429) {
                // Limit hlásí sama proxy, ne upstream - přes ni vedou všichni provideři stejně,
                // takže zkoušet zbytek řetězce je jen ztráta času.
                providerHealth.markAllUnavailable()
                throw RateLimitedException()
            }
            if (!resp.isSuccessful) return@use ProxyOutcome.Retryable
            val body = resp.body?.string() ?: return@use ProxyOutcome.Retryable
            val jsonBody = JSONObject(body)
            when (val error = jsonBody.optString("error").takeIf { it.isNotBlank() }) {
                null -> jsonBody.optString("text").takeIf { it.isNotBlank() }
                    ?.let { text ->
                        providerHealth.markHealthy(provider)
                        ProxyOutcome.Text(text)
                    }
                    ?: ProxyOutcome.BatchFailed
                UPSTREAM_EMPTY -> ProxyOutcome.BatchFailed
                else -> {
                    // upstream_rate_limited / upstream_error - provider odmítá obsluhu.
                    // Proxy sem posílá KONKRÉTNÍ důvod (viz UpstreamErrorCode v
                    // translate-proxy/index.ts) a ten se dřív beze stopy zahodil - přitom je
                    // to jediné, podle čeho jde poznat "došla kvóta" od "upstream je rozbitý".
                    Log.w(LOG_TAG, "proxy odmítla providera $provider: $error")
                    providerHealth.markUnavailable(provider)
                    ProxyOutcome.ProviderDown
                }
            }
        }
    } catch (e: RateLimitedException) {
        throw e
    } catch (_: IOException) {
        ProxyOutcome.Retryable // síť/timeout - druhý pokus o chvíli později běžně projde
    } catch (e: Exception) {
        e.report("translate:gemini:executeOnce:provider=$provider")
        ProxyOutcome.BatchFailed // neparsovatelné tělo odpovědi - opakování to nespraví
    }

    /** Jak dopadlo jedno volání proxy - viz [executeOnce]. */
    private sealed interface ProxyOutcome {
        data class Text(val value: String) : ProxyOutcome

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
