package com.haise.jiyu.source.mangathemesia

import com.haise.jiyu.source.interceptor.SharedCookieStore
import com.haise.jiyu.source.redirectingClient
import com.haise.jiyu.util.JsRunner
import com.haise.jiyu.util.decodeJsResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NetShieldSolverTest {

    private lateinit var server: MockWebServer
    private var minJsCode = 200

    private val challengeHtml = """
        <html><body><script>
        var a = slowAES.decrypt(c, 2, key, iv); document.cookie = "NetShield=" + toHex(a) + "; path=/";
        </script></body></html>
    """.trimIndent()

    private class FakeJs(var result: String?) : JsRunner {
        var baseUrl: String? = null
        var script: String? = null
        override suspend fun evaluate(baseUrl: String, script: String, timeoutMs: Long): String? {
            this.baseUrl = baseUrl; this.script = script
            return result
        }
    }

    private class FakeStore : SharedCookieStore {
        val stored = mutableListOf<Pair<String, String>>()
        override fun cookiesFor(url: String): String? = null
        override fun store(url: String, setCookieHeader: String) { stored += url to setCookieHeader }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                if (request.path == "/min.js") MockResponse().setResponseCode(minJsCode).setBody("var slowAES = {};")
                else MockResponse().setResponseCode(404)
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun solver(js: JsRunner, store: SharedCookieStore) =
        NetShieldSolver(redirectingClient(server), js, store)

    @Test
    fun `the challenge is recognised by its decrypt call`() {
        val s = solver(FakeJs(null), FakeStore())
        assertTrue(s.isChallenge(challengeHtml))
        assertFalse(s.isChallenge("<html><div class=\"bsx\"></div></html>"))
    }

    @Test
    fun `it runs the site script with min js and stores the computed cookie`() = runBlocking {
        val js = FakeJs("NetShield=abc123; expires=Fri, 01 Jan 2100 00:00:00 GMT; path=/")
        val store = FakeStore()
        assertTrue(solver(js, store).solve("https://site.test/", challengeHtml))

        // Skript = min.js + inline skript, kde `document.cookie =` je nahrazeno `return`.
        assertTrue(js.script!!.startsWith("var slowAES = {};"))
        assertTrue(Regex("""return\s+"NetShield=""").containsMatchIn(js.script!!))
        assertFalse(js.script!!.contains("document.cookie"))
        assertEquals("https://site.test", js.baseUrl)
        assertEquals(listOf("https://site.test" to "NetShield=abc123; expires=Fri, 01 Jan 2100 00:00:00 GMT; path=/"), store.stored)
    }

    @Test
    fun `it fails cleanly when the pieces are missing`() = runBlocking {
        val store = FakeStore()
        // bez inline skriptu
        assertFalse(solver(FakeJs("NetShield=x"), store).solve("https://site.test", "<html></html>"))
        // min.js nelze stáhnout
        minJsCode = 404
        assertFalse(solver(FakeJs("NetShield=x"), store).solve("https://site.test", challengeHtml))
        minJsCode = 200
        // skript nevrátí použitelnou cookie
        assertFalse(solver(FakeJs(null), store).solve("https://site.test", challengeHtml))
        assertFalse(solver(FakeJs("no-equals-sign"), store).solve("https://site.test", challengeHtml))
        assertTrue(store.stored.isEmpty())
    }

    @Test
    fun `js results are decoded from their json literal`() {
        assertNull(decodeJsResult(null))
        assertNull(decodeJsResult("null"))
        assertNull(decodeJsResult("undefined"))
        assertNull(decodeJsResult("\"\""))
        assertEquals("a=b; path=/", decodeJsResult("\"a=b; path=/\""))
        assertEquals("line1\nline2", decodeJsResult("\"line1\\nline2\""))
        assertEquals("say \"hi\" \\ x", decodeJsResult("\"say \\\"hi\\\" \\\\ x\""))
        assertEquals("é", decodeJsResult("\"\\u00e9\""))
        assertEquals("42", decodeJsResult("42"))
    }
}
