package com.haise.jiyu.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.SourceManager
import com.haise.jiyu.util.NetworkMonitor
import com.haise.jiyu.util.toFriendlyMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val repository: MangaRepository,
    private val sourceManager: SourceManager,
    private val networkMonitor: NetworkMonitor,
) : ViewModel() {

    val sources: StateFlow<List<MangaSource>> = sourceManager.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedSource = MutableStateFlow<MangaSource?>(null)
    val selectedSource: StateFlow<MangaSource?> = _selectedSource.asStateFlow()

    init {
        viewModelScope.launch {
            val first = sourceManager.getAll().firstOrNull()
            if (_selectedSource.value == null) {
                _selectedSource.value = first
                if (first != null) loadPopular(_activeFilter.value)
            }
        }
        // Auto-retry when connectivity is restored after an error
        viewModelScope.launch {
            networkMonitor.networkState.drop(1).collect { online ->
                if (online && _error.value != null && !_loading.value) retry()
            }
        }
    }

    private val _results = MutableStateFlow<List<SManga>>(emptyList())
    val results: StateFlow<List<SManga>> = _results.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private var currentPage = 1
    private var lastQuery: String? = null

    fun selectSource(source: MangaSource) {
        if (_selectedSource.value?.id == source.id) return
        _selectedSource.value = source
        loadPopular(_activeFilter.value)
    }

    fun loadMore() {
        if (_loading.value || !_hasMore.value) return
        val sourceId = _selectedSource.value?.id ?: return
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
                repository.addToLibrary(manga)
                onAdded(repository.mangaId(manga.sourceId, manga.url))
            } catch (e: Exception) {
                _error.value = e.toFriendlyMessage()
            }
        }
    }

    private val _activeFilter = MutableStateFlow(MangaFilter())
    val activeFilter: StateFlow<MangaFilter> = _activeFilter.asStateFlow()

    fun setFilters(filter: MangaFilter) {
        _activeFilter.value = filter
        val q = lastQuery
        if (q == null) loadPopular(filter) else search(q, filter)
    }

    fun loadPopular(filter: MangaFilter = _activeFilter.value) {
        val sourceId = _selectedSource.value?.id ?: return
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
        val sourceId = _selectedSource.value?.id ?: return
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
