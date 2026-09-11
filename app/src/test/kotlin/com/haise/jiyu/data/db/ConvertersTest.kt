package com.haise.jiyu.data.db

import com.haise.jiyu.data.db.entity.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ConvertersTest {

    private val converters = Converters()

    @Test
    fun `known status value round-trips correctly`() {
        DownloadStatus.entries.forEach { status ->
            assertEquals(status, converters.toDownloadStatus(converters.fromDownloadStatus(status)))
        }
    }

    @Test
    fun `unknown or corrupted stored value falls back to ERROR instead of crashing`() {
        assertEquals(DownloadStatus.ERROR, converters.toDownloadStatus("SOME_CORRUPTED_VALUE"))
        assertEquals(DownloadStatus.ERROR, converters.toDownloadStatus(""))
    }
}
