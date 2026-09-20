package com.haise.jiyu.source.interceptor

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * User-Agent, který používá SKUTEČNÝ WebView na tomhle zařízení. Cloudflare (hlavně Turnstile) porovnává hlavičku
 * User-Agent s tím, co o prohlížeči zjistí skript na stránce (verze Chrome, platforma, dotyky, GPU). Pevný řetězec
 * "Chrome 120 na Pixelu 7" na telefonu s novějším WebView tomu odporuje a výzva pak dokola selhává. `cf_clearance`
 * navíc patří konkrétnímu User-Agentu, takže stejný řetězec musí jít i s následnými požadavky OkHttp.
 *
 * Když se UA enginu zjistit nedá, použije se [CloudflareInterceptor.CHROME_UA] (a nezapamatuje se).
 */
object CloudflareUserAgent {
    @Volatile
    private var cached: String? = null

    fun value(
        context: Context,
        engineUserAgent: (Context) -> String? = { runCatching { WebSettings.getDefaultUserAgent(it) }.getOrNull() },
    ): String {
        cached?.let { return it }
        val ua = engineUserAgent(context)?.trim()?.takeIf { it.isNotEmpty() && it.contains("Android") }
            ?: return CloudflareInterceptor.CHROME_UA
        cached = ua
        return ua
    }

    internal fun resetForTest() {
        cached = null
    }
}

/**
 * Pasivní dotaz na stav stránky výzvy (jen čte DOM): `ok` = skutečná stránka, `wait` = výzva ještě běží nebo se
 * stránka načítá, `error` = tvrdá blokace. Tvrdí se z titulku (víc jazyků), viditelných prvků výzvy a neprázdného těla.
 * Změna cookie `cf_clearance` sama o sobě NENÍ důkaz, že je hotovo - může se změnit, zatímco mezistránka ještě běží.
 */
internal const val CF_PAGE_STATE_JS = """
(function() {
    try {
        var title = (document.title || '').toLowerCase();
        if (/attention required|access denied/.test(title)) return 'error';
        if (/just a moment|un instant|einen moment|un momento|um momento|один момент|bir dakika|chwileczk/.test(title)) return 'wait';
        if (document.readyState === 'loading') return 'wait';
        var markers = document.querySelectorAll(
            '#challenge-running, #challenge-stage, #cf-challenge-running, .cf-browser-verification, #cf-please-wait, #turnstile-wrapper'
        );
        for (var i = 0; i < markers.length; i++) {
            var r = markers[i].getBoundingClientRect();
            if (r.width > 0 && r.height > 0) return 'wait';
        }
        var body = document.body;
        if (!body) return 'wait';
        if (body.children.length === 0 && (body.textContent || '').trim() === '') return 'wait';
        return 'ok';
    } catch (e) { return 'wait'; }
})();
"""

/**
 * Rozhoduje, kdy je ověření opravdu hotové. Cookie `cf_clearance` je nutná podmínka; hotovo je až když stránka
 * [requiredStable]× po sobě odpoví `ok`. Když stránka `ok` nikdy neřekne (netypická stránka, třeba čistý JSON),
 * uznává se výsledek po [maxCookieOnlyPolls] průzkumech jen podle cookie, aby se uživatel nezasekl.
 */
class SolveTracker(
    private val requiredStable: Int = 2,
    private val maxCookieOnlyPolls: Int = 12,
) {
    private var stable = 0
    private var cookieOnly = 0

    /** [pageState] = výsledek [CF_PAGE_STATE_JS] (`null` = nezjištěno). Vrací `true`, když je hotovo. */
    fun onPoll(hasClearance: Boolean, pageState: String?): Boolean {
        if (!hasClearance) {
            stable = 0
            cookieOnly = 0
            return false
        }
        if (pageState == "ok") {
            stable++
            if (stable >= requiredStable) return true
        } else {
            stable = 0
        }
        cookieOnly++
        return cookieOnly >= maxCookieOnlyPolls
    }
}

/** Úložiště cookies, které umí Cloudflare cookies vyčistit - rozhraním kvůli testům bez Androidu. */
interface CookieEditor {
    fun cookieHeader(url: String): String?
    fun set(url: String, cookie: String)
    fun flush()
}

object AndroidCookieEditor : CookieEditor {
    private fun manager(): CookieManager? = try { CookieManager.getInstance() } catch (_: Throwable) { null }
    override fun cookieHeader(url: String): String? = try { manager()?.getCookie(url) } catch (_: Throwable) { null }
    override fun set(url: String, cookie: String) { try { manager()?.setCookie(url, cookie) } catch (_: Throwable) { } }
    override fun flush() { try { manager()?.flush() } catch (_: Throwable) { } }
}

/**
 * Před novým pokusem o řešení se smažou staré Cloudflare cookies hostitele. Odmítnutá `cf_clearance` by jinak zůstala
 * ve sdíleném úložišti (a `WebViewCookieInterceptor` by ji dál posílal), takže by řešení hned "uspělo" na základě cookie,
 * kterou web už nechce.
 */
object CloudflareCookies {
    private val CLOUDFLARE_COOKIE = Regex("""^(cf_clearance|__cf_bm|_cfuvid|__cflb|cf_chl_.*|cf_ob_info|cf_use_ob)$""")

    fun isCloudflareCookie(name: String) = CLOUDFLARE_COOKIE.matches(name)

    fun clear(url: String, editor: CookieEditor = AndroidCookieEditor) {
        val host = url.toHttpUrlOrNull()?.host ?: return
        val names = editor.cookieHeader(url).orEmpty().split(';')
            .map { it.substringBefore('=').trim() }
            .filter { it.isNotEmpty() && isCloudflareCookie(it) }
            .distinct()
        if (names.isEmpty()) return
        val domains = hostAndParents(host)
        for (name in names) {
            editor.set(url, "$name=; Max-Age=0; Path=/")
            for (domain in domains) {
                editor.set(url, "$name=; Max-Age=0; Path=/; Domain=$domain")
                editor.set(url, "$name=; Max-Age=0; Path=/; Domain=.$domain")
            }
        }
        editor.flush()
    }

    /** `a.b.site.com` -> `a.b.site.com`, `b.site.com`, `site.com` (aspoň dvě části). */
    internal fun hostAndParents(host: String): List<String> {
        val labels = host.split('.')
        return (0..labels.size - 2).map { labels.drop(it).joinToString(".") }
    }
}
