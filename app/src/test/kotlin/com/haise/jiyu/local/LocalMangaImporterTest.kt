package com.haise.jiyu.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * `copyToWithLimit` je top-level (ne členská metoda [LocalMangaImporter]) právě proto, aby
 * šla otestovat bez Android/Context runtime - viz komentář u definice.
 */
class LocalMangaImporterTest {

    @Test
    fun `copies all bytes when within limit`() {
        val data = ByteArray(1000) { it.toByte() }
        val out = ByteArrayOutputStream()
        val copied = ByteArrayInputStream(data).copyToWithLimit(out, limit = 1000)
        assertEquals(1000L, copied)
        assertEquals(1000, out.size())
    }

    @Test
    fun `throws IOException when data exceeds limit instead of copying unbounded`() {
        // Simuluje zip bombu - vstup vetsi nez povoleny limit se nesmi rozbalit cely.
        val data = ByteArray(20_000) { 1 }
        val out = ByteArrayOutputStream()
        assertThrows(IOException::class.java) {
            ByteArrayInputStream(data).copyToWithLimit(out, limit = 10_000)
        }
    }

    @Test
    fun `exact limit is allowed, one byte over is not`() {
        val exact = ByteArray(500) { 1 }
        val out = ByteArrayOutputStream()
        assertEquals(500L, exact.inputStream().copyToWithLimit(out, limit = 500))

        val overByOne = ByteArray(501) { 1 }
        assertThrows(IOException::class.java) {
            overByOne.inputStream().copyToWithLimit(ByteArrayOutputStream(), limit = 500)
        }
    }
}
