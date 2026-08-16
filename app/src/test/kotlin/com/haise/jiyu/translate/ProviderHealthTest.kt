package com.haise.jiyu.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Čistý JVM test krátkodobé paměti o dostupnosti providerů.
 *
 * Cíl: dávka kapitoly nemá znovu a znovu zkoušet providera, o kterém už z předchozí dávky
 * víme, že odmítá obsluhu - viz komentář v [ProviderHealth] a hlášené "22/54 po 15 minutách".
 */
class ProviderHealthTest {

    /** Ručně posouvatelné hodiny, ať test nemusí nic uspávat. */
    private class FakeClock(var millis: Long = 0L) {
        fun advance(by: Long) { millis += by }
    }

    private fun healthWith(clock: FakeClock) = ProviderHealth { clock.millis }

    private val minute = 60_000L

    @Test
    fun `provider is available before anything fails`() {
        assertTrue(healthWith(FakeClock()).isAvailable("gemini"))
    }

    @Test
    fun `a single failure sidelines the provider`() {
        val health = healthWith(FakeClock())
        health.markUnavailable("gemini")
        assertFalse(health.isAvailable("gemini"))
    }

    @Test
    fun `sidelining one provider leaves the others alone`() {
        val health = healthWith(FakeClock())
        health.markUnavailable("gemini")
        assertTrue(health.isAvailable("groq"))
        assertTrue(health.isAvailable("openrouter"))
    }

    @Test
    fun `provider recovers on its own once the cooldown passes`() {
        val clock = FakeClock()
        val health = healthWith(clock)
        health.markUnavailable("gemini")

        clock.advance(minute - 1)
        assertFalse("still inside the first cooldown", health.isAvailable("gemini"))

        clock.advance(1)
        assertTrue("first cooldown is one minute, so a per-minute limit recovers fast", health.isAvailable("gemini"))
    }

    @Test
    fun `repeated failures double the cooldown`() {
        val clock = FakeClock()
        val health = healthWith(clock)

        health.markUnavailable("gemini")
        clock.advance(minute)
        health.markUnavailable("gemini") // druhé selhání bez úspěchu mezi tím

        clock.advance(minute)
        assertFalse("second cooldown must be longer than the first", health.isAvailable("gemini"))

        clock.advance(minute)
        assertTrue(health.isAvailable("gemini"))
    }

    @Test
    fun `cooldown never grows past the cap`() {
        val clock = FakeClock()
        val health = healthWith(clock)
        repeat(50) { health.markUnavailable("gemini") }

        clock.advance(15 * minute)
        assertTrue("cap is 15 minutes even after many failures", health.isAvailable("gemini"))
    }

    @Test
    fun `a success clears the escalation ladder`() {
        val clock = FakeClock()
        val health = healthWith(clock)

        repeat(5) { health.markUnavailable("gemini") }
        health.markHealthy("gemini")
        assertTrue("a success must make the provider usable immediately", health.isAvailable("gemini"))

        // A další selhání musí začít zase od základní prodlevy, ne od vyeskalované.
        health.markUnavailable("gemini")
        clock.advance(minute)
        assertTrue("ladder should have restarted at the base cooldown", health.isAvailable("gemini"))
    }

    @Test
    fun `proxy level limit sidelines every provider at once`() {
        val health = healthWith(FakeClock())
        health.markAllUnavailable()

        ProviderHealth.ALL_PROVIDERS.forEach { assertFalse(it, health.isAvailable(it)) }
        assertTrue(health.allUnavailable())
    }

    @Test
    fun `allUnavailable stays false while any provider is still usable`() {
        val health = healthWith(FakeClock())
        health.markUnavailable("gemini")
        health.markUnavailable("groq")
        assertFalse("openrouter is still fine", health.allUnavailable())
    }

    @Test
    fun `allUnavailable goes back to false once the cooldowns expire`() {
        val clock = FakeClock()
        val health = healthWith(clock)
        health.markAllUnavailable()
        assertTrue(health.allUnavailable())

        clock.advance(minute)
        assertFalse("providers must recover by themselves, not stay dead forever", health.allUnavailable())
    }

    @Test
    fun `unknown provider name is treated as available`() {
        // Obranné chování - překlep v názvu providera nesmí utnout překlad úplně.
        assertTrue(healthWith(FakeClock()).isAvailable("neco-jineho"))
    }

    @Test
    fun `counts every provider the chain can use`() {
        assertEquals(listOf("gemini", "groq", "openrouter"), ProviderHealth.ALL_PROVIDERS)
    }

    // ── knownRetryAfterSeconds (viz translate-proxy retryDelaySecondsFrom*) ──

    @Test
    fun `a known retry delay is honored exactly instead of the guessed base cooldown`() {
        val clock = FakeClock()
        val health = healthWith(clock)
        health.markUnavailable("gemini", knownRetryAfterSeconds = 5.0)

        clock.advance(4_999)
        assertFalse("known delay is 5s, not the guessed 1min base cooldown", health.isAvailable("gemini"))
        clock.advance(1)
        assertTrue(health.isAvailable("gemini"))
    }

    @Test
    fun `a long known delay outlasts the guessed cooldown cap`() {
        // Presne pripad, kvuli kteremu tohle vzniklo: Gemini rekne "cekej hodiny", ne minuty -
        // hadany strop 15 min by providera pustil zpatky mnohem driv, nez ma smysl.
        val clock = FakeClock()
        val health = healthWith(clock)
        val twoHoursInSeconds = 2 * 60 * 60.0
        health.markUnavailable("gemini", knownRetryAfterSeconds = twoHoursInSeconds)

        clock.advance(15 * minute)
        assertFalse("15 min guessed cap must not apply when we know the real delay is longer", health.isAvailable("gemini"))

        clock.advance((2 * 60 - 15) * minute)
        assertTrue(health.isAvailable("gemini"))
    }

    @Test
    fun `a known delay resets the escalation ladder for the next guessed failure`() {
        val clock = FakeClock()
        val health = healthWith(clock)
        health.markUnavailable("gemini", knownRetryAfterSeconds = 5.0)
        clock.advance(5_000)

        // Dalsi selhani uz bez znameho signalu - musi zacit od zakladni prodlevy (1 min),
        // ne pokracovat v eskalaci, jako by predchozi znamy pokus byl hadany.
        health.markUnavailable("gemini")
        clock.advance(minute - 1)
        assertFalse(health.isAvailable("gemini"))
        clock.advance(1)
        assertTrue(health.isAvailable("gemini"))
    }

    @Test
    fun `a null or zero known delay falls back to the guessed cooldown`() {
        val clock = FakeClock()
        val health = healthWith(clock)
        health.markUnavailable("gemini", knownRetryAfterSeconds = 0.0)

        clock.advance(minute - 1)
        assertFalse("zero/invalid delay must not be treated as immediately available", health.isAvailable("gemini"))
        clock.advance(1)
        assertTrue(health.isAvailable("gemini"))
    }
}
