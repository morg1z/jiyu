package com.haise.jiyu.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import coil.Coil
import coil.annotation.ExperimentalCoilApi
import coil.disk.DiskCache
import com.haise.jiyu.di.ImageHttpClient
import com.haise.jiyu.ui.reader.CropBordersTransformation
import com.haise.jiyu.ui.reader.CropFractions
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Plán vykreslení jedné stránky ve webtoon režimu - viz [PageSlicer.plan].
 */
sealed interface PageSlicePlan {
    /** Normální stránka - vykreslí se jedním Coil requestem jako dřív (i fallback po selhání). */
    data object Single : PageSlicePlan

    /**
     * Extrémně vysoká stránka rozřezaná na [slices] řezů v souřadnicích ZDROJOVÉHO obrázku
     * (pixely, již obsahují případný ořez okrajů). Každý řez se dekóduje zvlášť přes
     * `BitmapRegionDecoder` v nativním rozlišení - stránka se nikdy nedrží v RAM celá
     * (800x30000 stránka by jinak byla ~96 MB bitmapa a nad ~8192 px výšky se ani nevejde
     * do GPU textury - Compose Image se taková bitmapa nevykreslí, potichu).
     */
    data class Tiled(
        val contentWidth: Int,
        val contentHeight: Int,
        val slices: List<Rect>,
    ) : PageSlicePlan {
        /** Poměr stran celé (oříznuté) stránky - pro výšku placeholderu i overlay. */
        val aspect: Float get() = contentWidth.toFloat() / contentHeight
    }
}

/**
 * Data model Coil requestu pro jeden řez vysoké stránky - obslouží ho
 * [com.haise.jiyu.source.PageSliceFetcher]. `wantCrop` je součástí klíče: plán i
 * dekódované řezy se pro zapnutý/vypnutý ořez liší a nesmí se promíchat v cache.
 */
data class PageSliceRequest(
    val pageUrl: String,
    val sliceIndex: Int,
    val referer: String?,
    val wantCrop: Boolean,
)

/**
 * Rozřezávání extrémně vysokých stránek na vodorovné řezy dekódované na vyžádání.
 *
 * Proč ne jen Coil: `AsyncImage` decoduje celý soubor do jedné bitmapy - u webtoon
 * stránky ~800x30000 je to ~96 MB ARGB v RAM (OOM na slabších telefonech) a nad limit
 * GPU textury (8192/16384 px) se bitmapa nevykreslí vůbec. Tachiyomi to řeší
 * SubsamplingScaleImageView; my to řešíme LazyColumnem z řezů - Compose drží složené
 * jen viditelné řezy (~800x2048 = ~6 MB každý), zbytek vysype GC/Coil memory cache.
 *
 * Zdrojové bajty se berou z Coil disk cache (prefetch je tam stejně uloží), při missu
 * se stáhnou do vlastního souboru `tall_page_src/` - jediný rozdíl oproti normálu je,
 * že se nikdy nedekóduje CEK stránka. Bajty v Coil cache se zapisují i přes editor,
 * takže přepnutí do pageru/curlu má stránku z disku bez druhého stahování.
 */
@Singleton
@OptIn(ExperimentalCoilApi::class) // openSnapshot/openEditor - stabilní v praxi, Coil 2.x je takto označuje
class PageSlicer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ImageHttpClient private val httpClient: OkHttpClient,
    private val imageProxyConfig: com.haise.jiyu.source.interceptor.ImageProxyConfig,
) {

    private val planCache = boundedLruMap<String, PageSlicePlan>(MAX_PLANS)
    private val planLocks = Array(LOCK_STRIPES) { Mutex() }

    /**
     * Plán pro [url]. Nikdy nehází - při JAKÉMKOLI problému vrací [PageSlicePlan.Single],
     * takže UI prostě spadne na původní jedno-obrázkovou cestu.
     */
    suspend fun plan(pageUrl: String, referer: String?, wantCrop: Boolean): PageSlicePlan {
        val key = "$pageUrl|$wantCrop"
        planCache[key]?.let { return it }
        // Stripe lock: dva volající pro stejnou stránku nestáhnou/dekódují bounds dvakrát.
        return planLocks[(key.hashCode() and Int.MAX_VALUE) % LOCK_STRIPES].withLock {
            planCache[key] ?: try {
                computePlan(pageUrl, referer, wantCrop)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Kontrakt "plan nikdy nehází" - cokoli neočekávaného = Single fallback.
                android.util.Log.w(TAG, "plan failed, falling back to Single", e)
                PageSlicePlan.Single
            }.also { planCache[key] = it }
        }
    }

    /**
     * Dekóduje jeden řez podle plánu v nativním rozlišení (žádný re-encode, žádný
     * downsampling - kvalita i při pinch-zoomu zůstává plná). Volá [PageSliceFetcher].
     * `null` = plán selhal / stránka už není rozřezaná - volající má hlásit chybu řezu.
     */
    suspend fun decodeSlice(request: PageSliceRequest): Bitmap? {
        val plan = plan(request.pageUrl, request.referer, request.wantCrop)
        if (plan !is PageSlicePlan.Tiled) return null
        val rect = plan.slices.getOrNull(request.sliceIndex) ?: return null
        return withSourceFile(request.pageUrl, request.referer) { file ->
            val decoder = openRegionDecoder(file) ?: return@withSourceFile null
            try {
                decoder.decodeRegion(rect, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
            } finally {
                decoder.recycle()
            }
        }
    }

    // ── Výpočet plánu ─────────────────────────────────────────────────────────

    private suspend fun computePlan(pageUrl: String, referer: String?, wantCrop: Boolean): PageSlicePlan {
        var reason = when {
            pageUrl.isBlank() -> "blank"
            isLazyUrl(pageUrl) -> "lazy"
            ScrambledImageUrl.parse(pageUrl) != null -> "scrambled"
            else -> null
        }
        val result = reason?.let { PageSlicePlan.Single } ?: withSourceFile(pageUrl, referer) { file ->
            if (isAnimatedOrUnreadableHeader(file)) {
                reason = "animated/unreadable"
                return@withSourceFile PageSlicePlan.Single
            }
            val decoder = openRegionDecoder(file) ?: run {
                reason = "no-decoder"
                return@withSourceFile PageSlicePlan.Single
            }
            try {
                val w = decoder.width
                val h = decoder.height
                if (w <= 0 || h <= 0) {
                    reason = "bad-bounds"
                    return@withSourceFile PageSlicePlan.Single
                }
                if (h <= MAX_UNSLICED_HEIGHT) {
                    reason = "short ${w}x${h}"
                    return@withSourceFile PageSlicePlan.Single
                }

                // Ořez okrajů: detekce na jednom vzorkovaném decodu celé stránky (≤ ~2 MP,
                // frakce jsou měřítkově invariantní). Řezy se pak počítají z CONTENT rectu,
                // takže řezané bitmapy jsou rovnou oříznuté - žádná Transformation navíc.
                var contentLeft = 0
                var contentTop = 0
                var contentRight = w - 1
                var contentBottom = h - 1
                var fractions: CropFractions? = null
                if (wantCrop) {
                    val sample = sampleSizeFor(w, h, CROP_DETECT_MAX_PIXELS)
                    val sampled = decoder.decodeRegion(
                        Rect(0, 0, w, h),
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    )
                    fractions = sampled?.let { CropBordersTransformation.detectContentFractions(it) }
                    sampled?.recycle()
                    if (fractions != null) {
                        contentLeft = (fractions.leftF * w).toInt().coerceIn(0, w - 2)
                        contentTop = (fractions.topF * h).toInt().coerceIn(0, h - 2)
                        contentRight = (w - 1 - (fractions.rightF * w).toInt()).coerceIn(contentLeft + 1, w - 1)
                        contentBottom = (h - 1 - (fractions.bottomF * h).toInt()).coerceIn(contentTop + 1, h - 1)
                    }
                }
                val contentW = contentRight - contentLeft + 1
                val contentH = contentBottom - contentTop + 1
                // Pozor: i když ořez stáhne obsah pod MAX_UNSLICED_HEIGHT, NESMÍME spadnout
                // na Single - normální cesta dekóduje celý >8192px zdroj (crop běží až jako
                // transformace po decodu), čímž by se OOM/textura-limit díra vrátila. Tiled
                // s jedním řezem dekóduje jen content region - vždy menší nebo rovno práci.
                val slices = TallImageSlicer.computeSlices(contentH, SLICE_HEIGHT).map { range ->
                    Rect(contentLeft, contentTop + range.first, contentRight + 1, contentTop + range.last + 1)
                }

                // Stejný kontrakt jako CropBordersTransformation: overlay mapuje OCR souřadnice
                // přes uložené frakce (null = nebyl ořez / overlay použije celý obrázek).
                CropBordersTransformation.recordCropFractions(pageUrl, fractions)
                PageSlicePlan.Tiled(contentW, contentH, slices)
            } finally {
                decoder.recycle()
            }
        } ?: run {
            if (reason == null) reason = "no-source"
            PageSlicePlan.Single
        }
        // Diag: Tiled = s nářezem, jinak důvod fallbacku na jedno-obrázkovou cestu
        // (pozor na délku - logujeme jen ocas URL, ne query/podpisy).
        when (val p = result) {
            is PageSlicePlan.Tiled -> android.util.Log.i(TAG,
                "plan TILED ${p.contentWidth}x${p.contentHeight} slices=${p.slices.size} url=…${pageUrl.takeLast(70)}")
            else -> android.util.Log.i(TAG,
                "plan Single($reason) url=…${pageUrl.takeLast(70)}")
        }
        return result
    }

    // ── Zdrojový soubor ───────────────────────────────────────────────────────

    /**
     * Otevře zdrojový soubor stránky a zavolá [block] dokud je přístup platný (u Coil
     * snapshotu drží pin po dobu bloku). `null` výsledek = zdroj nedostupný.
     *
     * - `file://` / absolutní cesta → přímo `File` (offline kapitoly).
     * - http(s) → Coil disk cache snapshot; při missu download do Coil cache (editor) -
     *   bajty tak slouží i normální cestě - a když editor odmítne (souběžný edit), do
     *   vlastního souboru `tall_page_src/<md5>.bin`.
     */
    private suspend fun <T> withSourceFile(pageUrl: String, referer: String?, block: (File) -> T): T? =
        withContext(Dispatchers.IO) {
            if (pageUrl.startsWith("file://")) return@withContext runCatching { block(File(pageUrl.removePrefix("file://"))) }.getOrNull()
            if (pageUrl.startsWith("/")) return@withContext runCatching { block(File(pageUrl)) }.getOrNull()
            if (!pageUrl.startsWith("http://") && !pageUrl.startsWith("https://")) return@withContext null

            val diskCache = Coil.imageLoader(context).diskCache ?: return@withContext null
            val diskKey = diskKeyFor(pageUrl)
            diskCache.openSnapshot(diskKey)?.use { return@withContext runCatching { block(it.data.toFile()) }.getOrNull() }

            // Zdroj může už být stažený ve vlastním souboru (když při plánování byl Coil
            // editor obsazený jiným zápisem) - bez kontroly existence by se STRÁNKA
            // STÁHLA ZNOVU u každého řezu (15 řezů = 15 stejných downloadů).
            val own = ownSliceSourceFile(pageUrl)
            if (own.isFile && own.length() > 0) {
                return@withContext runCatching { block(own) }.getOrNull()
            }

            // Miss - stáhnout. Preferujeme zápis do Coil cache, aby stránka nemusela
            // při pozdějším normálním requestu letět po síti znovu.
            // POZOR: downloadToCache přes rethrowIfControl přehazuje 429 i cancellation -
            // bez catch by 429 probublala do Compose LaunchedEffect = crash na main vlákně
            // (reálně zaznamenáno). "Zdroj nedostupný" = Single fallback a stránka zkusí
            // normální cestu, kde se 429 ukáže jako obvyklá chyba s retry.
            val file = try {
                downloadToCache(pageUrl, diskKey, referer, diskCache)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withContext null
            } ?: return@withContext null
            runCatching { block(file) }.getOrNull()
        }

    /**
     * Klíč do Coil disk cache pro bajty stránky. S zapnutým úsporným režimem obrázků
     * (`wsrv.nl` proxy) leží pod `pageUrl` zmenšená/ztrátová WebP kopie - řezy z ní by
     * byly degradované, přesně čemu je tahle cesta proti (kvalita předností). Proto
     * stejná konvence jako `PageBitmapLoader`: separátní klíč `#original` a request
     * s [ImageProxyInterceptor.HEADER_ORIGINAL], který jde vždy přímo.
     */
    private fun diskKeyFor(pageUrl: String): String =
        diskKeyFor(pageUrl, imageProxyConfig.enabled)

    private fun downloadToCache(pageUrl: String, diskKey: String, referer: String?, diskCache: DiskCache): File? {
        val request = Request.Builder().url(pageUrl).apply {
            if (!referer.isNullOrBlank()) header("Referer", referer)
            // Originální bajty vždy - přes proxy by se stáhla degradovaná kopie
            // (hlavičku interceptor před odesláním odstraní, viz ImageProxyInterceptor).
            header(com.haise.jiyu.source.interceptor.ImageProxyInterceptor.HEADER_ORIGINAL, "1")
        }.build()
        return try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                // Coil editor může vrátit null, když nad klíčem právě běží jiný zápis
                // (např. právě probíhající prefetch request) - pak vlastní soubor.
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
                file
            }
        } catch (e: Exception) {
            e.rethrowIfControl()
            null
        }
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

    // ── Pomocníci ─────────────────────────────────────────────────────────────

    private fun openRegionDecoder(file: File): BitmapRegionDecoder? = try {
        if (file.isFile && file.length() > 0) BitmapRegionDecoder.newInstance(file.absolutePath) else null
    } catch (e: Exception) {
        e.rethrowIfControl()
        null
    }

    /** Lazy URL (MangaHome, FanFox, EHentai...) se resolvují až přes `getImageUrl` -
     *  pro ně řezy nevyrábíme (stránky těchto zdrojů extrémně vysoké nebývají). */
    private fun isLazyUrl(pageUrl: String): Boolean =
        LazyPageUrl.decodeFragment(runCatching { Uri.parse(pageUrl) }.getOrNull()?.fragment) != null

    companion object {
        private const val TAG = "PageSlicer"
        /** Pod touto výškou stránku neřežeme - vejde se do GPU textury i paměti bezpečně. */
        const val MAX_UNSLICED_HEIGHT = 8192
        /** Výška jednoho řezu ve zdrojových pixelech (~6 MB bitmapa u 800px šířky). */
        const val SLICE_HEIGHT = 2048
        /** Strop pro vzorkovaný decode při detekci ořezu (drží <2 MB v RAM). */
        const val CROP_DETECT_MAX_PIXELS = 2_000_000
        private const val MAX_PLANS = 128
        private const val LOCK_STRIPES = 16
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

        /**
         * Nejmenší mocnina dvojky, pro kterou `w*h / sample² <= maxPixels` - ořezová
         * detekce nepotřebuje přesnost, jen uniformní hrany, takže stačí hrubý vzorek.
         */
        fun sampleSizeFor(width: Int, height: Int, maxPixels: Int): Int {
            var sample = 1
            while (sample < 64 && (width / sample).toLong() * (height / sample) > maxPixels) sample *= 2
            return sample
        }

        /**
         * Heuristika na hlavičce souboru: animovaný GIF (`GIF8`) nebo animovaný WebP
         * nebo APNG (`acTL`). Řezaná cesta umí jen statické regiony - animace by
         * ztuhla na první frame, proto Single.
         *
         * WebP: autoritativní je bit animace (0x02) ve flags bajtu VP8X chunku
         * (offset 20) - naivní sken řetězce "ANIM"/"ANMF" by minul soubory, kde
         * před ANIM leží velký ICCP/EXIF chunk za oknem čtení.
         */
        fun isAnimatedMagic(header: ByteArray): Boolean {
            if (header.size >= 3 && header[0] == 'G'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == 'F'.code.toByte()) return true
            val text = String(header, Charsets.ISO_8859_1) // 1:1 byte→char mapování pro sken magic markerů
            if (header.size >= 21 && text.startsWith("RIFF") && text.substring(8, 12) == "WEBP") {
                // Rozšířený formát vždy začíná chunkem VP8X (offset 12); flags byte je
                // hned za velikostí chunku na offsetu 20, bit 0x02 = animation.
                if (text.substring(12, 16) == "VP8X") {
                    if (header[20].toInt() and 0x02 != 0) return true
                }
                // Fallback pro poškozené/exotické soubory bez VP8X.
                if (text.contains("ANIM") || text.contains("ANMF")) return true
            }
            // PNG s acTL chunkem (APNG) - acTL leží vždy před IDAT, tj. v hlavičce.
            if (header.size >= 8 && header[0] == 0x89.toByte() && header[1] == 'P'.code.toByte() && text.contains("acTL")) return true
            return false
        }
    }

    private fun isAnimatedOrUnreadableHeader(file: File): Boolean = try {
        file.inputStream().use { input ->
            // 4 KiB stačí na RIFF hlavičku + VP8X + typické ICCP/EXIF/ANMF chunky; číst
            // musíme v cyklu - InputStream.read() může vrátit méně bajtů než délka bufferu.
            val header = ByteArray(4096)
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n <= 0) break
                read += n
            }
            read <= 0 || isAnimatedMagic(header.copyOf(read))
        }
    } catch (e: Exception) {
        e.rethrowIfControl()
        true
    }
}
