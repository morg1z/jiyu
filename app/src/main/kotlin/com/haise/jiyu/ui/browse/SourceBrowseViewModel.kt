package com.haise.jiyu.ui.browse

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.R
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.SourceManager
import com.haise.jiyu.util.NetworkMonitor
import com.haise.jiyu.util.toFriendlyMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Obsah jednoho konkrétního zdroje (Populární/Nejnovější, hledání, stránkování). */
@HiltViewModel
class SourceBrowseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MangaRepository,
    private val sourceManager: SourceManager,
    private val networkMonitor: NetworkMonitor,
    @ApplicationContext private val appContext: Context,
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

    // ── Otevření detailu mangy ze zdroje (bez přidání do knihovny) ──────────────
    private val _openingManga = MutableStateFlow<SManga?>(null)
    val openingManga: StateFlow<SManga?> = _openingManga.asStateFlow()

    // Chyba jen tohoto konkrétního otevření - na rozdíl od `error` nesmí schovat
    // celou už načtenou mřížku výsledků, proto má vlastní stav (zobrazí se jako snackbar).
    private val _openError = MutableStateFlow<String?>(null)
    val openError: StateFlow<String?> = _openError.asStateFlow()

    fun openManga(manga: SManga, onOpened: (String) -> Unit) {
        if (_openingManga.value != null) return
        _openingManga.value = manga
        viewModelScope.launch {
            try {
                val id = repository.openPreview(manga)
                onOpened(id)
            } catch (e: Exception) {
                _openError.value = e.toFriendlyMessage()
            } finally {
                _openingManga.value = null
            }
        }
    }

    fun clearOpenError() { _openError.value = null }

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
            _error.value = appContext.getString(R.string.detail_error_no_internet)
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
            _error.value = appContext.getString(R.string.detail_error_no_internet)
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
}
