package com.haise.jiyu.source.comments

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeWpCommentsTest {

    private fun doc(html: String) = Jsoup.parse(html)

    @Test
    fun `parses a single comment with all fields`() {
        val html = """
            <li class="comment" id="comment-130316">
              <article class="comment-body">
                <div class="comment-avatar"><img src="https://example.com/avatar.jpg"></div>
                <div class="comment-author">ana</div>
                <div class="comment-content"><p>Nice one</p></div>
                <div class="comment-metadata"><a href="#">June 24, 2026 at 6:12 am</a></div>
              </article>
            </li>
        """.trimIndent()

        val result = parseNativeWpComments(doc(html))

        assertEquals(1, result.size)
        val c = result[0]
        assertEquals("130316", c.id)
        assertEquals("ana", c.author)
        assertEquals("Nice one", c.content)
        assertEquals("https://example.com/avatar.jpg", c.avatarUrl)
        assertTrue(c.createdAt > 0L)
    }

    @Test
    fun `parses multiple comments`() {
        val html = """
            <li class="comment" id="comment-1">
              <article class="comment-body">
                <div class="comment-author">Eve</div>
                <div class="comment-content"><p>First</p></div>
              </article>
            </li>
            <li class="comment" id="comment-2">
              <article class="comment-body">
                <div class="comment-author">Frank</div>
                <div class="comment-content"><p>Second</p></div>
              </article>
            </li>
        """.trimIndent()

        val result = parseNativeWpComments(doc(html))

        assertEquals(2, result.size)
        assertEquals("Eve", result[0].author)
        assertEquals("Frank", result[1].author)
    }

    @Test
    fun `empty document returns empty list`() {
        assertEquals(emptyList<ChapterComment>(), parseNativeWpComments(doc("<html><body></body></html>")))
    }

    @Test
    fun `comment without author is skipped`() {
        val html = """
            <li class="comment" id="comment-1">
              <article class="comment-body">
                <div class="comment-content"><p>No author here</p></div>
              </article>
            </li>
        """.trimIndent()

        assertEquals(emptyList<ChapterComment>(), parseNativeWpComments(doc(html)))
    }

    @Test
    fun `comment without metadata date defaults createdAt to zero but is still included`() {
        val html = """
            <li class="comment" id="comment-1">
              <article class="comment-body">
                <div class="comment-author">Gina</div>
                <div class="comment-content"><p>No date</p></div>
              </article>
            </li>
        """.trimIndent()

        val result = parseNativeWpComments(doc(html))

        assertEquals(1, result.size)
        assertEquals(0L, result[0].createdAt)
        assertNull(result[0].avatarUrl)
    }
}
