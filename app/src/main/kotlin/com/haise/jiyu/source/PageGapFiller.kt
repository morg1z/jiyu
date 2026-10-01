package com.haise.jiyu.source

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.net.Uri
import com.haise.jiyu.BuildConfig
import com.haise.jiyu.data.db.ChapterDao
import com.haise.jiyu.data.db.MangaDao
import com.haise.jiyu.data.db.TranslatedPageDao
import com.haise.jiyu.di.ImageHttpClient
import com.haise.jiyu.util.LazyPageUrl
import com.haise.jiyu.util.RowProfileMatcher
import com.haise.jiyu.util.RowSignature
import com.haise.jiyu.util.ScrambledImageUrl
import com.haise.jiyu.util.report
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Cross-source "gap fill" chybějících stránek kapitoly.
 *
 * Pozadí (audit comick.art, RWS ch.215): agregátor zveřejnil seznam obrázků s dírami
 * v číslování (`144.webp` → `146.webp` - soubor na CDN 404, upload skupiny ho nikdy
 * neměl). Kapitola má "zdravých" 343 stránek, ale prostředek příběhu chybí. Appka
 * do téhle změny čtenáři tichě servírovala nekompletní kapitolu.
 *
 * Postup (voláno z [com.haise.jiyu.data.repository.MangaRepository.getChapterPages],
 * výsledek se cachuje stejně jako holý seznam):
 * 1. [PageGapDetector] najde díry v číslování URL (bez sítě).
 * 2. [CrossSourceSearch] najde stejný titul na jiných zdrojích; kandidáti se stejným
 *    číslem kapitoly se zkouší jako "donor".
 * 3. Zarovnání dvěma úrovněmi:
 *    a) číselná shoda (`alignByNumbers`) - donor má stránky s přesně chybějícím
 *       číslem mezi známými sousedy (stejný upload zrcadlený jinde) - bez stahování,
 *    b) kontentové zarovnání (`alignByContent`) - řádkové profily sousedních stránek
 *       se najdou uvnitř donorových obrázků a chybějící pás se OŘIZNE do lokálního
 *       souboru (zvládne i jiné řezání pásů); mezilehlé celé donor stránky se vloží
 *       jako lazy URL.
 * 4. Zásah je konzervativní: co se nedá ověřit, se nevkládá - kapitola radši zůstane
 *    s mezerou než s cizí stránkou.
 *
 * Vložené stránky:
 * - lokální výstřižky jako `file://` (OCR/offline-safe),
 * - celé donor stránky jako `LazyPageUrl` marker s donor sourceId - fetcher je pak
 *   resolvuje a stahuje se SPRÁVNÝM refererem donorova zdroje (viz LazyPageFetcher).
 *
 * Pozn. ke cache překladů: klíč je `chapterId::pageIndex` - vsunuté stránky indexy
 * posunou, takže při prvním úspěšném doplnění se staré překlady kapitoly mažou
 * (marker v SharedPreferences, při opakovaném doplnění už se nemaže).
 */
@Singleton
class PageGapFiller @Inject constructor(
    private val crossSourceSearch: CrossSourceSearch,
    private val mangaDao: MangaDao,
    private val chapterDao: ChapterDao,
    private val translatedPageDao: TranslatedPageDao,
    @param:ApplicationContext private val context: Context,
    @param:ImageHttpClient private val httpClient: OkHttpClient,
) {

    /**
     * Vrátí seznam stránek s doplněnými mezerami, nebo původní [pages], když se nic
     * nepodařilo ověřit. Nikdy nehází - jakékoli selhání = původní seznam (dnešní
     * chování). Celý běh je pod [GAP_FILL_TIMEOUT_MS] stropem, aby pomalý donor nemohl
     * rozbít timeout načítání kapitoly ve čtečce.
     */
    suspend fun fillIfGapped(
        source: MangaSource,
        chapterUrl: String,
        mangaUrl: String,
        pages: List<Page>,
    ): List<Page> {
        val urls = pages.map { it.imageUrl ?: it.url }
        val gaps = PageGapDetector.detect(urls)
        if (gaps.isEmpty()) return pages
        pruneStaleDonorFiles()
        if (BuildConfig.DEBUG) android.util.Log.i(TAG,
            "gaps in $chapterUrl: ${gaps.joinToString { "${it.insertIndex} misses ${it.missingNumbers}" }}")
        return try {
            withTimeoutOrNull(GAP_FILL_TIMEOUT_MS) {
                fillInternal(source, chapterUrl, mangaUrl, pages, gaps)
            } ?: pages
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.report("gapfill")
            pages
        }
    }

    private suspend fun fillInternal(
        source: MangaSource,
        chapterUrl: String,
        mangaUrl: String,
        pages: List<Page>,
        gaps: List<PageGapDetector.PageGap>,
    ): List<Page> {
        val manga = mangaDao.getMangaBySourceAndUrl(source.id, mangaUrl) ?: return pages
        val chapter = chapterDao.getAllForManga(manga.id).firstOrNull { it.url == chapterUrl }
        val chapterNumber = chapter?.chapterNumber
            ?: chapterNumberFromUrl(chapterUrl) ?: return pages

        // Donor kandidáti: stejný titul na jiném zdroji + stejné číslo kapitoly.
        val donors = collectDonors(manga, source, chapterNumber)
        for ((donorSource, donorChapter) in donors) {
            val donorPages = try {
                donorSource.getPageList(donorChapter)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            if (donorPages.isEmpty()) continue

            val donorUrls = donorPages.map { it.imageUrl ?: it.url }
            // 1) Číselná shoda - zdarma, bez stahování.
            PageGapAligner.alignByNumbers(gaps, pages.map { it.imageUrl ?: it.url }, donorUrls)?.let { byNum ->
                val insertions = byNum.mapValues { (_, idxs) ->
                    idxs.map { donorLazyPage(donorSource.id, donorPages[it]) }
                }
                if (insertions.values.any { list -> list.any { p -> p == null } }) return@let null
                return spliceAndInvalidate(
                    pages,
                    insertions.mapValues { (_, l) -> l.filterNotNull() },
                    chapter?.id,
                )
            }
            // 2) Kontentové zarovnání (řádkové profily + ořez do lokálního souboru).
            alignByContent(source, gaps, pages, donorSource, donorPages, md5(chapterUrl))?.let { byContent ->
                return spliceAndInvalidate(pages, byContent, chapter?.id)
            }
        }
        return pages
    }

    // ── Donor discovery ──────────────────────────────────────────────────────

    private class DoneCollecting : Exception()

    private suspend fun collectDonors(
        manga: com.haise.jiyu.data.db.entity.MangaEntity,
        source: MangaSource,
        chapterNumber: Float,
    ): List<Pair<MangaSource, SChapter>> {
        val out = mutableListOf<Pair<MangaSource, SChapter>>()
        try {
            withTimeoutOrNull(DONOR_SEARCH_TIMEOUT_MS) {
                crossSourceSearch.seeds(manga, source).collect { seed ->
                    seed.chapters
                        .filter { abs(it.chapterNumber - chapterNumber) < 0.01f }
                        .forEach { out += seed.source to it }
                    if (out.size >= MAX_DONOR_CANDIDATES) throw DoneCollecting()
                }
            }
        } catch (_: DoneCollecting) {
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        return out
    }

    /** `…-chapter-215-en` / `chap=215` apod. - fallback, když entita kapitoly chybí. */
    private fun chapterNumberFromUrl(url: String): Float? =
        Regex("""(?:chapter[-_/=]|chap[-_/=]|ch[-_/])(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)?.toFloatOrNull()

    // ── Zarovnání čísly souborů (čistá logika je v [PageGapAligner]) ─────────

    /** Donor stránka jako lazy marker (donor sourceId vede resolve+referer). */
    private fun donorLazyPage(donorSourceId: String, donorPage: Page): Page? {
        // URL s vlastním fragmentem (scramble/lazy) by dvojitý marker rozbil - takovou
        // donor stránku nevkládat jako vzdálenou (v alignByContent se stáhne jako výřez).
        if (Uri.parse(donorPage.url).fragment != null) return null
        if (ScrambledImageUrl.parse(donorPage.url) != null) return null
        return Page(index = 0, url = LazyPageUrl.encode(donorSourceId, donorPage.index, donorPage.url))
    }

    // ── Kontentové zarovnání ─────────────────────────────────────────────────

    /** Stránka po stažení uložená jako soubor (profil + případný ořez přes decoder). */
    private class DonorImage(val file: File, val width: Int, val height: Int, val signature: RowSignature)

    private suspend fun alignByContent(
        source: MangaSource,
        gaps: List<PageGapDetector.PageGap>,
        pages: List<Page>,
        donorSource: MangaSource,
        donorPages: List<Page>,
        dirKey: String,
    ): Map<Int, List<Page>>? {
        val cache = HashMap<Int, DonorImage?>()
        suspend fun donorImage(i: Int): DonorImage? = cache.getOrPut(i) { loadDonorImage(donorSource, donorPages[i], dirKey) }

        val insertions = HashMap<Int, List<Page>>()
        for (gap in gaps) {
            val before = pages.getOrNull(gap.insertIndex - 1)
            val after = pages.getOrNull(gap.insertIndex)
            if (before == null || after == null) continue
            val beforeSig = profileOf(source, before) ?: continue
            val afterSig = profileOf(source, after) ?: continue
            val beforeNeedle = RowProfileMatcher.edgeStrip(beforeSig, top = false) ?: continue
            val afterNeedle = RowProfileMatcher.edgeStrip(afterSig, top = true) ?: continue

            // Proporční odhad pozice mezery v donorovi - scan startuje tam, při
            // neúspěchu se rozšiřuje na celý seznam (donor stránky bývají desítky).
            val est = (gap.insertIndex.toFloat() / (pages.size + gap.missingCount) * donorPages.size).toInt()
            val order = (donorPages.indices).sortedBy { abs(it - est) }

            var beforeMatch: Pair<Int, RowProfileMatcher.Match>? = null
            var afterMatch: Pair<Int, RowProfileMatcher.Match>? = null
            for (i in order) {
                val img = donorImage(i) ?: continue
                if (beforeMatch == null) {
                    RowProfileMatcher.findStrip(beforeNeedle, img.signature)
                        ?.takeIf { it.margin >= RowProfileMatcher.MIN_MATCH_MARGIN }
                        ?.let { beforeMatch = i to it }
                }
                if (afterMatch == null) {
                    RowProfileMatcher.findStrip(afterNeedle, img.signature)
                        ?.takeIf { it.margin >= RowProfileMatcher.MIN_MATCH_MARGIN }
                        ?.let { afterMatch = i to it }
                }
                if (beforeMatch != null && afterMatch != null) break
            }
            val (bj, bm) = beforeMatch ?: continue
            val (bk, am) = afterMatch ?: continue
            val bImg = donorImage(bj) ?: continue
            val aImg = donorImage(bk) ?: continue
            val beforeEnd = bm.row + beforeNeedle.rows
            val afterStart = am.row
            if (bk < bj || (bk == bj && afterStart <= beforeEnd)) continue

            val inserted = mutableListOf<Page>()
            // Chvost donor[bj]: od konce "before" pásu do konce stránky.
            val tailStartPx = RowProfileMatcher.rowToSource(beforeEnd, bImg.signature, bImg.height) + TRIM_PX
            if (tailStartPx < bImg.height - MIN_CROP_PX) {
                cropToFile(bImg.file, dirKey, gap.insertIndex, part = "tail",
                    top = tailStartPx, bottom = bImg.height)?.let { inserted += it }
            }
            // Celé mezilehlé donor stránky - jako lazy vzdálené (ne crop).
            for (i in bj + 1 until bk) {
                donorLazyPage(donorSource.id, donorPages[i])?.let { inserted += it }
            }
            // Hlava donor[bk]: začátek až začátek "after" pásu.
            val headEndPx = RowProfileMatcher.rowToSource(afterStart, aImg.signature, aImg.height) - TRIM_PX
            if (headEndPx > MIN_CROP_PX) {
                cropToFile(aImg.file, dirKey, gap.insertIndex, part = "head",
                    top = 0, bottom = headEndPx)?.let { inserted += it }
            }
            if (inserted.isEmpty()) continue
            insertions[gap.insertIndex] = inserted
        }
        return if (insertions.isEmpty()) null else insertions
    }

    /** Profil stránky původního zdroje (stáhne + zmenší na profilovou šířku). */
    private suspend fun profileOf(source: MangaSource, page: Page): RowSignature? {
        val url = page.imageUrl ?: runCatching { source.getImageUrl(page) }.getOrNull() ?: return null
        val bytes = downloadBytes(url, source.homepageUrl) ?: return null
        val bmp = decodeSampled(bytes) ?: return null
        val sig = RowProfileMatcher.of(bmp)
        if (bmp !== null && !bmp.isRecycled) bmp.recycle()
        return sig
    }

    /** Donor stránka: bajty → dočasný soubor (pro přesný ořez) + zmenšený profil. */
    private suspend fun loadDonorImage(source: MangaSource, page: Page, dirKey: String): DonorImage? {
        val url = page.imageUrl ?: runCatching { source.getImageUrl(page) }.getOrNull() ?: return null
        if (ScrambledImageUrl.parse(url) != null) return null
        val bytes = downloadBytes(url, source.homepageUrl) ?: return null
        val bmp = decodeSampled(bytes) ?: return null
        val sig = RowProfileMatcher.of(bmp)
        bmp.recycle()
        val file = File(File(gapDirRoot, dirKey).apply { mkdirs() }, "donor_${page.index}_${System.nanoTime()}.img")
        try {
            FileOutputStream(file).use { it.write(bytes) }
        } catch (e: Exception) {
            return null
        }
        // Rozměry originálu pro mapování profil→pixely.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) { file.delete(); return null }
        return DonorImage(file, bounds.outWidth, bounds.outHeight, sig)
    }

    private fun cropToFile(
        donorFile: File,
        dirKey: String,
        insertIndex: Int,
        part: String,
        top: Int,
        bottom: Int,
    ): Page? {
        return try {
            val decoder = openRegionDecoder(donorFile)
            val bmp = if (decoder != null) {
                try {
                    decoder.decodeRegion(
                        android.graphics.Rect(0, top, decoder.width, bottom),
                        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
                    )
                } finally {
                    decoder.recycle()
                }
            } else {
                // Region decoder nektere formaty neumi (VP8L WebP apod.) - fallback na
                // plny decode + Bitmap orez. Donor stranka muze byt vysoka (webtoon
                // strip) = desitky MB v RAM; jednorazova cena, korutina muze zemrit
                // jen na OOM radeji nez zahodit plnitelnou mezeru.
                val full = BitmapFactory.decodeFile(donorFile.absolutePath) ?: return null
                try {
                    Bitmap.createBitmap(full, 0, top, full.width, (bottom - top).coerceAtMost(full.height - top))
                } finally {
                    full.recycle()
                }
            } ?: return null
            val dir = File(context.filesDir, "gapfill/$dirKey").apply { mkdirs() }
            val out = File(dir, "gap${insertIndex}_${part}.webp")
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 92, it) }
            bmp.recycle()
            val uri = "file://${out.absolutePath}"
            Page(index = 0, url = uri, imageUrl = uri)
        } catch (e: Exception) {
            null
        }
    }

    private fun openRegionDecoder(file: File): BitmapRegionDecoder? =
        runCatching {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(file.absolutePath, true)
        }.getOrNull()

    private suspend fun downloadBytes(url: String, referer: String?): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(Request.Builder().url(url).apply {
                    if (!referer.isNullOrBlank()) header("Referer", referer)
                }.build()).execute().use { r ->
                    if (r.isSuccessful) r.body?.bytes() else null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    /** Zmenšený decode pro profil (šířka ~profilu; stačí struktura, ne detaily). */
    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= RowProfileMatcher.PROFILE_WIDTH) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    /** Dočasné surové donor obrázky (source pro BitmapRegionDecoder) - cacheDir čistí systém,
     *  ale starší soubory mažeme sami, ať se při opakovaných pokusech nehromadí. */
    private val gapDirRoot: File get() = File(context.cacheDir, "gapfill_src").apply { mkdirs() }

    private fun pruneStaleDonorFiles() {
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
        gapDirRoot.listFiles()?.forEach { dir ->
            dir.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
        }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // ── Složení výsledku + invalidace překladové cache ───────────────────────

    private suspend fun spliceAndInvalidate(
        pages: List<Page>,
        insertions: Map<Int, List<Page>>,
        chapterId: String?,
    ): List<Page> {
        if (insertions.isEmpty()) return pages
        val reindexed = PageGapAligner.splice(pages, insertions)
        if (BuildConfig.DEBUG) android.util.Log.i(TAG,
            "filled ${insertions.size} gaps, ${pages.size} -> ${reindexed.size} pages")
        if (chapterId != null) invalidateChapterTranslationsOnce(chapterId)
        return reindexed
    }

    /**
     * Klíč překladové cache je chapterId::pageIndex - vložené stránky indexy posunuly,
     * takže staré strojové překlady by se aplikovaly na špatné stránky. Smažou se,
     * ale JEN při prvním doplnění téhle kapitoly (flag v prefs) - při dalších
     * doplněních (TTL refresh page listu) už indexy sedí a mazání by zahodilo
     * čerstvé překlady počítané na doplněném seznamu.
     */
    private suspend fun invalidateChapterTranslationsOnce(chapterId: String) {
        val prefs = context.getSharedPreferences(GAPFILL_PREFS, Context.MODE_PRIVATE)
        val key = md5(chapterId)
        if (prefs.getBoolean(key, false)) return
        translatedPageDao.deleteForChapter(chapterId)
        prefs.edit().putBoolean(key, true).apply()
    }

    private companion object {
        const val TAG = "PageGapFiller"
        /** Strop celého doplnění - nesmí blokovat načtení kapitoly. */
        const val GAP_FILL_TIMEOUT_MS = 30_000L
        const val DONOR_SEARCH_TIMEOUT_MS = 15_000L
        const val MAX_DONOR_CANDIDATES = 3
        /** Inward trim při ořezu - pár řádků překryvu mezery je horší než malá ztráta. */
        const val TRIM_PX = 12
        const val MIN_CROP_PX = 40
        const val GAPFILL_PREFS = "jiyu_gapfill"
    }
}
