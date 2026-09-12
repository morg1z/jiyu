package com.haise.jiyu.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.haise.jiyu.data.db.AppDatabase
import com.haise.jiyu.data.db.entity.ChapterEntity
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
    private val db: AppDatabase,
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
                if (sourceId == null) {
                    // Radsi preskocit nez omylem prirad mangu z neznameho zdroje pod
                    // "mangadex" - spatne prirazeny zdroj by ji pak appka nikdy nedokazala
                    // dohledat/aktualizovat (viz oprava tehle chyby v pripojenem auditu).
                    skipped++
                    errors += "Položka $i (\"$title\"): neznámý zdroj (Tachiyomi source id $tachiyomiSourceId), přeskočeno"
                    continue
                }

                val existing = repository.getMangaBySourceAndUrl(sourceId, url)
                if (existing != null && existing.inLibrary) {
                    skipped++
                    continue
                }

                // Manga i jeji kapitoly se zapisuji spolecne v jedne transakci - pad/preruseni
                // uprostred nesmi nechat mangu bez kapitol nebo naopak (viz audit nalez
                // "zadna transakce").
                db.withTransaction {
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

                    // Kapitoly z Tachiyomi zalohy se driv NIKDY nevkladaly - jen se oznacovaly
                    // precteny ty, co uz lokalne existovaly z jineho zdroje dat (typicky vubec
                    // zadne u cerstve importovane mangy). Ted se skutecne vlozi jako nove
                    // ChapterEntity radky, teprve pak se aplikuje read-status.
                    val chaptersArr = entry.optJSONArray("chapters") ?: entry.optJSONArray("backupChapters")
                    val existingChapters = repository.getAllChapters(manga.id).associateBy { it.url }
                    if (chaptersArr != null) {
                        val newChapters = mutableListOf<ChapterEntity>()
                        for (j in 0 until chaptersArr.length()) {
                            val ch = chaptersArr.getJSONObject(j)
                            val chapterUrl = ch.optString("url").takeIf { it.isNotBlank() } ?: continue
                            if (chapterUrl in existingChapters) continue
                            val chapterNumber = ch.optDouble("chapterNumber", ch.optDouble("chapter_number", -1.0))
                                .let { if (it < 0.0 || it.isNaN()) 0f else it.toFloat() }
                            newChapters += ChapterEntity(
                                id = "$sourceId::$chapterUrl",
                                mangaId = manga.id,
                                sourceId = sourceId,
                                url = chapterUrl,
                                name = ch.optString("name").ifBlank { "Kapitola $chapterNumber" },
                                chapterNumber = chapterNumber,
                                dateUpload = ch.optLong("dateUpload", ch.optLong("date_upload", 0L)),
                                read = ch.optBoolean("read", false),
                                lastPageRead = ch.optInt("lastPageRead", ch.optInt("last_page_read", 0)),
                                discoveredAt = System.currentTimeMillis(),
                            )
                        }
                        if (newChapters.isNotEmpty()) repository.upsertAllChapters(newChapters)
                    }

                    // Historie čtení (Tachiyomi ji drží odděleně od chapters[].read u některých
                    // exportů) může označit za přečtené i kapitoly, které chapters[].read
                    // neuvádí - aplikuje se navíc, přes plný (starý + nově vložený) seznam.
                    val historyArr = entry.optJSONArray("history") ?: entry.optJSONArray("backupHistory")
                    val historyReadUrls = buildSet<String> {
                        historyArr?.let { arr ->
                            for (j in 0 until arr.length()) add(arr.getJSONObject(j).optString("url"))
                        }
                    }
                    if (historyReadUrls.isNotEmpty()) {
                        repository.getAllChapters(manga.id)
                            .filter { it.url in historyReadUrls && !it.read }
                            .forEach { repository.updateReadProgress(it.id, read = true, lastPageRead = 0) }
                    }

                    imported++
                }
            } catch (e: Exception) {
                errors += "Položka $i: ${e.message?.take(60)}"
            }
        }

        TachiyomiImportResult(imported, skipped, errors)
    }

    /** `null` = neznámý zdroj - volající mangu radši přeskočí, než aby ji tiše přiřadil pod špatný zdroj. */
    private fun mapTachiyomiSource(tachiyomiId: Long, url: String): String? = when {
        tachiyomiId == 2499283573021220255L -> "mangadex"
        url.contains("mangadex.org")        -> "mangadex"
        url.contains("mangaplus")           -> "mangaplus"
        url.contains("webtoons")            -> "webtoons"
        url.contains("bato.to")             -> "batoto"
        else                                -> null
    }
}
