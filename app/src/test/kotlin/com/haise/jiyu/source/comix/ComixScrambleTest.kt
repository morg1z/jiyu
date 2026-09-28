package com.haise.jiyu.source.comix

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComixScrambleTest {

    // ---------- planFromHeaders ----------

    @Test
    fun `no headers means no plan`() {
        assertNull(ComixScramble.planFromHeaders { null })
    }

    @Test
    fun `enc headers produce xor plan`() {
        val plan = ComixScramble.planFromHeaders { name ->
            mapOf("x-enc-seed" to "12345", "x-enc-len" to "1024", "x-enc-algo" to "1")[name]
        }!!
        assertTrue(plan.hasXor)
        assertFalse(plan.hasGrid)
        assertEquals(12345, plan.encSeed)
        assertEquals(1024, plan.encLen)
    }

    @Test
    fun `scramble headers produce grid plan`() {
        val plan = ComixScramble.planFromHeaders { name ->
            mapOf(
                "x-scramble-grid" to "5x5",
                "x-scramble-seed" to "777",
                "x-scramble-algo" to "3",
            )[name]
        }!!
        assertTrue(plan.hasGrid)
        assertFalse(plan.hasXor)
        assertEquals(777, plan.gridSeed)
        assertEquals("3", plan.gridAlgo)
    }

    @Test
    fun `scramble hash folds into seed`() {
        val withHash = ComixScramble.planFromHeaders { name ->
            mapOf(
                "x-scramble-grid" to "5x5",
                "x-scramble-seed" to "100",
                "x-scramble-hash" to "03632",
            )[name]
        }!!
        assertEquals(100 xor 58414, withHash.gridSeed)
    }

    @Test
    fun `unknown grid algo yields no grid`() {
        assertNull(
            ComixScramble.planFromHeaders { name ->
                mapOf("x-scramble-grid" to "5x5", "x-scramble-seed" to "5", "x-scramble-algo" to "9")[name]
            },
        )
    }

    // ---------- decodeXor ----------

    @Test
    fun `lcg xor is symmetric - double decode restores`() {
        val original = "hello comix page bytes".toByteArray()
        val encoded = ComixScramble.decodeXor(original, 4242, original.size, "1")
        assertFalse(original.contentEquals(encoded))
        val restored = ComixScramble.decodeXor(encoded, 4242, original.size, "1")
        assertArrayEquals(original, restored)
    }

    @Test
    fun `xor only touches first len bytes`() {
        val original = ByteArray(64) { it.toByte() }
        val encoded = ComixScramble.decodeXor(original, 7, 16, "1")
        assertTrue(original.copyOfRange(16, 64).contentEquals(encoded.copyOfRange(16, 64)))
        assertFalse(original.copyOfRange(0, 16).contentEquals(encoded.copyOfRange(0, 16)))
    }

    @Test
    fun `algo 2 picks a variant that decodes to image signature`() {
        // PNG signature obrazek
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) +
            ByteArray(60) { (it * 7).toByte() }
        // Zakodujeme xorshift variantou (seed or 1, low byte) - algo2 ji musi najit.
        val encoded = xorshiftEncode(png, 99 or 1, png.size, highByte = false)
        val decoded = ComixScramble.decodeXor(encoded, 99, png.size, "2")
        assertTrue(ComixScramble.run { decoded.hasImageSignature() })
    }

    private fun xorshiftEncode(bytes: ByteArray, seed: Int, length: Int, highByte: Boolean): ByteArray {
        val result = bytes.copyOf()
        var state = seed
        for (i in 0 until minOf(result.size, length)) {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            val key = if (highByte) state ushr 24 else state and 0xFF
            result[i] = (result[i].toInt() xor key).toByte()
        }
        return result
    }

    // ---------- gridOrder ----------

    @Test
    fun `gridOrder is a permutation of 0-24`() {
        val order = ComixScramble.gridOrder(12345, "1")
        assertEquals(25, order.size)
        assertEquals((0 until 25).toSet(), order.toSet())
    }

    @Test
    fun `gridOrder is deterministic`() {
        assertArrayEquals(ComixScramble.gridOrder(777, "3"), ComixScramble.gridOrder(777, "3"))
    }

    @Test
    fun `different seeds give different orders`() {
        assertFalse(ComixScramble.gridOrder(1, "1").contentEquals(ComixScramble.gridOrder(2, "1")))
    }

    @Test
    fun `descrambling restores the original tile layout`() {
        // Server scrambluje jako scrambled[pozice] = original[order[pozice]];
        // interceptor dela output[order[src]] = scrambled[src] -> zpet na original.
        val order = ComixScramble.gridOrder(31337, "1")
        val original = IntArray(25) { it }
        val scrambled = IntArray(25) { i -> original[order[i]] }
        val restored = IntArray(25)
        for (src in 0 until 25) restored[order[src]] = scrambled[src]
        assertArrayEquals(original, restored)
    }

    @Test
    fun `algo 3 uses xorshift - differs from lcg`() {
        assertFalse(ComixScramble.gridOrder(999, "3").contentEquals(ComixScramble.gridOrder(999, "1")))
    }
}
