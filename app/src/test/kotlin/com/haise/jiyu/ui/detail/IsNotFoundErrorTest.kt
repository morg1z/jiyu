package com.haise.jiyu.ui.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class IsNotFoundErrorTest {

    @Test
    fun `404 and 410 from a source count as not found`() {
        assertTrue(isNotFoundError(IOException("HTTP 404 při načítání https://x.com/manga/a")))
        assertTrue(isNotFoundError(IOException("HTTP 410 při načítání https://x.com/manga/a")))
    }

    @Test
    fun `outages and other failures are not a reason to relink the title`() {
        assertFalse(isNotFoundError(IOException("HTTP 503 při načítání https://x.com")))
        assertFalse(isNotFoundError(IOException("HTTP 4040 při načítání https://x.com")))
        assertFalse(isNotFoundError(java.net.UnknownHostException("x.com")))
        assertFalse(isNotFoundError(IllegalStateException("HTTP 404")))
    }
}
