package com.haise.jiyu.ui.duplicates

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.util.ChapterStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.util.normalizeMangaTitle
import com.haise.jiyu.util.report
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
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _groups = MutableStateFlow<List<DuplicateGroup>>(emptyList())
    val groups: StateFlow<List<DuplicateGroup>> = _groups.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init { scan() }

    fun scan() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val library = repository.getAllLibraryManga()
                val grouped = library
                    .groupBy { normalizeMangaTitle(it.title) }
                    .filter { (_, items) -> items.size > 1 }
                    .map { (key, items) -> DuplicateGroup(key, items.sortedBy { it.sourceId }) }
                    .sortedBy { it.normalizedTitle }
                _groups.value = grouped
            } catch (e: Exception) {
                // Bez catch by chyba z getAllLibraryManga() (napr. poskozeny radek v DB) byla
                // nezachycena vyjimka ve viewModelScope (SupervisorJob nema vlastni handler) -
                // ta appku spadne, ne jen necha obrazovku vecne tocit se.
                e.report("duplicates:scan")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun removeFromLibrary(mangaId: String) {
        viewModelScope.launch {
            repository.removeFromLibrary(mangaId)
            scan()
        }
    }
}
