package com.haise.jiyu.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.data.db.ReadHistoryDao
import com.haise.jiyu.data.db.entity.ReadHistoryEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class HistoryGroup(
    val label: String,
    val items: List<ReadHistoryEntity>,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val historyDao: ReadHistoryDao,
) : ViewModel() {

    val groups: StateFlow<List<HistoryGroup>> = historyDao.observeRecent()
        .map { entries -> groupByDate(entries) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteEntry(entry: ReadHistoryEntity) {
        viewModelScope.launch { historyDao.delete(entry.chapterId) }
    }

    fun clearAll() {
        viewModelScope.launch { historyDao.deleteAll() }
    }

    private fun groupByDate(entries: List<ReadHistoryEntity>): List<HistoryGroup> {
        val today    = startOfDay(0)
        val yesterday = startOfDay(-1)
        val thisWeek = startOfDay(-6)

        val fmt = SimpleDateFormat("d. MMMM", Locale("cs"))

        return entries
            .groupBy { entry ->
                val t = entry.readAt
                when {
                    t >= today     -> "Dnes"
                    t >= yesterday -> "Včera"
                    t >= thisWeek  -> "Tento týden"
                    else           -> fmt.format(Date(t))
                }
            }
            .map { (label, items) -> HistoryGroup(label, items) }
    }

    private fun startOfDay(offsetDays: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, offsetDays)
        return cal.timeInMillis
    }
}
