package com.haise.jiyu.ui.comichome

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
 * Jedna položka sjednoceného komiksového katalogu - stejný titul nalezený na více
 * zdrojích se zobrazuje jednou (deduplikace přes normalizovaný název).
 * Komiksový protějšek NovelCatalogEntry - stejná sémantika.
 */
data class ComicCatalogEntry(
    val key: String,
    val representative: SManga,
    val sourceCount: Int,
    val sourceNames: List<String>,
    /** Referer pro Coil request obálky - viz NovelCatalogEntry.coverReferer. */
    val coverReferer: String? = null,
)

/**
 * Sjednotí výpisy jednotlivých zdrojů do deduplikovaného katalogu - stejná logika
 * jako mergeNovelEntries (klíč = normalizovaný název, reprezentant = nejlepší
 * rank, kopie bez obálky penalizované).
 */
internal fun mergeComicEntries(listings: List<List<Pair<SManga, Int>>>): List<ComicCatalogEntry> {
    data class Acc(var best: Pair<SManga, Int>, var count: Int, val names: LinkedHashSet<String>)
    val groups = LinkedHashMap<String, Acc>()
    for (list in listings) {
        for ((manga, rank) in list) {
            val key = normalizeMangaTitle(manga.title)
            if (key.isBlank()) continue
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
            ComicCatalogEntry(
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
 * Jedna položka feedu "Aktualizace" na Komiks Domů - nejnovější číslo/kapitola
 * titulu dohledaná přes getChapterList zdroje reprezentanta. Vizuální obdoba
 * ComicK chapter updates feedu.
 */
data class ComicChapterUpdate(
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
class ComicHomeViewModel @Inject constructor(
    private val sourceManager: SourceManager,
    private val repository: MangaRepository,
) : ViewModel() {

    private val _items = MutableStateFlow<List<ComicCatalogEntry>>(emptyList())
    val items: StateFlow<List<ComicCatalogEntry>> = _items.asStateFlow()

    /** Katalog ve variantě "Nejnovější" - viz NovelHomeViewModel.latestItems. */
    private val _latestItems = MutableStateFlow<List<ComicCatalogEntry>>(emptyList())
    val latestItems: StateFlow<List<ComicCatalogEntry>> = _latestItems.asStateFlow()

    private val _latestLoading = MutableStateFlow(false)
    val latestLoading: StateFlow<Boolean> = _latestLoading.asStateFlow()

    private val _updates = MutableStateFlow<List<ComicChapterUpdate>>(emptyList())
    val updates: StateFlow<List<ComicChapterUpdate>> = _updates.asStateFlow()

    private val _updatesLoading = MutableStateFlow(false)
    val updatesLoading: StateFlow<Boolean> = _updatesLoading.asStateFlow()

    private val _showLatest = MutableStateFlow(false)
    val showLatest: StateFlow<Boolean> = _showLatest.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _failedSources = MutableStateFlow(0)
    val failedSources: StateFlow<Int> = _failedSources.asStateFlow()

    private var feedJob: Job? = null
    private var searchJob: Job? = null
    private var latestJob: Job? = null
    private var updatesJob: Job? = null
    private var enrichJob: Job? = null

    init { loadFeed() }

    /** Agregovaný Komiks režim je JEN pro anglické comic zdroje (es/tr kopie
     * uživatele číst nebudou) - stejný scope jako ComicResolver. */
    private suspend fun comicSources() = sourceManager.getAll()
        .filter { it.contentType == "COMIC" && it.language == "en" && !it.isAdult }

    private fun emitMerged(listings: List<List<Pair<SManga, Int>>>) {
        _items.value = mergeComicEntries(listings).map { entry ->
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
                val sources = comicSources()
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
                                    e.report("comichome:feed:${source.id}")
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
        latestJob = viewModelScope.launch { loadLatestFeed(force) }
    }

    private suspend fun loadLatestFeed(force: Boolean) {
        _latestLoading.value = true
        _latestItems.value = emptyList()
        try {
            val sources = comicSources().filter { "latest" in it.availableSorts }
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
                                e.report("comichome:latest:${source.id}")
                                emptyList()
                            }
                        }
                        if (list.isNotEmpty()) {
                            listings.add(list)
                            _latestItems.value = mergeComicEntries(listings.toList()).map { entry ->
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
     * Dotáhne detaily top položek, kterým listingy nenesly žánry/status - bez
     * toho by Domů sekce (Dokončené, žánrové řady) u komiksů zůstaly prázdné,
     * protože comic listingy tahle metadata většinou nemají. Jde přes sdílenou
     * details cache repository (zadny DB zapis), bounded semafor + timeout.
     * Položky, co metadata uz maji (napi. z listing API), se preskakuji.
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
                                e.report("comichome:enrich:${entry.representative.sourceId}")
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
        fun patch(list: List<ComicCatalogEntry>) = list.map { e ->
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

    private fun loadUpdatesFeed() {
        updatesJob?.cancel()
        updatesJob = viewModelScope.launch {
            _updatesLoading.value = true
            _updates.value = emptyList()
            try {
                val candidates = _latestItems.value.take(UPDATES_FEED_LIMIT)
                val collected = java.util.concurrent.CopyOnWriteArrayList<ComicChapterUpdate>()
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
                                            ComicChapterUpdate(
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
                                    e.report("comichome:updates:${entry.representative.sourceId}")
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
                val sources = comicSources()
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
                                    e.report("comichome:search:${source.id}")
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
        const val UPDATES_FEED_LIMIT = 24
        const val ENRICH_LIMIT = 24
        const val ENRICH_CONCURRENT = 4
        const val ENRICH_TIMEOUT_MS = 12_000L
    }
}
