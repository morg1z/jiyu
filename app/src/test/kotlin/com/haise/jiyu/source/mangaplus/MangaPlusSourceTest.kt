package com.haise.jiyu.source.mangaplus

import com.haise.jiyu.source.redirectingClient
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MANGA Plus API vraci raw protobuf (viz MangaPlusProto.kt). Kazdy pozadavek
 * (krome /register) vyzaduje predchozi registraci zarizeni a "secret" query
 * parametr - viz komentar v MangaPlusSource.kt. Testy si rucne zakoduji
 * minimalni platne zpravy (pole 1=success, 2=registerationData/1=deviceSecret,
 * 35=SearchView/3=AllTitlesGroup, 8=TitleDetailView s kapitolami na poli 38
 * (chapterListV2), 10=MangaViewer).
 */
class MangaPlusSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MangaPlusSource

    private fun encodeVarint(value: Long): ByteArray {
        val out = mutableListOf<Byte>()
        var v = value
        while (true) {
            var b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            out.add(b.toByte())
            if (v == 0L) break
        }
        return out.toByteArray()
    }

    private fun tag(field: Int, wireType: Int) = encodeVarint(((field.toLong() shl 3) or wireType.toLong()))
    private fun varintField(field: Int, value: Long) = tag(field, 0) + encodeVarint(value)
    private fun stringField(field: Int, value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return tag(field, 2) + encodeVarint(bytes.size.toLong()) + bytes
    }
    private fun bytesField(field: Int, value: ByteArray): ByteArray {
        return tag(field, 2) + encodeVarint(value.size.toLong()) + value
    }

    private val registerBytes = run {
        val registerationData = stringField(1, "test-secret-123")
        val success = bytesField(2, registerationData)
        bytesField(1, success)
    }

    private val allTitlesBytes = run {
        val title = varintField(1, 100L) + stringField(2, "One Piece") + stringField(4, "https://cdn.example.com/op.jpg")
        val group = bytesField(2, title)
        val searchView = bytesField(3, group)
        val success = bytesField(35, searchView)
        bytesField(1, success)
    }

    private val titleDetailBytes = run {
        val titleMsg = varintField(1, 100L) + stringField(2, "One Piece") + stringField(4, "https://cdn.example.com/op.jpg")
        val chapterMsg = varintField(2, 1092L) + stringField(3, "Chapter 1092") + varintField(6, 1750000000L)
        val view = bytesField(1, titleMsg) + bytesField(38, chapterMsg)
        val success = bytesField(8, view)
        bytesField(1, success)
    }

    private val mangaViewerBytes = run {
        val pageMsg = stringField(1, "https://cdn.example.com/op/1092/01.jpg")
        val mangaPage = bytesField(1, pageMsg)
        val viewer = bytesField(1, mangaPage)
        val success = bytesField(10, viewer)
        bytesField(1, success)
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/register") -> MockResponse().setBody(Buffer().write(registerBytes))
                    path.startsWith("/api/title_list/all_v3") -> MockResponse().setBody(Buffer().write(allTitlesBytes))
                    path.startsWith("/api/title_detailV3") -> MockResponse().setBody(Buffer().write(titleDetailBytes))
                    path.startsWith("/api/manga_viewer") -> MockResponse().setBody(Buffer().write(mangaViewerBytes))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        source = MangaPlusSource(redirectingClient(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getPopular decodes titleId, name and cover from protobuf`() = runTest {
        val result = source.getPopular(1)
        assertEquals(1, result.size)
        assertEquals("One Piece", result[0].title)
        assertEquals("100", result[0].url)
        assertEquals("https://cdn.example.com/op.jpg", result[0].coverUrl)
    }

    @Test
    fun `getChapterList decodes chapter id, name and timestamp`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        assertEquals(1, chapters.size)
        assertEquals("Chapter 1092", chapters[0].name)
        assertEquals(1750000000000L, chapters[0].dateUpload)
    }

    @Test
    fun `getPageList decodes image URL from MangaViewer`() = runTest {
        val manga = source.getPopular(1).first()
        val chapters = source.getChapterList(manga)
        val pages = source.getPageList(chapters[0])
        assertEquals(1, pages.size)
        assertEquals("https://cdn.example.com/op/1092/01.jpg", pages[0].imageUrl)
    }

    @Test
    fun `HTML error response instead of protobuf returns empty list, not an exception`() = runTest {
        server.shutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html>error page</html>")
        }
        server.start()
        val failingSource = MangaPlusSource(redirectingClient(server))
        assertTrue(failingSource.getPopular(1).isEmpty())
    }
}
