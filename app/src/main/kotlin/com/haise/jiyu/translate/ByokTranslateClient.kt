package com.haise.jiyu.translate

import com.haise.jiyu.util.executeCancellable
import com.haise.jiyu.di.LlmHttpClient
import com.haise.jiyu.security.SecureCredentialStore
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.util.report
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
 * Sestaví prompt pro obecný (uživatelem zvolený) chat-completions model - na rozdíl od
 * [GeminiUltraPrompt] (psaný natvrdo pro konkrétní chování Gemini/Groq přes vlastní proxy)
 * musí fungovat i na modelech, které appka nezná a nemůže doladit. Prostý číslovaný seznam
 * dovnitř/ven je nejrobustnější formát, co i slabší/menší modely typicky dodrží - žádné
 * vnořené JSON schéma, které by se dalo pokazit špatně escapovanou uvozovkou.
 */
internal fun buildByokPrompt(texts: List<String>, targetLanguage: String, sourceLanguage: String, glossary: Map<String, String>): String = buildString {
    append("You are a professional translator. Translate each numbered line below from ")
    append(if (sourceLanguage == "Auto" || sourceLanguage.isBlank()) "its source language" else sourceLanguage)
    append(" to ")
    append(targetLanguage)
    append(". Reply with EXACTLY ")
    append(texts.size)
    append(" lines, one translation per line, in the same order and numbering, no extra commentary before or after.\n")
    if (glossary.isNotEmpty()) {
        append("\nUse this glossary for proper nouns/technical terms whenever they appear (translate everything else normally):\n")
        glossary.entries.forEach { (source, target) -> append("- \"$source\" -> \"$target\"\n") }
    }
    append("\n")
    texts.forEachIndexed { i, text -> append("${i + 1}. $text\n") }
}

/**
 * Rozparsuje odpověď modelu na řešení podle číslování ("1. ...", "1) ...", nebo jen "..." bez
 * čísla, pokud model číslování samo nevrátilo) - shovívavě, protože ne každý model dodrží
 * přesně požadovaný formát. Vrátí null, když se počet řádků neshoduje s [expectedCount] -
 * volající pak zkusí bezpečnější cestu (po jedné větě zvlášť), stejný vzor jako
 * [OnDeviceTranslator.translateChunk].
 *
 * Strip číslování hledá KONKRÉTNÍ očekávané pořadové číslo dané řádky (1 pro první, 2 pro
 * druhou, ...), ne libovolné číslo na začátku - text určený k překladu (herní UI, menu se
 * seznamem "1. Útok" / "2. Obrana"...) může sám legitimně začínat číslem s tečkou, a
 * obecný `^\d+[.).:]\s*` vzor by takový začátek omylem uřízl jako by šlo o naši vlastní
 * přidanou číslovací předponu (nahlášeno v auditu).
 */
internal fun parseByokResponse(content: String, expectedCount: Int): List<String>? {
    val lines = content.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.size != expectedCount) return null
    return lines.mapIndexed { i, line -> line.replace(numberingPrefixFor(i + 1), "") }
}

private fun numberingPrefixFor(lineNumber: Int): Regex = Regex("""^$lineNumber[.).:]\s*""")

/**
 * Volitelný "bring your own key" vlastní LLM endpoint - poslední záloha PŘED on-device ML Kit
 * (viz [TranslateRepository], item 14 v plánu). NIKDY výchozí ani povinné: appka je bez
 * konfigurace/klíče funkční jako dřív (bezplatní pooled provideři + on-device), tohle je jen
 * volitelná záchrana pro uživatele, kterému se nedostává na sdílenou kvótu.
 *
 * Formát požadavku je OpenAI-kompatibilní chat-completions (`POST {baseUrl}/chat/completions`,
 * `{"model", "messages"}`) - nejrozšířenější konvence, kterou mluví jak veřejná API (OpenAI,
 * OpenRouter přímo bez proxy...), tak self-hosted servery (Ollama, LM Studio, vLLM).
 *
 * API klíč se čte ze [SecureCredentialStore] (šifrovaně přes Android Keystore) - NIKDY z
 * obyčejného DataStore nastavení, na rozdíl od base URL/modelu, které nejsou tajné.
 */
@Singleton
class ByokTranslateClient @Inject constructor(
    @LlmHttpClient private val client: OkHttpClient,
    private val settings: SettingsRepository,
    private val secureStore: SecureCredentialStore,
) {
    suspend fun isConfigured(): Boolean {
        if (!settings.byokEnabled.first()) return false
        val baseUrl = settings.byokBaseUrl.first()
        val apiKey = secureStore.get(KEY_API_KEY)
        return baseUrl.isNotBlank() && !apiKey.isNullOrBlank()
    }

    /**
     * Přeloží dávku textů. Vrací prázdný seznam při jakémkoli selhání (nenakonfigurováno,
     * síťová chyba, neplatná odpověď) - volající (viz [TranslateRepository]) to bere stejně
     * jako "tenhle krok řetězce nic nevrátil", ne jako výjimku k propagaci.
     */
    suspend fun translateBatch(
        texts: List<String>,
        targetLanguage: String,
        sourceLanguage: String,
        glossary: Map<String, String>,
    ): List<String> = withContext(Dispatchers.IO) {
        if (texts.isEmpty() || !isConfigured()) return@withContext emptyList()
        val baseUrl = settings.byokBaseUrl.first().trimEnd('/')
        val model = settings.byokModel.first().ifBlank { DEFAULT_MODEL }
        val apiKey = secureStore.get(KEY_API_KEY) ?: return@withContext emptyList()

        val result = complete(baseUrl, apiKey, model, buildByokPrompt(texts, targetLanguage, sourceLanguage, glossary))
            ?.let { parseByokResponse(it, texts.size) }
        if (result != null) return@withContext result

        // Nesedici pocet radku - zkusi se kazda veta ZVLAST (mensi prompt, min prostoru na
        // chybu v cislovani), stejny fallback jako OnDeviceTranslator.translateChunk. Strop -
        // bez nej by velka davka (desitky bublin na strance) znamenala stejny pocet
        // jednotlivych HTTP volani navic proti uzivatelovu VLASTNIMU (byok) API, bez zadneho
        // omezeni ani prodlevy mezi nimi. Nad stropem se radsi vrati prazdno - volajici
        // translateChain zkusi dalsiho providera/on-device presne jako pri jakemkoli jinem
        // selhani tohohle kroku.
        if (texts.size > MAX_INDIVIDUAL_FALLBACK) return@withContext emptyList()
        texts.mapIndexed { index, text ->
            val translated = complete(baseUrl, apiKey, model, buildByokPrompt(listOf(text), targetLanguage, sourceLanguage, glossary))
                ?.let { parseByokResponse(it, 1) }
                ?.firstOrNull()
                ?: ""
            if (index < texts.lastIndex) delay(INDIVIDUAL_FALLBACK_DELAY_MS)
            translated
        }
    }

    private suspend fun complete(baseUrl: String, apiKey: String, model: String, prompt: String): String? {
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.3)
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                },
            )
        }.toString()
        return completeRaw(baseUrl, apiKey, body)
    }

    /**
     * EXPERIMENT (plán položka 18): "přečti text v tomhle obrázku" - jen OCR, ne překlad.
     * Ten už řeší existující [TranslateRepository]'s translateChain později, s kontextem
     * (glosář/kontext díla/cílový jazyk), které tahle třída na téhle úrovni nemá k dispozici -
     * kombinovat "přečti a rovnou přelož" by tenhle kontext obešlo, ne využilo.
     *
     * Multimodální OpenAI-kompatibilní formát ([image_url] s `data:` URI) - stejná konvence,
     * kterou mluví vision-schopné modely (GPT-4o, Claude přes OpenAI-kompatibilní proxy...).
     * Vrací null při jakémkoli selhání nebo když model odpoví prázdně/jen komentářem.
     */
    suspend fun readBubbleText(imageBytes: ByteArray): String? = withContext(Dispatchers.IO) {
        if (imageBytes.isEmpty() || !isConfigured()) return@withContext null
        val baseUrl = settings.byokBaseUrl.first().trimEnd('/')
        val model = settings.byokModel.first().ifBlank { DEFAULT_MODEL }
        val apiKey = secureStore.get(KEY_API_KEY) ?: return@withContext null

        val base64Image = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.1)
            put(
                "messages",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("role", "user")
                            put(
                                "content",
                                JSONArray().apply {
                                    put(
                                        JSONObject().apply {
                                            put("type", "text")
                                            put(
                                                "text",
                                                "This image is a single cropped comic/manga speech bubble. " +
                                                    "Transcribe EXACTLY the text written inside it, in its original " +
                                                    "language and script - do not translate it. Reply with ONLY the " +
                                                    "transcribed text, no quotes, no commentary. If there is no " +
                                                    "legible text, reply with an empty response.",
                                            )
                                        },
                                    )
                                    put(
                                        JSONObject().apply {
                                            put("type", "image_url")
                                            put("image_url", JSONObject().apply { put("url", "data:image/png;base64,$base64Image") })
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }.toString()
        completeRaw(baseUrl, apiKey, body)
    }

    private suspend fun completeRaw(baseUrl: String, apiKey: String, requestBodyJson: String): String? {
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).executeCancellable { response ->
                if (!response.isSuccessful) return@executeCancellable null
                val responseText = response.body?.string() ?: return@executeCancellable null
                val json = JSONObject(responseText)
                json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.takeIf { it.isNotBlank() }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.report("translate:byok:complete")
            null
        }
    }

    /** Synchronní - [SecureCredentialStore] čte z EncryptedSharedPreferences, ne ze sítě/DataStore. */
    fun apiKey(): String? = secureStore.get(KEY_API_KEY)
    fun setApiKey(key: String) = secureStore.set(KEY_API_KEY, key)
    fun clearApiKey() = secureStore.remove(KEY_API_KEY)

    private companion object {
        const val KEY_API_KEY = "byok_api_key"
        const val DEFAULT_MODEL = "gpt-4o-mini"
        /** Viz translateBatch doc - nad tuhle velikost davky se fallback po jednotlivych vetach vubec nezkousi. */
        const val MAX_INDIVIDUAL_FALLBACK = 20
        const val INDIVIDUAL_FALLBACK_DELAY_MS = 150L
    }
}
