package com.haise.jiyu.source

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test
    fun `batched stahne davky soubezne a zachova poradi`() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val out = fetchPagesBatched(maxPages = 12) { page ->
            val now = active.incrementAndGet()
            peak.updateAndGet { p -> maxOf(p, now) }
            delay(20)
            active.decrementAndGet()
            PageBatch(listOf(page), isLast = page == 12)
        }
        assertEquals((1..12).toList(), out)
        // Více než 1 souběžný fetch dokazuje paralelní dávky.
        assertTrue("očekávám souběžnost > 1, peak=${peak.get()}", peak.get() > 1)
        assertTrue(peak.get() <= 4)
    }

    @Test
    fun `batched zastavi po isLast a zahodi stranky za ni`() = runBlocking {
        val fetched = mutableListOf<Int>()
        val out = fetchPagesBatched(maxPages = 50) { page ->
            synchronized(fetched) { fetched.add(page) }
            // Stránka 3 hlásí konec, ale dávka 1..4 už jede - stránka 4 se zahodí,
            // další dávky se nestahují.
            PageBatch(listOf(page * 10), isLast = page == 3)
        }
        assertEquals(listOf(10, 20, 30), out)
        assertTrue("nesmí se tahat stránka >= 5", synchronized(fetched) { fetched.max() } <= 4)
    }

    @Test
    fun `batched firstPage respektuje jiny index zacatku`() = runBlocking {
        val out = fetchPagesBatched(firstPage = 0, maxPages = 3) { page ->
            PageBatch(listOf(page), isLast = false)
        }
        assertEquals(listOf(0, 1, 2), out)
    }

    @Test
    fun `batched chyba stranky propaguje`() {
        assertThrows(IOException::class.java) {
            runBlocking {
                fetchPagesBatched<Int>(maxPages = 10) { page ->
                    if (page == 2) throw IOException("boom")
                    PageBatch(listOf(page), isLast = false)
                }
            }
        }
    }

    @Test
    fun `batched prazdna stranka jako isLast vrati dosavadni polozky`() = runBlocking {
        val out = fetchPagesBatched(maxPages = 10) { page ->
            PageBatch(
                if (page == 2) emptyList() else listOf(page),
                isLast = page == 2,
            )
        }
        assertEquals(listOf(1), out)
    }

    @Test
    fun `batched chyba spekulativni stranky za isLast se zahodi`() = runBlocking {
        // Simuluje HTML zdroje, kde stránka za koncem vrací 404 - sekvenční smyčka
        // by ji nikdy nefetchovala, takže chyba nesmí zabít výsledek.
        val out = fetchPagesBatched(maxPages = 10) { page ->
            if (page > 2) throw IOException("404")
            PageBatch(listOf(page), isLast = page == 2)
        }
        assertEquals(listOf(1, 2), out)
    }
}
