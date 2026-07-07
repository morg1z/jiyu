package com.haise.jiyu.ui.css

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CustomCssViewModel @Inject constructor(private val settings: SettingsRepository) : ViewModel() {
    val customCss: StateFlow<String> = settings.customCss
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun save(css: String) {
        viewModelScope.launch { settings.setCustomCss(css) }
    }
}
