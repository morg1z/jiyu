package com.haise.jiyu.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.haise.jiyu.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class RelativeTimeUnit { NOW, MINUTES, HOURS, DAYS, DATE }

internal data class RelativeTimeBucket(val unit: RelativeTimeUnit, val value: Long)

/** Do které škály patří stáří v minutách - hranice 1 min / 1 h / 1 den / 30 dní, potom se ukazuje datum. */
internal fun relativeTimeBucket(diffMinutes: Long): RelativeTimeBucket = when {
    diffMinutes < 1 -> RelativeTimeBucket(RelativeTimeUnit.NOW, 0)
    diffMinutes < 60 -> RelativeTimeBucket(RelativeTimeUnit.MINUTES, diffMinutes)
    diffMinutes < 1440 -> RelativeTimeBucket(RelativeTimeUnit.HOURS, diffMinutes / 60)
    diffMinutes < 43200 -> RelativeTimeBucket(RelativeTimeUnit.DAYS, diffMinutes / 1440)
    else -> RelativeTimeBucket(RelativeTimeUnit.DATE, 0)
}

/**
 * "před 2 h" / "2 h ago" apod. podle jazyka appky, jinak datum. Dřív existovaly dvě téměř shodné kopie s natvrdo
 * českými texty. Prázdný řetězec pro neznámý čas (<= 0).
 */
@Composable
fun relativeTimeLabel(timeMs: Long): String {
    if (timeMs <= 0L) return ""
    val context = LocalContext.current
    val bucket = relativeTimeBucket((System.currentTimeMillis() - timeMs) / 60_000L)
    return when (bucket.unit) {
        RelativeTimeUnit.NOW -> context.getString(R.string.relative_time_now)
        RelativeTimeUnit.MINUTES -> context.getString(R.string.relative_time_minutes, bucket.value)
        RelativeTimeUnit.HOURS -> context.getString(R.string.relative_time_hours, bucket.value)
        RelativeTimeUnit.DAYS -> context.getString(R.string.relative_time_days, bucket.value)
        RelativeTimeUnit.DATE -> SimpleDateFormat("d. M. yyyy", Locale.getDefault()).format(Date(timeMs))
    }
}
