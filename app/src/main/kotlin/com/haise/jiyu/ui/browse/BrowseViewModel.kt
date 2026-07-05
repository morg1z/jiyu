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

    init {
        loadPopular()
    }

    fun selectSource(source: MangaSource) {
        if (_selectedSource.value.id == source.id) return
        _selectedSource.value = source
        loadPopular()
    }

    fun loadPopular() {
        viewModelScope.launch {
            _loading.value = true
            _results.value = repository.getPopular(_selectedSource.value.id)
            _loading.value = false
        }
    }

    fun search(query: String) {
        if (query.isBlank()) {
            loadPopular()
            return
        }
        viewModelScope.launch {
            _loading.value = true
            _results.value = repository.search(_selectedSource.value.id, query)
            _loading.value = false
        }
    }

    /** Přidá mangu do knihovny a vrátí její lokální ID k navigaci na detail. */
    fun addToLibrary(manga: SManga, onAdded: (String) -> Unit) {
        viewModelScope.launch {
            repository.addToLibrary(manga)
            onAdded(repository.mangaId(manga.sourceId, manga.url))
        }
    }
}
