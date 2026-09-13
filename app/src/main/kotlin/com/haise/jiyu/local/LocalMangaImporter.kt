package com.haise.jiyu.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.haise.jiyu.data.db.ChapterDao
import com.haise.jiyu.data.db.MangaDao
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.db.entity.MangaEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalMangaImporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val mangaDao: MangaDao,
    private val chapterDao: ChapterDao,
) {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "bmp")

    companion object {
        // 500 MB rozbaleno je pro jeden komiks/svazek velkoryse, ale zastavi zip bombu
        // (extremni kompresni pomer) drive, nez zaplni disk (nahlaseno v auditu).
        private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 500L * 1024 * 1024
        private const val MAX_ENTRIES = 5000
    }

    suspend fun import(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val displayName = resolveFilename(uri) ?: "lokalni_${System.currentTimeMillis()}"
            val mangaTitle = displayName.substringBeforeLast(".")
            val sanitized = mangaTitle
                .replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
                .take(80)
                .trimEnd('_', ' ')
                .ifBlank { "local_${System.currentTimeMillis()}" }

            val outputDir = File(context.filesDir, "local/$sanitized")
            if (outputDir.exists()) outputDir.deleteRecursively()
            outputDir.mkdirs()

            val images = mutableListOf<File>()
            try {
                var totalBytes = 0L
                var entryCount = 0
                context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                    ZipInputStream(input).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            entryCount++
                            if (entryCount > MAX_ENTRIES) error("Archiv obsahuje příliš mnoho souborů")
                            val name = entry.name
                            val ext = name.substringAfterLast('.', "").lowercase()
                            if (!entry.isDirectory && ext in imageExtensions) {
                                val flatName = name.substringAfterLast('/')
                                val outFile = File(outputDir, flatName)
                                outFile.outputStream().buffered().use { out ->
                                    totalBytes += zip.copyToWithLimit(out, MAX_TOTAL_UNCOMPRESSED_BYTES - totalBytes)
                                }
                                images.add(outFile)
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                } ?: error("Soubor nelze otevřít")
            } catch (e: Exception) {
                // Uklidit rozpracovany vystup - jinak by po chybe (napr. prekroceny limit
                // nize) zustal na disku napul rozbaleny archiv (nahlaseno v auditu).
                outputDir.deleteRecursively()
                throw e
            }

            if (images.isEmpty()) {
                outputDir.deleteRecursively()
                error("Archiv neobsahuje žádné obrázky")
            }
            images.sortBy { it.name }

            val mangaId  = "local::$sanitized"
            val chapterId = "local_ch::$sanitized"

            mangaDao.upsert(
                MangaEntity(
                    id = mangaId,
                    sourceId = "local",
                    url = sanitized,
                    title = mangaTitle,
                    coverUrl = "file://${images.first().absolutePath}",
                    description = null,
                    status = null,
                    inLibrary = true,
                    addedAt = System.currentTimeMillis(),
                )
            )
            chapterDao.upsertAll(
                listOf(
                    ChapterEntity(
                        id = chapterId,
                        mangaId = mangaId,
                        sourceId = "local",
                        url = "",
                        name = "Lokální soubor",
                        chapterNumber = 1f,
                        dateUpload = System.currentTimeMillis(),
                        downloadStatus = DownloadStatus.DOWNLOADED,
                        localPath = outputDir.absolutePath,
                        pageCount = images.size,
                    )
                )
            )
            chapterId
        }
    }

    private fun resolveFilename(uri: Uri): String? {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment
    }
}

/** Jako `InputStream.copyTo`, ale hodi [java.io.IOException], kdyz zapsana data prekroci
 * `limit` - obycejny `copyTo` by rozbalil libovolne velky (i podvrzeny) obsah bez omezeni.
 * Top-level `internal`, ne `private` clenska metoda - aby slo otestovat primo bez
 * Android/Context runtime (viz [LocalMangaImporterTest]). */
internal fun java.io.InputStream.copyToWithLimit(out: java.io.OutputStream, limit: Long): Long {
    val buffer = ByteArray(8192)
    var copied = 0L
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        copied += n
        if (copied > limit) throw java.io.IOException("Archiv překračuje limit velikosti (možná zip bomba)")
        out.write(buffer, 0, n)
    }
    return copied
}
