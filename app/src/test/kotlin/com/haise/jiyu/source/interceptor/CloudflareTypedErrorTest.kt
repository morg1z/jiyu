package com.haise.jiyu.source.interceptor

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.haise.jiyu.di.AppModule
import com.haise.jiyu.util.CloudflareBlockedException
import com.haise.jiyu.util.CloudflareProtectedException
import com.haise.jiyu.util.NetworkMonitor
import com.haise.jiyu.util.NonRetryable
import com.haise.jiyu.util.toFriendlyMessage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class CloudflareTypedErrorTest {

    private fun interceptor(): CloudflareInterceptor {
        val context = mockk<Context>(relaxed = true)
        val dataStore = mockk<DataStore<Preferences>>()
        every { dataStore.data } returns flowOf(emptyPreferences())
        return CloudflareInterceptor(context, dataStore)
    }

    private fun response(request: Request, code: Int, body: String, vararg headers: Pair<String, String>) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .body(body.toResponseBody()).build()

    /** Falešný řetěz, který pokaždé vrátí zadanou odpověď; volání je "zrušené", takže se řešení výzvy hned vzdá. */
    private fun chainReturning(code: Int, body: String, vararg headers: Pair<String, String>): Pair<Interceptor.Chain, () -> Int> {
        val request = Request.Builder().url("https://protected.test/manga/").build()
        val call = mockk<Call>()
        every { call.isCanceled() } returns true
        var proceeded = 0
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.call() } returns call
        every { chain.proceed(any()) } answers { proceeded++; response(request, code, body, *headers) }
        return chain to { proceeded }
    }

    @Test
    fun `an unsolved challenge throws a typed error instead of returning the blocked page`() {
        val cf = interceptor()
        val (chain, proceeded) = chainReturning(403, "<html>Just a moment</html>", "cf-mitigated" to "challenge")
        val e = assertThrows(CloudflareProtectedException::class.java) { cf.intercept(chain) }
        assertEquals("protected.test", e.host)
        assertTrue(e is NonRetryable)
        assertEquals("žádný druhý požadavek na blokovanou stránku", 1, proceeded())
    }

    @Test
    fun `in noSolve mode a challenge fails fast and never enters cooldown`() = kotlinx.coroutines.runBlocking {
        val cf = interceptor()
        val (chain, proceeded) = chainReturning(403, "<html>Just a moment</html>", "cf-mitigated" to "challenge")
        InteractiveChallengePolicy.noSolve {
            assertThrows(CloudflareProtectedException::class.java) { cf.intercept(chain) }
        }
        assertEquals(1, proceeded())
        // Po hromadném hledání smí pozdější ruční procházení tohoto webu dostat výzvu - cooldown se nezapsal.
        val (second, secondProceeded) = chainReturning(200, "<html>ok</html>")
        assertEquals(200, cf.intercept(second).use { it.code })
        assertEquals(1, secondProceeded())
    }

    @Test
    fun `a hard waf block is reported as blocked, not solvable`() {
        val cf = interceptor()
        val (chain, _) = chainReturning(
            403, "<html>Sorry, you have been blocked by the security service</html>", "Server" to "cloudflare",
        )
        assertThrows(CloudflareBlockedException::class.java) { cf.intercept(chain) }
    }

    @Test
    fun `after a failure the host is in cooldown and fails fast without solving again`() {
        val cf = interceptor()
        val (first, _) = chainReturning(403, "x", "cf-mitigated" to "challenge")
        // Zrušené volání: řešení se vzdá, ale selhání se do cooldownu nezapisuje jen když je interaktivní výzva
        // potlačená - tady je povolená, takže cooldown vznikne.
        assertThrows(CloudflareProtectedException::class.java) { cf.intercept(first) }
        val (second, proceeded) = chainReturning(403, "x", "cf-mitigated" to "challenge")
        assertThrows(CloudflareProtectedException::class.java) { cf.intercept(second) }
        assertEquals(1, proceeded())
    }

    @Test
    fun `a normal response passes through untouched`() {
        val cf = interceptor()
        val (chain, proceeded) = chainReturning(200, "<html>ok</html>")
        assertEquals(200, cf.intercept(chain).use { it.code })
        assertEquals(1, proceeded())
    }

    @Test
    fun `friendly messages for both errors`() {
        assertTrue(CloudflareProtectedException("h", "u").toFriendlyMessage().contains("Vyřešit ověření"))
        assertTrue(CloudflareBlockedException("h", "u").toFriendlyMessage().contains("zablokoval"))
    }

    @Test
    fun `cloudflare runs before throttle and retry in the source client`() {
        val context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns File(System.getProperty("java.io.tmpdir"), "jiyu-order-test-cache")
        val monitor = mockk<NetworkMonitor>(relaxed = true)
        val client = AppModule.provideOkHttpClient(context, interceptor(), DomainOverrides(), monitor, com.haise.jiyu.source.SourceSlowdown(), NetworkProxyConfig())

        val names = client.interceptors.map { it.javaClass.simpleName }
        fun idx(name: String) = names.indexOf(name).also { assertTrue("$name chybí v $names", it >= 0) }
        assertTrue(idx("DomainOverrideInterceptor") < idx("NoNetworkInterceptor"))
        assertTrue(idx("NoNetworkInterceptor") < idx("WebViewCookieInterceptor"))
        assertTrue(idx("WebViewCookieInterceptor") < idx("CloudflareInterceptor"))
        assertTrue(idx("CloudflareInterceptor") < idx("ThrottleInterceptor"))
        assertTrue(idx("ThrottleInterceptor") < idx("RetryInterceptor"))
        assertTrue(idx("RetryInterceptor") < idx("RateLimitInterceptor"))
        assertTrue(client.networkInterceptors.any { it is CacheLimitInterceptor })
        assertEquals(15_000, client.connectTimeoutMillis)
    }
}
