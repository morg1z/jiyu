package com.haise.jiyu.ui.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.data.db.ReadHistoryDao
import com.haise.jiyu.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class ReadingGoalsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val readHistoryDao: ReadHistoryDao,
) : ViewModel() {

    val weeklyGoal: StateFlow<Int> = settings.weeklyGoal
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val readingStreak: StateFlow<Int> = settings.readingStreak
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _chaptersThisWeek = MutableStateFlow(0)
    val chaptersThisWeek: StateFlow<Int> = _chaptersThisWeek.asStateFlow()

    init {
        viewModelScope.launch { loadWeeklyProgress() }
    }

    private suspend fun loadWeeklyProgress() {
        val monday = LocalDate.now().with(DayOfWeek.MONDAY)
        val sinceMs = monday.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        _chaptersThisWeek.value = readHistoryDao.countSince(sinceMs)
    }

    fun setWeeklyGoal(chapters: Int) {
        viewModelScope.launch { settings.setWeeklyGoal(chapters) }
    }
}
