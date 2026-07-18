package com.haise.jiyu.source.novelbuddy

import com.haise.jiyu.source.redirectingClient
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NovelBuddySourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: NovelBuddySource

    private val searchJson = """
        {"success":true,"data":{"items":[
            {"id":"abc123","url":"/test-series","name":"Test Series","cover":"https://cdn.example.com/test.jpg",
             "summary":"A summary.","status":"ongoing","genres":[{"name":"Action","slug":"action"}]}
        ]}}
    """.trimIndent()

    private val detailJson = """
        {"success":true,"data":{"title":{"id":"abc123","url":"/test-series","name":"Test Series",
            "cover":"https://cdn.example.com/test.jpg","summary":"<p>A summary.</p>","status":"completed",
            "genres":[{"name":"Action","slug":"action"},{"name":"Adventure","slug":"adventure"}]}}}
    """.trimIndent()

    private val chaptersJson = """
        {"success":true,"data":{"chapters":[
            {"id":"c2","url":"/test-series/chapter-2","name":"Chapter 2","number":2,"updated_at":"2026-07-18T10:00:00.000Z"},
            {"id":"c1","url":"/test-series/chapter-1","name":"Chapter 1","number":1,"updated_at":"2026-01-10T10:00:00.000Z"}
        ]}}
    """.trimIndent()

    private val pageHtml = """
        <html><body>
        <div class="novel-tts-content"><p class="mt-1.5 text-[14px]">Chapter 2</p>
        <p>First paragraph.</p><p>Second paragraph.</p>
        <p class="mt-1 text-[12px] text-fg-subtle">One tap helps us surface trending chapters</p>
        </div>
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/titles/search") -> MockResponse().setBody(searchJson)
                    path == "/titles/abc123" -> MockResponse().setBody(detailJson)
                    path == "/titles/abc123/chapters" -> MockResponse().setBody(chaptersJson)
                    path == "/test-series/chapter-2" -> MockResponse().setBody(pageHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = NovelBuddySource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular parses title, cover and encodes title id in url`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("Test Series", result[0].title)
        assertEquals("/test-series::abc123", result[0].url)
        assertEquals("Ongoing", result[0].status)
    }

    @Test
    fun `full flow parses details, chapters newest-first and strips boilerplate paragraphs`() = runTest {
        val manga = source.getPopular(1).first()
        val details = source.getMangaDetails(manga)
        assertEquals("A summary.", details.description)
        assertEquals("Completed", details.status)
        assertEquals(listOf("Action", "Adventure"), details.genres)

        val chapters = source.getChapterList(manga)
        assertEquals(2, chapters.size)
        assertEquals(2f, chapters[0].chapterNumber)
        assertEquals(1f, chapters[1].chapterNumber)

        val pages = source.getPageList(chapters[0])
        assertEquals(1, pages.size)
        assertEquals("First paragraph.\n\nSecond paragraph.", pages[0].url)
        assertEquals("novel://text", pages[0].imageUrl)
    }

    @Test
    fun `malformed JSON returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not json")
        }
        server.start()
        val emptySource = NovelBuddySource(redirectingClient(server))
        assertTrue(emptySource.getPopular(1).isEmpty())
    }
}
