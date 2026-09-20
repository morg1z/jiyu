package com.haise.jiyu.source.mangathemesia

import com.haise.jiyu.source.SourceHttp
import com.haise.jiyu.source.interceptor.AndroidSharedCookieStore
import com.haise.jiyu.source.interceptor.SharedCookieStore
import com.haise.jiyu.util.JsRunner
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException

/**
 * Lehká JS výzva "NetShield" na některých webech (tělo stránky je jen skript, který spočítá cookie a stránku
 * načte znovu): web dodá vlastní `min.js` a inline skript, jenž vypočte cookie. Appka udělá totéž, co by udělal
 * prohlížeč - skript spustí (viz [JsRunner]), cookie uloží do sdíleného úložiště cookies a požadavek se zopakuje.
 *
 * Nic se neobchází: vypočítá se přesně ta cookie, kterou by si web vyžádal od každého návštěvníka.
 */
class NetShieldSolver(
    private val client: OkHttpClient,
    private val js: JsRunner,
    private val cookies: SharedCookieStore = AndroidSharedCookieStore,
) {
    fun isChallenge(html: String): Boolean = html.contains("slowAES.decrypt")

    /** `true`, když se cookie podařilo získat a uložit (pak má smysl zopakovat původní požadavek). */
    suspend fun solve(baseUrl: String, html: String): Boolean {
        val root = baseUrl.trimEnd('/')
        val inline = Jsoup.parse(html).select("script").firstOrNull { it.html().contains("slowAES.decrypt") }?.html()
            ?: return false
        val library = fetch("$root/min.js") ?: return false
        // `document.cookie = X` -> `return X`: skript místo zápisu cookie vrátí její hodnotu.
        val script = library + "\n\n" + inline.replace(Regex("""document\.cookie\s*="""), "return ")
        val cookie = js.evaluate(root, script, timeoutMs = 10_000) ?: return false
        if (!cookie.contains('=')) return false
        cookies.store(root, cookie)
        return true
    }

    private fun fetch(url: String): String? = try {
        client.newCall(Request.Builder().url(url).header("User-Agent", SourceHttp.USER_AGENT_DESKTOP).build()).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        }
    } catch (_: IOException) {
        null
    }
}
