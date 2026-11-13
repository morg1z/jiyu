package com.haise.jiyu.util

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.getSystemService

/** Device-state checks used to limit reader page prefetching under resource pressure. */
object DeviceResourcePolicy {

    /**
     * Má appka šetřit zdroje (úsporný režim baterie nebo málo paměti)? Čtečka pak předstahuje jen minimum stránek -
     * dopředu stažené stránky by jinak zbytečně zatěžovaly síť, baterii i paměť.
     */
    fun isSavingResources(context: Context): Boolean = try {
        val power = context.getSystemService<android.os.PowerManager>()
        (power?.isPowerSaveMode == true) || isLowMemory(context)
    } catch (_: Exception) {
        // Dotaz na stav zařízení nesmí nikdy shodit čtečku - při chybě se prostě nešetří.
        false
    }

    private fun isLowMemory(context: Context): Boolean {
        val am = context.getSystemService<ActivityManager>() ?: return false
        if (am.isLowRamDevice) return true
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.lowMemory
    }

}
