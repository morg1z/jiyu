package com.haise.jiyu.ui.updates

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.R
import com.haise.jiyu.data.db.ChapterDao
import com.haise.jiyu.data.db.UpdateItem
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.source.SManga
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UpdatesViewModel @Inject constructor(
    private val chapterDao: ChapterDao,
    private val repository: MangaRepository,
    private val settings: SettingsRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    init {
        viewModelScope.launch { settings.clearNewChapters() }
    }

    val updates: StateFlow<List<UpdateItem>> = chapterDao.observeUpdates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _refreshError = MutableStateFlow<String?>(null)
    val refreshError: StateFlow<String?> = _refreshError.asStateFlow()

    fun clearRefreshError() { _refreshError.value = null }

    fun refresh() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            _refreshError.value = null
            val errors = mutableListOf<String>()
            repository.getAllLibraryManga().forEach { manga ->
                try {
                    val sManga = SManga(manga.sourceId, manga.url, manga.title, manga.coverUrl, manga.description, manga.status)
                    repository.refreshChapters(manga.id, sManga)
                } catch (_: Exception) {
                    errors += manga.title
                }
            }
            if (errors.isNotEmpty()) {
                val suffix = if (errors.size > 3) appContext.getString(R.string.library_refresh_error_and_more) else ""
                _refreshError.value = appContext.getString(R.string.updates_refresh_error, errors.take(3).joinToString(), suffix)
            }
            _isRefreshing.value = false
        }
    }

    fun markAllRead() {
        viewModelScope.launch { chapterDao.markAllRead() }
    }
}
