package com.haise.jiyu.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.data.db.TranslatedPageDao
import com.haise.jiyu.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val translatedPageDao: TranslatedPageDao,
) : ViewModel() {

    val targetLanguage: StateFlow<String> = settings.targetLanguage
        .stateIn(viewModelScope, SharingStarted.Eagerly, "Czech")

    val theme: StateFlow<String> = settings.theme
        .stateIn(viewModelScope, SharingStarted.Eagerly, "system")

    val readingDirection: StateFlow<String> = settings.readingDirection
        .stateIn(viewModelScope, SharingStarted.Eagerly, "ltr")

    private val _cacheCount = MutableStateFlow(0)
    val cacheCount: StateFlow<Int> = _cacheCount.asStateFlow()

    init {
        refreshCacheCount()
    }

    fun setTargetLanguage(lang: String) = viewModelScope.launch { settings.setTargetLanguage(lang) }
    fun setTheme(t: String)             = viewModelScope.launch { settings.setTheme(t) }
    fun setReadingDirection(dir: String) = viewModelScope.launch { settings.setReadingDirection(dir) }

    fun clearTranslationCache() = viewModelScope.launch {
        translatedPageDao.deleteAll()
        _cacheCount.value = 0
    }

    private fun refreshCacheCount() = viewModelScope.launch {
        _cacheCount.value = translatedPageDao.count()
    }
}
