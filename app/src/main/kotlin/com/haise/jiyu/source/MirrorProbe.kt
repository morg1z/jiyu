package com.haise.jiyu.source

import com.haise.jiyu.source.interceptor.DomainOverrides
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Nová adresa zdroje, kterou našla [MirrorProbe]. [autoApply] = bezpečné použít bez ptaní (stejná značka domény). */
data class MirrorCandidate(val sourceId: String, val host: String, val autoApply: Boolean)

/**
 * Zjišťuje, jestli se web zdroje nepřestěhoval: pošle `HEAD` na jeho hlavní adresu a když ho server přesměruje na
 * jiný host, vrátí ho jako kandidáta. Použije se po selhání spojení, aby uživatel nemusel čekat na novou verzi appky
 * (ruční náhradní doména už umíme, tohle ji navrhne samo).
 *
 * Přesměrování na cizí doménu může být parkovací nebo reklamní stránka, proto se nová adresa použije automaticky
 * JEN když má stejnou "značku" domény (`site.com` → `site.net`); jinak se uživateli jen nabídne ([MirrorCandidate.autoApply]
 * = `false`). Mrtvá doména bez DNS se takhle nedá zachránit (není kam přesměrovat).
 */
@Singleton
class MirrorProbe @Inject constructor(private val client: OkHttpClient) {

    suspend fun detect(sourceId: String, homepageUrl: String): MirrorCandidate? = withContext(Dispatchers.IO) {
        val original = try { homepageUrl.toHttpUrl() } catch (_: IllegalArgumentException) { return@withContext null }
        val probe = client.newBuilder().callTimeout(8, TimeUnit.SECONDS).followRedirects(true).build()
        val finalUrl = try {
            probe.newCall(Request.Builder().url(original).head().build()).execute().use { r ->
                if (r.isSuccessful) r.request.url else null
            }
        } catch (_: IOException) {
            null
        } ?: return@withContext null

        val newHost = DomainOverrides.canonicalHost(finalUrl.host)
        val oldHost = DomainOverrides.canonicalHost(original.host)
        if (newHost == oldHost || isIpAddress(newHost)) return@withContext null
        MirrorCandidate(sourceId, newHost, autoApply = sameBrand(oldHost, newHost))
    }

    companion object {
        private val SECOND_LEVEL_TLDS = setOf("co", "com", "org", "net", "gov", "ac", "edu", "or", "ne")

        private fun isIpAddress(host: String) = host.matches(Regex("""[0-9.]+|\[.*\]"""))

        /** "Značka" domény = název před příponou (`site` v `www.site.co.uk`), bez pomlček a velkých písmen. */
        internal fun brand(host: String): String {
            val labels = host.lowercase().split('.').filter { it.isNotEmpty() }
            if (labels.size < 2) return host.lowercase()
            val idx = if (labels.size >= 3 && labels[labels.size - 2] in SECOND_LEVEL_TLDS && labels.last().length == 2) {
                labels.size - 3
            } else {
                labels.size - 2
            }
            return labels[idx].replace("-", "")
        }

        internal fun sameBrand(a: String, b: String): Boolean = brand(a) == brand(b)
    }
}
