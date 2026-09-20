package com.haise.jiyu.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundedLruMapTest {

    @Test
    fun `evicts the least recently used entry once the limit is exceeded`() {
        val map = boundedLruMap<String, Int>(2)
        map["a"] = 1
        map["b"] = 2
        map["a"]
        map["c"] = 3

        assertEquals(2, map.size)
        assertNull(map["b"])
        assertEquals(1, map["a"])
        assertEquals(3, map["c"])
    }
}
