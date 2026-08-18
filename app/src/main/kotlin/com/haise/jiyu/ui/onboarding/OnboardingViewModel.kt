package com.haise.jiyu.ui.onboarding

import android.content.Context

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.settings.AppMode
import com.haise.jiyu.settings.ReadingDirection
import com.haise.jiyu.settings.ReadingMode
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.util.isAdultOn
import com.haise.jiyu.util.isPlausibleBirthDate
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _step = MutableStateFlow(0)
    val step: StateFlow<Int> = _step.asStateFlow()

    // Jazyk: tag BCP-47 (cs / en / fr / es)
    private val _selectedLanguage = MutableStateFlow("cs")
    val selectedLanguage: StateFlow<String> = _selectedLanguage.asStateFlow()

    // Vychozi hodnota je agregovany styl (ComicK) - nejjednodussi cesta pro vetsinu
    // uzivatelu. Manualni vyber zdroju je pro pokrocile uzivatele, kteri chteji novele
    // ci americke komiksy.
    private val _appMode = MutableStateFlow(AppMode.COMICK)
    val appMode: StateFlow<String> = _appMode.asStateFlow()

    private val _readingDir = MutableStateFlow(ReadingDirection.LTR)
    val readingDir: StateFlow<String> = _readingDir.asStateFlow()

    private val _readingMode = MutableStateFlow(ReadingMode.MANGA)
    val readingMode: StateFlow<String> = _readingMode.asStateFlow()

    private val _downloadFolderUri = MutableStateFlow<String?>(null)
    val downloadFolderUri: StateFlow<String?> = _downloadFolderUri.asStateFlow()

    /**
     * Zadané datum narození. Drží se JEN v paměti po dobu onboardingu - do úložiště jde pouze
     * odvozený příznak plnoletosti (viz [complete] a [SettingsKeys.IS_ADULT]).
     */
    private val _birthDate = MutableStateFlow<LocalDate?>(null)
    val birthDate: StateFlow<LocalDate?> = _birthDate.asStateFlow()

    private val _crashReporting = MutableStateFlow(false)
    val crashReporting: StateFlow<Boolean> = _crashReporting.asStateFlow()

    val totalSteps = 6

    init {
        // Výchozí jazyk (cs) je v UI hned zaškrtnutý — načti uloženou hodnotu,
        // a pokud neexistuje, nastav výchozí češtinu přes DataStore. MainActivity
        // pak vytvoří lokalizovaný Context bez nutnosti restartu aplikace.
        viewModelScope.launch {
            _selectedLanguage.value = settings.appLanguage.first()
            settings.setAppLanguage(_selectedLanguage.value)
        }
    }

    fun setLanguage(tag: String) {
        _selectedLanguage.value = tag
        viewModelScope.launch { settings.setAppLanguage(tag) }
    }

    fun setAppMode(mode: String) { _appMode.value = mode }
    fun setReadingDir(dir: String) { _readingDir.value = dir }
    fun setReadingMode(mode: String) { _readingMode.value = mode }
    fun setDownloadFolderUri(uri: String?) { _downloadFolderUri.value = uri }
    fun setBirthDate(date: LocalDate?) { _birthDate.value = date }
    fun setCrashReporting(enabled: Boolean) { _crashReporting.value = enabled }

    /** Dává zadané datum smysl a je mu aspoň 18? Viz [isAdultOn]. */
    fun isAdultNow(): Boolean {
        val date = _birthDate.value ?: return false
        val today = LocalDate.now()
        return isPlausibleBirthDate(date, today) && isAdultOn(date, today)
    }

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
                settings.setAppMode(_appMode.value)
                settings.setReadingDirection(_readingDir.value)
                settings.setReadingMode(_readingMode.value)
                settings.setDownloadFolderUri(_downloadFolderUri.value)
                // Do úložiště jde jen odvozený příznak, samotné datum narození se zahodí -
                // viz [SettingsKeys.IS_ADULT]. Zdroje pro dospělé se odemknou jen tehdy,
                // když věk opravdu sedí; jinak zůstanou skryté (výchozí stav).
                val adult = isAdultNow()
                settings.setIsAdult(adult)
                settings.setShowAdultSources(adult)
                settings.setCrashReporting(_crashReporting.value)
                settings.setOnboardingCompleted()
            }
            onDone()
        }
    }
}
