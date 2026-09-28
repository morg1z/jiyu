package com.haise.jiyu.source.comix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ComixCipherTest {

    private fun material(seed: Long = 42L): ComixCipherMaterial {
        val random = Random(seed)
        val sboxes = (0 until 3).map {
            (0 until 256).shuffled(random)
        }
        val keys = (0 until 3).map { (0 until 24).map { random.nextInt(256) } }
        return ComixCipherMaterial(sboxes, keys)
    }

    /** Replika forward substituce pro overeni, ze decrypt() je skutecna inverze. */
    private fun forward(data: ByteArray, material: ComixCipherMaterial): ByteArray {
        var out = data
        val previous = intArrayOf(189, 133, 32)
        repeat(3) { round ->
            val sbox = material.sboxes[round]
            val key = material.keys[round]
            val res = ByteArray(out.size)
            var prev = previous[round]
            for (i in out.indices) {
                val sub = sbox[(out[i].toInt() and 0xff) xor key[i % key.size] xor prev]
                res[i] = sub.toByte()
                prev = sub
            }
            out = res
        }
        return out
    }

    @Test
    fun `decrypt inverts the 3-round substitution`() {
        val m = material()
        val cipher = ComixCipher(m)
        val plain = "/manga?order[score]=desc&page=1"
        val encrypted = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(forward(plain.toByteArray(Charsets.UTF_8), m))
        assertEquals(plain, cipher.decrypt(encrypted))
    }

    @Test
    fun `sign is deterministic and url-safe base64`() {
        val cipher = ComixCipher(material())
        val a = cipher.sign("/api/v1/manga", "limit=28&page=1")
        val b = cipher.sign("/api/v1/manga", "limit=28&page=1")
        assertEquals(a, b)
        assertTrue(a.matches(Regex("[A-Za-z0-9_-]+")))
        assertTrue(a.isNotEmpty())
    }

    @Test
    fun `sign differs per input`() {
        val cipher = ComixCipher(material())
        val a = cipher.sign("/api/v1/manga", "page=1")
        val b = cipher.sign("/api/v1/manga", "page=2")
        assertTrue(a != b)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid material rejected`() {
        ComixCipher(ComixCipherMaterial(sboxes = listOf(listOf(1, 2)), keys = emptyList()))
    }

    @Test
    fun `empty query signs just the path`() {
        val cipher = ComixCipher(material())
        val a = cipher.sign("/api/v1/chapters/123", "")
        val b = cipher.sign("/api/v1/chapters/123", "")
        assertEquals(a, b)
    }
}
