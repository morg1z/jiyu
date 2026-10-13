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

/**
 * Dva různé zdroje / katalogové záznamy se stejným názvem nejsou automaticky
 * duplikát - ComicK má např. dvě odlišné série "Demon Devourer" od různých
 * autorů. Skupinu podle názvu proto ještě rozdělíme podle tvůrce (autor,
 * případně artist). Položky bez autora se přihodí k největší podskupině -
 * typicky záznam z druhého zdroje, kde se metadata nenaplnila.
 * Výsledek: jen podskupiny s >= 2 položkami.
 */
internal fun findDuplicateGroups(library: List<MangaEntity>): List<DuplicateGroup> =
    library
        .groupBy { normalizeMangaTitle(it.title) }
        .filter { (_, items) -> items.size > 1 }
        .flatMap { (key, items) ->
            val byCreator = items.groupBy { m -> normalizeMangaTitle(m.author ?: m.artist ?: "") }
            val known = byCreator.filterKeys { it.isNotBlank() }
            if (known.size <= 1) listOf(items)
            else {
                val largest = known.values.maxBy { it.size }
                known.map { (_, v) -> if (v === largest) v + byCreator[""].orEmpty() else v }
                    .filter { it.size > 1 }
            }.map { DuplicateGroup(key, it.sortedBy { m -> m.sourceId }) }
        }
        .sortedBy { it.normalizedTitle }

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
                _groups.value = findDuplicateGroups(repository.getAllLibraryManga())
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
