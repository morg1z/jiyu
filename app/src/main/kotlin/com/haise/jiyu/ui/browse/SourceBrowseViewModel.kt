package com.haise.jiyu.ui.browse

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
import com.haise.jiyu.util.NetworkMonitor
import com.haise.jiyu.util.toFriendlyMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Obsah jednoho konkrétního zdroje (Populární/Nejnovější, hledání, stránkování). */
@HiltViewModel
class SourceBrowseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MangaRepository,
    private val settings: SettingsRepository,
    private val sourceManager: SourceManager,
    private val networkMonitor: NetworkMonitor,
) : ViewModel() {

    private val sourceId: String = checkNotNull(savedStateHandle["sourceId"])

    private val _source = MutableStateFlow<MangaSource?>(null)
    val source: StateFlow<MangaSource?> = _source.asStateFlow()

    private val _activeFilter = MutableStateFlow(MangaFilter())
    val activeFilter: StateFlow<MangaFilter> = _activeFilter.asStateFlow()

    private val _results = MutableStateFlow<List<SManga>>(emptyList())
    val results: StateFlow<List<SManga>> = _results.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _showLatest = MutableStateFlow(false)
    val showLatest: StateFlow<Boolean> = _showLatest.asStateFlow()

    private var currentPage = 1
    private var lastQuery: String? = null

    init {
        viewModelScope.launch {
            _source.value = sourceManager.getById(sourceId)
            loadPopular(_activeFilter.value)
        }
        // Auto-retry when connectivity is restored after an error
        viewModelScope.launch {
            networkMonitor.networkState.drop(1).collect { online ->
                if (online && _error.value != null && !_loading.value) retry()
            }
        }
    }

    fun loadMore() {
        if (_loading.value || !_hasMore.value) return
        currentPage++
        val q = lastQuery
        val filter = _activeFilter.value
        viewModelScope.launch {
            _loading.value = true
            try {
                val page = if (q == null)
                    repository.getPopular(sourceId, currentPage, filter)
                else
                    repository.search(sourceId, q, currentPage, filter)
                if (page.isEmpty()) {
                    _hasMore.value = false
                } else {
                    _results.value = _results.value + page
                    _hasMore.value = page.size >= 20
                }
            } catch (e: Exception) {
                currentPage--
            } finally {
                _loading.value = false
            }
        }
    }

    fun retry() {
        val q = lastQuery
        if (q == null) loadPopular(_activeFilter.value) else search(q, _activeFilter.value)
    }

    fun addToLibrary(manga: SManga, onAdded: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val matches = repository.findLibraryMatchesByTitle(manga.title, manga.sourceId)
                if (matches.isNotEmpty()) {
                    val sourceName = _source.value?.name ?: manga.sourceId
                    _pendingDuplicateAdd.value = PendingAdd(manga, sourceName, matches, onAdded = onAdded)
                    launch {
                        val count = repository.previewChapterCount(manga)
                        _pendingDuplicateAdd.update { it?.copy(newChapterCount = count) }
                    }
                    return@launch
                }
                performAdd(manga, onAdded)
            } catch (e: Exception) {
                _error.value = e.toFriendlyMessage()
            }
        }
    }

    private fun performAdd(manga: SManga, onAdded: (String) -> Unit) {
        viewModelScope.launch {
            try {
                repository.addToLibrary(manga)
                val id = repository.mangaId(manga.sourceId, manga.url)
                val catId = settings.defaultCategoryId.first()
                if (catId != null) repository.addMangaToCategory(id, catId)
                onAdded(id)
            } catch (e: Exception) {
                _error.value = e.toFriendlyMessage()
            }
        }
    }

    // ── Detekce duplicit při přidávání ──────────────────────────────────────────
    data class PendingAdd(
        val manga: SManga,
        val newSourceName: String,
        val matches: List<DuplicateMatch>,
        val newChapterCount: Int? = null,
        val onAdded: (String) -> Unit,
    )

    private val _pendingDuplicateAdd = MutableStateFlow<PendingAdd?>(null)
    val pendingDuplicateAdd: StateFlow<PendingAdd?> = _pendingDuplicateAdd.asStateFlow()

    fun confirmAddDespiteDuplicate() {
        val pending = _pendingDuplicateAdd.value ?: return
        _pendingDuplicateAdd.value = null
        performAdd(pending.manga, pending.onAdded)
    }

    fun cancelDuplicateAdd() { _pendingDuplicateAdd.value = null }

    fun setFilters(filter: MangaFilter) {
        _activeFilter.value = filter
        val q = lastQuery
        if (q == null) loadPopular(filter) else search(q, filter)
    }

    fun setShowLatest(latest: Boolean) {
        _showLatest.value = latest
        loadPopular(_activeFilter.value.copy(sortBy = if (latest) "latest" else "popular"))
    }

    fun loadPopular(filter: MangaFilter = _activeFilter.value) {
        lastQuery = null
        currentPage = 1
        if (!networkMonitor.isOnline) {
            _error.value = "Nejsi připojen k internetu"
            _results.value = emptyList()
            _hasMore.value = false
            return
        }
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val page = repository.getPopular(sourceId, 1, filter)
                _results.value = page
                _hasMore.value = page.size >= 20
            } catch (e: Exception) {
                _error.value = e.toFriendlyMessage()
                _results.value = emptyList()
                _hasMore.value = false
            } finally {
                _loading.value = false
            }
        }
    }

    fun search(query: String, filter: MangaFilter = _activeFilter.value) {
        if (query.isBlank()) { loadPopular(filter); return }
        lastQuery = query
        currentPage = 1
        if (!networkMonitor.isOnline) {
            _error.value = "Nejsi připojen k internetu"
            _results.value = emptyList()
            _hasMore.value = false
            return
        }
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val page = repository.search(sourceId, query, 1, filter)
                _results.value = page
                _hasMore.value = page.size >= 20
            } catch (e: Exception) {
                _error.value = e.toFriendlyMessage()
                _results.value = emptyList()
                _hasMore.value = false
            } finally {
                _loading.value = false
            }
        }
    }

    private val _previewManga = MutableStateFlow<SManga?>(null)
    val previewManga: StateFlow<SManga?> = _previewManga.asStateFlow()

    fun showPreview(manga: SManga) { _previewManga.value = manga }
    fun dismissPreview() { _previewManga.value = null }
}
