package com.haise.jiyu.source.comments

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WpDiscuzCommentsTest {

    private fun doc(html: String) = Jsoup.parse(html)

    @Test
    fun `parses a single comment with all fields`() {
        val html = """
            <div class="wpd-comment-wrap">
              <div class="wpd-comment-left"><div class="wpd-avatar"><img src="https://example.com/avatar1.jpg"></div></div>
              <div class="wpd-comment-right" id="comment-100">
                <div class="wpd-comment-header">
                  <div class="wpd-comment-author">Alice</div>
                  <div class="wpd-comment-date" title="11.08.2026 00:22">17 days ago</div>
                </div>
                <div class="wpd-comment-text"><p>Great chapter!</p></div>
              </div>
            </div>
        """.trimIndent()

        val result = parseWpDiscuzComments(doc(html))

        assertEquals(1, result.size)
        val c = result[0]
        assertEquals("100", c.id)
        assertEquals("Alice", c.author)
        assertEquals("Great chapter!", c.content)
        assertEquals("https://example.com/avatar1.jpg", c.avatarUrl)
        assertTrue(c.createdAt > 0L)
    }

    @Test
    fun `parses multiple comments`() {
        val html = """
            <div class="wpd-comment-wrap">
              <div class="wpd-comment-right" id="comment-1">
                <div class="wpd-comment-author">Bob</div>
                <div class="wpd-comment-text"><p>First</p></div>
              </div>
            </div>
            <div class="wpd-comment-wrap">
              <div class="wpd-comment-right" id="comment-2">
                <div class="wpd-comment-author">Carol</div>
                <div class="wpd-comment-text"><p>Second</p></div>
              </div>
            </div>
        """.trimIndent()

        val result = parseWpDiscuzComments(doc(html))

        assertEquals(2, result.size)
        assertEquals("Bob", result[0].author)
        assertEquals("Carol", result[1].author)
    }

    @Test
    fun `empty document returns empty list`() {
        assertEquals(emptyList<ChapterComment>(), parseWpDiscuzComments(doc("<html><body></body></html>")))
    }

    @Test
    fun `comment without author is skipped`() {
        val html = """
            <div class="wpd-comment-wrap">
              <div class="wpd-comment-right" id="comment-1">
                <div class="wpd-comment-text"><p>No author here</p></div>
              </div>
            </div>
        """.trimIndent()

        assertEquals(emptyList<ChapterComment>(), parseWpDiscuzComments(doc(html)))
    }

    @Test
    fun `comment without date title defaults createdAt to zero but is still included`() {
        val html = """
            <div class="wpd-comment-wrap">
              <div class="wpd-comment-right" id="comment-1">
                <div class="wpd-comment-author">Dan</div>
                <div class="wpd-comment-text"><p>No date</p></div>
              </div>
            </div>
        """.trimIndent()

        val result = parseWpDiscuzComments(doc(html))

        assertEquals(1, result.size)
        assertEquals(0L, result[0].createdAt)
        assertNull(result[0].avatarUrl)
    }

    @Test
    fun `comment with blank content is skipped`() {
        val html = """
            <div class="wpd-comment-wrap">
              <div class="wpd-comment-right" id="comment-1">
                <div class="wpd-comment-author">Henry</div>
                <div class="wpd-comment-text"><p></p></div>
              </div>
            </div>
        """.trimIndent()

        assertEquals(emptyList<ChapterComment>(), parseWpDiscuzComments(doc(html)))
    }

    @Test
    fun `nested reply inside parent comment does not pollute parent content and appears as its own entry`() {
        val html = """
            <div class="wpd-comment-wrap">
              <div class="wpd-comment-right" id="comment-1">
                <div class="wpd-comment-author">Parent</div>
                <div class="wpd-comment-text"><p>Parent text</p></div>
                <div class="wpd-comment-wrap">
                  <div class="wpd-comment-right" id="comment-2">
                    <div class="wpd-comment-author">Child</div>
                    <div class="wpd-comment-text"><p>Child reply text</p></div>
                  </div>
                </div>
              </div>
            </div>
        """.trimIndent()

        val result = parseWpDiscuzComments(doc(html))

        assertEquals(2, result.size)
        val parent = result.first { it.author == "Parent" }
        assertEquals("Parent text", parent.content)
        val child = result.first { it.author == "Child" }
        assertEquals("Child reply text", child.content)
    }
}
