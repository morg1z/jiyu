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

@Singleton
class GroqTranslateClient @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    private companion object {
        /**
         * Řeší tři různé případy jmen/míst/technik:
         * 1) zdroj už je v latince (OCR z anglického scanu) -> zachovat přesně
         * 2) zdroj je v japonštině/čínštině/korejštině/indonéštině atd. a existuje pro
         *    dané dílo ustálený ANGLICKÝ název (fanouškovský nebo oficiální) -> použít ho,
         *    nevymýšlet vlastní přepis ani nepřekládat význam jména do cílového jazyka
         *    (např. "Tempest" nesmí skončit jako "Bouřlivákov").
         * 3) žádný ustálený anglický název neexistuje (méně známé dílo) -> model ho
         *    má sám vytvořit V ANGLIČTINĚ, ne v cílovém jazyce a ne nechat v originálním
         *    písmu.
         */
        const val NAME_HANDLING_INSTRUCTION =
            "For character names, place names, organizations, and named skills/techniques, use " +
            "the name commonly used in English translations of this work - both fan translations " +
            "and official English releases count as valid sources - regardless of what language " +
            "you are translating from or into. If no established English name is known for a " +
            "particular term, render it into English yourself rather than leaving it in the " +
            "original script or inventing a name in the target language. When an established " +
            "English name IS known, do not invent a different transliteration for it, do not " +
            "translate its literal meaning into the target language (a city whose name means " +
            "\"storm\" in the original language should stay as its established English name, not " +
            "become the target-language word for storm), and do not substitute an alternate " +
            "localized name used in other language editions - official translations into other " +
            "languages sometimes rename things in ways that don't match what fans use, ignore " +
            "those. If the source text already contains the name written in Latin letters, keep " +
            "it exactly as written. Translate the same recurring name or term the same way every " +
            "time. "
    }

    private val apiKey get() = BuildConfig.GROQ_API_KEY

    /** false = GROQ_API_KEY chybí v local.properties, překlad nemá šanci fungovat. */
    val isConfigured: Boolean get() = apiKey.isNotBlank()

    /**
     * Přeloží seznam textů v jednom API volání.
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
    ): List<String> = translateBatchInternal(
        texts = texts,
        targetLanguage = targetLanguage,
        sourceLanguage = sourceLanguage,
        glossary = glossary,
        systemPrompt = { fromClause, target ->
            "You are an experienced manga translator. Given a JSON array of manga text strings, " +
            "return a JSON array of $fromClause$target translations in exactly the same order. " +
            "Translate jokes, wordplay, slang, and cultural references naturally and idiomatically " +
            "so the result reads fluently in $target, rather than translating word-for-word. " +
            NAME_HANDLING_INSTRUCTION +
            "Return ONLY the JSON array, no explanations, no markdown."
        },
    )

    /**
     * Překlad odstavců light novel kapitoly - odlišný prompt od manga bublin,
     * zachovává tón a odstavcovou strukturu prózy.
     */
    suspend fun translateNovelBatch(
        paragraphs: List<String>,
        targetLanguage: String = "Czech",
        sourceLanguage: String = "Auto",
        glossary: Map<String, String> = emptyMap(),
    ): List<String> = translateBatchInternal(
        texts = paragraphs,
        targetLanguage = targetLanguage,
        sourceLanguage = sourceLanguage,
        glossary = glossary,
        systemPrompt = { fromClause, target ->
            "You are a professional literary translator specializing in light novels. " +
            "Given a JSON array of paragraphs from a light novel chapter, return a JSON array of " +
            "${fromClause}$target translations in exactly the same order, preserving tone, dialogue " +
            "formatting and paragraph structure. Translate idioms, jokes and cultural references " +
            "naturally so the prose reads fluently in $target, not word-for-word. " +
            NAME_HANDLING_INSTRUCTION +
            "Return ONLY the JSON array, no explanations, no markdown."
        },
    )

    private suspend fun translateBatchInternal(
        texts: List<String>,
        targetLanguage: String,
        sourceLanguage: String,
        glossary: Map<String, String>,
        systemPrompt: (fromClause: String, target: String) -> String,
    ): List<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || texts.isEmpty()) return@withContext emptyList()

        val textsJson = JSONArray(texts).toString()
        val fromClause = if (sourceLanguage != "Auto") "from $sourceLanguage " else ""

        val glossaryClause = if (glossary.isNotEmpty()) {
            "\n\nThe following terms MUST be translated exactly as specified below, with no " +
            "deviation, regardless of what the general translation style would otherwise suggest:\n" +
            glossary.entries.joinToString("\n") { (source, target) -> "- \"$source\" → \"$target\"" }
        } else ""

        val body = JSONObject().apply {
            put("model", "llama-3.3-70b-versatile")
            put("temperature", 0.1)
            put("max_tokens", 4096)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt(fromClause, targetLanguage) + glossaryClause)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", textsJson)
                })
            })
        }

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val responseText = httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                resp.body?.string() ?: return@withContext emptyList()
            }

            val content = JSONObject(responseText)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            // Odpověď by měla být JSON array; ořežeme případný markdown fencing
            val cleaned = content.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val arr = JSONArray(cleaned)
            List(arr.length()) { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
