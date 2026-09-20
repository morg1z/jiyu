package com.haise.jiyu.util

import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

private val RELATIVE_AGO = Regex(
    """(\d+)\s*(second|sec|minute|min|hour|hr|day|week|month|year)s?\s+ago""",
    RegexOption.IGNORE_CASE,
)
private val RELATIVE_DAY_WORD = Regex("""\b(today|yesterday)\b""", RegexOption.IGNORE_CASE)

/** Absolutní formáty, které se na webech se scanlacemi skutečně vyskytují; zkouší se v tomto pořadí. */
private val ABSOLUTE_DATE_PATTERNS = listOf(
    "MMMM d, yyyy",
    "MMM d, yyyy",
    "MMM dd,yyyy",
    "MMMM d yyyy",
    "d MMMM yyyy",
    "d MMM yyyy",
    "d MMM yy",
    "MMM-dd-yyyy",
    "yyyy-MM-dd",
    "yyyy/MM/dd",
    "dd/MM/yyyy",
    "dd-MM-yyyy",
    "dd.MM.yyyy",
    "dd/MM/yy",
)

private val ORDINAL_SUFFIX = Regex("""(?<=\d)(st|nd|rd|th)\b""", RegexOption.IGNORE_CASE)

/**
 * Datum kapitoly z textu, jak ho zobrazuje web: relativní ("2 days ago", "yesterday"), ISO-8601
 * ("2026-07-01T10:00:00Z") i běžné absolutní formáty. Neznámý nebo nerozpoznaný text = `0L`
 * (NE "teď"): jinak by se `dateUpload` měnil při každém stažení seznamu a kapitola by se
 * sama tvářila jako nová.
 *
 * [locale] platí jen pro názvy měsíců v absolutních formátech (relativní zápisy jsou anglicky).
 * [now] je jen pro testy.
 */
fun parseChapterDate(text: String?, locale: Locale = Locale.ENGLISH, now: Long = System.currentTimeMillis()): Long {
    val t = text?.trim().orEmpty()
    if (t.isEmpty()) return 0L

    RELATIVE_AGO.find(t)?.let { m ->
        val value = m.groupValues[1].toLongOrNull() ?: return 0L
        val unitMs = when (m.groupValues[2].lowercase()) {
            "second", "sec" -> 1_000L
            "minute", "min" -> 60_000L
            "hour", "hr" -> 3_600_000L
            "day" -> 86_400_000L
            "week" -> 7 * 86_400_000L
            "month" -> 30 * 86_400_000L
            "year" -> 365 * 86_400_000L
            else -> return 0L
        }
        return now - value * unitMs
    }
    RELATIVE_DAY_WORD.find(t)?.let { m ->
        return if (m.groupValues[1].equals("yesterday", ignoreCase = true)) now - 86_400_000L else now
    }

    if (t.length >= 10 && t[4] == '-' && t.contains('T')) {
        runCatching { return Instant.parse(t).toEpochMilli() }
        // "2026-07-01T10:00:00.123456Z" apod. - ISO bez/ s posunem, který Instant.parse nepřijme
        runCatching { return java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli() }
        runCatching { return java.time.LocalDateTime.parse(t.substringBefore('Z')).atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }
    }

    val cleaned = t.replace(ORDINAL_SUFFIX, "")
    for (pattern in ABSOLUTE_DATE_PATTERNS) {
        val parsed = runCatching {
            SimpleDateFormat(pattern, locale).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(cleaned)
        }.getOrNull()
        if (parsed != null) return parsed.time
    }
    return 0L
}
