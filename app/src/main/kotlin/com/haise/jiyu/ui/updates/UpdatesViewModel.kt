package com.haise.jiyu.ui.updates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.data.db.ChapterDao
import com.haise.jiyu.data.db.UpdateItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class UpdatesViewModel @Inject constructor(
    chapterDao: ChapterDao,
) : ViewModel() {

    val updates: StateFlow<List<UpdateItem>> = chapterDao.observeUpdates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
