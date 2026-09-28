package com.haise.jiyu.source

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class PagedFetchTest {

    @Test
    fun `prazdny rozsah vrati prazdny seznam bez volani fetch`() = runBlocking {
        val calls = AtomicInteger()
        val out = fetchPagesParallel(2, 1) { calls.incrementAndGet(); listOf(it) }
        assertEquals(emptyList<Int>(), out)
        assertEquals(0, calls.get())
    }

    @Test
    fun `stahne vsechny stranky v rozsahu a zachova poradi stranek`() = runBlocking {
        val out = fetchPagesParallel(2, 6) { page -> listOf(page, page * 10) }
        assertEquals(listOf(2, 20, 3, 30, 4, 40, 5, 50, 6, 60), out)
    }

    @Test
    fun `soubeznost nikdy nepresahne strop parallelism`() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        fetchPagesParallel(1, 12, parallelism = 3) {
            val now = active.incrementAndGet()
            peak.updateAndGet { p -> maxOf(p, now) }
            delay(20)
            active.decrementAndGet()
            emptyList<Int>()
        }
        assertEquals(3, peak.get())
    }

    @Test
    fun `chyba stranky propaguje a prerusi zbytek`() {
        assertThrows(IOException::class.java) {
            runBlocking {
                fetchPagesParallel(1, 10) { page ->
                    if (page == 3) throw IOException("boom")
                    delay(5)
                    listOf(page)
                }
            }
        }
    }
}
