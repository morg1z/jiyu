package com.haise.jiyu.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlUtilsTest {

    private val base = "https://example.com"

    @Test
    fun `absolute URL of the same site becomes a path, whatever the www or scheme`() {
        assertEquals("/manga/a?x=1", toSourcePath(base, "https://example.com/manga/a?x=1"))
        assertEquals("/manga/a", toSourcePath(base, "https://www.example.com/manga/a"))
        assertEquals("/manga/a", toSourcePath(base, "http://example.com/manga/a"))
        assertEquals("/manga/a", toSourcePath(base, "//example.com/manga/a"))
    }

    @Test
    fun `relative paths and foreign hosts are left alone`() {
        assertEquals("/manga/a", toSourcePath(base, "/manga/a"))
        assertEquals("https://cdn.other.net/x", toSourcePath(base, "https://cdn.other.net/x"))
        assertEquals("https://cdn.other.net/x", toSourcePath(base, "//cdn.other.net/x"))
    }

    @Test
    fun `a bare host URL maps to an empty path like the old removePrefix did`() {
        assertEquals("", toSourcePath(base, "https://example.com"))
    }

    @Test
    fun `request URLs are built from a path, kept when already absolute`() {
        assertEquals("https://example.com/manga/a", resolveSourceUrl(base, "/manga/a"))
        assertEquals("https://other.com/x", resolveSourceUrl(base, "https://other.com/x"))
        assertEquals("https://cdn.x/y", resolveSourceUrl(base, "//cdn.x/y"))
        assertEquals("https://example.com/rel", resolveSourceUrl(base, "rel"))
    }

    @Test
    fun `media URLs are resolved, junk is dropped`() {
        assertEquals("https://cdn.x/1.jpg", absoluteMediaUrl(base, "//cdn.x/1.jpg"))
        assertEquals("https://example.com/img/1.jpg", absoluteMediaUrl("https://example.com/en", "/img/1.jpg"))
        assertEquals("https://cdn.x/1.jpg", absoluteMediaUrl(base, " https://cdn.x/1.jpg "))
        assertNull(absoluteMediaUrl(base, ""))
        assertNull(absoluteMediaUrl(base, null))
        assertNull(absoluteMediaUrl(base, "data:image/png;base64,AAAA"))
        assertNull(absoluteMediaUrl(base, "1.jpg"))
    }
}
