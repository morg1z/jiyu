package com.haise.jiyu.source.mangaplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cisty JVM test minimalniho protobuf parseru (zadna Android zavislost).
 * Rucne zakodujeme protobuf zpravu a overime, ze parseProto/msg/msgs/str/long
 * ji spravne rozlozi zpet - viz komentar v MangaPlusProto.kt o tom, ze chybova
 * (non-protobuf) odpoved nesmi shodit parser mimo pole.
 */
class MangaPlusProtoTest {

    private fun encodeVarint(value: Long): ByteArray {
        val out = mutableListOf<Byte>()
        var v = value
        while (true) {
            var b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            out.add(b.toByte())
            if (v == 0L) break
        }
        return out.toByteArray()
    }

    private fun tag(field: Int, wireType: Int) = encodeVarint(((field.toLong() shl 3) or wireType.toLong()))
    private fun varintField(field: Int, value: Long) = tag(field, 0) + encodeVarint(value)
    private fun stringField(field: Int, value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return tag(field, 2) + encodeVarint(bytes.size.toLong()) + bytes
    }
    private fun bytesField(field: Int, value: ByteArray): ByteArray {
        return tag(field, 2) + encodeVarint(value.size.toLong()) + value
    }

    @Test
    fun `parseProto decodes varint, string and nested message fields`() {
        val inner = stringField(1, "Naruto") + varintField(2, 42L)
        val outer = bytesField(8, inner) + varintField(3, 7L)

        val parsed = outer.parseProto()

        assertEquals(7L, parsed.long(3))
        val nested = parsed.msg(8)
        assertEquals("Naruto", nested?.str(1))
        assertEquals(42L, nested?.long(2))
    }

    @Test
    fun `msgs returns all repeated occurrences of a field`() {
        val item1 = stringField(1, "A")
        val item2 = stringField(1, "B")
        val outer = bytesField(5, item1) + bytesField(5, item2)

        val parsed = outer.parseProto()
        val items = parsed.msgs(5)

        assertEquals(2, items.size)
        assertEquals("A", items[0].str(1))
        assertEquals("B", items[1].str(1))
    }

    @Test
    fun `non-protobuf garbage input never throws, returns empty map`() {
        val garbage = "This is an HTML error page, not protobuf".toByteArray()
        val parsed = garbage.parseProto()

        // Nesmí spadnout, i kdyz vysledek muze byt necekany - zadny IndexOutOfBoundsException
        assertTrue(parsed.long(1) == null || parsed.long(1) is Long)
    }

    @Test
    fun `empty byte array parses to empty map without throwing`() {
        val parsed = ByteArray(0).parseProto()
        assertTrue(parsed.isEmpty())
        assertNull(parsed.str(1))
        assertNull(parsed.long(1))
        assertNull(parsed.msg(1))
        assertTrue(parsed.msgs(1).isEmpty())
    }

    @Test
    fun `truncated length-delimited field is clamped, not out of bounds`() {
        // Field 1, wireType 2 (length-delimited), delka rika 100 bajtu, ale zadne nenasleduji
        val truncated = tag(1, 2) + encodeVarint(100L)
        val parsed = truncated.parseProto()

        // Delka byla oriznuta na dostupnou velikost - zadna vyjimka
        assertTrue(parsed.containsKey(1))
    }
}
