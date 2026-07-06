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
    private val apiKey get() = BuildConfig.GROQ_API_KEY

    /** false = GROQ_API_KEY chybí v local.properties, překlad nemá šanci fungovat. */
    val isConfigured: Boolean get() = apiKey.isNotBlank()

    /**
     * Přeloží seznam textů v jednom API volání.
     * Vrátí seznam překladů ve stejném pořadí; při chybě vrátí prázdný list.
     */
    suspend fun translateBatch(
        texts: List<String>,
        targetLanguage: String = "Czech",
        sourceLanguage: String = "Auto",
    ): List<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || texts.isEmpty()) return@withContext emptyList()

        val textsJson = JSONArray(texts).toString()
        val fromClause = if (sourceLanguage != "Auto") "from $sourceLanguage " else ""

        val body = JSONObject().apply {
            put("model", "llama-3.3-70b-versatile")
            put("temperature", 0.1)
            put("max_tokens", 1500)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content",
                        "You are a manga translator. Given a JSON array of manga text strings, " +
                        "return a JSON array of ${fromClause}$targetLanguage translations in exactly the same order. " +
                        "Return ONLY the JSON array, no explanations, no markdown."
                    )
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
