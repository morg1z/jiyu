package com.haise.jiyu.util

import android.content.Context
import android.graphics.BitmapRegionDecoder
import android.net.Uri
import coil.Coil
import coil.annotation.ExperimentalCoilApi
import coil.disk.DiskCache
import com.haise.jiyu.di.ImageHttpClient
import com.haise.jiyu.source.interceptor.ImageProxyConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Zdrojové BAJTY stránky jako soubor - sdílený stroj pro všechny cesty, které potřebují
 * číst pixely přímo ze souboru mimo Coil dekódování (PageSlicer řezy, PageRegionSource
 * záplaty). Vytaženo z PageSlicer, aby se neduplikovala resoluce:
 *
 * - `file://` / absolutní cesta → přímo `File` (offline kapitoly),
 * - http(s) → Coil disk cache snapshot (stejné bajty jako display cesta, prefetch je
 *   tam stejně uloží), případně vlastní soubor `tall_page_src/<md5>.bin` (vznikne, když
 *   je Coil editor obsazený souběžným zápisem),
 * - cache miss → plný download do Coil cache pod stejným klíčem jako display (žádný
 *   druhý download navíc; bez proxy bajty sdílí i normální zobrazení).
 *
 * S úsporným režimem obrázků se používá separátní `#original` klíč - pod holým pageUrl
 * leží degradovaná WebP kopie od wsrv.nl, originál se žádá hlavičkou
 * [com.haise.jiyu.source.interceptor.ImageProxyInterceptor.HEADER_ORIGINAL] (stejná
 * konvence jako `PageBitmapLoader`, viz [diskKeyFor]).
 */
@Singleton
@OptIn(ExperimentalCoilApi::class) // openSnapshot/openEditor - stabilní v praxi, Coil 2.x je takto označuje
class PageImageSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ImageHttpClient private val httpClient: OkHttpClient,
    private val imageProxyConfig: ImageProxyConfig,
) {

    /**
     * Otevře zdrojový soubor stránky a zavolá [block] dokud je přístup platný (u Coil
     * snapshotu drží pin po dobu bloku). `null` výsledek = zdroj nedostupný.
     * Skládá se z [withExistingFile] + [withDownloadedFile] - viz ty.
     */
    suspend fun <T> withFile(pageUrl: String, referer: String?, block: (File) -> T): T? =
        withExistingFile(pageUrl, block = block) ?: withDownloadedFile(pageUrl, referer, block)

    /**
     * Zdroj jen z už EXISTUJÍCÍCH bajtů - žádná síť:
     * - `file://` / absolutní cesta → přímo `File` (offline kapitoly).
     * - http(s) → Coil disk cache snapshot, případně vlastní soubor `tall_page_src/<md5>.bin`
     *   (vznikl, když při plánování byl Coil editor obsazený jiným zápisem - bez kontroly
     *   existence by se STRÁNKA STÁHLA ZNOVU u každého řezu, 15 řezů = 15 stejných downloadů).
     */
    suspend fun <T> withExistingFile(pageUrl: String, diskKey: String? = null, block: (File) -> T): T? =
        withContext(Dispatchers.IO) {
            if (pageUrl.startsWith("file://")) return@withContext runCatching { block(File(pageUrl.removePrefix("file://"))) }.getOrNull()
            if (pageUrl.startsWith("/")) return@withContext runCatching { block(File(pageUrl)) }.getOrNull()
            if (!pageUrl.startsWith("http://") && !pageUrl.startsWith("https://")) return@withContext null

            val diskCache = Coil.imageLoader(context).diskCache ?: return@withContext null
            diskCache.openSnapshot(diskKey ?: diskKeyFor(pageUrl, imageProxyConfig.enabled))?.use {
                return@withContext runCatching { block(it.data.toFile()) }.getOrNull()
            }
            val own = ownSliceSourceFile(pageUrl)
            if (own.isFile && own.length() > 0) {
                return@withContext runCatching { block(own) }.getOrNull()
            }
            null
        }

    /**
     * Zdroj po stažení ze sítě do Coil cache / vlastního souboru. `null` = download
     * nedostupný/selhal. POZOR: downloadToCache přes rethrowIfControl přehazuje 429 i
     * cancellation - bez catch by 429 probublala do Compose LaunchedEffect = crash na
     * main vlákně (reálně zaznamenáno). "Zdroj nedostupný" = volající spadne na jinou cestu.
     */
    suspend fun <T> withDownloadedFile(pageUrl: String, referer: String?, block: (File) -> T): T? =
        withContext(Dispatchers.IO) {
            if (!pageUrl.startsWith("http://") && !pageUrl.startsWith("https://")) return@withContext null
            val diskCache = Coil.imageLoader(context).diskCache ?: return@withContext null
            val file = try {
                downloadToCache(pageUrl, diskKeyFor(pageUrl, imageProxyConfig.enabled), referer, diskCache)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withContext null
            } ?: return@withContext null
            runCatching { block(file) }.getOrNull()
        }

    /** `BitmapRegionDecoder` nad souborem; null = soubor nečitelný/nejde dekódovat. */
    fun openRegionDecoder(file: File): BitmapRegionDecoder? = try {
        if (file.isFile && file.length() > 0) BitmapRegionDecoder.newInstance(file.absolutePath) else null
    } catch (e: Exception) {
        e.rethrowIfControl()
        null
    }

    /** Lazy URL (MangaHome, FanFox, EHentai...) se resolvují až přes `getImageUrl` -
     *  pro ně region-decode nevyrábíme (bajty v cache leží pod jiným klíčem). */
    fun isLazyUrl(pageUrl: String): Boolean =
        LazyPageUrl.decodeFragment(runCatching { Uri.parse(pageUrl) }.getOrNull()?.fragment) != null

    private fun downloadToCache(pageUrl: String, diskKey: String, referer: String?, diskCache: DiskCache): File? {
        val request = Request.Builder().url(pageUrl).apply {
            if (!referer.isNullOrBlank()) header("Referer", referer)
            // Originální bajty vždy - přes proxy by se stáhla degradovaná kopie
            // (hlavičku interceptor před odesláním odstraní, viz ImageProxyInterceptor).
            header(com.haise.jiyu.source.interceptor.ImageProxyInterceptor.HEADER_ORIGINAL, "1")
            // Interaktivní cesta (display) - viz PageSlicer.probeRemotePlan.
            header(com.haise.jiyu.source.interceptor.SlowdownInterceptor.HEADER_PRIORITY, "1")
        }.build()
        return try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                persistBodyToCache(pageUrl, body, diskKey, diskCache)
            }
        } catch (e: Exception) {
            e.rethrowIfControl()
            null
        }
    }

    /**
     * Zapíše tělo odpovědi jako zdroj stránky: preferovaně do Coil disk cache (bajty pak
     * slouží i normální cestě), při obsazeném editoru (souběžný zápis, např. právě
     * probíhající prefetch request) do vlastního souboru `tall_page_src/<md5>.bin`.
     * Sdílené mezi [downloadToCache] a `PageSlicer.probeRemotePlan` (odpověď 200 = celý
     * soubor); proto internal.
     */
    internal fun persistBodyToCache(pageUrl: String, body: okhttp3.ResponseBody, diskKey: String, diskCache: DiskCache): File? {
        val editor = diskCache.openEditor(diskKey)
        if (editor != null) {
            try {
                diskCache.fileSystem.write(editor.data) { body.source().readAll(this) }
                editor.commitAndOpenSnapshot()?.use { return it.data.toFile() }
                return null // commit selhal - snapshot zůstal null
            } catch (e: Exception) {
                editor.abort()
                throw e
            }
        }
        val file = ownSliceSourceFile(pageUrl)
        // Unikátní tmp jméno - dva souběžné downloady (plán + řez) nesmí psát
        // do stejného souboru, jinak se bajty propletou a výsledek je torzo.
        val tmp = File(file.parentFile, "${file.name}.${System.nanoTime()}.tmp")
        tmp.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
        if (!tmp.renameTo(file)) { tmp.delete(); return null }
        pruneOwnSliceDir()
        return file
    }

    /** Vlastní soubor mimo Coil cache - jen když je disk-cache entry obsazená souběžným editem. */
    private fun ownSliceSourceFile(pageUrl: String): File {
        val dir = File(context.cacheDir, OWN_SLICE_DIR).apply { mkdirs() }
        val md5 = MessageDigest.getInstance("MD5").digest(pageUrl.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$md5.bin")
    }

    private fun pruneOwnSliceDir() {
        val dir = File(context.cacheDir, OWN_SLICE_DIR)
        val files = dir.listFiles() ?: return
        // Siřelé .tmp (crash/zrušení uprostřed zápisu) - bez úklidu by se hromadily
        // navždy, protože se do počtu souborů nezapočítávají.
        val staleCutoff = System.currentTimeMillis() - 10 * 60 * 1000L
        files.filter { it.isFile && it.name.endsWith(".tmp") && it.lastModified() < staleCutoff }
            .forEach { it.delete() }
        val done = files.filter { it.isFile && !it.name.endsWith(".tmp") }
        if (done.size <= OWN_SLICE_MAX_FILES) return
        done.sortedBy { it.lastModified() }
            .take(done.size - OWN_SLICE_MAX_FILES)
            .forEach { it.delete() }
    }

    companion object {
        private const val OWN_SLICE_DIR = "tall_page_src"
        private const val OWN_SLICE_MAX_FILES = 48

        /**
         * Klíč do Coil disk cache pro zdrojové bajty stránky. Bez proxy je to holá URL
         * (sdílí entry s normálním zobrazením); se zapnutým úsporným režimem leží pod
         * `pageUrl` degradovaná WebP kopie, proto separátní `#original` klíč - stejná
         * konvence jako `PageBitmapLoader` (čistá funkce kvůli JVM testům).
         */
        internal fun diskKeyFor(pageUrl: String, proxyEnabled: Boolean): String =
            if (proxyEnabled) pageUrl.substringBeforeLast("#") + "#original" else pageUrl
    }
}
