package com.haise.jiyu.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimitInterceptorTest {

    @Test
    fun `numeric Retry-After is parsed as seconds converted to ms`() {
        assertEquals(120_000L, parseRetryAfterMs("120"))
    }

    @Test
    fun `zero Retry-After is a valid value, not treated as missing`() {
        assertEquals(0L, parseRetryAfterMs("0"))
    }

    @Test
    fun `HTTP-date Retry-After in the future is parsed as a positive duration`() {
        val future = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(5)
        val header = future.format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
        val result = parseRetryAfterMs(header)
        assertTrue(result != null && result > 0)
    }

    @Test
    fun `malformed header returns null`() {
        assertNull(parseRetryAfterMs("not a valid header"))
    }
}
