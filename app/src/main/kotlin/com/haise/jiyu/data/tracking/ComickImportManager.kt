package com.haise.jiyu.data.tracking

import com.haise.jiyu.data.db.AppDatabase
import com.haise.jiyu.data.db.ChapterDao
import com.haise.jiyu.data.db.MangaDao
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.data.repository.deserializeAltTitles
import com.haise.jiyu.data.repository.serializeAltTitles
import com.haise.jiyu.settings.ComickSyncMode
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.interceptor.InteractiveChallengePolicy
import com.haise.jiyu.util.report
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

// region Mapovani - ciste funkce, testovatelne

/** Comick status -> Jiyu readingStatus (1:1). Viz docs: 1-5. */
internal fun comickStatusToLocal(status: Int?): String? = when (status) {
    1 -> "READING"
    2 -> "COMPLETED"
    3 -> "ON_HOLD"
    4 -> "DROPPED"
    5 -> "PLAN_TO_READ"
    else -> null
}

/** Jiyu readingStatus -> Comick status (inverze [comickStatusToLocal]). */
internal fun localStatusToComick(status: String?): Int? = when (status) {
    "READING" -> 1
    "COMPLETED" -> 2
    "ON_HOLD" -> 3
    "DROPPED" -> 4
    "PLAN_TO_READ" -> 5
    else -> null
}

/** publication_status 1 ongoing, 2 completed, 3 cancelled, 4 hiatus; translation_completed ma prednost. */
internal fun comickPublicationStatus(publicationStatus: Int?, translationCompleted: Boolean?): String? = when {
    translationCompleted == true -> "COMPLETED"
    publicationStatus == 1 -> "ONGOING"
    publicationStatus == 2 -> "COMPLETED"
    publicationStatus == 3 -> "DROPPED"
    publicationStatus == 4 -> "HIATUS"
    else -> null
}

/** Normalizace titulu pro fuzzy matching: bez diakritiky, jen [a-z0-9]. */
internal fun normalizeComickTitle(t: String): String =
    Normalizer.normalize(t, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "")

// endregion

/** Jak byla Comick entry naparovana na lokalni mangu. */
enum class ComickMatchKind { HID, SLUG, TITLE, NEW, UNMATCHED }

data class ComickPendingImport(
    val entry: ComickLibraryEntry,
    /** Id lokalni mangy (u NEW null). */
    val localMangaId: String?,
    val matchKind: ComickMatchKind,
    /** U TITLE/UNMATCHED kandidati (title nejednoznacny) - pro rucni vyber v UI. */
    val candidateMangaIds: List<String> = emptyList(),
)

data class ComickImportPreview(
    val totalRemote: Int,
    val animeSkipped: Int,
    val matched: List<ComickPendingImport>,
    val newEntries: List<ComickPendingImport>,
    val unmatched: List<ComickPendingImport>,
) {
    val mangaCount get() = matched.size + newEntries.size
    /** Pocty per Comick status (1-5) jen z manga entries - pro preview rozpis. */
    val byStatus: Map<Int, Int> = (matched + newEntries)
        .mapNotNull { it.entry.status }
        .groupingBy { it }
        .eachCount()
}

data class ComickImportReport(
    val imported: Int,
    val matchedExisting: Int,
    val statusApplied: Int,
    val ratingApplied: Int,
    val progressApplied: Int,
    /** Remote progress nebyl vyssi nez lokalni - lokalni stav zachovan. */
    val keptLocalProgress: Int,
    val progressSkipped: Int,
    val unmatched: Int,
    val animeSkipped: Int,
    val failed: Int,
)

sealed interface ComickImportState {
    data object Idle : ComickImportState
    data class Fetching(val fetched: Int) : ComickImportState
    data class Preview(val preview: ComickImportPreview) : ComickImportState
    data class Applying(val done: Int, val total: Int) : ComickImportState
    data class Done(val report: ComickImportReport) : ComickImportState
    data class Failed(val code: Int?, val message: String?) : ComickImportState
}

/**
 * Import cele Comick knihovny do Jiyu:
 * 1. [fetchAndPreview] - strankovane GET /me/library (limit 100), dedupe podle hid,
 *    matching tierovite: hid -> comick-source slug -> normalizovany titul -> NEW/UNMATCHED.
 * 2. [applyPreview] - teprve po potvrzeni uzivatelem: vytvori comick-source manga pro nove
 *    tituly (jsou rovnou citelne, protoze ComicK zdroj cte /comic/{slug}), navaze hid,
 *    aplikuje status/rating/progress (nikdy nesnizi lokalni progress).
 *
 * 429 se resi pres Retry-After (default 60 s + jitter), max [MAX_RATE_RETRIES] opakovani.
 */
@Singleton
class ComickImportManager @Inject constructor(
    private val comickRepository: ComickRepository,
    private val mangaRepository: MangaRepository,
    private val mangaDao: MangaDao,
    private val chapterDao: ChapterDao,
    private val settings: SettingsRepository,
    private val db: AppDatabase,
) {
    private val _state = MutableStateFlow<ComickImportState>(ComickImportState.Idle)
    val state: StateFlow<ComickImportState> = _state.asStateFlow()

    /** Serializace bulk operaci - soubeh startovniho pullu s manualnim importem/apply
     *  by delal zdvojenou praci (zapisy jsou idempotentni, HTTP traffic ne). */
    private val ioMutex = Mutex()

    fun reset() { _state.value = ComickImportState.Idle }

    private companion object {
        const val PAGE_LIMIT = 100
        const val MAX_RATE_RETRIES = 4
        const val DEFAULT_RATE_DELAY_MS = 60_000L
    }

    /**
     * Stahne celou knihovnu a pripravi preview. Stranky jsou levne (100/stranka, limit
     * 60 cteni/min) - po padu appky se proste fetcne znovu, cursor checkpoint do DataStore
     * zapisujeme jen jako diagnosticky znacce rozdeleneho behu.
     */
    suspend fun fetchAndPreview(): ComickImportPreview? = withContext(Dispatchers.IO) {
        ioMutex.withLock { fetchAndPreviewLocked() }
    }

    private suspend fun fetchAndPreviewLocked(): ComickImportPreview? {
        if (!comickRepository.hasClientId) return null
        val entries = LinkedHashMap<String, ComickLibraryEntry>() // dedupe podle hid
        var cursor: String? = null
        var retries = 0
        try {
            _state.value = ComickImportState.Fetching(0)
            while (true) {
                val page = try {
                    comickRepository.listLibraryPage(cursor = cursor, mediaType = "manga", limit = PAGE_LIMIT)
                } catch (e: ComickApiException) {
                    if (e.code == 429 && retries < MAX_RATE_RETRIES) {
                        retries++
                        delay((e.retryAfterMs ?: DEFAULT_RATE_DELAY_MS) + (0..2000).random())
                        continue
                    }
                    throw e
                }
                retries = 0
                if (page == null) {
                    _state.value = ComickImportState.Failed(null, "not connected")
                    return null
                }
                page.entries.forEach { entries.putIfAbsent(it.hid, it) }
                _state.value = ComickImportState.Fetching(entries.size)
                settings.setComickImportCursor(page.nextCursor)
                cursor = page.nextCursor ?: break
            }
            settings.setComickImportCursor(null)
        } catch (e: ComickApiException) {
            e.report("comick:import:fetch")
            _state.value = ComickImportState.Failed(e.code, null)
            return null
        } catch (e: Exception) {
            e.report("comick:import:fetch")
            _state.value = ComickImportState.Failed(null, e.message)
            return null
        }

        val preview = buildPreview(entries.values.toList())
        _state.value = ComickImportState.Preview(preview)
        return preview
    }

    /** Matching tiers pro vsechny nactene entries. Verejne pro testy. */
    suspend fun buildPreview(entries: List<ComickLibraryEntry>): ComickImportPreview = withContext(Dispatchers.IO) {
        val manga = entries.filter { it.mediaType != "anime" }
        val animeSkipped = entries.size - manga.size

        // Tier-3 index: normalizovany titul (+ alt tituly) -> library manga kandidati.
        val libraryManga = mangaDao.getAllLibrary()
        val titleIndex = HashMap<String, MutableList<MangaEntity>>()
        fun indexTitle(raw: String?, manga: MangaEntity) {
            val key = normalizeComickTitle(raw ?: return)
            if (key.isBlank()) return
            titleIndex.getOrPut(key) { mutableListOf() }.add(manga)
        }
        libraryManga.forEach { m ->
            indexTitle(m.title, m)
            deserializeAltTitles(m.alternateTitles).forEach { indexTitle(it, m) }
        }

        val matched = mutableListOf<ComickPendingImport>()
        val newEntries = mutableListOf<ComickPendingImport>()
        val unmatched = mutableListOf<ComickPendingImport>()

        for (entry in manga) {
            // Tier 1: uz navazane hid.
            mangaDao.findByComickHid(entry.hid)?.let {
                matched += ComickPendingImport(entry, it.id, ComickMatchKind.HID)
                continue
            }
            // Tier 2: comick zdroj - presny slug match v url.
            val slug = entry.slug
            if (slug != null) {
                mangaDao.getMangaBySourceAndUrl("comick", "https://api.comick.dev/comic/$slug")?.let {
                    matched += ComickPendingImport(entry, it.id, ComickMatchKind.SLUG)
                    continue
                }
            }
            // Tier 3: presny normalizovany titul (entry.title + entry.titles[]).
            val entryNames = buildList {
                add(entry.title)
                entry.titles.forEach { add(it.title) }
            }
            val candidates = entryNames
                .mapNotNull { n -> n?.let { titleIndex[normalizeComickTitle(it)] } }
                .flatten()
                .distinctBy { it.id }
            when {
                candidates.size == 1 ->
                    matched += ComickPendingImport(entry, candidates[0].id, ComickMatchKind.TITLE)
                candidates.size > 1 ->
                    unmatched += ComickPendingImport(entry, null, ComickMatchKind.UNMATCHED, candidates.map { it.id })
                slug != null ->
                    newEntries += ComickPendingImport(entry, null, ComickMatchKind.NEW)
                else ->
                    unmatched += ComickPendingImport(entry, null, ComickMatchKind.UNMATCHED)
            }
        }

        ComickImportPreview(
            totalRemote = entries.size,
            animeSkipped = animeSkipped,
            matched = matched,
            newEntries = newEntries,
            unmatched = unmatched,
        )
    }

    /**
     * Aplikuje potvrzeny preview. Per-entry transakce - pad uprostred necha dokoncene
     * entry v konzistentnim stavu a zbytek jde zopakovat celym importem (idempotentni).
     */
    suspend fun applyPreview(preview: ComickImportPreview): ComickImportReport = withContext(Dispatchers.IO) {
        ioMutex.withLock { applyPreviewLocked(preview) }
    }

    private suspend fun applyPreviewLocked(preview: ComickImportPreview): ComickImportReport {
        val items = preview.matched + preview.newEntries
        var statusApplied = 0
        var ratingApplied = 0
        var progressApplied = 0
        var keptLocal = 0
        var progressSkipped = 0
        var failed = 0

        items.forEachIndexed { index, pending ->
            _state.value = ComickImportState.Applying(index + 1, items.size)
            try {
                val outcome = applyEntry(pending)
                if (outcome.statusApplied) statusApplied++
                if (outcome.ratingApplied) ratingApplied++
                when {
                    outcome.progressApplied -> progressApplied++
                    outcome.keptLocal -> keptLocal++
                    else -> progressSkipped++
                }
            } catch (e: Exception) {
                e.report("comick:import:apply:${pending.entry.hid}")
                failed++
            }
        }

        settings.setComickLastSyncAt(System.currentTimeMillis())
        val report = ComickImportReport(
            imported = preview.newEntries.size - failed,
            matchedExisting = preview.matched.size - failed,
            statusApplied = statusApplied,
            ratingApplied = ratingApplied,
            progressApplied = progressApplied,
            keptLocalProgress = keptLocal,
            progressSkipped = progressSkipped,
            unmatched = preview.unmatched.size,
            animeSkipped = preview.animeSkipped,
            failed = failed,
        )
        _state.value = ComickImportState.Done(report)
        return report
    }

    /** Vysledek [applyEntry] - pro reporty (a testy). */
    data class ApplyOutcome(
        val statusApplied: Boolean,
        val ratingApplied: Boolean,
        val progressApplied: Boolean,
        val keptLocal: Boolean,
    )

    /** Vytvori lokalni comick-source mangu pro entry bez lokalniho match. */
    private suspend fun createLocalManga(entry: ComickLibraryEntry): MangaEntity {
        val slug = entry.slug ?: error("NEW entry without slug")
        val url = "https://api.comick.dev/comic/$slug"
        val now = System.currentTimeMillis()
        return MangaEntity(
            id = mangaRepository.mangaId("comick", url),
            sourceId = "comick",
            url = url,
            title = entry.title ?: slug,
            coverUrl = entry.coverUrl,
            description = null,
            status = comickPublicationStatus(entry.publicationStatus, entry.translationCompleted),
            inLibrary = true,
            lastUpdated = now,
            genres = "",
            // zbytek (vc. Comick "others" bucketu) -> MANGA zamerne: contentType ridi
            // cross-source resolver a OTHER by titul nikdy nevyresolvoval na zdroj.
            contentType = when (entry.country) {
                "kr" -> "MANHWA"
                "cn" -> "MANHUA"
                else -> "MANGA"
            },
            comickHid = entry.hid,
            readingStatus = comickStatusToLocal(entry.status),
            // Razitko odpovida remote stavu - pristi pull tak lokalni status prepise
            // jen kdyz se na Comicku fakt neco zmenilo (conflict resolution).
            statusUpdatedAt = entry.updatedAt ?: now,
            userRating = entry.rating?.let { (it * 10).roundToInt() },
            addedAt = entry.followedAt ?: now,
            alternateTitles = serializeAltTitles(
                entry.titles.map { it.title }.filter { it.isNotBlank() && it != entry.title }
            ),
            translationCompleted = entry.translationCompleted,
        )
    }

    /**
     * Aplikuje jednu entry na lokalni mangu (novou nebo existujici) - sdili ho import i
     * per-manga sync (syncFromComick). Pravidla:
     * - status/rating z Comick se vzdy aplikuji (remote null rating lokalni nemaze),
     * - progress se aplikuje JEN kdyz remote cislo > max lokalniho precteneho - nikdy
     *   nesnizuje lokalni postup; neciselny/null progress se zachova netknuty,
     * - nove tituly kapitoly jeste nemaji - progress se aplikuje az pri prvni sync po
     *   nacteni seznamu kapitol (viz docs: nikdy nesnizovat remote kvuli prazdne lokalni).
     */
    suspend fun applyEntry(pending: ComickPendingImport): ApplyOutcome = withContext(Dispatchers.IO) {
        val entry = pending.entry
        val existing = pending.localMangaId?.let { mangaDao.getById(it) }
        val manga = existing ?: createLocalManga(entry)
        db.withTransaction {
            // Insert jen kdyz radek fakt neexistuje (getById>upsert, ne slepy upsert - ten by
            // prepsal description/author z drivejsiho detail-fetch null hodnotami). Kryje i
            // matched-mangu smazanou mezi preview a apply.
            if (existing == null && mangaDao.getById(manga.id) == null) mangaDao.upsert(manga)
            // Matchnuty radek muze byt tombstone (inLibrary=0 - drive odebrany, nebo jen
            // prohlednuty pres slug/title match). Import i explicitni sync jsou vedomy akt,
            // takze ho vracime do knihovny - jinak by se "matched" entry tise neukazala.
            if (!manga.inLibrary) mangaDao.setInLibrary(manga.id, true)
            mangaDao.setComickHid(manga.id, entry.hid)
            // Status/rating: remote prepisuje jen kdyz je entry.updatedAt novejsi nez
            // posledni lokalni zapis (statusUpdatedAt, viz MangaEntity). Jinak by pull
            // po selhanem twoway pushi uzivatelovu zmenu statusu tise vratil zpet.
            // updatedAt chybi-li (starsi API tvar), remote vyhrava jako dosud.
            val remoteIsNewer = entry.updatedAt == null || entry.updatedAt > manga.statusUpdatedAt
            if (remoteIsNewer) {
                comickStatusToLocal(entry.status)?.let { mapped ->
                    // Compare-before-write: totozna hodnota nechceme prepsat (zbytecna
                    // Room invalidace + Flow re-emise pri kazdem startovem pullu).
                    if (manga.readingStatus != mapped) {
                        mangaDao.setReadingStatusAt(manga.id, mapped, entry.updatedAt ?: System.currentTimeMillis())
                    }
                }
                entry.rating?.let { r ->
                    val mapped = (r * 10).roundToInt()
                    if (manga.userRating != mapped) mangaDao.setRating(manga.id, mapped)
                }
            }
            applyProgress(manga.id, entry)
        }
        val remote = entry.progress?.number?.toFloatOrNull()
        val chapters = chapterDao.getAllForManga(manga.id)
        val maxLocal = chapters.filter { it.read }.maxOfOrNull { it.chapterNumber } ?: -1f
        val outcome = ApplyOutcome(
            statusApplied = comickStatusToLocal(entry.status) != null,
            ratingApplied = entry.rating != null,
            progressApplied = remote != null && remote > maxLocal,
            keptLocal = remote != null && remote <= maxLocal && chapters.isNotEmpty(),
        )
        // Cerstva comick-source manga nema ZADNE lokalni kapitoly - seznam se normalne
        // stahne az pri otevreni detailu, takze Seznam u importu ukazoval prazdny stav
        // (bez poctu kapitol) a applyProgress v tx nahore sel s prazdnym seznamem ven.
        // Prefetch seznamu + druhy pruchod applyProgress to resi hned pri importu
        // (zaroven tim odpada masivni "nove kapitoly" vlna pri pristim periodickem
        // updatu - refreshChapters prvni napln pocita jako baseline, ne novinky).
        if (manga.sourceId == "comick" && chapters.isEmpty()) prefetchChapters(manga, entry)
        outcome
    }

    /**
     * Stahne seznam kapitol pro cerstve importovanou comick mangu a pak znovu aplikuje
     * progress ([applyProgress] s prazdnym seznamem bez efektu vyskace ven - nove tituly
     * pred prefetchi zadne kapitoly nemaji). Best-effort: chyba zdroje import neshodi,
     * kapitoly se stahnou pri dalsi aktualizaci knihovny nebo otevreni detailu.
     */
    private suspend fun prefetchChapters(manga: MangaEntity, entry: ComickLibraryEntry) {
        try {
            mangaRepository.refreshChapters(
                manga.id,
                SManga(manga.sourceId, manga.url, manga.title, manga.coverUrl, contentType = manga.contentType),
            )
        } catch (e: Exception) {
            e.report("comick:import:chapters:${entry.hid}")
            return
        }
        if (chapterDao.countForManga(manga.id) == 0) return
        db.withTransaction { applyProgress(manga.id, entry) }
        // Zanry/description se normalne naplni az pri otevreni detailu - bez tohohle
        // by ciste importovana knihovna mela prazdnou statistiku "Oblibene zanry".
        // detailFetchedAt gate: uspesny prazdny fetch se neopakuje (titul muze mit
        // legitimne prazdne zanry) - jinak by pull sync delal detail fetch donekonecna.
        if (manga.genres.isBlank() && manga.detailFetchedAt == 0L) {
            try {
                mangaRepository.refreshMangaDetails(
                    manga.id,
                    SManga(manga.sourceId, manga.url, manga.title, manga.coverUrl, contentType = manga.contentType),
                )
            } catch (e: Exception) {
                e.report("comick:import:details:${entry.hid}")
            }
        }
    }

    /** Musi bezet uvnitr withTransaction (viz [applyEntry]). */
    private suspend fun applyProgress(mangaId: String, entry: ComickLibraryEntry) {
        val remote = entry.progress?.number?.toFloatOrNull() ?: return
        val chapters = chapterDao.getAllForManga(mangaId)
        if (chapters.isEmpty()) return
        val maxLocal = chapters.filter { it.read }.maxOfOrNull { it.chapterNumber } ?: -1f
        if (remote <= maxLocal) return // lokalni je dal - remote nikdy nesnizuje

        val stamp = entry.progressedAt ?: System.currentTimeMillis()
        val toMark = chapters.filter { !it.read && it.chapterNumber <= remote && it.chapterNumber > 0 }
        toMark.forEach { ch ->
            chapterDao.updateProgress(ch.id, read = true, lastPageRead = 0, lastReadAt = stamp)
        }
        toMark.maxByOrNull { it.chapterNumber }?.let { top ->
            mangaDao.updateLastReadChapterAndTime(mangaId, top.id, stamp)
        }
    }

    /**
     * Prubezny pull Comick -> Jiyu (volane pri startu appky, viz JiyuApp): stahne celou
     * remote knihovnu a aplikuje status/rating/progress na UZ navazane tituly
     * (findByComickHid). Stejna pravidla jako import - remote progress se aplikuje jen
     * kdyz je vyssi nez lokalni, nesnizuje. Nove nenavazane entries zamerne ignoruje:
     * pridavat tituly do knihovny je prace importu, sync sam nerozhoduje o jejim obsahu.
     * Zamcene na comickSyncMode != OFF. Vraci pocet aplikovanych titulu.
     */
    suspend fun pullLibrarySync(): Int = withContext(Dispatchers.IO) {
        ioMutex.withLock { pullLibrarySyncLocked() }
    }

    private suspend fun pullLibrarySyncLocked(): Int {
        if (settings.comickSyncMode.first() == ComickSyncMode.OFF) return 0
        if (!comickRepository.hasClientId) return 0
        val entries = LinkedHashMap<String, ComickLibraryEntry>()
        var cursor: String? = null
        var retries = 0
        try {
            while (true) {
                val page = try {
                    comickRepository.listLibraryPage(cursor = cursor, mediaType = "manga", limit = PAGE_LIMIT)
                } catch (e: ComickApiException) {
                    if (e.code == 429 && retries < MAX_RATE_RETRIES) {
                        retries++
                        delay((e.retryAfterMs ?: DEFAULT_RATE_DELAY_MS) + (0..2000).random())
                        continue
                    }
                    throw e
                }
                retries = 0
                if (page == null) return 0 // nepripojeno / unauthorized
                page.entries.forEach { entries.putIfAbsent(it.hid, it) }
                cursor = page.nextCursor ?: break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.report("comick:pullSync:fetch")
            return 0
        }

        var applied = 0
        InteractiveChallengePolicy.suppressed {
            entries.values.forEach { entry ->
                try {
                    // inLibrary-filtrovana varianta: pasivni sync nesmi ozivit titul, co user
                    // odebral - bez nej by applyProgress re-stampnul lastReadAt a browsed
                    // cleanup by takovy radek uz nikdy nesmazal (zombie sync kazdy start).
                    val local = mangaDao.findByComickHidInLibrary(entry.hid) ?: return@forEach
                    applyEntry(ComickPendingImport(entry, local.id, ComickMatchKind.HID))
                    applied++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.report("comick:pullSync:${entry.hid}")
                }
                delay(300)
            }
        }
        if (applied > 0) settings.setComickLastSyncAt(System.currentTimeMillis())
        return applied
    }

    /**
     * Dohlida importovane comick tituly, kterym chybi seznam kapitol - importy starsi
     * verze je nechaly bez kapitol (Seznam pak neukazuje pocet kapitol a preskocil se
     * i progress). Volano z appky pri startu; best-effort a throttled - chyba u jednoho
     * titulu jen preskoci, dobehnou pristim startem nebo periodickym updatem.
     *
     * S pripojenym Comickem jede plny sync entry (status/rating/progress + prefetch
     * kapitol pres applyEntry); bez pripojeni aspon seznam kapitol, aby se pocet
     * zobrazil (progress bez remote dat doplnit nejde - nesnizovat lokalni je zamer).
     * Interaktivni Cloudflare vyzva je potlacena - startova prace na pozadi nema
     * vyskakovat dialog (stejny vzor jako ChapterUpdateWorker).
     */
    suspend fun backfillMissingChapterLists() {
        val chapterMissing = try {
            mangaDao.comickMangaMissingChapters()
        } catch (e: Exception) {
            e.report("comick:backfill")
            return
        }
        val genreMissing = try {
            mangaDao.comickMangaMissingGenres()
        } catch (e: Exception) {
            e.report("comick:backfill:genres")
            emptyList()
        }
        val needsChapters = chapterMissing.mapTo(HashSet()) { it.id }
        val candidates = (chapterMissing + genreMissing).distinctBy { it.id }
        val syncAllowed = settings.comickSyncMode.first() != ComickSyncMode.OFF
        candidates.forEach { manga ->
            InteractiveChallengePolicy.suppressed {
                val hid = manga.comickHid
                // Remote sync aplikuje status/rating/progress - to je sync, takze v mode
                // OFF ji preskocime a jen dotahneme seznam kapitol (badge v Seznamu).
                if (manga.id in needsChapters) {
                    val synced = syncAllowed && hid != null && try {
                        syncMangaFromRemote(manga.id, hid)
                    } catch (e: Exception) {
                        e.report("comick:backfill:$hid")
                        false
                    }
                    if (!synced) {
                        try {
                            mangaRepository.refreshChapters(
                                manga.id,
                                SManga(manga.sourceId, manga.url, manga.title, manga.coverUrl, contentType = manga.contentType),
                            )
                        } catch (e: Exception) {
                            e.report("comick:backfill:chapters:${manga.id}")
                        }
                    }
                }
                // Zanry (a ostatni detail metadata) se normalne naplni az pri otevreni
                // detailu - importovane tituly bez nej by jinak v "Oblibene zanry"
                // statistice nikdy nemely. Detail call neni sync remote stavu, takze
                // bezi i v mode OFF (stejne jako chapter fallback vyse).
                // Snapshot `manga` je z doby pred syncMangaFromRemote, co pres prefetch
                // detail uz mohlo stahnout - precteme cerstvou entitu, at nedelame druhy
                // fetch. detailFetchedAt gate: i neuspech razitkujeme (fetch detailu bez
                // vysledku = neexistujici comic) - bez nej by se tituly s legitimne
                // prazdnymi zanry zkously znovu pri kazdem startu.
                val fresh = mangaDao.getById(manga.id) ?: return@suppressed
                if (fresh.genres.isBlank() && fresh.detailFetchedAt == 0L) {
                    val ok = try {
                        mangaRepository.refreshMangaDetails(
                            fresh.id,
                            SManga(fresh.sourceId, fresh.url, fresh.title, fresh.coverUrl, contentType = fresh.contentType),
                        )
                    } catch (e: Exception) {
                        e.report("comick:backfill:details:${manga.id}")
                        false
                    }
                    if (!ok) mangaDao.setDetailFetchedAt(fresh.id, System.currentTimeMillis())
                }
            }
            delay(1_200)
        }
    }

    /** Per-manga pull sync (viz MangaDetailViewModel.syncFromComick) - fetch + apply. */
    suspend fun syncMangaFromRemote(mangaId: String, hid: String): Boolean = withContext(Dispatchers.IO) {
        val entry = comickRepository.getLibraryEntry(hid) ?: return@withContext false
        try {
            applyEntry(ComickPendingImport(entry, mangaId, ComickMatchKind.HID))
            settings.setComickLastSyncAt(System.currentTimeMillis())
            true
        } catch (e: Exception) {
            e.report("comick:sync:$hid")
            false
        }
    }

    /**
     * Rucni navazani unmatched entry na lokalni mangu. Funkcni API - picker UI
     * zatim neexistuje (sheet ukazuje jen pocet unmatched), candidateMangaIds
     * v [ComickPendingImport] jsou pro nej pripravene.
     */
    suspend fun bindEntryToManga(mangaId: String, entry: ComickLibraryEntry) =
        applyEntry(ComickPendingImport(entry, mangaId, ComickMatchKind.TITLE))
}
