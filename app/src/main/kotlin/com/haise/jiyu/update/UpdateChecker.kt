package com.haise.jiyu.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateInfo(val version: String, val releaseUrl: String, val notes: String, val apkUrl: String?)

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
            val notes = json.optString("body").take(500)
            val url = json.optString("html_url")
            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
            }
            if (isNewer(tag, currentVersion)) UpdateInfo(tag, url, notes, apkUrl) else null
        } catch (_: Exception) { null }
    }

    private fun isNewer(remote: String, current: String): Boolean {
        val r = remote.split(".").mapNotNull { it.toIntOrNull() }
        val c = current.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }
}
