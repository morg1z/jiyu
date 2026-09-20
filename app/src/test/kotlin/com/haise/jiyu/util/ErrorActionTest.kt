package com.haise.jiyu.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class ErrorActionTest {

    @Test
    fun `cloudflare protection offers the manual solve`() {
        assertEquals(
            ErrorAction.SolveCloudflare("https://site.test/x"),
            CloudflareProtectedException("site.test", "https://site.test/x").toErrorAction(),
        )
    }

    @Test
    fun `a hard block offers nothing because solving would not help`() {
        assertNull(CloudflareBlockedException("site.test", "https://site.test/x").toErrorAction())
    }

    @Test
    fun `moved source offers the new domain`() {
        assertEquals(ErrorAction.UseNewDomain("src", "new.example"), SourceMovedException("src", "new.example").toErrorAction())
    }

    @Test
    fun `login and interactive errors open the source web`() {
        assertEquals(ErrorAction.OpenSourceWeb("src", "https://s/login"), AuthRequiredException("src", "https://s/login").toErrorAction())
        assertEquals(ErrorAction.OpenSourceWeb("src", "https://s/c"), InteractiveActionRequiredException("src", "https://s/c").toErrorAction())
    }

    @Test
    fun `the action is found through wrapped causes`() {
        val wrapped = IOException("outer", RuntimeException("mid", CloudflareProtectedException("h", "https://h/")))
        assertEquals(ErrorAction.SolveCloudflare("https://h/"), wrapped.toErrorAction())
    }

    @Test
    fun `plain errors have no action`() {
        assertNull(IOException("net").toErrorAction())
        assertNull(NoNetworkException().toErrorAction())
        assertNull(RuntimeException("x").toErrorAction())
    }
}
