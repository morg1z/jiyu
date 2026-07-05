package com.haise.jiyu.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.repository.MangaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    repository: MangaRepository,
) : ViewModel() {

    val library: StateFlow<List<MangaEntity>> = repository.observeLibrary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
