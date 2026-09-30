package com.haise.jiyu.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateInfo(
    val version: String,
    val releaseUrl: String,
    val notes: String,
    val apkUrl: String?,
    /** SHA-256 otisk APK assetu, pokud ho release poznámky obsahují - viz [extractSha256FromReleaseNotes]. */
    val sha256: String?,
)

/** Vytaženo mimo třídu, aby šlo testovat bez OkHttpClient mocku - stejný vzor jako `SourceResolverViewModel`'s top-level `internal fun`. */
internal fun isNewerVersion(remote: String, current: String): Boolean {
    val r = remote.split(".").mapNotNull { it.toIntOrNull() }
    val c = current.split(".").mapNotNull { it.toIntOrNull() }
    for (i in 0 until maxOf(r.size, c.size)) {
        val rv = r.getOrElse(i) { 0 }
        val cv = c.getOrElse(i) { 0 }
        if (rv != cv) return rv > cv
    }
    return false
}

/**
 * Vytáhne SHA-256 otisk APK z release poznámek, pokud ho obsahují (konvence: řádek jako
 * "sha256: <64 hex znaků>" nebo "SHA-256: <...>"). Parsuje se z PLNÉHO textu release body,
 * ne z [UpdateInfo.notes] (ten je zkrácený na 500 znaků jen pro zobrazení - digest by tak
 * mohl zmizet, kdyby release poznámky byly delší). `null`, když release žádný digest
 * neobsahuje (starší vydání před zavedením týhle konvence) - [ApkUpdateInstaller] pak
 * integritu z důvodu zpětné kompatibility nekontroluje.
 */
internal fun extractSha256FromReleaseNotes(body: String): String? =
    Regex("(?i)sha-?256[:\\s]*([0-9a-fA-F]{64})").find(body)?.groupValues?.get(1)?.lowercase()

/**
 * SEC-8: `browser_download_url` z release JSONu pustíme jen když vede na github.com přes
 * https - poškozená/přepsaná response (nebo zrcadlená API odpověď) by jinak mohla appku
 * poslat stáhnout APK odkudkoli. GitHub asset URL je vždy `github.com/.../releases/download/…`.
 * Funkce je top-level pro JVM testy (stejný vzor jako [isNewerVersion]).
 */
internal fun isAllowedApkUrl(url: String): Boolean {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    return uri.scheme == "https" && (host == "github.com" || host.endsWith(".github.com"))
}

/**
 * Kontroluje nejnovější GitHub Release repozitáře jako jednoduchou náhradu
 * Play Store auto-update mechanismu (appka není publikovaná na Play Store).
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val client: OkHttpClient,
) {
    companion object {
        private const val RELEASES_URL = "https://api.github.com/repos/morg1z/jiyu/releases/latest"
    }

    suspend fun checkForUpdate(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(RELEASES_URL)
                .header("Accept", "application/vnd.github+json")
                .build()
            val body = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string()
            } ?: return@withContext null
            val json = JSONObject(body)
            val tag = json.optString("tag_name").removePrefix("v").ifBlank { return@withContext null }
            val fullNotes = json.optString("body")
            val notes = fullNotes.take(500)
            val sha256 = extractSha256FromReleaseNotes(fullNotes)
            val url = json.optString("html_url")
            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                        // SEC-8: jen github.com/https - cizi host se zahodi, APK se
                        // pak stáhne jen přes releaseUrl (otevřený release v prohlížeči
                        // nepoužíváme, ale uživatel může APK vyhledat ručně).
                        apkUrl = asset.optString("browser_download_url").takeIf { isAllowedApkUrl(it) }
                        break
                    }
                }
            }
            if (isNewerVersion(tag, currentVersion)) UpdateInfo(tag, url, notes, apkUrl, sha256) else null
        } catch (_: Exception) { null }
    }
}
