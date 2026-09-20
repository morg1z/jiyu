package com.haise.jiyu.util

import com.haise.jiyu.source.SourceRateLimitedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class SourceExceptionsTest {

    @Test
    fun `cancellation is always rethrown`() {
        assertThrows(CancellationException::class.java) { CancellationException("stop").rethrowIfControl() }
    }

    @Test
    fun `a source rate limit is rethrown so the user sees the retry message`() {
        assertThrows(SourceRateLimitedException::class.java) { SourceRateLimitedException(3_000L).rethrowIfControl() }
    }

    @Test
    fun `ordinary failures are still swallowed by the caller`() {
        // rethrowIfControl nesmi nic hodit - volajici zdroj pak dal vrati prazdny seznam jako drive.
        IOException("site down").rethrowIfControl()
        IllegalArgumentException("bad url").rethrowIfControl()
        assertEquals(true, true)
    }
}
