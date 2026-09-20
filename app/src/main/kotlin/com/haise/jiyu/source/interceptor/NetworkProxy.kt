package com.haise.jiyu.source.interceptor

import okhttp3.Authenticator
import okhttp3.Credentials
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

enum class ProxyType { HTTP, SOCKS }

/** Volitelná HTTP/SOCKS proxy zadaná uživatelem (Nastavení → Zdroje). [user]/[password] jen pro HTTP proxy s přihlášením. */
data class ProxySettings(
    val type: ProxyType,
    val host: String,
    val port: Int,
    val user: String? = null,
    val password: String? = null,
) {
    companion object {
        private val HOST = Regex("""^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$""")

        /** Ověří vstup z formuláře; `null` = neplatný host/port (nic se pak neuloží). */
        fun parse(type: ProxyType, host: String, port: String, user: String?, password: String?): ProxySettings? {
            val h = host.trim()
            val p = port.trim().toIntOrNull() ?: return null
            if (!HOST.matches(h) || p !in 1..65535) return null
            val u = user?.trim()?.takeIf { it.isNotEmpty() }
            return ProxySettings(type, h, p, u, if (u != null) password else null)
        }
    }
}

/**
 * Aktuální uživatelská proxy pro klienty zdrojů a obrázků (LLM/překladové klienty ji záměrně nepoužívají). Když není
 * nastavená, [selector] předá rozhodnutí systémovému výběru, takže se chování appky nemění. Lokální adresy jdou vždy
 * přímo. Přihlášení funguje pro HTTP proxy ([authenticator], hlavička `Proxy-Authorization`); SOCKS jen bez přihlášení.
 */
@Singleton
class NetworkProxyConfig @Inject constructor() {

    @Volatile
    var settings: ProxySettings? = null

    val selector: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val s = settings ?: return ProxySelector.getDefault()?.select(uri) ?: listOf(Proxy.NO_PROXY)
            val host = uri?.host.orEmpty()
            if (host == "localhost" || host.startsWith("127.") || host.startsWith("192.168.") || host.startsWith("10.")) {
                return listOf(Proxy.NO_PROXY)
            }
            val type = if (s.type == ProxyType.SOCKS) Proxy.Type.SOCKS else Proxy.Type.HTTP
            return listOf(Proxy(type, InetSocketAddress.createUnresolved(s.host, s.port)))
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }

    val authenticator: Authenticator = Authenticator { _, response ->
        val s = settings
        // Jen jeden pokus s přihlášením (jinak by špatné heslo cyklilo) a jen pro HTTP proxy s uživatelem.
        if (s?.user == null || s.type != ProxyType.HTTP || response.request.header("Proxy-Authorization") != null) {
            null
        } else {
            response.request.newBuilder()
                .header("Proxy-Authorization", Credentials.basic(s.user, s.password.orEmpty()))
                .build()
        }
    }
}
