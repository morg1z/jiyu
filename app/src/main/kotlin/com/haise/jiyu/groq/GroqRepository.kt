package com.haise.jiyu.groq

import com.haise.jiyu.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroqRepository @Inject constructor(private val client: OkHttpClient) {
    companion object {
        private const val API_URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val MODEL = "llama-3.1-8b-instant"
    }

    private val apiKey get() = BuildConfig.GROQ_API_KEY

    suspend fun chat(systemPrompt: String, userMessage: String): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        try {
            val body = JSONObject().apply {
                put("model", MODEL)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                    put(JSONObject().apply { put("role", "user"); put("content", userMessage) })
                })
                put("max_tokens", 300)
                put("temperature", 0.7)
            }
            val request = Request.Builder()
                .url(API_URL)
                .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .build()
            client.newCall(request).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: return@withContext null)
                json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) { null }
    }

    suspend fun getMangaInsight(title: String, description: String?, genres: List<String>): String? {
        val system = "Jsi expert na mangu. Odpovídej stručně česky, max 2 věty."
        val user = "Manga: '$title'. Žánry: ${genres.joinToString()}. Popis: ${description?.take(300) ?: "N/A"}. Čemu je manga podobná a pro koho je vhodná?"
        return chat(system, user)
    }

    suspend fun getChapterSummary(mangaTitle: String, chapterName: String, previousChapterName: String?): String? {
        val system = "Jsi asistent pro čtenáře mangy. Odpovídej česky, max 3 věty."
        val user = buildString {
            append("Manga: '$mangaTitle'. ")
            if (previousChapterName != null) append("Předchozí kapitola: '$previousChapterName'. ")
            append("Co se pravděpodobně stalo v kapitole '$chapterName'? ")
            append("Odpověz obecně bez spoilerů — uživatel si nepamatuje děj a chce rychlé připomenutí.")
        }
        return chat(system, user)
    }
}
