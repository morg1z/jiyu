package com.haise.jiyu.data.backup

import android.content.Context
import android.net.Uri
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.repository.MangaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class TachiyomiImportResult(
    val imported: Int,
    val skipped: Int,
    val errors: List<String>,
)

@Singleton
class TachiyomiBackupImporter @Inject constructor(
    private val repository: MangaRepository,
) {
    suspend fun importFromUri(context: Context, uri: Uri): TachiyomiImportResult = withContext(Dispatchers.IO) {
        val json = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return@withContext TachiyomiImportResult(0, 0, listOf("Nelze otevřít soubor"))

        val root = try { JSONObject(json) } catch (_: Exception) {
            return@withContext TachiyomiImportResult(
                0, 0,
                listOf("Neplatný JSON formát. Pro .tachibk soubory exportuj z Mihon jako JSON (Settings → Backup → Create backup)."),
            )
        }

        val mangasArray: JSONArray = when {
            root.has("mangas")      -> root.getJSONArray("mangas")
            root.has("backupManga") -> root.getJSONArray("backupManga")
            else -> return@withContext TachiyomiImportResult(0, 0, listOf("Nepodporovaný formát zálohy"))
        }

        var imported = 0
        var skipped = 0
        val errors = mutableListOf<String>()

        for (i in 0 until mangasArray.length()) {
            try {
                val entry = mangasArray.getJSONObject(i)
                val mangaObj = if (entry.has("manga")) entry.getJSONObject("manga") else entry

                val url = mangaObj.optString("url").takeIf { it.isNotBlank() } ?: continue
                val title = mangaObj.optString("title").takeIf { it.isNotBlank() } ?: "Bez názvu"
                val coverUrl = mangaObj.optString("thumbnailUrl").takeIf { it.isNotBlank() }
                val author = mangaObj.optString("author").takeIf { it.isNotBlank() }
                val description = mangaObj.optString("description").takeIf { it.isNotBlank() }
                val inLibrary = mangaObj.optBoolean("favorite", true)
                val tachiyomiSourceId = mangaObj.optLong("source", 0L)
                val sourceId = mapTachiyomiSource(tachiyomiSourceId, url)

                val existing = repository.getMangaByUrl(url)
                if (existing != null && existing.inLibrary) {
                    skipped++
                    continue
                }

                val manga = MangaEntity(
                    id = existing?.id ?: "$sourceId::$url",
                    sourceId = sourceId,
                    url = url,
                    title = title,
                    coverUrl = coverUrl,
                    author = author,
                    description = description,
                    status = null,
                    inLibrary = inLibrary,
                    contentType = "MANGA",
                )
                repository.upsertManga(manga)

                // Mark chapters read based on backup data
                val chaptersArr = entry.optJSONArray("chapters") ?: entry.optJSONArray("backupChapters")
                val historyArr = entry.optJSONArray("history") ?: entry.optJSONArray("backupHistory")
                val readUrls = buildSet<String> {
                    chaptersArr?.let { arr ->
                        for (j in 0 until arr.length()) {
                            val ch = arr.getJSONObject(j)
                            if (ch.optBoolean("read", false)) add(ch.optString("url"))
                        }
                    }
                    historyArr?.let { arr ->
                        for (j in 0 until arr.length()) add(arr.getJSONObject(j).optString("url"))
                    }
                }
                if (readUrls.isNotEmpty()) {
                    repository.getAllChapters(manga.id)
                        .filter { it.url in readUrls }
                        .forEach { repository.updateReadProgress(it.id, read = true, lastPageRead = 0) }
                }

                imported++
            } catch (e: Exception) {
                errors += "Položka $i: ${e.message?.take(60)}"
            }
        }

        TachiyomiImportResult(imported, skipped, errors)
    }

    private fun mapTachiyomiSource(tachiyomiId: Long, url: String): String = when {
        tachiyomiId == 2499283573021220255L -> "mangadex"
        url.contains("mangadex.org")        -> "mangadex"
        url.contains("mangaplus")           -> "mangaplus"
        url.contains("webtoons")            -> "webtoons"
        url.contains("bato.to")             -> "batoto"
        else                                -> "mangadex"
    }
}
