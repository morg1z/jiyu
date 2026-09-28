package com.haise.jiyu.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.settings.AppMode
import com.haise.jiyu.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    val newChaptersCount: StateFlow<Int> = settings.newChaptersCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val appMode: StateFlow<String> = settings.appMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppMode.SOURCES)

    /** Prepina agregovane rezimy z long-press sheetu na zalozce Prochazet. */
    fun setAppMode(mode: String) = viewModelScope.launch { settings.setAppMode(mode) }

    // Tip "dlouhy stisk na Prochazet = prepinani rezimu" - nezavisly flag na
    // onboardingu, ukaze se i lidem, kteri onboarding uz davno prosli.
    val browseModeTipShown: StateFlow<Boolean> = settings.browseModeTipShown
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setBrowseModeTipShown() = viewModelScope.launch { settings.setBrowseModeTipShown() }
}
