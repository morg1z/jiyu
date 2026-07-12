package com.haise.jiyu.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Abstrahuje úložiště stažených kapitol - buď obyčejný File path (výchozí,
 * app-private úložiště), nebo SAF content:// URI (uživatelem vybraná složka
 * v Nastavení -> "Složka stahování", může mířit i na lokálně synchronizovanou
 * cloudovou složku). Všechna volající místa (reader, download manager, mazání)
 * jedou přes tuhle třídu, aby nemusela řešit rozdíl mezi oběma variantami.
 */
object ChapterStorage {

    private fun isSaf(path: String) = path.startsWith("content://")

    /** Vytvoří novou složku pro kapitolu a vrátí její "localPath" (File path nebo content URI). */
    fun createChapterDir(context: Context, downloadFolderUri: String?, chapterEntityId: String): String {
        if (downloadFolderUri != null) {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(downloadFolderUri))
            val dir = root?.createDirectory(chapterEntityId)
            if (dir != null) return dir.uri.toString()
        }
        val dir = File(context.filesDir, "downloads/$chapterEntityId")
        dir.mkdirs()
        return dir.absolutePath
    }

    fun writePage(context: Context, dirPath: String, fileName: String, bytes: ByteArray) {
        if (isSaf(dirPath)) {
            val dir = DocumentFile.fromSingleUri(context, Uri.parse(dirPath)) ?: return
            val mime = when (fileName.substringAfterLast('.', "").lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> "image/jpeg"
            }
            val file = dir.createFile(mime, fileName) ?: return
            context.contentResolver.openOutputStream(file.uri)?.use { it.write(bytes) }
        } else {
            File(dirPath, fileName).writeBytes(bytes)
        }
    }

    /** Seřazený seznam URL/URI stránek pro čtečku - Coil umí načíst jak file://, tak content://. */
    fun listPageUrls(context: Context, dirPath: String): List<String> {
        return if (isSaf(dirPath)) {
            DocumentFile.fromSingleUri(context, Uri.parse(dirPath))
                ?.listFiles()
                ?.filter { it.name?.endsWith(".cbz") != true }
                ?.sortedBy { it.name ?: "" }
                ?.map { it.uri.toString() }
                ?: emptyList()
        } else {
            File(dirPath).listFiles()
                ?.sortedBy { it.name }
                ?.map { "file://${it.absolutePath}" }
                ?: emptyList()
        }
    }

    /** Vytvoří .cbz archiv se všemi stránkami kapitoly (uvnitř stejné složky u SAF, jako sourozenec u File). */
    fun createCbz(context: Context, dirPath: String, chapterEntityId: String) {
        if (isSaf(dirPath)) {
            val dir = DocumentFile.fromSingleUri(context, Uri.parse(dirPath)) ?: return
            val cbzFile = dir.createFile("application/vnd.comicbook+zip", "$chapterEntityId.cbz") ?: return
            context.contentResolver.openOutputStream(cbzFile.uri)?.use { out ->
                ZipOutputStream(BufferedOutputStream(out)).use { zip ->
                    dir.listFiles()
                        .filter { it.uri != cbzFile.uri }
                        .sortedBy { it.name ?: "" }
                        .forEach { child ->
                            zip.putNextEntry(ZipEntry(child.name ?: "page"))
                            context.contentResolver.openInputStream(child.uri)?.use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                }
            }
        } else {
            val chapterDir = File(dirPath)
            val cbzFile = File(chapterDir.parent, "${chapterDir.name}.cbz")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(cbzFile))).use { zip ->
                chapterDir.listFiles()?.sortedBy { it.name }?.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /** Velikost stažené kapitoly v bajtech - pro File i SAF. */
    fun sizeBytes(context: Context, dirPath: String): Long {
        return try {
            if (isSaf(dirPath)) {
                DocumentFile.fromSingleUri(context, Uri.parse(dirPath))
                    ?.listFiles()
                    ?.sumOf { it.length() }
                    ?: 0L
            } else {
                File(dirPath).walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }
        } catch (_: Exception) { 0L }
    }

    fun deleteRecursively(context: Context, dirPath: String) {
        try {
            if (isSaf(dirPath)) {
                DocumentFile.fromSingleUri(context, Uri.parse(dirPath))?.delete()
            } else {
                File(dirPath).deleteRecursively()
            }
        } catch (_: Exception) {}
    }
}
