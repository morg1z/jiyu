package com.haise.jiyu.translate

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MangaOcrDecodeTest {

    @Test
    fun `stops early when nextToken returns eosId`() = runTest {
        val script = listOf(10, 11, 3) // 3 = eos
        var call = 0
        val result = greedyDecode(bosId = 2, eosId = 3, maxTokens = 96) { _ ->
            script[call++]
        }
        assertEquals(listOf(10, 11), result)
    }

    @Test
    fun `truncates at maxTokens when eosId never produced`() = runTest {
        val result = greedyDecode(bosId = 2, eosId = 3, maxTokens = 5) { _ -> 42 }
        assertEquals(listOf(42, 42, 42, 42, 42), result)
    }

    @Test
    fun `returns empty list when eosId is produced immediately`() = runTest {
        val result = greedyDecode(bosId = 2, eosId = 3, maxTokens = 96) { _ -> 3 }
        assertEquals(emptyList<Int>(), result)
    }

    @Test
    fun `nextToken receives full sequence so far including bos`() = runTest {
        val seen = mutableListOf<List<Int>>()
        greedyDecode(bosId = 2, eosId = 3, maxTokens = 3) { soFar ->
            seen += soFar
            if (soFar.size >= 3) 3 else 10 + soFar.size
        }
        assertEquals(listOf(2), seen[0])
        assertEquals(listOf(2, 11), seen[1])
        assertEquals(listOf(2, 11, 12), seen[2])
    }
}
