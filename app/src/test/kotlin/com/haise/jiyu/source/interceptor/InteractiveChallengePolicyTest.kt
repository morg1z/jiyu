package com.haise.jiyu.source.interceptor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractiveChallengePolicyTest {

    @Test
    fun `by default an interactive challenge is allowed`() {
        assertFalse(InteractiveChallengePolicy.isSuppressed)
    }

    @Test
    fun `inside suppressed the flag is visible on the worker threads the coroutine hops to`() = runBlocking {
        val seenOnIo = InteractiveChallengePolicy.suppressed {
            withContext(Dispatchers.IO) { InteractiveChallengePolicy.isSuppressed }
        }

        assertTrue(seenOnIo)
    }

    @Test
    fun `noSolve implies suppressed and is visible on worker threads`() = runBlocking {
        val (suppressed, noSolve) = InteractiveChallengePolicy.noSolve {
            withContext(Dispatchers.IO) { InteractiveChallengePolicy.isSuppressed to InteractiveChallengePolicy.isNoSolve }
        }
        assertTrue(suppressed)
        assertTrue(noSolve)
        assertFalse(InteractiveChallengePolicy.isNoSolve)
    }

    @Test
    fun `suppressed alone does not turn on noSolve`() = runBlocking {
        assertFalse(InteractiveChallengePolicy.suppressed { InteractiveChallengePolicy.isNoSolve })
    }

    @Test
    fun `the flag does not leak out of the suppressed block`() = runBlocking {
        InteractiveChallengePolicy.suppressed { }

        assertFalse(InteractiveChallengePolicy.isSuppressed)
        assertFalse(withContext(Dispatchers.IO) { InteractiveChallengePolicy.isSuppressed })
    }
}
