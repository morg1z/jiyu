package com.haise.jiyu.ui.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.community.CommunityRepository
import com.haise.jiyu.community.PublicMangaEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CommunityViewModel @Inject constructor(
    private val communityRepository: CommunityRepository,
) : ViewModel() {

    private val _entries = MutableStateFlow<List<PublicMangaEntry>>(emptyList())
    val entries: StateFlow<List<PublicMangaEntry>> = _entries.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                _entries.value = communityRepository.getPublicLists()
            } catch (_: Exception) {
                _error.value = "Nepodařilo se načíst community seznam"
            }
            _loading.value = false
        }
    }
}
