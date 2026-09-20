package com.haise.jiyu.source.interceptor

import com.haise.jiyu.settings.decodeDomainOverrides
import com.haise.jiyu.settings.encodeDomainOverrides
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DomainOverridesTest {

    private fun clientRecording(overrides: DomainOverrides, seen: MutableList<Request>) = OkHttpClient.Builder()
        .addInterceptor(DomainOverrideInterceptor(overrides))
        .addInterceptor(Interceptor { chain ->
            seen += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("".toResponseBody()).build()
        })
        .build()

    @Test
    fun `request host is rewritten, path query and other hosts stay`() {
        val overrides = DomainOverrides().apply { hostMap = mapOf("old.example" to "new.example") }
        val seen = mutableListOf<Request>()
        val client = clientRecording(overrides, seen)

        client.newCall(Request.Builder().url("https://old.example/manga/x/?page=2").build()).execute().close()
        client.newCall(Request.Builder().url("https://www.old.example/a").build()).execute().close()
        client.newCall(Request.Builder().url("https://cdn.other.test/img.jpg").build()).execute().close()

        assertEquals("https://new.example/manga/x/?page=2", seen[0].url.toString())
        assertEquals("https://new.example/a", seen[1].url.toString())
        assertEquals("https://cdn.other.test/img.jpg", seen[2].url.toString())
    }

    @Test
    fun `referer and origin follow the new host`() {
        val overrides = DomainOverrides().apply { hostMap = mapOf("old.example" to "new.example") }
        val seen = mutableListOf<Request>()
        clientRecording(overrides, seen).newCall(
            Request.Builder().url("https://old.example/x")
                .header("Referer", "https://old.example/").header("Origin", "https://old.example").build(),
        ).execute().close()

        assertEquals("https://new.example/", seen[0].header("Referer"))
        assertEquals("https://new.example", seen[0].header("Origin"))
    }

    @Test
    fun `empty map leaves requests untouched`() {
        val seen = mutableListOf<Request>()
        clientRecording(DomainOverrides(), seen).newCall(Request.Builder().url("https://old.example/x").build()).execute().close()
        assertEquals("https://old.example/x", seen[0].url.toString())
    }

    @Test
    fun `input is normalized to a bare host or rejected`() {
        assertEquals("new-site.com", DomainOverrides.normalizeInput("new-site.com"))
        assertEquals("new-site.com", DomainOverrides.normalizeInput("  https://New-Site.com/manga/?x=1 "))
        assertEquals("sub.new-site.co.uk", DomainOverrides.normalizeInput("http://sub.new-site.co.uk"))
        assertEquals("new-site.com", DomainOverrides.normalizeInput("new-site.com/path"))
        assertNull(DomainOverrides.normalizeInput(""))
        assertNull(DomainOverrides.normalizeInput("localhost"))
        assertNull(DomainOverrides.normalizeInput("has space.com"))
        assertNull(DomainOverrides.normalizeInput("192.168.1.10"))
        assertNull(DomainOverrides.normalizeInput("-bad-.com"))
        assertNull(DomainOverrides.normalizeInput("new-site.com:8080"))
    }

    @Test
    fun `stored overrides round trip and skip garbage lines`() {
        val map = mapOf("mangadex" to "mirror.example", "madara:1" to "x.example")
        assertEquals(map, decodeDomainOverrides(encodeDomainOverrides(map)))
        assertEquals(emptyMap<String, String>(), decodeDomainOverrides(null))
        assertEquals(mapOf("a" to "b.example"), decodeDomainOverrides("garbage\n\tno-id\nno-host\t\na\tb.example"))
    }
}
