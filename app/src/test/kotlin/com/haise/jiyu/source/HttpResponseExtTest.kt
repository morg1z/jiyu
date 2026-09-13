package com.haise.jiyu.source

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Ověřuje [bodyOrThrow] - dřív desítky zdrojů braly tělo odpovědi bez ohledu na její stav,
 * takže chybová stránka (403/404/503) doputovala až do Jsoupu, selektory na ní nic nenašly
 * a uživatel dostal prázdný seznam. Mrtvý/blokující zdroj tak vypadal stejně jako
 * "tady prostě nic není".
 */
class HttpResponseExtTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun fetch(): String = runBlocking {
        val client = redirectingClient(server)
        val url = server.url("/manga/foo").toString()
        client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { it.bodyOrThrow(url) }
    }

    @Test
    fun `successful response returns the body unchanged`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>obsah</html>"))
        assertEquals("<html>obsah</html>", fetch())
    }

    @Test
    fun `blocked source throws instead of handing its error page to the parser`() {
        // Presne ten pripad, ktery driv tise propadl: Cloudflare/WAF vrati 403 s HTML
        // strankou, ta se naparsuje, nic se v ni nenajde -> "zadne vysledky".
        server.enqueue(MockResponse().setResponseCode(403).setBody("<html>Sorry, you have been blocked</html>"))
        // IOException, ne IllegalStateException - RetryInterceptor (AppModule.kt) chyta jen
        // IOException, IllegalStateException by mu tenhle nalez znovu obesel (nahlaseno v auditu).
        val e = assertThrows(java.io.IOException::class.java) { fetch() }
        assertTrue("hlaska musi nest stavovy kod, jinak je nalez k nicemu", e.message!!.contains("403"))
    }

    @Test
    fun `dead source returning 404 throws`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("<html>Not Found</html>"))
        assertThrows(java.io.IOException::class.java) { fetch() }
    }

    @Test
    fun `server error throws`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("maintenance"))
        assertThrows(java.io.IOException::class.java) { fetch() }
    }

    @Test
    fun `empty body on a successful response is still valid, not an error`() {
        // 200 s prazdnym telem je legitimni (napr. prazdna stranka strankovani) - tohle
        // se chybou stat NESMI, jinak by se z konce seznamu stala hlasena chyba.
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        assertEquals("", fetch())
    }
}
