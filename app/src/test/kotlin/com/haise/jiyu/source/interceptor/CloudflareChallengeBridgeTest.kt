package com.haise.jiyu.source.interceptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testy [CloudflareChallengeBridge] - hlavně regrese na "jeden globální latch pro všechny
 * hostitele" (viz audit nález): dva různí hostitelé musí umět čekat NEZÁVISLE, ne se
 * navzájem blokovat na sdíleném zámku po celou dobu jednoho z nich.
 */
class CloudflareChallengeBridgeTest {

    private fun waitUntil(timeoutMs: Long = 2000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) error("condition never became true within ${timeoutMs}ms")
            Thread.sleep(10)
        }
    }

    @Test
    fun `a second host is queued while the first is pending, then gets its turn after the first resolves`() {
        var resultA: String? = null
        var resultB: String? = null

        val threadA = Thread {
            resultA = CloudflareChallengeBridge.awaitUserSolve("https://a.example/x", "a.example", timeoutSeconds = 10)
        }
        threadA.start()
        waitUntil { CloudflareChallengeBridge.pending.value?.host == "a.example" }

        val threadB = Thread {
            resultB = CloudflareChallengeBridge.awaitUserSolve("https://b.example/y", "b.example", timeoutSeconds = 10)
        }
        threadB.start()

        // B se zaradilo do fronty, ale nesmi predbehnout A ani na nem viset zablokovane -
        // vlakno B je aktivni (ne zaseknute na monitoru), jen jeho VLASTNI latch jeste
        // neni odpocitan. UI porad ukazuje A.
        Thread.sleep(100)
        assertEquals("a.example", CloudflareChallengeBridge.pending.value?.host)
        assertTrue("thread B se nesmi zaseknout na spolecnem zamku", threadB.isAlive)

        CloudflareChallengeBridge.resolve("cookie_a")
        threadA.join(2000)
        assertEquals("cookie_a", resultA)

        // Po vyreseni A se ma B zverejnit jako dalsi pending vyzva.
        waitUntil { CloudflareChallengeBridge.pending.value?.host == "b.example" }

        CloudflareChallengeBridge.resolve("cookie_b")
        threadB.join(2000)
        assertEquals("cookie_b", resultB)

        assertNull("po vyreseni obou nema zustat zadna pending vyzva", CloudflareChallengeBridge.pending.value)
    }

    @Test
    fun `resolve with null cookies (dialog dismissed) is returned as null, not swallowed`() {
        var result: String? = "not set"
        val thread = Thread {
            result = CloudflareChallengeBridge.awaitUserSolve("https://c.example/z", "c.example", timeoutSeconds = 10)
        }
        thread.start()
        waitUntil { CloudflareChallengeBridge.pending.value?.host == "c.example" }

        CloudflareChallengeBridge.resolve(null)
        thread.join(2000)

        assertNull(result)
    }
}
