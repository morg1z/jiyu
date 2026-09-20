package com.haise.jiyu.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DiskFullExceptionTest {

    @Test
    fun `DiskFullException is recognised regardless of its message`() {
        assertTrue(isDiskFullError(DiskFullException()))
    }

    @Test
    fun `system ENOSPC IOException is recognised`() {
        assertTrue(isDiskFullError(IOException("write failed: ENOSPC (No space left on device)")))
    }

    @Test
    fun `an ordinary network failure is not a full disk`() {
        assertFalse(isDiskFullError(IOException("Unable to resolve host")))
        assertFalse(isDiskFullError(IllegalStateException("ENOSPC")))
    }
}
