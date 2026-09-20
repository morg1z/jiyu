package com.haise.jiyu.source.interceptor

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Zapnutí úsporného režimu obrázků - čtou ho interceptory na vlákně OkHttp, proto jen `@Volatile` příznak. */
@Singleton
class ImageProxyConfig @Inject constructor() {
    @Volatile
    var enabled: Boolean = false
}

/**
 * Volitelný úsporný režim obrázků (nastavení, ve výchozím stavu VYPNUTO): obrázky se stahují přes veřejnou zdarma
 * dostupnou službu wsrv.nl, která je překóduje do menšího WebP - na slabém signálu je to násobně méně dat.
 * Zdarma, bez účtu a bez karty; cenou je, že služba vidí adresy stahovaných obrázků (proto opt-in a upozornění).
 *
 * Bezpečnostní síť: když proxy selže (chyba, blokace, nedostupnost), požadavek se hned zopakuje přímo na původní
 * adresu, takže se nic nezhorší; a když přímé stažení projde, host se pro zbytek běhu proxy zbaví (asi ji blokuje).
 *
 * Požadavky s hlavičkou [HEADER_ORIGINAL] (OCR a překlad, stahování kapitol, MangaPlus se šifrovanými daty) jdou
 * VŽDY přímo a v původní kvalitě - hlavička se před odesláním odstraní.
 */
class ImageProxyInterceptor(
    private val config: ImageProxyConfig,
    private val blockedHosts: MutableSet<String> = ConcurrentHashMap.newKeySet(),
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val wantsOriginal = request.header(HEADER_ORIGINAL) != null
        val direct = if (wantsOriginal) request.newBuilder().removeHeader(HEADER_ORIGINAL).build() else request

        val url = request.url
        if (wantsOriginal || !config.enabled || request.method != "GET" || !isEligible(url)) return chain.proceed(direct)

        val proxied = direct.newBuilder().url(proxyUrl(url)).removeHeader("Referer").build()
        val viaProxy: Response? = try {
            chain.proceed(proxied)
        } catch (_: IOException) {
            null
        } catch (_: com.haise.jiyu.source.SourceRateLimitedException) {
            // 429 od proxy (RateLimitInterceptor ho mění na tuhle výjimku, která NENÍ IOException) - obrázek se
            // zkusí stáhnout přímo.
            null
        } catch (_: RuntimeException) {
            null
        }
        if (viaProxy != null && viaProxy.isSuccessful) return viaProxy
        viaProxy?.close()

        val response = chain.proceed(direct)
        if (response.isSuccessful) blockedHosts += url.host
        return response
    }

    private fun isEligible(url: HttpUrl): Boolean {
        if (url.host == PROXY_HOST || url.host in blockedHosts) return false
        if (url.host == "localhost" || url.host.matches(Regex("""[0-9.]+"""))) return false
        return true
    }

    private fun proxyUrl(original: HttpUrl): HttpUrl = HttpUrl.Builder()
        .scheme("https")
        .host(PROXY_HOST)
        .addQueryParameter("url", original.toString())
        .addQueryParameter("output", "webp")
        .addQueryParameter("q", "80")
        .addQueryParameter("we", null) // bez zvětšování
        .build()

    companion object {
        const val HEADER_ORIGINAL = "X-Jiyu-Original"
        const val PROXY_HOST = "wsrv.nl"
    }
}
