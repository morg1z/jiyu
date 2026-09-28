package com.haise.jiyu.ui.novelhome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.SourceManager
import com.haise.jiyu.source.interceptor.InteractiveChallengePolicy
import com.haise.jiyu.util.normalizeMangaTitle
import com.haise.jiyu.util.titleMatchesQuery
import com.haise.jiyu.util.report
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Jedna položka sjednoceného novelového katalogu - stejný titul nalezený na více
 * zdrojích se zobrazuje jednou (deduplikace přes normalizovaný název).
 *
 * [representative] je konkrétní SManga kopie použitá pro obálku/název v gridu.
 * Po kliku NEotvíráme přímo její detail - místo toho jde cesta přes
 * NovelResolver, který titul dohledá ve všech novel zdrojích a vybere nejlepší
 * kopii (ta z listingu nemusí být ta s nejvíc kapitolami).
 */
data class NovelCatalogEntry(
    val key: String,
    val representative: SManga,
    val sourceCount: Int,
    val sourceNames: List<String>,
    /** Referer pro Coil request obálky - hodně novel webů bez něj hotlink blokuje
     * (karta pak padá na placeholder). Bere se z homepageUrl zdroje reprezentanta. */
    val coverReferer: String? = null,
)

/**
 * Sjednotí výpisy jednotlivých zdrojů do deduplikovaného katalogu. Klíč =
 * normalizovaný název; reprezentant = kopie s nejlepším rankem (pozice ve
 * výpisu svého zdroje), u shody ta s obálkou. Řazení: nejlepší rank napříč
 * zdroji, pak počet zdrojů, kde titul existuje (víc kopií = spolehlivější).
 */
internal fun mergeNovelEntries(listings: List<List<Pair<SManga, Int>>>): List<NovelCatalogEntry> {
    data class Acc(var best: Pair<SManga, Int>, var count: Int, val names: LinkedHashSet<String>)
    val groups = LinkedHashMap<String, Acc>()
    for (list in listings) {
        for ((manga, rank) in list) {
            val key = normalizeMangaTitle(manga.title)
            if (key.isBlank()) continue
            // Kopie BEZ obalky se chova, jako by mela o [NO_COVER_RANK_PENALTY] horsi rank -
            // jinak reprezentantem skoncila karta, ktera nikdy nenacte cover (source listing
            // coverUrl nenaplňuje / host blokuje hotlink) a grid je plny placeholderu.
            val effectiveRank = rank + if (manga.coverUrl.isNullOrBlank()) NO_COVER_RANK_PENALTY else 0
            val acc = groups[key]
            if (acc == null) {
                groups[key] = Acc(manga to effectiveRank, 1, linkedSetOf(manga.sourceId))
            } else {
                acc.count++
                acc.names += manga.sourceId
                if (effectiveRank < acc.best.second) acc.best = manga to effectiveRank
            }
        }
    }
    return groups.entries
        .sortedWith(compareBy({ it.value.best.second }, { -it.value.count }))
        .take(MAX_CATALOG_ITEMS)
        .map { (key, acc) ->
            NovelCatalogEntry(
                key = key,
                representative = acc.best.first,
                sourceCount = acc.count,
                sourceNames = acc.names.toList(),
            )
        }
}

private const val MAX_CATALOG_ITEMS = 120
private const val NO_COVER_RANK_PENALTY = 10_000

/**
 * Jedna položka feedu "Aktualizace" na Novela Domů - nejnovější kapitola titulu
 * dohledaná pres getChapterList zdroje reprezentanta (latest sweep urči, KDE se
 * nedavno neco pridalo; chapter list pak presne cislo+datum). Vizuální obdoba
 * ComicK chapter updates feedu.
 */
data class NovelChapterUpdate(
    val key: String,
    val manga: SManga,
    val chapterName: String,
    val chapterNumber: Float,
    val dateUpload: Long,
    val sourceName: String,
    val sourceCount: Int,
    val coverReferer: String?,
)

@HiltViewModel
class NovelHomeViewModel @Inject constructor(
    private val sourceManager: SourceManager,
    private val repository: MangaRepository,
) : ViewModel() {

    private val _items = MutableStateFlow<List<NovelCatalogEntry>>(emptyList())
    val items: StateFlow<List<NovelCatalogEntry>> = _items.asStateFlow()

    /** Katalog ve variantě "Nejnovější" (sweep se sortBy=latest u zdrojů, co řazení
     * umí) - druhá polovina přepínače na Domů a obsah Katalogu v latest módu. */
    private val _latestItems = MutableStateFlow<List<NovelCatalogEntry>>(emptyList())
    val latestItems: StateFlow<List<NovelCatalogEntry>> = _latestItems.asStateFlow()

    private val _latestLoading = MutableStateFlow(false)
    val latestLoading: StateFlow<Boolean> = _latestLoading.asStateFlow()

    /** Feed posledních kapitol (viz [NovelChapterUpdate]) pro sekci Aktualizace. */
    private val _updates = MutableStateFlow<List<NovelChapterUpdate>>(emptyList())
    val updates: StateFlow<List<NovelChapterUpdate>> = _updates.asStateFlow()

    private val _updatesLoading = MutableStateFlow(false)
    val updatesLoading: StateFlow<Boolean> = _updatesLoading.asStateFlow()

    /** false = sekce/katalog ukazují Populární sweep, true = Nejnovější sweep. */
    private val _showLatest = MutableStateFlow(false)
    val showLatest: StateFlow<Boolean> = _showLatest.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Kolik zdrojů se nepodařilo načíst (rate-limit/timeout/CF) - info chip. */
    private val _failedSources = MutableStateFlow(0)
    val failedSources: StateFlow<Int> = _failedSources.asStateFlow()

    private var feedJob: Job? = null
    private var searchJob: Job? = null
    private var latestJob: Job? = null
    private var updatesJob: Job? = null
    private var enrichJob: Job? = null

    init { loadFeed() }

    /** Agregovaný Novela režim je JEN pro anglické novel zdroje (ru/ar/tr/id/es kopie
     * uživatele číst nebudou a sweep nad nimi jen zdržuje) - stejný scope jako NovelResolver. */
    private suspend fun novelSources() = sourceManager.getAll()
        .filter { it.contentType == "NOVEL" && it.language == "en" && !it.isAdult }

    /** Dosud nasbírané výpisy -> merge + doplnění cover refereru ze zdroje reprezentanta. */
    private fun emitMerged(listings: List<List<Pair<SManga, Int>>>) {
        _items.value = mergeNovelEntries(listings).map { entry ->
            entry.copy(coverReferer = sourceManager.getByIdSync(entry.representative.sourceId)?.homepageUrl)
        }
    }

    fun setShowLatest(latest: Boolean) { _showLatest.value = latest }

    fun loadFeed(force: Boolean = false) {
        feedJob?.cancel()
        latestJob?.cancel()
        updatesJob?.cancel()
        enrichJob?.cancel()
        feedJob = viewModelScope.launch {
            _loading.value = true
            _query.value = ""
            _failedSources.value = 0
            try {
                val sources = novelSources()
                // Per-source výstup: List<Pair<SManga, rank ve výpisu zdroje>>.
                // Výsledky se do gridu emitují INKREMENTÁLNĚ - jakmile doběhne každý zdroj,
                // hned se přemerže a překreslí katalog. Původní awaitAll() čekalo na
                // NEJPOMALEJŠÍ zdroj (~40 webů / 6 paralelně / 15s timeout = minuty spinneru
                // na prázdné obrazovce).
                val listings = java.util.concurrent.CopyOnWriteArrayList<List<Pair<SManga, Int>>>()
                coroutineScope {
                    val semaphore = Semaphore(MAX_CONCURRENT_SOURCES)
                    sources.map { source ->
                        async {
                            val list = semaphore.withPermit {
                                try {
                                    withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS) {
                                        InteractiveChallengePolicy.noSolve {
                                            repository.getPopular(source.id, 1, MangaFilter(), force)
                                        }
                                    }?.take(PER_SOURCE_FEED_LIMIT)
                                        ?.mapIndexed { index, m -> m to index }
                                        .orEmpty()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    _failedSources.update { it + 1 }
                                    e.report("novelhome:feed:${source.id}")
                                    emptyList()
                                }
                            }
                            if (list.isNotEmpty()) {
                                listings.add(list)
                                emitMerged(listings.toList())
                            }
                        }
                    }.awaitAll()
                }
            } finally {
                _loading.value = false
            }
        }
        // Latest sweep + z nej postavený feed Aktualizace běží paralelně vedle
        // popular sweepu - Domů sekce se plní průběžně stejně jako katalog.
        latestJob = viewModelScope.launch { loadLatestFeed(force) }
    }

    /** Sweep "Nejnovější" - jen zdroje, co umí sortBy=latest (jinak by listing
     * vracel popular a sekce lhala). Po dokončení spustí build feedu Aktualizace. */
    private suspend fun loadLatestFeed(force: Boolean) {
        _latestLoading.value = true
        _latestItems.value = emptyList()
        try {
            val sources = novelSources().filter { "latest" in it.availableSorts }
            val listings = java.util.concurrent.CopyOnWriteArrayList<List<Pair<SManga, Int>>>()
            coroutineScope {
                val semaphore = Semaphore(MAX_CONCURRENT_SOURCES)
                sources.map { source ->
                    async {
                        val list = semaphore.withPermit {
                            try {
                                withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS) {
                                    InteractiveChallengePolicy.noSolve {
                                        repository.getPopular(source.id, 1, MangaFilter(sortBy = "latest"), force)
                                    }
                                }?.take(PER_SOURCE_FEED_LIMIT)
                                    ?.mapIndexed { index, m -> m to index }
                                    .orEmpty()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                _failedSources.update { it + 1 }
                                e.report("novelhome:latest:${source.id}")
                                emptyList()
                            }
                        }
                        if (list.isNotEmpty()) {
                            listings.add(list)
                            _latestItems.value = mergeNovelEntries(listings.toList()).map { entry ->
                                entry.copy(coverReferer = sourceManager.getByIdSync(entry.representative.sourceId)?.homepageUrl)
                            }
                        }
                    }
                }.awaitAll()
            }
        } finally {
            _latestLoading.value = false
        }
        loadUpdatesFeed()
        enrichTopEntries()
    }

    /**
     * Dotáhne detaily top položek, kterým listingy nenesly žánry/status - část
     * novel zdrojů je plní už v listingu (NovelBuddy), jiné až v detailu; bez
     * enrichmentu by Domů sekce (Dokončené, žánrové řady) byly řídké. Jde přes
     * sdílenou details cache repository (žádný DB zápis), bounded semafor +
     * timeout. Položky, co metadata už mají, se přeskakují.
     */
    private fun enrichTopEntries() {
        enrichJob?.cancel()
        enrichJob = viewModelScope.launch {
            val union = (_items.value + _latestItems.value).distinctBy { it.key }
            val targets = union
                .filter { it.representative.genres.isEmpty() && it.representative.status == null }
                .take(ENRICH_LIMIT)
            if (targets.isEmpty()) return@launch
            coroutineScope {
                val semaphore = Semaphore(ENRICH_CONCURRENT)
                targets.map { entry ->
                    async {
                        val details = semaphore.withPermit {
                            try {
                                withTimeoutOrNull(ENRICH_TIMEOUT_MS) {
                                    InteractiveChallengePolicy.noSolve {
                                        repository.fetchDetails(entry.representative)
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                e.report("novelhome:enrich:${entry.representative.sourceId}")
                                null
                            }
                        }
                        if (details != null) applyDetails(entry.key, details)
                    }
                }.awaitAll()
            }
        }
    }

    private fun applyDetails(key: String, details: SManga) {
        fun patch(list: List<NovelCatalogEntry>) = list.map { e ->
            if (e.key != key) e else e.copy(
                representative = details.copy(
                    // Detail muze mit slabsi/zadnou obalku - drz lepsi variantu.
                    coverUrl = details.coverUrl ?: e.representative.coverUrl,
                    title = details.title.ifBlank { e.representative.title },
                ),
            )
        }
        _items.update(::patch)
        _latestItems.update(::patch)
    }

    /** Aktualizace: z nejčerstvějších titulů latest sweepu dotažeme seznamy kapitol
     * a vezmeme z nich nejnovější - výsledek = ComicK-styl feed "Ch.N · před X h".
     * Emituje se inkrementálně, pořadí drží rank latest sweepu (≈ chronologii). */
    private fun loadUpdatesFeed() {
        updatesJob?.cancel()
        updatesJob = viewModelScope.launch {
            _updatesLoading.value = true
            _updates.value = emptyList()
            try {
                val candidates = _latestItems.value.take(UPDATES_FEED_LIMIT)
                val collected = java.util.concurrent.CopyOnWriteArrayList<NovelChapterUpdate>()
                coroutineScope {
                    val semaphore = Semaphore(MAX_CONCURRENT_SOURCES)
                    candidates.map { entry ->
                        async {
                            val update = semaphore.withPermit {
                                try {
                                    withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS) {
                                        InteractiveChallengePolicy.noSolve {
                                            val source = sourceManager.getByIdSync(entry.representative.sourceId) ?: return@noSolve null
                                            val newest = source.getChapterList(entry.representative).maxByOrNull { it.dateUpload } ?: return@noSolve null
                                            NovelChapterUpdate(
                                                key = entry.key,
                                                manga = entry.representative,
                                                chapterName = newest.name,
                                                chapterNumber = newest.chapterNumber,
                                                dateUpload = newest.dateUpload,
                                                sourceName = source.name,
                                                sourceCount = entry.sourceCount,
                                                coverReferer = entry.coverReferer,
                                            )
                                        }
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    e.report("novelhome:updates:${entry.representative.sourceId}")
                                    null
                                }
                            }
                            if (update != null) {
                                collected.add(update)
                                _updates.value = collected.toList()
                            }
                        }
                    }.awaitAll()
                }
            } finally {
                _updatesLoading.value = false
            }
        }
    }

    /** Sjednocené hledání napříč novel zdroji - stejný inkrementální sweep jako feed. */
    fun search(q: String) {
        if (q.isBlank()) return
        _query.value = q
        searchJob?.cancel()
        enrichJob?.cancel()
        // Vynulovat items - jinak se behem hledani ukazuje jako "vysledky" stary
        // popular feed, a kdyz nic nematchuje, zustane viset misto empty stavu.
        _items.value = emptyList()
        searchJob = viewModelScope.launch {
            _searching.value = true
            try {
                val sources = novelSources()
                val semaphore = Semaphore(MAX_CONCURRENT_SOURCES)
                val listings = java.util.concurrent.CopyOnWriteArrayList<List<Pair<SManga, Int>>>()
                coroutineScope {
                    sources.map { source ->
                        async {
                            val list = semaphore.withPermit {
                                try {
                                    withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS) {
                                        InteractiveChallengePolicy.noSolve {
                                            repository.search(source.id, q, 1, MangaFilter())
                                        }
                                    }?.filter { titleMatchesQuery(it.title, q) }
                                        ?.take(PER_SOURCE_SEARCH_LIMIT)
                                        ?.mapIndexed { index, m -> m to index }
                                        .orEmpty()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    e.report("novelhome:search:${source.id}")
                                    emptyList()
                                }
                            }
                            if (list.isNotEmpty()) {
                                listings.add(list)
                                emitMerged(listings.toList())
                            }
                        }
                    }.awaitAll()
                }
            } finally {
                _searching.value = false
            }
        }
    }

    fun clearSearch() { loadFeed() }

    private companion object {
        const val MAX_CONCURRENT_SOURCES = 6
        const val PER_SOURCE_TIMEOUT_MS = 15_000L
        const val PER_SOURCE_FEED_LIMIT = 15
        const val PER_SOURCE_SEARCH_LIMIT = 10
        /** Kolik nejčerstvějších titulů z latest sweepu dostane dotažený seznam
         * kapitol pro feed Aktualizace (každý = 1 request navíc). */
        const val UPDATES_FEED_LIMIT = 24
        const val ENRICH_LIMIT = 24
        const val ENRICH_CONCURRENT = 4
        const val ENRICH_TIMEOUT_MS = 12_000L
    }
}
