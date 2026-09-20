package com.haise.jiyu.source.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uživatelské přesměrování domény zdroje ("zrcadlo"): když web změní adresu, uživatel zadá novou a appka ji
 * použije bez nové verze. Zdroje mají doménu napevno v kódu, proto se přepisuje až na úrovni požadavku
 * ([DomainOverrideInterceptor]) - platí i pro adresy titulů a kapitol uložené v knihovně se starým hostem.
 */
@Singleton
class DomainOverrides @Inject constructor() {

    /** Původní host (bez `www.`, malými písmeny) -> nový host. Nahrazuje se celá mapa naráz. */
    @Volatile
    var hostMap: Map<String, String> = emptyMap()

    fun resolve(host: String): String? {
        val map = hostMap
        if (map.isEmpty()) return null
        return map[canonicalHost(host)]
    }

    companion object {
        private val HOST_REGEX = Regex("""^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$""")

        fun canonicalHost(host: String): String = host.trim().lowercase().removePrefix("www.")

        /**
         * Z uživatelského vstupu ("https://Novy-Web.com/manga/", "novy-web.com") vytáhne čistý host, nebo `null`,
         * pokud to není platná doménová adresa (mezery, IP, port, prázdné, jen TLD).
         */
        fun normalizeInput(input: String): String? {
            var s = input.trim()
            if (s.isEmpty()) return null
            if ("://" in s) {
                s = runCatching { URI(s).host }.getOrNull() ?: return null
            } else {
                s = s.substringBefore('/').substringBefore('?').substringBefore('#')
            }
            s = s.trim().trimEnd('.').lowercase()
            if (!HOST_REGEX.matches(s)) return null
            if (s.matches(Regex("""[0-9.]+"""))) return null
            return s
        }
    }
}

/**
 * Přepíše host požadavku podle [DomainOverrides] a ve stejném duchu i hlavičky `Referer`/`Origin`, které zdroje
 * skládají ze staré adresy. Stojí jako první v řetězci, takže omezení počtu spojení a frekvence požadavků
 * (Throttle/RateLimit) už počítají s novým hostem.
 */
class DomainOverrideInterceptor(private val overrides: DomainOverrides) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val newHost = overrides.resolve(request.url.host) ?: return chain.proceed(request)
        val builder = request.newBuilder().url(request.url.newBuilder().host(newHost).build())
        for (name in listOf("Referer", "Origin")) {
            val value = request.header(name) ?: continue
            val host = runCatching { URI(value).host }.getOrNull() ?: continue
            if (overrides.resolve(host) == newHost) {
                builder.header(name, value.replaceFirst(host, newHost))
            }
        }
        return chain.proceed(builder.build())
    }
}
