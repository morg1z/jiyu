package com.haise.jiyu.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.data.repository.DuplicateMatch
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.SourceManager
import com.haise.jiyu.source.interceptor.InteractiveChallengePolicy
import com.haise.jiyu.util.CloudflareProtectedException
import com.haise.jiyu.util.toFriendlyMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.haise.jiyu.util.report

data class SourceResult(
    val source: MangaSource,
    val loading: Boolean = true,
    val results: List<SManga> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class GlobalSearchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sourceManager: SourceManager,
    private val repository: MangaRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    val savedSearches: StateFlow<List<String>> = settings.savedSearches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun saveSearch(q: String) = viewModelScope.launch { if (q.isNotBlank()) settings.addSavedSearch(q) }
    fun removeSavedSearch(q: String) = viewModelScope.launch { settings.removeSavedSearch(q) }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<SourceResult>>(emptyList())
    val results: StateFlow<List<SourceResult>> = _results.asStateFlow()

    init {
        val initialQuery = savedStateHandle.get<String>("q").orEmpty()
        if (initialQuery.isNotBlank()) search(initialQuery)
    }

    // Předchozí hledání se při novém dotazu ruší - jinak by jeho asynchronní bloky dál zapisovaly do
    // _results, které už patří novému dotazu.
    private var searchJob: Job? = null

    // Strop souběžných dotazů na zdroje (dřív se startovaly všechny zdroje najednou).
    private val searchPermits = Semaphore(MAX_CONCURRENT_SOURCES)

    fun search(q: String) {
        if (q.isBlank()) return
        _query.value = q
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val sources = sourceManager.getAll().filter { it.includeInGlobalSearch }
            _results.value = sources.map { SourceResult(it) }
            // Fáze 1: všechny zdroje najednou (strop souběžnosti), BEZ řešení Cloudflare - zdroj za výzvou se jen
            // rychle označí k pozdějšímu ověření. Nic se neotevírá ani nezdržuje, výsledky ostatních přibývají hned.
            val needsVerification = java.util.Collections.synchronizedList(mutableListOf<MangaSource>())
            sources.map { source ->
                async {
                    val result = searchPermits.withPermit {
                        try {
                            val list = InteractiveChallengePolicy.noSolve { repository.search(source.id, q, 1, MangaFilter()) }
                            SourceResult(source, loading = false, results = list.take(10))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: CloudflareProtectedException) {
                            needsVerification += source
                            SourceResult(source, loading = true)
                        } catch (e: Exception) {
                            SourceResult(source, loading = false, error = e.toFriendlyMessage())
                        }
                    }
                    publish(result)
                }
            }.awaitAll()

            // Fáze 2 (na pozadí): zdroje za Cloudflare se ověří JEDEN po druhém tichým řešením ve skrytém WebView -
            // bez dialogu, bez souběžných WebView, každý s časovým limitem. Ověřený web se pak hledá normálně
            // (clearance se pamatuje) a výsledek se doplní do seznamu. Co se nepodaří, zůstane jako chyba (ruční
            // ověření je dál možné otevřením zdroje).
            for (source in needsVerification.toList()) {
                val result = try {
                    val list = kotlinx.coroutines.withTimeout(VERIFY_TIMEOUT_MS) {
                        InteractiveChallengePolicy.suppressed { repository.search(source.id, q, 1, MangaFilter()) }
                    }
                    SourceResult(source, loading = false, results = list.take(10))
                } catch (e: CancellationException) {
                    if (e is kotlinx.coroutines.TimeoutCancellationException) {
                        SourceResult(source, loading = false, error = CloudflareProtectedException(source.id, "").toFriendlyMessage())
                    } else throw e
                } catch (e: Exception) {
                    SourceResult(source, loading = false, error = e.toFriendlyMessage())
                }
                publish(result)
            }
        }
    }

    private fun publish(result: SourceResult) {
        _results.update { current ->
            current.map { if (it.source.id == result.source.id) result else it }
                .sortedWith(compareBy {
                    when {
                        it.results.isNotEmpty() -> 0
                        it.loading -> 1
                        it.error != null -> 2
                        else -> 3
                    }
                })
        }
    }

    fun mangaId(manga: SManga): String = repository.mangaId(manga.sourceId, manga.url)

    /** Titul přidán do knihovny - obrazovka podle toho otevře jeho detail (událost místo lambdy uložené ve stavu). */
    private val _addedEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val addedEvents: SharedFlow<String> = _addedEvents.asSharedFlow()

    /** Přidání do knihovny selhalo - zpráva pro uživatele (jednorázová, viz [clearAddError]). */
    private val _addError = MutableStateFlow<String?>(null)
    val addError: StateFlow<String?> = _addError.asStateFlow()

    fun clearAddError() { _addError.value = null }

    fun addToLibrary(manga: SManga) {
        viewModelScope.launch {
            try {
                val matches = repository.findLibraryMatchesByTitle(manga.title, manga.sourceId)
                if (matches.isNotEmpty()) {
                    val sourceName = _results.value.find { it.source.id == manga.sourceId }?.source?.name ?: manga.sourceId
                    _pendingDuplicateAdd.value = PendingAdd(manga, sourceName, matches)
                    launch {
                        val count = repository.previewChapterCount(manga)
                        _pendingDuplicateAdd.update { it?.copy(newChapterCount = count) }
                    }
                    return@launch
                }
                performAdd(manga)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.report("search:addToLibrary:duplicateCheck")
                _addError.value = e.toFriendlyMessage()
            }
        }
    }

    private fun performAdd(manga: SManga) {
        viewModelScope.launch {
            try {
                repository.addToLibrary(manga)
                val id = repository.mangaId(manga.sourceId, manga.url)
                val catId = settings.defaultCategoryId.first()
                if (catId != null) repository.addMangaToCategory(id, catId)
                _addedEvents.emit(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.report("search:addToLibrary")
                _addError.value = e.toFriendlyMessage()
            }
        }
    }

    // ── Detekce duplicit při přidávání ──────────────────────────────────────────
    data class PendingAdd(
        val manga: SManga,
        val newSourceName: String,
        val matches: List<DuplicateMatch>,
        val newChapterCount: Int? = null,
    )

    private val _pendingDuplicateAdd = MutableStateFlow<PendingAdd?>(null)
    val pendingDuplicateAdd: StateFlow<PendingAdd?> = _pendingDuplicateAdd.asStateFlow()

    fun confirmAddDespiteDuplicate() {
        val pending = _pendingDuplicateAdd.value ?: return
        _pendingDuplicateAdd.value = null
        performAdd(pending.manga)
    }

    fun cancelDuplicateAdd() { _pendingDuplicateAdd.value = null }

    private companion object {
        const val MAX_CONCURRENT_SOURCES = 6

        /** Nejdéle na ověření jednoho zdroje za Cloudflare při hromadném hledání (tiché řešení běží jen ~15 s). */
        const val VERIFY_TIMEOUT_MS = 30_000L
    }
}
