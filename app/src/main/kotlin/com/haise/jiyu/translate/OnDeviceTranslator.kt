package com.haise.jiyu.translate

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Lokální on-device překlad přes ML Kit Translate. Slouží jako záloha, když není
 * nakonfigurovaný žádný cloudový provider (Supabase/Groq/Gemini), takže uživatel
 * může vidět alespoň hrubý náhled překladu v bublinách.
 *
 * Od verze v1.2.32 dělá několik věcí, aby byl náhled o něco lepší:
 * 1) Bubliny se nepřekládají zvlášť, ale v krátkých dávkách (několik bublin najednou),
 *    aby ML Kit viděl kontext a lépe si poradil s osobami/zájmeny.
 * 2) Volitelný glosář se nahradí placeholdery před překladem a vrátí zpět po něm,
 *    takže jména/techniky zůstanou konzistentní s tím, co si uživatel nastavil.
 * 3) Zjištění zdrojového jazyka se dělá z prvních tří bublin místo jedné,
 *    aby detekce neshodila na prvním zvukoskopu/jménu.
 *
 * Modely se stahují dynamicky (stejně jako u Google Translate offline), takže první
 * použití může chvíli trvat. Pro náhled angličtina→čeština je to však řádově
 * sekundy a nepotřebuje žádný API klíč.
 */
class OnDeviceTranslator {

    private val conditions = DownloadConditions.Builder().build()
    private val langId = LanguageIdentification.getClient()

    /**
     * Přeloží sadu textů najednou s kontextem a opčním glosářem.
     * Vrací seznam stejné délky jako vstup; null znamená, že konkrétní řetězec se nepřeložil.
     */
    suspend fun translate(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
        glossary: Map<String, String> = emptyMap(),
    ): List<String?> {
        if (texts.isEmpty()) return emptyList()
        val sourceCode = resolveSourceCode(sourceLanguage, texts) ?: return texts.map { null }
        val targetCode = resolveTargetCode(targetLanguage) ?: return texts.map { null }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceCode)
            .setTargetLanguage(targetCode)
            .build()

        val translator = Translation.getClient(options)
        return try {
            downloadModel(translator)

            // Sjednotíme nové řádky, protože nový řádek používáme jako oddělovač dávky.
            val normalized = texts.map { it.replace(NEWLINE, " ").replace(CR, "").trim() }

            val glossaryEntries = prepareGlossary(glossary)
            val prepared = normalized.map { applyGlossaryPlaceholders(it, glossaryEntries) }

            val result = mutableListOf<String?>()
            for (chunk in chunkTexts(prepared)) {
                result += translateChunk(translator, chunk, glossaryEntries)
            }

            // Pokud by se náhodou výstupní počet neshodoval (model nespočítal řádky),
            // vrátíme se k překladu jednotlivých bublin.
            if (result.size != texts.size) {
                return prepared.map { safeTranslateOne(translator, it, glossaryEntries) }
            }
            result
        } finally {
            translator.close()
        }
    }

    private fun prepareGlossary(glossary: Map<String, String>): List<GlossaryEntry> {
        if (glossary.isEmpty()) return emptyList()
        // Nejdřív nejdelší termíny, aby kratší nezničily podslova delších.
        return glossary.entries
            .sortedByDescending { it.key.length }
            .mapIndexed { index, entry ->
                GlossaryEntry(
                    source = entry.key,
                    target = entry.value,
                    placeholder = "__G${index}__",
                )
            }
    }

    private fun applyGlossaryPlaceholders(text: String, entries: List<GlossaryEntry>): String {
        if (entries.isEmpty()) return text
        var result = text
        for (entry in entries) {
            result = result.replace(entry.source, entry.placeholder)
        }
        return result
    }

    private fun restoreGlossary(text: String, entries: List<GlossaryEntry>): String? {
        if (text.isBlank()) return null
        var result = text
        for (entry in entries) {
            result = result.replace(entry.placeholder, entry.target)
        }
        return result.takeIf { it.isNotBlank() }
    }

    private fun chunkTexts(texts: List<String>): List<List<String>> {
        if (texts.isEmpty()) return emptyList()
        val chunks = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var currentLen = 0
        for (text in texts) {
            if (current.isNotEmpty() && (currentLen + text.length > BATCH_CHAR_LIMIT || current.size >= BATCH_MAX_ITEMS)) {
                chunks += current
                current = mutableListOf()
                currentLen = 0
            }
            current += text
            currentLen += text.length
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }

    private suspend fun translateChunk(
        translator: Translator,
        chunk: List<String>,
        entries: List<GlossaryEntry>,
    ): List<String?> {
        val joined = chunk.joinToString(ITEM_SEPARATOR)
        return try {
            val translated = translateOne(translator, joined).trim()
            val parts = translated.replace(CR, "").split(ITEM_SEPARATOR).map { it.trim() }
            if (parts.size == chunk.size) {
                parts.map { restoreGlossary(it, entries) }
            } else {
                // ML Kit nespočítal oddělovače - překládáme znovu po jednom.
                chunk.map { safeTranslateOne(translator, it, entries) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "batch translate failed", e)
            chunk.map { null }
        }
    }

    private suspend fun safeTranslateOne(
        translator: Translator,
        text: String,
        entries: List<GlossaryEntry>,
    ): String? {
        if (text.isBlank()) return null
        return try {
            restoreGlossary(translateOne(translator, text).trim(), entries)
        } catch (e: Exception) {
            Log.w(TAG, "translate failed for '$text'", e)
            null
        }
    }

    private suspend fun downloadModel(translator: Translator) {
        suspendCancellableCoroutine { cont ->
            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener { cont.resume(Unit) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }

    private suspend fun translateOne(translator: Translator, text: String): String {
        return suspendCancellableCoroutine { cont ->
            translator.translate(text)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }

    private suspend fun resolveSourceCode(sourceLanguage: String, texts: List<String>): String? {
        if (sourceLanguage.isNotBlank() && sourceLanguage != "Auto") {
            return toTranslateLanguage(sourceLanguage)
        }
        if (texts.isEmpty()) return null
        val sample = texts.take(SAMPLE_FOR_LANG_ID).joinToString(" ")
        return identifyLanguage(sample) ?: TranslateLanguage.ENGLISH
    }

    private suspend fun identifyLanguage(text: String): String? = suspendCancellableCoroutine { cont ->
        langId.identifyLanguage(text)
            .addOnSuccessListener { code ->
                cont.resume(if (code == "und" || code == null) null else code)
            }
            .addOnFailureListener {
                Log.w(TAG, "language identification failed", it)
                cont.resume(null)
            }
    }

    private fun resolveTargetCode(targetLanguage: String): String? = toTranslateLanguage(targetLanguage)

    private fun toTranslateLanguage(languageName: String): String? = when (languageName) {
        "Czech" -> TranslateLanguage.CZECH
        "English" -> TranslateLanguage.ENGLISH
        "Japanese" -> TranslateLanguage.JAPANESE
        "Korean" -> TranslateLanguage.KOREAN
        "Chinese" -> TranslateLanguage.CHINESE
        "German" -> TranslateLanguage.GERMAN
        "French" -> TranslateLanguage.FRENCH
        "Spanish" -> TranslateLanguage.SPANISH
        "Russian" -> TranslateLanguage.RUSSIAN
        "Portuguese" -> TranslateLanguage.PORTUGUESE
        "Italian" -> TranslateLanguage.ITALIAN
        "Polish" -> TranslateLanguage.POLISH
        "Turkish" -> TranslateLanguage.TURKISH
        "Dutch" -> TranslateLanguage.DUTCH
        "Arabic" -> TranslateLanguage.ARABIC
        "Hindi" -> TranslateLanguage.HINDI
        "Thai" -> TranslateLanguage.THAI
        "Vietnamese" -> TranslateLanguage.VIETNAMESE
        "Indonesian" -> TranslateLanguage.INDONESIAN
        else -> null
    }

    private data class GlossaryEntry(
        val source: String,
        val target: String,
        val placeholder: String,
    )

    companion object {
        private const val TAG = "OnDeviceTranslator"
        private const val BATCH_CHAR_LIMIT = 2000
        private const val BATCH_MAX_ITEMS = 6
        private const val SAMPLE_FOR_LANG_ID = 3
        private const val ITEM_SEPARATOR = "\n"
        private const val NEWLINE = "\n"
        private const val CR = "\r"
    }
}
