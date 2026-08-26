package com.haise.jiyu.source.interceptor

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareBlockedDetectionTest {

    private fun response(code: Int, headers: Map<String, String> = emptyMap(), body: String = ""): Response {
        val builder = Response.Builder()
            .request(Request.Builder().url("https://example.com/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .body(body.toResponseBody("text/html".toMediaTypeOrNull()))
        headers.forEach { (name, value) -> builder.addHeader(name, value) }
        return builder.build()
    }

    @Test
    fun `modern cf-mitigated challenge header is detected regardless of body`() {
        val resp = response(403, mapOf("cf-mitigated" to "challenge", "Server" to "cloudflare"), body = "<title>Just a moment...</title>")
        assertTrue(isCloudflareBlocked(resp))
    }

    @Test
    fun `legacy body markers still work as fallback when header is absent`() {
        val resp = response(403, mapOf("Server" to "cloudflare"), body = "<div id='cf-browser-verification'>...</div>")
        assertTrue(isCloudflareBlocked(resp))
    }

    @Test
    fun `plain 403 from a cloudflare-fronted server without any challenge markers still flagged by legacy fallback`() {
        // Zdokumentovane chovani (ne nutne idealni) - zustava beze zmeny, viz komentar
        // u isCloudflareBlocked v CloudflareInterceptor.kt.
        val resp = response(403, mapOf("Server" to "cloudflare"), body = "Forbidden")
        assertTrue(isCloudflareBlocked(resp))
    }

    @Test
    fun `successful response is never flagged even with cloudflare server header`() {
        val resp = response(200, mapOf("Server" to "cloudflare", "cf-mitigated" to "challenge"), body = "<html>real content</html>")
        assertFalse(isCloudflareBlocked(resp))
    }

    @Test
    fun `403 from a non-cloudflare server without markers is not flagged`() {
        val resp = response(403, mapOf("Server" to "nginx"), body = "Forbidden")
        assertFalse(isCloudflareBlocked(resp))
    }

    @Test
    fun `wordfence-style hard block page is detected as unsolvable`() {
        val resp = response(
            403,
            body = """
                <h1>Sorry, you have been blocked</h1>
                <p>You are unable to access mfcdn.net</p>
                <h2>Why have I been blocked?</h2>
                <p>This website is using a security service to protect itself from online attacks.</p>
            """.trimIndent(),
        )
        assertTrue(isUnsolvableWafBlock(resp))
    }

    @Test
    fun `solvable managed challenge is not flagged as unsolvable`() {
        val resp = response(403, mapOf("cf-mitigated" to "challenge"), body = "<title>Just a moment...</title>")
        assertFalse(isUnsolvableWafBlock(resp))
    }

    @Test
    fun `503 hard block body is not flagged - only 403 carries this signature`() {
        val resp = response(503, body = "you have been blocked - security service")
        assertFalse(isUnsolvableWafBlock(resp))
    }
}
