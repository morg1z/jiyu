package com.haise.jiyu.ui.duplicates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.repository.MangaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DuplicateGroup(
    val normalizedTitle: String,
    val items: List<MangaEntity>,
)

@HiltViewModel
class DuplicateDetectorViewModel @Inject constructor(
    private val repository: MangaRepository,
) : ViewModel() {

    private val _groups = MutableStateFlow<List<DuplicateGroup>>(emptyList())
    val groups: StateFlow<List<DuplicateGroup>> = _groups.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init { scan() }

    fun scan() {
        viewModelScope.launch {
            _isLoading.value = true
            val library = repository.getAllLibraryManga()
            val grouped = library
                .groupBy { normalize(it.title) }
                .filter { (_, items) -> items.size > 1 }
                .map { (key, items) -> DuplicateGroup(key, items.sortedBy { it.sourceId }) }
                .sortedBy { it.normalizedTitle }
            _groups.value = grouped
            _isLoading.value = false
        }
    }

    fun removeFromLibrary(mangaId: String) {
        viewModelScope.launch {
            repository.removeFromLibrary(mangaId)
            scan()
        }
    }

    private fun normalize(title: String): String =
        title.lowercase()
            .replace(Regex("[^a-z0-9\\u00C0-\\u024F\\u0400-\\u04FF ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
}
