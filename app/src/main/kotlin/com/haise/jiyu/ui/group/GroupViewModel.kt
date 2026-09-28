package com.haise.jiyu.ui.group

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.comick.ComicKSource
import com.haise.jiyu.source.comick.GroupInfo
import com.haise.jiyu.util.report
import com.haise.jiyu.util.toFriendlyMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Obrazovka "Skupina" - další tituly, které daná ComicK překladatelská skupina přeložila. */
@HiltViewModel
class GroupViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val comicKSource: ComicKSource,
    private val repository: MangaRepository,
) : ViewModel() {

    private val slug: String = checkNotNull(savedStateHandle["slug"])

    /** Název skupiny přijde jako nav argument (z `chapter.groups`), takže hlavička nemusí čekat na network round-trip.
     * Pokud je prázdný (ComicK umí vrátit prázdné `group_name`), padne to na `slug` - ten je vždy neprázdný. */
    private val _title = MutableStateFlow(savedStateHandle.get<String>("title")?.takeIf { it.isNotBlank() } ?: slug)
    val title: StateFlow<String> = _title.asStateFlow()

    private val _groupInfo = MutableStateFlow<GroupInfo?>(null)
    val groupInfo: StateFlow<GroupInfo?> = _groupInfo.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _openingManga = MutableStateFlow<SManga?>(null)
    val openingManga: StateFlow<SManga?> = _openingManga.asStateFlow()

    private val _openError = MutableStateFlow<String?>(null)
    val openError: StateFlow<String?> = _openError.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _error.value = null
            _loading.value = true
            try {
                val info = comicKSource.getGroup(slug)
                _groupInfo.value = info
                if (info.title.isNotBlank()) _title.value = info.title
            } catch (e: Exception) {
                e.report("group:getGroup:$slug")
                _error.value = e.toFriendlyMessage()
            } finally {
                _loading.value = false
            }
        }
    }

    fun retry() = load()

    fun openManga(manga: SManga, onOpened: (String) -> Unit) {
        if (_openingManga.value != null) return
        _openingManga.value = manga
        viewModelScope.launch {
            try {
                val id = repository.registerPreview(manga)
                onOpened(id)
            } catch (e: Exception) {
                e.report("group:openManga")
                _openError.value = e.toFriendlyMessage()
            } finally {
                _openingManga.value = null
            }
        }
    }

    fun clearOpenError() { _openError.value = null }
}
