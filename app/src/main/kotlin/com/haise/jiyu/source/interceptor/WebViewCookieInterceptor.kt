package com.haise.jiyu.source.interceptor

import android.webkit.CookieManager
import okhttp3.Interceptor
import okhttp3.Response

/** Úložiště cookies sdílené s WebView - odděleno rozhraním, aby šlo interceptor testovat bez Androidu. */
interface SharedCookieStore {
    /** Hlavička `Cookie` pro [url] (`a=b; c=d`), nebo `null`. */
    fun cookiesFor(url: String): String?

    /** Uloží jednu hlavičku `Set-Cookie` přijatou z [url]. */
    fun store(url: String, setCookieHeader: String)
}

/** Skutečné úložiště: `android.webkit.CookieManager`. Když WebView v systému není, nedělá nic. */
object AndroidSharedCookieStore : SharedCookieStore {
    private fun manager(): CookieManager? = try { CookieManager.getInstance() } catch (_: Throwable) { null }

    override fun cookiesFor(url: String): String? = try { manager()?.getCookie(url) } catch (_: Throwable) { null }

    override fun store(url: String, setCookieHeader: String) {
        try { manager()?.setCookie(url, setCookieHeader) } catch (_: Throwable) { /* cookies jsou jen optimalizace */ }
    }
}

/**
 * Sdílí cookies mezi appkou a WebView: před požadavkem k němu doplní cookies, které pro daný host zná WebView
 * (vyřešená Cloudflare výzva, přihlášení nebo souhlas s věkem provedený ve WebView), a cookies z odpovědí uloží
 * zpátky. Dřív se nedržely nikde kromě ručně spravovaného `cf_clearance`.
 *
 * Záměrně NENÍ OkHttp `CookieJar` - jeho `BridgeInterceptor` by přepsal `Cookie` hlavičku, kterou si některé
 * zdroje (BatCave, E-Hentai) nastavují samy. Tady mají cookies uvedené v požadavku přednost před sdílenými.
 */
class WebViewCookieInterceptor(
    private val store: SharedCookieStore = AndroidSharedCookieStore,
    private val maxHeaderLength: Int = 4_096,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val shared = store.cookiesFor(url)
        val outgoing = if (shared.isNullOrBlank()) {
            request
        } else {
            val merged = mergeKeepingExisting(request.header("Cookie"), shared)
            // Příliš dlouhá hlavička by web odmítl (431) - v tom případě se sdílené cookies nepřidají.
            if (merged.length > maxHeaderLength) request else request.newBuilder().header("Cookie", merged).build()
        }
        val response = chain.proceed(outgoing)
        response.headers("Set-Cookie").forEach { store.store(url, it) }
        return response
    }
}

/** Sloučí cookies; při shodě jména vyhrává [existing] (cookie uvedená přímo v požadavku). */
internal fun mergeKeepingExisting(existing: String?, shared: String): String {
    if (existing.isNullOrBlank()) return shared
    val existingNames = existing.split(";").mapNotNull { it.substringBefore('=', "").trim().ifEmpty { null } }.toSet()
    val extra = shared.split(";").map { it.trim() }
        .filter { it.isNotEmpty() && it.substringBefore('=').trim() !in existingNames }
    return (listOf(existing.trim()) + extra).joinToString("; ")
}
