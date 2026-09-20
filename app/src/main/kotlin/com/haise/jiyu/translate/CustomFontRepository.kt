package com.haise.jiyu.translate

import android.content.Context
import com.haise.jiyu.di.ImageHttpClient
import com.haise.jiyu.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * true, když [contentType] (nebo, chybí-li/je-li obecný, přípona [url]) vypadá jako platný
 * font soubor - stažení libovolného URL bez kontroly by appce dovolilo uložit a pak zkusit
 * načíst jako "font" cokoliv (HTML chybová stránka, obrázek...), což [androidx.compose.ui.text.font.Font]
 * jen tiše odmítne (bublina spadne zpátky na vestavěný font, bez zjevné chyby proč).
 */
internal fun isAcceptableFontContentType(contentType: String, url: String): Boolean {
    val normalized = contentType.substringBefore(';').trim().lowercase()
    if (normalized in ACCEPTABLE_FONT_CONTENT_TYPES) return true
    // Nektere servery (typicky proste staticke hostingy/CDN bez spravne nakonfigurovaneho
    // MIME typu) vraci obecny "application/octet-stream" nebo dokonce "text/plain" i pro
    // platny font soubor - v tom pripade rozhoduje pripona URL misto hlavicky.
    if (normalized == "application/octet-stream" || normalized == "text/plain" || normalized.isBlank()) {
        val lowerUrl = url.substringBefore('?').substringBefore('#').lowercase()
        return FONT_EXTENSIONS.any { lowerUrl.endsWith(it) }
    }
    return false
}

private val ACCEPTABLE_FONT_CONTENT_TYPES = setOf(
    "font/ttf", "font/otf", "font/woff", "font/woff2", "font/collection",
    "application/font-sfnt", "application/x-font-ttf", "application/x-font-otf", "application/vnd.ms-opentype",
)
private val FONT_EXTENSIONS = listOf(".ttf", ".otf", ".ttc")

/** Jméno souboru v cache pro daný URL - deterministické (hash URL), aby stejný URL vždy mapoval na stejný soubor a šlo ho znovu použít bez opakovaného stahování. */
internal fun cachedFontFileName(url: String): String {
    val ext = url.substringBefore('?').substringBefore('#').substringAfterLast('.', missingDelimiterValue = "ttf").lowercase()
    val safeExt = if (ext in listOf("ttf", "otf", "ttc")) ext else "ttf"
    return "custom_font_${url.hashCode()}.$safeExt"
}

/**
 * Stažení a cache uživatelem zadaného fontu pro bubliny v čtečce (viz [SettingsRepository.customFontUrl],
 * item 15 v plánu). Přes [ImageHttpClient] - stejný jednoduchý (bez scrapovacích interceptorů)
 * klient jako pro stahování obrázků, protože font je jen další statický soubor z CDN/hostingu.
 *
 * Uloženo do `filesDir/custom_fonts/` - soubor přežívá mezi běhy appky, takže se nestahuje
 * znovu při každém startu, jen při skutečné změně URL.
 */
@Singleton
class CustomFontRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @ImageHttpClient private val client: OkHttpClient,
    private val settings: SettingsRepository,
) {
    /** Null = žádný vlastní font (neuloženo, nebo stažený soubor mezitím zmizel). */
    val activeFontFile: Flow<File?> = settings.customFontUrl.map { url ->
        if (url.isBlank()) null else cachedFile(url).takeIf { it.exists() }
    }

    /**
     * Stáhne, ověří a uloží font z [url]. URL se do nastavení uloží AŽ PO úspěchu - selhání
     * (špatný typ obsahu, moc velký soubor, síťová chyba) nesmí appku nechat myslet si, že má
     * platný vlastní font, který ve skutečnosti nikdy nedorazil.
     */
    suspend fun downloadAndApply(url: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) {
            return@withContext Result.failure(IllegalArgumentException("only https:// URLs are supported"))
        }
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}"))
                val contentType = response.header("Content-Type").orEmpty()
                if (!isAcceptableFontContentType(contentType, url)) {
                    return@withContext Result.failure(IllegalArgumentException("unexpected content-type: \"$contentType\""))
                }
                val body = response.body ?: return@withContext Result.failure(IOException("empty response body"))
                if (body.contentLength() in 1..MAX_FONT_BYTES) {
                    // ok, deklarovaná délka je rozumná - stejně se dál hlídá i při skutečném
                    // streamování níž (server může lhát nebo Content-Length vůbec neposlat,
                    // pak vrací -1 a spoléhá se JEN na streamovaný limit).
                } else if (body.contentLength() > MAX_FONT_BYTES) {
                    return@withContext Result.failure(IOException("font too large: ${body.contentLength()} bytes"))
                }

                val fontsDir = File(context.filesDir, CUSTOM_FONTS_DIR).apply { if (!exists()) mkdirs() }
                val tmpFile = File(fontsDir, "download.tmp")
                var written = 0L
                body.byteStream().use { input ->
                    tmpFile.outputStream().use { output ->
                        val buffer = ByteArray(8 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            written += read
                            if (written > MAX_FONT_BYTES) {
                                tmpFile.delete()
                                return@withContext Result.failure(IOException("font exceeded size cap while downloading"))
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (written == 0L) {
                    tmpFile.delete()
                    return@withContext Result.failure(IOException("empty font file"))
                }
                val finalFile = cachedFile(url)
                if (!tmpFile.renameTo(finalFile)) {
                    tmpFile.copyTo(finalFile, overwrite = true)
                    tmpFile.delete()
                }
                settings.setCustomFontUrl(url)
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Zruší vlastní font - appka se vrátí na vestavěnou sadu Comic Neue/Exo2. */
    suspend fun clearCustomFont() {
        settings.setCustomFontUrl("")
        withContext(Dispatchers.IO) {
            File(context.filesDir, CUSTOM_FONTS_DIR).deleteRecursively()
        }
    }

    private fun cachedFile(url: String): File = File(File(context.filesDir, CUSTOM_FONTS_DIR), cachedFontFileName(url))

    private companion object {
        const val CUSTOM_FONTS_DIR = "custom_fonts"
        const val MAX_FONT_BYTES = 10L * 1024 * 1024 // 10 MB - komfortni strop pro jeden .ttf/.otf, appka je bez omezeni na velikost jinak.
    }
}
