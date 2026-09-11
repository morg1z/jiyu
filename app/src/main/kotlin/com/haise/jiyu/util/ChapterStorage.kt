package com.haise.jiyu.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.haise.jiyu.util.report

/**
 * Abstrahuje úložiště stažených kapitol - buď obyčejný File path (výchozí,
 * app-private úložiště), nebo SAF content:// URI (uživatelem vybraná složka
 * v Nastavení -> "Složka stahování", může mířit i na lokálně synchronizovanou
 * cloudovou složku). Všechna volající místa (reader, download manager, mazání)
 * jedou přes tuhle třídu, aby nemusela řešit rozdíl mezi oběma variantami.
 */
object ChapterStorage {

    private fun isSaf(path: String) = path.startsWith("content://")

    /**
     * Očistí název mangy/kapitoly na bezpečný název souboru/složky - dřív se stažená
     * kapitola pojmenovávala podle "sourceId::URL adresy kapitoly", což je nečitelné a
     * navíc na Windows (kam si uživatel stažené kapitoly chce zkopírovat, viz uživatelský
     * dotaz) rovnou nefunkční jméno souboru (URL obsahuje ':' a '/'). Zakázané znaky
     * (Windows je přísnější než Android/Linux, sjednocujeme na jeho pravidla, ať název
     * funguje všude) se nahradí mezerou, ne smažou - jinak by se slova bez mezery kolem
     * zakázaného znaku slepila dohromady.
     */
    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(120)
        return cleaned.ifBlank { "bez_nazvu" }
    }

    /**
     * Číslo kapitoly zarovnané nulami zleva, aby se soubory/složky v obyčejném průzkumníku
     * řadily ve správném pořadí čtení, ne abecedně ("Kapitola 10" před "Kapitola 2").
     * [Locale.ROOT] je tu záměrně - `String.format`/Kotlin `.format()` bez explicitního
     * locale používá SYSTÉMOVÝ locale telefonu, který u desetinné čárky/tečky není vždy
     * anglický (např. čeština má desetinnou ČÁRKU) - bez tohohle by "10.5" na některých
     * telefonech vyšlo jako "10,5", nekonzistentní jméno souboru napříč zařízeními.
     */
    private fun formattedChapterNumber(chapterNumber: Float): String =
        if (chapterNumber == chapterNumber.toLong().toFloat()) String.format(java.util.Locale.ROOT, "%04d", chapterNumber.toLong())
        else String.format(java.util.Locale.ROOT, "%06.1f", chapterNumber)

    /** "0001 - Název kapitoly" (nebo jen číslo, pokud kapitola nemá vlastní název odlišný od čísla) - viz [sanitizeFileName]/[formattedChapterNumber]. */
    fun chapterFolderName(chapterNumber: Float, chapterName: String): String {
        val num = formattedChapterNumber(chapterNumber)
        val cleanName = sanitizeFileName(chapterName)
        return if (cleanName.isBlank() || cleanName == "bez_nazvu") num else "$num - $cleanName"
    }

    /** Vytvoří (případně dohledá již existující) vnořenou složku manga/kapitola a vrátí její "localPath" (File path nebo content URI). */
    fun createChapterDir(context: Context, downloadFolderUri: String?, mangaFolderName: String, chapterFolderName: String): String {
        val safeMangaFolder = sanitizeFileName(mangaFolderName)
        if (downloadFolderUri != null) {
            var current: DocumentFile? = DocumentFile.fromTreeUri(context, Uri.parse(downloadFolderUri))
            for (segment in listOf(safeMangaFolder, chapterFolderName)) {
                // Složka pro mangu se mezi jednotlivými kapitolami opakuje (stahování víc
                // kapitol téže mangy) - findFile/reuse, ne vytvořit duplicitní "One Piece (1)".
                val existing = current?.findFile(segment)?.takeIf { it.isDirectory }
                current = existing ?: current?.createDirectory(segment)
            }
            if (current != null) return current.uri.toString()
        }
        val dir = File(context.filesDir, "downloads/$safeMangaFolder/$chapterFolderName")
        dir.mkdirs()
        return dir.absolutePath
    }

    /**
     * Zapíše jednu stránku na disk. Vrací `false`, pokud zápis v SAF režimu tiše selže
     * (DocumentFile/openOutputStream může vrátit null bez vyhození výjimky - typicky
     * plná/odpojená cílová složka, zrušené oprávnění k URI) - volající (viz
     * [com.haise.jiyu.download.ChapterDownloadWorker]) na `false` reaguje jako na chybu
     * stahování, místo aby kapitolu omylem označil za kompletně staženou s chybějící
     * stránkou. Plain-`File` větev `false` nikdy nevrací - `writeBytes` při selhání sama
     * vyhodí výjimku, která propadne stejnou cestou výš.
     */
    fun writePage(context: Context, dirPath: String, fileName: String, bytes: ByteArray): Boolean {
        if (isSaf(dirPath)) {
            val dir = DocumentFile.fromSingleUri(context, Uri.parse(dirPath)) ?: return false
            val mime = when (fileName.substringAfterLast('.', "").lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> "image/jpeg"
            }
            val file = dir.createFile(mime, fileName) ?: return false
            return context.contentResolver.openOutputStream(file.uri)?.use { it.write(bytes) } != null
        } else {
            File(dirPath, fileName).writeBytes(bytes)
            return true
        }
    }

    /** Už staženo z předchozího (přerušeného) pokusu - viz skip-already-downloaded logika ve worker page loopu. */
    fun pageExists(context: Context, dirPath: String, fileName: String): Boolean {
        return try {
            if (isSaf(dirPath)) {
                val file = DocumentFile.fromSingleUri(context, Uri.parse(dirPath))?.findFile(fileName)
                file != null && file.exists() && file.length() > 0
            } else {
                val file = File(dirPath, fileName)
                file.exists() && file.length() > 0
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Rychlý předletový kanárek PŘED stahováním - u plain-`File` úložiště ověří volné místo
     * na cílovém filesystému, u SAF (může mířit i na synchronizovanou cloudovou složku,
     * u které volné místo nejde spolehlivě zjistit) se vždy vrátí `true` a spoléhá se na
     * ENOSPC detekci v [com.haise.jiyu.download.ChapterDownloadWorker] až při samotném zápisu.
     */
    fun hasEnoughFreeSpace(dirPath: String, minFreeBytes: Long = MIN_FREE_BYTES_FOR_DOWNLOAD): Boolean {
        if (isSaf(dirPath)) return true
        return try {
            File(dirPath).usableSpace >= minFreeBytes
        } catch (_: Exception) {
            true
        }
    }

    private const val MIN_FREE_BYTES_FOR_DOWNLOAD = 20L * 1024 * 1024

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

    /** Vytvoří .cbz archiv se všemi stránkami kapitoly (uvnitř stejné složky u SAF, jako sourozenec u File) pod čitelným jménem [cbzBaseName] (bez přípony). */
    fun createCbz(context: Context, dirPath: String, cbzBaseName: String) {
        val safeName = sanitizeFileName(cbzBaseName)
        if (isSaf(dirPath)) {
            val dir = DocumentFile.fromSingleUri(context, Uri.parse(dirPath)) ?: return
            val cbzFile = dir.createFile("application/vnd.comicbook+zip", "$safeName.cbz") ?: return
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
            val cbzFile = File(chapterDir.parent, "$safeName.cbz")
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
        } catch (e: Exception) {
            e.report("storage:deleteChapterFiles")
        }
    }
}
