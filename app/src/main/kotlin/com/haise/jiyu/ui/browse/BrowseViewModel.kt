package com.haise.jiyu.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.SourceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val repository: MangaRepository,
    sourceManager: SourceManager,
) : ViewModel() {

    val sources: List<MangaSource> = sourceManager.getAll()

    private val _selectedSource = MutableStateFlow(sources.first())
    val selectedSource: StateFlow<MangaSource> = _selectedSource.asStateFlow()

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

    init { loadPopular() }

    fun selectSource(source: MangaSource) {
        if (_selectedSource.value.id == source.id) return
        _selectedSource.value = source
        loadPopular()
    }

    fun loadPopular() {
        lastQuery = null
        currentPage = 1
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val page = repository.getPopular(_selectedSource.value.id, 1)
                _results.value = page
                _hasMore.value = page.size >= 20
            } catch (e: Exception) {
                _error.value = e.message ?: "Chyba sítě"
                _results.value = emptyList()
                _hasMore.value = false
            } finally {
                _loading.value = false
            }
        }
    }

    fun search(query: String) {
        if (query.isBlank()) { loadPopular(); return }
        lastQuery = query
        currentPage = 1
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val page = repository.search(_selectedSource.value.id, query, 1)
                _results.value = page
                _hasMore.value = page.size >= 20
            } catch (e: Exception) {
                _error.value = e.message ?: "Chyba sítě"
                _results.value = emptyList()
                _hasMore.value = false
            } finally {
                _loading.value = false
            }
        }
    }

    fun loadMore() {
        if (_loading.value || !_hasMore.value) return
        currentPage++
        val q = lastQuery
        viewModelScope.launch {
            _loading.value = true
            try {
                val page = if (q == null)
                    repository.getPopular(_selectedSource.value.id, currentPage)
                else
                    repository.search(_selectedSource.value.id, q, currentPage)
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
        if (q == null) loadPopular() else search(q)
    }

    fun addToLibrary(manga: SManga, onAdded: (String) -> Unit) {
        viewModelScope.launch {
            repository.addToLibrary(manga)
            onAdded(repository.mangaId(manga.sourceId, manga.url))
        }
    }

    private val _previewManga = MutableStateFlow<SManga?>(null)
    val previewManga: StateFlow<SManga?> = _previewManga.asStateFlow()

    fun showPreview(manga: SManga) { _previewManga.value = manga }
    fun dismissPreview() { _previewManga.value = null }
}
