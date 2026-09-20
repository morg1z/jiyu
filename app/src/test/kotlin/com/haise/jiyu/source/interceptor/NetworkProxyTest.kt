package com.haise.jiyu.source.interceptor

import com.haise.jiyu.ui.reader.PREFETCH_WINDOW
import com.haise.jiyu.ui.reader.PREFETCH_WINDOW_METERED
import com.haise.jiyu.ui.reader.PREFETCH_WINDOW_MINIMAL
import com.haise.jiyu.ui.reader.prefetchWindowFor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

class NetworkProxyTest {

    @Test
    fun `parse accepts valid input and trims it`() {
        assertEquals(
            ProxySettings(ProxyType.HTTP, "proxy.example.com", 8080),
            ProxySettings.parse(ProxyType.HTTP, " proxy.example.com ", " 8080 ", null, null),
        )
        assertEquals(
            ProxySettings(ProxyType.SOCKS, "10.0.0.5", 1080, "bob", "pw"),
            ProxySettings.parse(ProxyType.SOCKS, "10.0.0.5", "1080", "bob", "pw"),
        )
    }

    @Test
    fun `parse rejects a bad host or port and drops a password without a user`() {
        assertNull(ProxySettings.parse(ProxyType.HTTP, "", "8080", null, null))
        assertNull(ProxySettings.parse(ProxyType.HTTP, "has space.com", "8080", null, null))
        assertNull(ProxySettings.parse(ProxyType.HTTP, "proxy.example.com", "0", null, null))
        assertNull(ProxySettings.parse(ProxyType.HTTP, "proxy.example.com", "70000", null, null))
        assertNull(ProxySettings.parse(ProxyType.HTTP, "proxy.example.com", "abc", null, null))
        assertNull(ProxySettings.parse(ProxyType.HTTP, "https://proxy.example.com", "8080", null, null))
        assertNull(ProxySettings.parse(ProxyType.HTTP, "proxy.example.com", "8080", "  ", "secret")?.password)
    }

    @Test
    fun `without a configured proxy the system selection is used`() {
        val config = NetworkProxyConfig()
        // Bez vlastní proxy se rozhoduje systémový výběr (v testu nejspíš přímé spojení) - hlavně nesmí spadnout.
        val chosen = config.selector.select(URI("https://site.test/"))
        assertTrue(chosen.isNotEmpty())
    }

    @Test
    fun `a configured proxy is used for remote hosts but not for local ones`() {
        val config = NetworkProxyConfig().apply { settings = ProxySettings(ProxyType.HTTP, "proxy.example.com", 3128) }
        val remote = config.selector.select(URI("https://site.test/x")).single()
        assertEquals(Proxy.Type.HTTP, remote.type())
        assertEquals(InetSocketAddress.createUnresolved("proxy.example.com", 3128), remote.address())

        assertEquals(Proxy.NO_PROXY, config.selector.select(URI("http://192.168.1.5/")).single())
        assertEquals(Proxy.NO_PROXY, config.selector.select(URI("http://localhost:8080/")).single())

        config.settings = ProxySettings(ProxyType.SOCKS, "socks.example.com", 1080)
        assertEquals(Proxy.Type.SOCKS, config.selector.select(URI("https://site.test/")).single().type())
    }

    private fun proxyAuthResponse(existingAuth: String? = null): Response {
        val request = Request.Builder().url("https://site.test/").apply { existingAuth?.let { header("Proxy-Authorization", it) } }.build()
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(407).message("Proxy Authentication Required")
            .body("".toResponseBody()).build()
    }

    @Test
    fun `an http proxy with a user answers 407 once with basic credentials`() {
        val config = NetworkProxyConfig().apply { settings = ProxySettings(ProxyType.HTTP, "p.test", 3128, "bob", "pw") }
        val retried = config.authenticator.authenticate(null, proxyAuthResponse())
        assertEquals("Basic Ym9iOnB3", retried?.header("Proxy-Authorization"))
        // Druhý 407 s už odeslaným přihlášením = špatné heslo, dál se nezkouší.
        assertNull(config.authenticator.authenticate(null, proxyAuthResponse(existingAuth = "Basic Ym9iOnB3")))
    }

    @Test
    fun `no authentication without a user, for socks or without a proxy`() {
        assertNull(NetworkProxyConfig().authenticator.authenticate(null, proxyAuthResponse()))
        val noUser = NetworkProxyConfig().apply { settings = ProxySettings(ProxyType.HTTP, "p.test", 3128) }
        assertNull(noUser.authenticator.authenticate(null, proxyAuthResponse()))
        val socks = NetworkProxyConfig().apply { settings = ProxySettings(ProxyType.SOCKS, "p.test", 1080, "bob", "pw") }
        assertNull(socks.authenticator.authenticate(null, proxyAuthResponse()))
    }

    @Test
    fun `prefetch window shrinks when saving resources`() {
        assertEquals(PREFETCH_WINDOW, prefetchWindowFor(unmetered = true, savingResources = false))
        assertEquals(PREFETCH_WINDOW_METERED, prefetchWindowFor(unmetered = false, savingResources = false))
        assertEquals(PREFETCH_WINDOW_MINIMAL, prefetchWindowFor(unmetered = true, savingResources = true))
        assertEquals(PREFETCH_WINDOW_MINIMAL, prefetchWindowFor(unmetered = false, savingResources = true))
    }
}
