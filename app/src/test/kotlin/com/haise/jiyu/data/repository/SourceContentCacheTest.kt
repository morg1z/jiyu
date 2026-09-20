package com.haise.jiyu.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SourceContentCacheTest {

    private val cache = SourceContentCache()

    @Test
    fun `two concurrent callers share one load`() = runBlocking {
        val loads = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val a = async { cache.getOrLoad("s", "k", 60_000, 4) { loads.incrementAndGet(); gate.await(); listOf(1, 2) } }
        val b = async { cache.getOrLoad("s", "k", 60_000, 4) { loads.incrementAndGet(); gate.await(); listOf(9) } }
        gate.complete(Unit)
        assertEquals(listOf(1, 2), a.await())
        assertEquals(listOf(1, 2), b.await())
        assertEquals(1, loads.get())
    }

    @Test
    fun `a successful result is served from cache until it expires`() = runBlocking {
        var now = 0L
        cache.nowMs = { now }
        val loads = AtomicInteger()
        suspend fun load() = cache.getOrLoad("s", "k", 1_000, 4) { loads.incrementAndGet(); "v${loads.get()}" }
        assertEquals("v1", load())
        now = 999
        assertEquals("v1", load())
        now = 1_000
        assertEquals("v2", load())
    }

    @Test
    fun `failures are not cached`() = runBlocking {
        val loads = AtomicInteger()
        assertThrows(java.io.IOException::class.java) {
            runBlocking { cache.getOrLoad<String>("s", "k", 60_000, 4) { loads.incrementAndGet(); throw java.io.IOException("boom") } }
        }
        assertEquals("ok", cache.getOrLoad("s", "k", 60_000, 4) { loads.incrementAndGet(); "ok" })
        assertEquals(2, loads.get())
    }

    @Test
    fun `an empty list is not cached`() = runBlocking {
        val loads = AtomicInteger()
        assertTrue(cache.getOrLoad("s", "k", 60_000, 4) { loads.incrementAndGet(); emptyList<Int>() }.isEmpty())
        assertEquals(listOf(1), cache.getOrLoad("s", "k", 60_000, 4) { loads.incrementAndGet(); listOf(1) })
        assertEquals(2, loads.get())
    }

    @Test
    fun `force replaces the cached value`() = runBlocking {
        assertEquals("a", cache.getOrLoad("s", "k", 60_000, 4) { "a" })
        assertEquals("b", cache.getOrLoad("s", "k", 60_000, 4, force = true) { "b" })
        assertEquals("b", cache.getOrLoad("s", "k", 60_000, 4) { "c" })
    }

    @Test
    fun `cancelling one caller does not cancel the shared load for the others`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        // UNDISPATCHED: `a` se rozběhne hned a zaregistruje sdílené načítání, než ho zrušíme (jinak by se v
        // jednovláknovém runBlocking zrušilo ještě před startem a "b" by načítalo samo).
        val a = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            cache.getOrLoad("s", "k", 60_000, 4) { gate.await(); "shared" }
        }
        val b = async { cache.getOrLoad("s", "k", 60_000, 4) { "never" } }
        a.cancelAndJoin()
        gate.complete(Unit)
        assertEquals("shared", b.await())
    }

    @Test
    fun `the least recently used entry is evicted`() = runBlocking {
        val loads = AtomicInteger()
        suspend fun load(key: String) = cache.getOrLoad("s", key, 60_000, 2) { loads.incrementAndGet(); key }
        load("a"); load("b"); load("c") // "a" vypadne
        assertEquals(3, loads.get())
        load("c")
        assertEquals(3, loads.get())
        load("a")
        assertEquals(4, loads.get())
    }

    @Test
    fun `clear and trim drop entries`() = runBlocking {
        val loads = AtomicInteger()
        suspend fun load(key: String) = cache.getOrLoad("s", key, 60_000, 8) { loads.incrementAndGet(); key }
        load("a"); load("b"); load("c")
        cache.trim(1)
        load("c")
        assertEquals(3, loads.get())
        load("a")
        assertEquals(4, loads.get())
        cache.clear()
        load("c")
        assertEquals(5, loads.get())
    }
}
