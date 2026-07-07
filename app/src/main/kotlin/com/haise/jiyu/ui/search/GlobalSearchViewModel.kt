package com.haise.jiyu.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.SourceManager
import com.haise.jiyu.util.toFriendlyMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SourceResult(
    val source: MangaSource,
    val loading: Boolean = true,
    val results: List<SManga> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class GlobalSearchViewModel @Inject constructor(
    private val sourceManager: SourceManager,
    private val repository: MangaRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<SourceResult>>(emptyList())
    val results: StateFlow<List<SourceResult>> = _results.asStateFlow()

    fun search(q: String) {
        if (q.isBlank()) return
        _query.value = q
        viewModelScope.launch {
            val sources = sourceManager.getAll()
            _results.value = sources.map { SourceResult(it) }
            sources.mapIndexed { i, source ->
                async {
                    val result = try {
                        val list = repository.search(source.id, q, 1, MangaFilter())
                        SourceResult(source, loading = false, results = list.take(10))
                    } catch (e: Exception) {
                        SourceResult(source, loading = false, error = e.toFriendlyMessage())
                    }
                    _results.update { current ->
                        current.toMutableList().also { if (i < it.size) it[i] = result }
                    }
                }
            }.awaitAll()
        }
    }

    fun mangaId(manga: SManga): String = repository.mangaId(manga.sourceId, manga.url)

    fun addToLibrary(manga: SManga, onAdded: (String) -> Unit) {
        viewModelScope.launch {
            try {
                repository.addToLibrary(manga)
                onAdded(repository.mangaId(manga.sourceId, manga.url))
            } catch (_: Exception) {}
        }
    }
}
