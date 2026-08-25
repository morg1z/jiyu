package com.haise.jiyu.util

import com.haise.jiyu.source.SourceRateLimitedException
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorMessagesTest {

    @Test
    fun `rate limited with known retry-after shows seconds to wait`() {
        val message = SourceRateLimitedException(45_000L).toFriendlyMessage()
        assertEquals("Příliš mnoho požadavků - zkus to znovu za 45s", message)
    }

    @Test
    fun `rate limited without retry-after shows generic wait message`() {
        val message = SourceRateLimitedException(0L).toFriendlyMessage()
        assertEquals("Příliš mnoho požadavků - zkus to znovu za chvíli", message)
    }

    @Test
    fun `sub-second retry-after still rounds up to at least 1s`() {
        val message = SourceRateLimitedException(400L).toFriendlyMessage()
        assertEquals("Příliš mnoho požadavků - zkus to znovu za 1s", message)
    }
}
