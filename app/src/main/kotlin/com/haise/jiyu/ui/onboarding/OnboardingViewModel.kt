package com.haise.jiyu.ui.onboarding

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.settings.ReadingDirection
import com.haise.jiyu.settings.ReadingMode
import com.haise.jiyu.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _step = MutableStateFlow(0)
    val step: StateFlow<Int> = _step.asStateFlow()

    // Jazyk: tag BCP-47 (cs / en / fr / es)
    private val _selectedLanguage = MutableStateFlow("cs")
    val selectedLanguage: StateFlow<String> = _selectedLanguage.asStateFlow()

    private val _readingDir = MutableStateFlow(ReadingDirection.LTR)
    val readingDir: StateFlow<String> = _readingDir.asStateFlow()

    private val _readingMode = MutableStateFlow(ReadingMode.MANGA)
    val readingMode: StateFlow<String> = _readingMode.asStateFlow()

    private val _downloadFolderUri = MutableStateFlow<String?>(null)
    val downloadFolderUri: StateFlow<String?> = _downloadFolderUri.asStateFlow()

    val totalSteps = 4

    fun setLanguage(tag: String) {
        _selectedLanguage.value = tag
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    fun setReadingDir(dir: String) { _readingDir.value = dir }
    fun setReadingMode(mode: String) { _readingMode.value = mode }
    fun setDownloadFolderUri(uri: String?) { _downloadFolderUri.value = uri }

    fun nextStep() {
        if (_step.value < totalSteps - 1) _step.value++
    }

    fun prevStep() {
        if (_step.value > 0) _step.value--
    }

    /**
     * Zápisy do DataStore běží v NonCancellable kontextu a onDone se volá až po jejich
     * dokončení — jinak by navigace pryč z onboardingu zrušila viewModelScope (a tím i
     * rozepsaný zápis) dřív, než se onboardingCompleted stihne uložit na disk.
     */
    fun complete(onDone: () -> Unit) {
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                settings.setReadingDirection(_readingDir.value)
                settings.setReadingMode(_readingMode.value)
                settings.setDownloadFolderUri(_downloadFolderUri.value)
                settings.setOnboardingCompleted()
            }
            onDone()
        }
    }
}
