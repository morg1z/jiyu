package com.haise.jiyu.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SleepTimerManager @Inject constructor() {
    private var timerJob: Job? = null
    private val _remainingSeconds = MutableStateFlow<Int?>(null)
    val remainingSeconds: StateFlow<Int?> = _remainingSeconds.asStateFlow()
    val isRunning: Boolean get() = timerJob?.isActive == true

    fun start(minutes: Int, onFinish: () -> Unit) {
        timerJob?.cancel()
        timerJob = CoroutineScope(Dispatchers.Default).launch {
            var remaining = minutes * 60
            _remainingSeconds.value = remaining
            while (remaining > 0) {
                delay(1000)
                remaining--
                _remainingSeconds.value = remaining
            }
            _remainingSeconds.value = null
            withContext(Dispatchers.Main) { onFinish() }
        }
    }

    fun cancel() {
        timerJob?.cancel()
        timerJob = null
        _remainingSeconds.value = null
    }
}
