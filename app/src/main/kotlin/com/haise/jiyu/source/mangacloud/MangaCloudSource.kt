package com.haise.jiyu.source.mangacloud

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ziska relacni cookie pro api.mangacloud.org. Produkcni implementace
 * [WebViewMangaCloudSession] tise na pozadi nacte homepage v WebView a
 * pockej na dobehnuti Turnstile flow; v testech se nahrazuje fejkem,
 * aby se nemuselo cekat na skutecny WebView.
 */
interface MangaCloudSession {
    fun getCookie(): String?
}

/**
 * Produkcni implementace [MangaCloudSession]. MangaCloud (mangacloud.org)
 * je Vite/React SPA, cele API bezi na api.mangacloud.org. Kazdy pozadavek
 * na API vyzaduje relacni cookie, kterou frontend ziska tak, ze na
 * homepage tise (bez interakce) vyresi neviditelny Cloudflare Turnstile a
 * vysledny token posle na POST /auth/alive s `credentials: include` -
 * odpoved nastavi HttpOnly cookie platnou pro cely *.mangacloud.org
 * (tedy i pro api. subdoménu). Bez teto cookie API vraci 409/401 i na
 * cistá GET volani bez auth.
 */
class WebViewMangaCloudSession(private val context: Context) : MangaCloudSession {

    private val webBase = "https://mangacloud.org"
    private val apiBase = "https://api.mangacloud.org"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cachedCookie = AtomicReference<String?>(null)
    private val cachedExpiresAt = AtomicLong(0)
    private val sessionTtlMs = TimeUnit.MINUTES.toMillis(20)

    override fun getCookie(): String? {
        if (cachedCookie.get() != null && System.currentTimeMillis() < cachedExpiresAt.get()) {
            return cachedCookie.get()
        }
        val cookie = bootstrapViaWebView() ?: return null
        cachedCookie.set(cookie)
        cachedExpiresAt.set(System.currentTimeMillis() + sessionTtlMs)
        return cookie
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun bootstrapViaWebView(): String? {
        val latch = CountDownLatch(1)
        mainHandler.post {
            val webView = WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        // Turnstile flow ve strance ma nahodnou prodlevu 10-20s
                        // pred POST /auth/alive - pockame dost dlouho, aby stihl
                        // dobehnout i s rezervou na sitovy round-trip.
                        postDelayed({ latch.countDown() }, 24_000L)
                    }
                }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                loadUrl(webBase)
            }
            mainHandler.postDelayed({
                if (latch.count > 0) {
                    webView.destroy()
                    latch.countDown()
                }
            }, 28_000L)
        }
        latch.await(30, TimeUnit.SECONDS)
        val cookies = CookieManager.getInstance().getCookie(apiBase)
        return cookies?.takeIf { it.isNotBlank() }
    }
}

/**
 * MangaCloud (mangacloud.org) - viz [WebViewMangaCloudSession] pro detaily
 * o relacni cookie, kterou API vyzaduje na kazdy request.
 */
@Singleton
class MangaCloudSource @Inject constructor(
    private val client: OkHttpClient,
    private val session: MangaCloudSession,
) : MangaSource {

    override val id = "mangacloud"
    override val name = "MangaCloud"
    override val homepageUrl get() = "https://mangacloud.org"

    private val apiBase = "https://api.mangacloud.org"
    private val cdnBase = "https://pika.mangacloud.org"

    private fun apiGet(path: String): String {
        val cookie = session.getCookie()
        val builder = Request.Builder().url("$apiBase$path")
        if (cookie != null) builder.header("Cookie", cookie)
        return client.newCall(builder.build()).execute().use { it.body?.string() ?: "" }
    }

    private fun apiPost(path: String, jsonBody: JSONObject): String {
        val cookie = session.getCookie()
        val body = jsonBody.toString().toRequestBody("application/json".toMediaType())
        val builder = Request.Builder().url("$apiBase$path").post(body)
        if (cookie != null) builder.header("Cookie", cookie)
        return client.newCall(builder.build()).execute().use { it.body?.string() ?: "" }
    }

    private fun coverUrl(comicId: String, cover: JSONObject?): String? {
        if (cover == null) return null
        val coverId = cover.optString("id").ifBlank { return null }
        val format = cover.optString("f").ifBlank { return null }
        return "$cdnBase/$comicId/$coverId.$format"
    }

    private fun mangaFromJson(json: JSONObject): SManga? {
        val mangaId = json.optString("id").ifBlank { return null }
        val title = json.optString("title").ifBlank { return null }
        return SManga(
            sourceId = id,
            url = "/$mangaId",
            title = title,
            coverUrl = coverUrl(mangaId, json.optJSONObject("cover")),
        )
    }

    override suspend fun getPopular(page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (page == 1) {
                val json = JSONObject(apiGet("/comic-popular-view/today"))
                val arr = json.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
                (0 until arr.length()).mapNotNull { mangaFromJson(arr.getJSONObject(it)) }
            } else {
                val body = JSONObject().put("page", page).put("sort", "chapter_date-DESC")
                val json = JSONObject(apiPost("/comic/library", body))
                val arr = json.optJSONArray("data") ?: JSONArray()
                (0 until arr.length()).mapNotNull { mangaFromJson(arr.getJSONObject(it)) }
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("terms", query)
            val json = JSONObject(apiPost("/search", body))
            val arr = json.optJSONArray("data") ?: JSONArray()
            (0 until arr.length()).mapNotNull { mangaFromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            val mangaId = manga.url.removePrefix("/")
            val json = JSONObject(apiGet("/comic/$mangaId")).getJSONObject("data")
            val genres = json.optJSONArray("tags")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it).optString("name") }
            } ?: emptyList()

            manga.copy(
                title = json.optString("title").ifBlank { manga.title },
                coverUrl = coverUrl(mangaId, json.optJSONObject("cover")) ?: manga.coverUrl,
                description = json.optString("description").ifBlank { null },
                author = json.optString("authors").ifBlank { null },
                genres = genres,
                status = json.optString("status").ifBlank { null },
            )
        } catch (_: Exception) { manga }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val mangaId = manga.url.removePrefix("/")
            val json = JSONObject(apiGet("/comic/$mangaId")).getJSONObject("data")
            val arr = json.optJSONArray("chapters") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                val chapterId = c.getString("id")
                val num = c.optDouble("number", 0.0).toFloat()
                val chName = c.optString("name").ifBlank { null }
                SChapter(
                    sourceId = id,
                    mangaUrl = manga.url,
                    url = "/$mangaId/$chapterId",
                    name = chName ?: "Chapter ${if (num == num.toInt().toFloat()) num.toInt().toString() else num.toString()}",
                    chapterNumber = num,
                    dateUpload = parseIso(c.optString("created_date")),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun parseIso(text: String?): Long = try {
        java.time.Instant.parse(text).toEpochMilli()
    } catch (_: Exception) {
        System.currentTimeMillis()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        try {
            val (mangaId, chapterId) = chapter.url.removePrefix("/").split("/", limit = 2)
                .let { it[0] to it[1] }
            val json = JSONObject(apiGet("/chapters/$chapterId")).getJSONObject("data")
            val images = json.optJSONArray("images") ?: JSONArray()
            (0 until images.length()).mapNotNull { i ->
                val img = images.getJSONObject(i)
                val imgId = img.optString("id").ifBlank { return@mapNotNull null }
                val format = img.optString("f").ifBlank { return@mapNotNull null }
                val url = "$cdnBase/$mangaId/$chapterId/$imgId.$format"
                Page(i, url, url)
            }
        } catch (_: Exception) { emptyList() }
    }
}
