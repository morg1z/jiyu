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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
        val dirKey = md5(chapterUrl)
        // Trvalá cache doplněného seznamu - opakované otevření kapitoly po TTL
        // page cache by jinak znamenalo znovu ~20-30s donor sweep. Klíčem je hash
        // host URL seznamu: změní-li se zdroj (jiné URL), cache se ignoruje.
        loadCachedFill(dirKey, urls)?.let { cached ->
            if (BuildConfig.DEBUG) android.util.Log.i(TAG,
                "cached fill for $chapterUrl: ${pages.size} -> ${cached.size} pages")
            return cached
        }
        if (BuildConfig.DEBUG) android.util.Log.i(TAG,
            "gaps in $chapterUrl: ${gaps.joinToString { "${it.insertIndex} misses ${it.missingNumbers}" }}")
        return try {
            // Dokončený splice se propaguje i mimo návratovou hodnotu bloku -
            // úspěšný donor zruší scope, ale cancel() se teprve šíří dětmi;
            // `withTimeoutOrNull` může vyhodit timeout, zatímco fillInternal už
            // výsledek má (audit: 3 mezery doplněné v :32.7 zahozené v :47).
            val completed = java.util.concurrent.atomic.AtomicReference<List<Page>?>(null)
            val filled = withTimeoutOrNull(GAP_FILL_TIMEOUT_MS) {
                fillInternal(source, chapterUrl, mangaUrl, pages, gaps, completed, dirKey)
            }
            val result = filled ?: completed.get()
            if (result == null && BuildConfig.DEBUG) android.util.Log.i(TAG,
                "gap fill timed out after ${GAP_FILL_TIMEOUT_MS}ms for $chapterUrl")
            // Ukládat jen kompletně doplněné výsledky - částečný fill nechat
            // znovu zkusit, příště může pomalý donor doběhnout.
            if (result != null && result !== pages &&
                result.size == pages.size + gaps.sumOf { it.missingCount }) {
                saveCachedFill(dirKey, urls, result)
            }
            result ?: pages
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
        completed: java.util.concurrent.atomic.AtomicReference<List<Page>?>,
        dirKey: String,
    ): List<Page> {
        val manga = mangaDao.getMangaBySourceAndUrl(source.id, mangaUrl) ?: run {
            if (BuildConfig.DEBUG) android.util.Log.i(TAG, "no library manga for $mangaUrl")
            return pages
        }
        val chapter = chapterDao.getAllForManga(manga.id).firstOrNull { it.url == chapterUrl }
        val chapterNumber = chapter?.chapterNumber
            ?: chapterNumberFromUrl(chapterUrl)
        if (chapterNumber == null) {
            if (BuildConfig.DEBUG) android.util.Log.i(TAG, "no chapter number for $chapterUrl")
            return pages
        }

        // Donor kandidáti se zkoušejí PARALELNĚ, jak seeds streamují - sekvenční
        // tryDonor v collect by jeden pomalý donor (typicky scrambled CDN, které
        // content scan nedokáže matchnout) zablokoval na celý rozpočet a později
        // dorazivší vhodný donor by se už nikdy nezkusil. První úspěch scope zruší.
        val filled = java.util.concurrent.atomic.AtomicReference<List<Page>?>(null)
        val donorsTried = java.util.concurrent.atomic.AtomicInteger(0)
        val donorsSeen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        // Jeden donor pokus na zdroj - comix seed obsahoval ch.215 od dvou skupin,
        // obě tryDonor stahovaly tentýž (scrambled) obsah a rozpůlily rozpočet.
        val donorSourcesSeen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        try {
            kotlinx.coroutines.coroutineScope {
                crossSourceSearch.seeds(manga, source, relaxedTitleMatch = true).collect { seed ->
                    if (BuildConfig.DEBUG) android.util.Log.i(TAG,
                        "seed ${seed.source.id}: '${seed.manga.title}' (${seed.chapters.size} chapters)")
                    seed.chapters
                        .filter { abs(it.chapterNumber - chapterNumber) < 0.01f }
                        .forEach { donorChapter ->
                            // Stejný donor zdroj+kapitola umí dorazit ve více seedech
                            // (sweep posílá víc dotazů) - bez deduplikace by dvě tryDonor
                            // stahovaly tentýž obsah a vyžraly sloty MAX_DONOR_CANDIDATES.
                            if (!donorsSeen.add(seed.source.id + '|' + donorChapter.url)) return@forEach
                            if (!donorSourcesSeen.add(seed.source.id)) return@forEach
                            if (donorsTried.incrementAndGet() > MAX_DONOR_CANDIDATES) return@forEach
                            launch {
                                try {
                                    tryDonor(seed.source, donorChapter, gaps, pages, source, chapter?.id, dirKey)
                                        ?.let { result ->
                                            if (filled.compareAndSet(null, result)) {
                                                completed.set(result)
                                                this@coroutineScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
                                            }
                                        }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    // Chyba jednoho donoru nesmí zabít scope - bez catchu by
                                    // propagace z launch zrušila i ostatní pokusy o dary.
                                    if (BuildConfig.DEBUG) android.util.Log.i(TAG,
                                        "donor ${seed.source.id} failed: ${e.javaClass.simpleName}: ${e.message}")
                                }
                            }
                        }
                }
            }
        } catch (e: CancellationException) {
            // Zrušení z vlastního úspěšného donoru (viz výše) není skutečná kancelace
            // vnějšího běhu - bez filled-checku by se propagovalo dál a výsledek zahodilo.
            if (filled.get() == null) throw e
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.i(TAG,
                "fill sweep failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        if (BuildConfig.DEBUG) android.util.Log.i(TAG,
            "ch.$chapterNumber donors tried: ${donorsTried.get()}, filled=${filled.get() != null}")
        return filled.get() ?: pages
    }

    /** Jeden donor kandidát: page list → číselné zarovnání → kontentové zarovnání. */
    private suspend fun tryDonor(
        donorSource: MangaSource,
        donorChapter: SChapter,
        gaps: List<PageGapDetector.PageGap>,
        pages: List<Page>,
        source: MangaSource,
        chapterId: String?,
        dirKey: String,
    ): List<Page>? {
        val donorPages = try {
            donorSource.getPageList(donorChapter)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.i(TAG, "donor ${donorSource.id} page list failed: ${e.message}")
            return null
        }
        if (donorPages.isEmpty()) return null
        if (BuildConfig.DEBUG) android.util.Log.i(TAG, "donor ${donorSource.id}: ${donorPages.size} pages")

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
                chapterId,
            )
        }
        // 2) Kontentové zarovnání (řádkové profily + ořez do lokálního souboru).
        // Donor s většinou "ozdobených" URL (scramble/lazy fragmenty, descramble query
        // parametry) nemá smysl skenovat - stažené bajty nejsou finální pixely, takže
        // profily nikdy nematchnou a scan jen vyžere rozpočet (audit: comix donor).
        val decorated = donorUrls.count {
            Uri.parse(it).fragment != null || ScrambledImageUrl.parse(it) != null
        }
        if (decorated * 2 <= donorUrls.size) {
            alignByContent(source, gaps, pages, donorSource, donorPages, dirKey)?.let { byContent ->
                return spliceAndInvalidate(pages, byContent, chapterId)
            }
        } else if (BuildConfig.DEBUG) {
            android.util.Log.i(TAG, "donor ${donorSource.id}: skipped content scan, $decorated/${donorUrls.size} decorated urls")
        }
        if (BuildConfig.DEBUG) android.util.Log.i(TAG, "donor ${donorSource.id}: no alignment")
        return null
    }

    // ── Donor discovery ──────────────────────────────────────────────────────

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
        // Sdílená donor cache + jeden semafor napříč všemi mezerami donoru - scan
        // sousedních mezer sahá na překrývající se donor stránky (každá se stáhne
        // jednou) a 4 mezery × SCAN_CONCURRENCY by jinak pouštěly 16 souběžných
        // downloadů+dekódů najednou. synchronizedMap(HashMap) - ConcurrentHashMap
        // nepodporuje null hodnoty a getOrPut{null} by házel NPE za každý fail.
        // Race na getOrPut = případný dvojí download (škodný, ne chybný).
        val cache = java.util.Collections.synchronizedMap(HashMap<Int, DonorImage?>())
        val fetchSem = Semaphore(SCAN_CONCURRENCY)
        suspend fun donorImage(i: Int): DonorImage? =
            cache.getOrPut(i) { fetchSem.withPermit { loadDonorImage(donorSource, donorPages[i], dirKey) } }

        // Izomorfní donor = jeho počet stránek přesně odpovídá host+chybějící
        // (webtoons 347 vs 343+4) → řezání je shodné a mapování deterministické:
        // host[i] ↔ donor[i + počet mezer před i]. Stačí 2 sondy na mezeru místo
        // ~48 stránkového scanu - live: gap@317 se scanem nestihl, izomorfně ano.
        val totalMissing = gaps.sumOf { it.missingCount }
        val isomorphic = donorPages.size == pages.size + totalMissing
        var missingBefore = 0

        // Mezery se řeší paralelně - sekvenčně by 4 díry × (profily host stránek +
        // scan donor stránek) nepřinesly výsledek pod stropem. Vlastní deadline
        // pod celkovým stropem: i částečný výsledek (některé mezery) se vrátí
        // místo ztráty všech, když poslední mezera dobíhá do globálního timeoutu.
        val insertions = java.util.concurrent.ConcurrentHashMap<Int, List<Page>>()
        withTimeoutOrNull(CONTENT_ALIGN_BUDGET_MS) {
            kotlinx.coroutines.coroutineScope {
                gaps.forEach { gap ->
                    val gapMissingBefore = missingBefore
                    missingBefore += gap.missingCount
                    launchGap(gap, pages, source, donorSource, donorPages, dirKey,
                        isomorphic = isomorphic, missingBefore = gapMissingBefore,
                        donorImage = { donorImage(it) }) { idx, list ->
                        if (list.isNotEmpty()) insertions[idx] = list
                    }
                }
            }
        }
        return if (insertions.isEmpty()) null else insertions.toMap()
    }

    private fun kotlinx.coroutines.CoroutineScope.launchGap(
        gap: PageGapDetector.PageGap,
        pages: List<Page>,
        source: MangaSource,
        donorSource: MangaSource,
        donorPages: List<Page>,
        dirKey: String,
        isomorphic: Boolean,
        missingBefore: Int,
        donorImage: suspend (Int) -> DonorImage?,
        onDone: (Int, List<Page>) -> Unit,
    ) = launch {
        val before = pages.getOrNull(gap.insertIndex - 1)
        val after = pages.getOrNull(gap.insertIndex)
        if (before == null || after == null) return@launch
        val beforeSig = profileOf(source, before) ?: return@launch
        val afterSig = profileOf(source, after) ?: return@launch
        val beforeNeedle = RowProfileMatcher.edgeStrip(beforeSig, top = false) ?: return@launch
        val afterNeedle = RowProfileMatcher.edgeStrip(afterSig, top = true) ?: return@launch
        var beforeMatch: Pair<Int, RowProfileMatcher.Match>? = null
        var afterMatch: Pair<Int, RowProfileMatcher.Match>? = null
        var bestSeen = 0f

        // Izomorfní donor: předpokládané donor indexy sousedů jsou přesné -
        // ověříme je přímo (2 downloady na mezeru). Prochází-li obě sondy, celý
        // 48-stránkový scan se přeskočí; při selhání se normálně scanuje.
        if (isomorphic) {
            val impliedBefore = gap.insertIndex - 1 + missingBefore
            val impliedAfter = gap.insertIndex + missingBefore + gap.missingCount
            beforeMatch = donorPages.getOrNull(impliedBefore)?.let { donorImage(impliedBefore) }
                ?.let { img -> RowProfileMatcher.findStripRaw(beforeNeedle, img.signature) }
                ?.takeIf { it.score >= RELAXED_MATCH_SCORE && it.margin >= RELAXED_MATCH_MARGIN }
                ?.let { impliedBefore to it }
            afterMatch = donorPages.getOrNull(impliedAfter)?.let { donorImage(impliedAfter) }
                ?.let { img -> RowProfileMatcher.findStripRaw(afterNeedle, img.signature) }
                ?.takeIf { it.score >= RELAXED_MATCH_SCORE && it.margin >= RELAXED_MATCH_MARGIN }
                ?.let { impliedAfter to it }
            if (BuildConfig.DEBUG) android.util.Log.i(TAG,
                "gap@${gap.insertIndex} in ${donorSource.id}: iso probe before=$impliedBefore->${beforeMatch?.second?.score}, after=$impliedAfter->${afterMatch?.second?.score}")
        }

        if (beforeMatch != null && afterMatch != null) {
            if (BuildConfig.DEBUG) android.util.Log.i(TAG,
                "gap@${gap.insertIndex} in ${donorSource.id}: isomorphic hit, skipping scan")
        } else {
        if (BuildConfig.DEBUG) android.util.Log.i(TAG,
            "gap@${gap.insertIndex} in ${donorSource.id}: anchors profiled, scanning ${donorPages.size}p")

        // Proporční odhad pozice mezery v donorovi + omezené okno scanu - bez něj by
        // špatný donor (titul matchl, obsah ne) stáhl všechny stránky až do timeoutu.
        val est = (gap.insertIndex.toFloat() / (pages.size + gap.missingCount) * donorPages.size).toInt()
        val order = (donorPages.indices).sortedBy { abs(it - est) }.take(MAX_DONOR_SCAN_PAGES)

        // Stahování donor stránek běží paralelně (SCAN_CONCURRENCY) a výsledky se
        // testují v pořadí dokončení - sekvenční scan by jednu ~1s stránku násobil
        // přes celé okno a 30s strop doplnění neudržel.
        val fetched = kotlinx.coroutines.channels.Channel<Pair<Int, DonorImage?>>(
            capacity = kotlinx.coroutines.channels.Channel.UNLIMITED)
        var remaining = 0
        val fetchJobs = java.util.Collections.synchronizedList(mutableListOf<kotlinx.coroutines.Job>())
        for (i in order) {
            remaining++
            fetchJobs += launch {
                // Null se posílá taky - consumer počítá dokončené fetchery, bez
                // něj by smyčka visela na receive(), kdyby některý download padl.
                // A exception se překládá na null - propagace z launch by zrušila
                // sourozenecké gap/fetch joby celého donoru (tiše spolknuté nahoře).
                val img = try {
                    donorImage(i)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) android.util.Log.i(TAG,
                        "donor ${donorSource.id}#$i fetch error: ${e.javaClass.simpleName}")
                    null
                }
                fetched.trySend(i to img)
            }
        }

        while (remaining > 0 && (beforeMatch == null || afterMatch == null)) {
            val (i, img) = fetched.receive()
            remaining--
            if (img == null) continue
            if (beforeMatch == null) {
                val m = RowProfileMatcher.findStripRaw(beforeNeedle, img.signature)
                if (m != null && m.score > bestSeen) bestSeen = m.score
                if (m != null && m.score >= RowProfileMatcher.MIN_MATCH_SCORE && m.margin >= RowProfileMatcher.MIN_MATCH_MARGIN)
                    beforeMatch = i to m
            }
            if (afterMatch == null) {
                val m = RowProfileMatcher.findStripRaw(afterNeedle, img.signature)
                if (m != null && m.score > bestSeen) bestSeen = m.score
                if (m != null && m.score >= RowProfileMatcher.MIN_MATCH_SCORE && m.margin >= RowProfileMatcher.MIN_MATCH_MARGIN)
                    afterMatch = i to m
            }
        }
        // Zrušit dobíhající fetchery - jsou dětmi tohoto jobu, takže bez zrušení
        // by coroutine gap doběhl až s posledním downloadem a blokoval scope
        // donoru i po nalezení obou kotev.
        fetchJobs.forEach { it.cancel() }
        if (BuildConfig.DEBUG) android.util.Log.i(TAG,
            "gap@${gap.insertIndex} in ${donorSource.id}: before=${beforeMatch?.first}, after=${afterMatch?.first} (scanned ${order.size - remaining}/${order.size}, bestScore=$bestSeen)")
        }

        // Jednokotvený fallback: jedna kotva matchla přesně, druhá ne - typicky
        // u donorů s o řez posunutým řezáním nebo plochými okraji (bílý spodek
        // stránky má nulový margin všude). Předpokládanou donor stránku spočítáme
        // z nalezené kotvy a chybějící kotvu na ní ověříme s uvolněným prahem.
        // Levné (1 stažení) a pořád ověřené - na slepou důvěru se nevkládá nic.
        if (beforeMatch == null && afterMatch != null) {
            val implied = afterMatch!!.first - gap.missingCount - 1
            val m = donorPages.getOrNull(implied)?.let { donorImage(implied) }
                ?.let { RowProfileMatcher.findStripRaw(beforeNeedle, it.signature) }
            if (m != null && m.score >= RELAXED_MATCH_SCORE && m.margin >= RELAXED_MATCH_MARGIN) {
                beforeMatch = implied to m
            }
            if (BuildConfig.DEBUG) android.util.Log.i(TAG,
                "gap@${gap.insertIndex} in ${donorSource.id}: implied before=$implied relaxed=${m?.score}/${m?.margin}")
        } else if (afterMatch == null && beforeMatch != null) {
            val implied = beforeMatch!!.first + gap.missingCount + 1
            val m = donorPages.getOrNull(implied)?.let { donorImage(implied) }
                ?.let { RowProfileMatcher.findStripRaw(afterNeedle, it.signature) }
            if (m != null && m.score >= RELAXED_MATCH_SCORE && m.margin >= RELAXED_MATCH_MARGIN) {
                afterMatch = implied to m
            }
            if (BuildConfig.DEBUG) android.util.Log.i(TAG,
                "gap@${gap.insertIndex} in ${donorSource.id}: implied after=$implied relaxed=${m?.score}/${m?.margin}")
        }

        val (bj, bm) = beforeMatch ?: return@launch
        val (bk, am) = afterMatch ?: return@launch
        val bImg = donorImage(bj) ?: return@launch
        val aImg = donorImage(bk) ?: return@launch
        val beforeEnd = bm.row + beforeNeedle.rows
        val afterStart = am.row
        if (bk < bj || (bk == bj && afterStart <= beforeEnd)) return@launch

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
        onDone(gap.insertIndex, inserted)
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
        val t0 = System.nanoTime()
        val url = page.imageUrl ?: runCatching { source.getImageUrl(page) }.getOrNull() ?: return null
        if (ScrambledImageUrl.parse(url) != null) return null
        if (Uri.parse(url).fragment != null) return null // scramble/lazy markery - viz donor skip
        val bytes = downloadBytes(url, source.homepageUrl) ?: run {
            if (BuildConfig.DEBUG) android.util.Log.i(TAG, "donor ${source.id}#${page.index}: download failed")
            return null
        }
        val bmp = decodeSampled(bytes) ?: return null
        val sig = RowProfileMatcher.of(bmp)
        bmp.recycle()
        if (BuildConfig.DEBUG && page.index % 10 == 0) android.util.Log.i(TAG,
            "donor ${source.id}#${page.index}: ${bytes.size/1024}KB ${(System.nanoTime()-t0)/1_000_000}ms sig=${sig.rows}r")
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
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val call = httpClient.newCall(Request.Builder().url(url).apply {
                if (!referer.isNullOrBlank()) header("Referer", referer)
            }.build())
            // enqueue je async + call.cancel() se naváže na zrušení korutiny - bez
            // toho by zrušené fetch joby (úspěšný donor ruší scope) visely v
            // blokujícím execute() až do konce requestu a `withTimeoutOrNull` by
            // mezitím zahodil i už dokončené výsledky.
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    if (cont.isActive) cont.resume(null) {}
                }
                override fun onResponse(call: okhttp3.Call, r: okhttp3.Response) {
                    r.use {
                        if (cont.isActive)
                            cont.resume(if (r.isSuccessful) r.body?.bytes() else null) {}
                    }
                }
            })
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

    // ── Trvalá cache doplněného seznamu stránek ──────────────────────────────

    /**
     * Soubor s doplněným seznamem stránek kapitoly: `filesDir/gapfill/<dirKey>/pages.txt`.
     * První řádka `host <počet> <md5(URL seznamu)>` - hash slouží jako invalidace,
     * když zdroj změní svůj page list. Další řádky: `url\timageUrl` (`-` = null).
     */
    private fun cachedFillFile(dirKey: String): File =
        File(context.filesDir, "gapfill/$dirKey/pages.txt")

    private fun saveCachedFill(dirKey: String, hostUrls: List<String>, filled: List<Page>) {
        runCatching {
            val f = cachedFillFile(dirKey)
            f.parentFile?.mkdirs()
            f.writeText(buildString {
                append("host ").append(hostUrls.size).append(' ')
                    .append(md5(hostUrls.joinToString("\n"))).append('\n')
                filled.forEach { p ->
                    append(p.url).append('\t').append(p.imageUrl ?: "-").append('\n')
                }
            })
            if (BuildConfig.DEBUG) android.util.Log.i(TAG, "saved fill cache $dirKey (${filled.size} pages)")
        }
    }

    private fun loadCachedFill(dirKey: String, hostUrls: List<String>): List<Page>? {
        val f = cachedFillFile(dirKey)
        if (!f.isFile) return null
        return runCatching {
            val lines = f.readLines()
            val expect = "host ${hostUrls.size} ${md5(hostUrls.joinToString("\n"))}"
            if (lines.firstOrNull() != expect) { f.delete(); return null }
            lines.drop(1).mapIndexed { i, line ->
                val url = line.substringBefore('\t')
                val img = line.substringAfter('\t', "").takeIf { it != "-" && it.isNotEmpty() }
                // file:// crop stránky musí fyzicky existovat - jinak cache zahodit
                if (url.startsWith("file://") && !File(url.removePrefix("file://")).isFile) return null
                Page(index = i, url = url, imageUrl = img)
            }.takeIf { it.size > hostUrls.size }
        }.getOrNull()
    }

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
        /** Donorů navržených sweepu - parallel tries, scrambled/čas limit odfiltruje sám. */
        const val MAX_DONOR_CANDIDATES = 6
        /** Inward trim při ořezu - pár řádků překryvu mezery je horší než malá ztráta. */
        const val TRIM_PX = 12
        const val MIN_CROP_PX = 40
        /** Kolik donor stránek nejdál od proporcí odhadnuté pozice se pro jednu mezeru
         *  stáhne - dost na odchylku řezání, málo na to, aby špatný donor vyžral čas. */
        const val MAX_DONOR_SCAN_PAGES = 48
        /** Souběžných downloadů donor stránek na mezeru - slušnost k serveru i síti. */
        const val SCAN_CONCURRENCY = 4
        /** Pod-strop kontentového zarovnání jednoho donoru - po jeho vypršení se
         *  vrátí i částečné insertions místo ztráty všech v globálním timeoutu. */
        const val CONTENT_ALIGN_BUDGET_MS = 20_000L
        /** Uvolněný práh pro "implied" druhou kotvu - je podepřená silnou první
         *  kotvou, jen se ověřuje, že spočítaná donor stránka vůbec sedí. Margin
         *  zde řeší jen ambiguitu OFFSETU v rámci stránky (u cropu ≈ desítky px,
         *  částečně kryje TRIM_PX), takže stačí epsilon proti totální rovnicovosti.
         *  Live data: správná stránka měla score 0.9999 / margin 0.0065. */
        const val RELAXED_MATCH_SCORE = 0.60f
        const val RELAXED_MATCH_MARGIN = 0.002f
        const val GAPFILL_PREFS = "jiyu_gapfill"
    }
}
