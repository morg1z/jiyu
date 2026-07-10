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
    @ApplicationContext private val context: Context,
    private val mangaDao: MangaDao,
    private val chapterDao: ChapterDao,
) {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "bmp")

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
            context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (!entry.isDirectory && ext in imageExtensions) {
                            val flatName = name.substringAfterLast('/')
                            val outFile = File(outputDir, flatName)
                            outFile.outputStream().buffered().use { out -> zip.copyTo(out) }
                            images.add(outFile)
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: error("Soubor nelze otevřít")

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
