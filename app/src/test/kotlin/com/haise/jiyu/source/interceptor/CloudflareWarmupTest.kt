package com.haise.jiyu.source.interceptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Ciste JVM testy sady CF-chranenych hostu pro [CloudflareWarmup] - perzistence v JSON
 * (`{"host": lastFailAtMs}`), backoff po selhani, uceni novych hostu a samo-cisteni.
 */
class CloudflareWarmupTest {

    private val seed = setOf("seed.example", "seed2.example")
    private val backoff = TimeUnit.MINUTES.toMillis(30)
    private val now = 1_000_000_000L

    // ── mergeWarmupHost ─────────────────────────────────────────────────────

    @Test
    fun `merge adds a new host with zero fail time`() {
        val json = mergeWarmupHost(null, "a.example")
        assertEquals(setOf("a.example"), warmupHostsDue(json, emptySet(), now, backoff).toSet())
    }

    @Test
    fun `merge keeps existing fail timestamp`() {
        val failed = markWarmupFailed(null, "a.example", now)
        val merged = mergeWarmupHost(failed, "a.example")
        // lastFailAt se nesmi prepsat na 0 - host zustane v backoffu.
        assertTrue(warmupHostsDue(merged, emptySet(), now + 1, backoff).isEmpty())
    }

    @Test
    fun `merge is idempotent`() {
        val once = mergeWarmupHost(null, "a.example")
        assertEquals(once, mergeWarmupHost(once, "a.example"))
    }

    // ── markWarmupFailed ────────────────────────────────────────────────────

    @Test
    fun `failed host enters backoff and leaves it after it expires`() {
        val json = markWarmupFailed(null, "a.example", now)
        assertTrue(warmupHostsDue(json, emptySet(), now + backoff - 1, backoff).isEmpty())
        assertEquals(listOf("a.example"), warmupHostsDue(json, emptySet(), now + backoff, backoff))
    }

    // ── removeWarmupHost ────────────────────────────────────────────────────

    @Test
    fun `remove drops the host from the set`() {
        val json = mergeWarmupHost(null, "a.example")
        val removed = removeWarmupHost(json, "a.example")
        assertTrue(warmupHostsDue(removed, emptySet(), now, backoff).isEmpty())
    }

    @Test
    fun `remove on missing host is a no-op`() {
        val json = mergeWarmupHost(null, "a.example")
        assertEquals(setOf("a.example"), warmupHostsDue(removeWarmupHost(json, "b.example"), emptySet(), now, backoff).toSet())
    }

    // ── warmupHostsDue ──────────────────────────────────────────────────────

    @Test
    fun `learned hosts come before seeds - they are the ones the user actually hit`() {
        val json = mergeWarmupHost(null, "learned.example")
        assertEquals(listOf("learned.example", "seed.example", "seed2.example"), warmupHostsDue(json, seed, now, backoff))
    }

    @Test
    fun `seed hosts are always due even without any record`() {
        assertEquals(seed.toList(), warmupHostsDue(null, seed, now, backoff))
    }

    @Test
    fun `learned hosts survive in the persisted json`() {
        var json: String? = null
        json = mergeWarmupHost(json, "cdn.example")
        json = mergeWarmupHost(json, "page.example")
        assertEquals(listOf("cdn.example", "page.example"), warmupHostsDue(json, emptySet(), now, backoff))
    }

    @Test
    fun `a host that never failed stays due regardless of backoff`() {
        val json = mergeWarmupHost(null, "a.example")
        assertEquals(listOf("a.example"), warmupHostsDue(json, emptySet(), now + TimeUnit.DAYS.toMillis(30), backoff))
    }

    @Test
    fun `corrupted json falls back to the seed`() {
        assertEquals(seed.toList(), warmupHostsDue("{tohle neni json", seed, now, backoff))
    }

    @Test
    fun `seed hosts that failed enter backoff too`() {
        val json = markWarmupFailed(null, "seed.example", now)
        assertEquals(listOf("seed2.example"), warmupHostsDue(json, seed, now + 1, backoff))
    }

    @Test
    fun `a removed seed host is not resurrected by persistence`() {
        // Seed je konstanta - removeHost ho ze sady "neodstrani", protoze warmupHostsDue
        // seed vzdy prida. To je schvalne: seed drzi jen zname CF weby; kdyby web CF
        // vypnul, probe ho preskoci diky platne clearance / nezablokuje se.
        val removed = removeWarmupHost(null, "seed.example")
        assertTrue(warmupHostsDue(removed, seed, now, backoff).contains("seed.example"))
    }

    @Test
    fun `non-seed hosts absent from json are not due`() {
        val json = mergeWarmupHost(null, "a.example")
        assertFalse(warmupHostsDue(json, emptySet(), now, backoff).contains("b.example"))
    }
}
