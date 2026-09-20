package com.haise.jiyu.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.Locale

class ChapterDateParserTest {

    private val now = Instant.parse("2026-07-10T12:00:00Z").toEpochMilli()
    private fun utc(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `blank and unknown text is 0 not now`() {
        assertEquals(0L, parseChapterDate(null, now = now))
        assertEquals(0L, parseChapterDate("  ", now = now))
        assertEquals(0L, parseChapterDate("soon", now = now))
    }

    @Test
    fun `relative english dates count back from now`() {
        assertEquals(now - 2 * 86_400_000L, parseChapterDate("2 days ago", now = now))
        assertEquals(now - 3_600_000L, parseChapterDate("1 hour ago", now = now))
        assertEquals(now - 5 * 60_000L, parseChapterDate("5 mins ago", now = now))
        assertEquals(now - 7 * 86_400_000L, parseChapterDate("1 Week Ago", now = now))
    }

    @Test
    fun `today and yesterday`() {
        assertEquals(now, parseChapterDate("Today", now = now))
        assertEquals(now - 86_400_000L, parseChapterDate("yesterday", now = now))
    }

    @Test
    fun `absolute formats`() {
        assertEquals(utc("2026-07-01T00:00:00Z"), parseChapterDate("July 1, 2026", now = now))
        assertEquals(utc("2026-07-01T00:00:00Z"), parseChapterDate("Jul 1, 2026", now = now))
        assertEquals(utc("2026-07-01T00:00:00Z"), parseChapterDate("2026-07-01", now = now))
        assertEquals(utc("2026-07-01T00:00:00Z"), parseChapterDate("2026/07/01", now = now))
        assertEquals(utc("2026-07-01T00:00:00Z"), parseChapterDate("01/07/2026", now = now))
        assertEquals(utc("2026-07-01T00:00:00Z"), parseChapterDate("1 Jul 2026", now = now))
        assertEquals(utc("2026-07-01T00:00:00Z"), parseChapterDate("July 1st, 2026", now = now))
    }

    @Test
    fun `iso timestamps`() {
        assertEquals(utc("2026-07-01T10:00:00Z"), parseChapterDate("2026-07-01T10:00:00Z", now = now))
        assertEquals(utc("2026-07-01T10:00:00Z"), parseChapterDate("2026-07-01T12:00:00+02:00", now = now))
        assertEquals(utc("2026-07-01T10:00:00Z"), parseChapterDate("2026-07-01T10:00:00.000000Z", now = now))
    }

    @Test
    fun `locale controls month names`() {
        assertEquals(utc("2026-07-01T00:00:00Z"), parseChapterDate("1 luglio 2026", Locale.ITALIAN, now))
        assertEquals(0L, parseChapterDate("1 luglio 2026", Locale.ENGLISH, now))
    }
}
