package com.haise.jiyu.source.mangaplus

import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MANGA Plus by Shueisha (mangaplus.shueisha.co.jp) — zdarma, legální.
 * Zobrazuje první + nejnovější kapitoly titulů Shueisha (One Piece, Naruto, Jujutsu Kaisen…).
 *
 * API vrací protobuf binary; parsujeme ručně bez external závislosti (viz MangaPlusProto.kt).
 * Pole response.success = field 1, uvnitř:
 *   field 25 → AllTitlesViewV2 (seznam všech titulů)
 *   field  8 → TitleDetailView (detail + seznam kapitol)
 *   field 10 → MangaViewer (stránky kapitoly)
 */
@Singleton
class MangaPlusSource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "mangaplus"
    override val name = "MANGA Plus"

    private val apiBase = "https://jumpg-webapi.tokyo-cdn.com"

    // Vrátí první + poslední kapitoly jako populární tituly
    override suspend fun getPopular(page: Int): List<SManga> = withContext(Dispatchers.IO) {
        val bytes = get("$apiBase/api/title_list/allV2")
        val success = bytes.parseProto().msg(1) ?: return@withContext emptyList()
        val view = success.msg(25) ?: return@withContext emptyList()
        view.msgs(1)                       // repeated UpdatedTitleV2Group
            .flatMap { it.msgs(2) }        // repeated Title
            .distinctBy { it.long(1) }
            .map { it.toSManga() }
            .filter { it.url.isNotEmpty() }
    }

    // MANGA Plus nemá search API — filtrujeme lokálně ze seznamu všech titulů
    override suspend fun search(query: String, page: Int): List<SManga> =
        getPopular().filter { it.title.contains(query, ignoreCase = true) }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        val bytes = get("$apiBase/api/title_detail?title_id=${manga.url}")
        val success = bytes.parseProto().msg(1) ?: return@withContext manga
        val view = success.msg(8) ?: return@withContext manga
        view.msg(1)?.toSManga() ?: manga
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        val bytes = get("$apiBase/api/title_detail?title_id=${manga.url}")
        val success = bytes.parseProto().msg(1) ?: return@withContext emptyList()
        val view = success.msg(8) ?: return@withContext emptyList()
        // field 5 = firstChapterList, field 6 = lastChapterList
        (view.msgs(5) + view.msgs(6))
            .distinctBy { it.long(2) }
            .sortedByDescending { it.long(7) ?: 0L }
            .mapNotNull { it.toSChapter(manga.url) }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        val bytes = get("$apiBase/api/manga_viewer?chapter_id=${chapter.url}&split=yes&img_quality=high")
        val success = bytes.parseProto().msg(1) ?: return@withContext emptyList()
        val viewer = success.msg(10) ?: return@withContext emptyList()
        viewer.msgs(1)                  // repeated MangaPage
            .mapIndexedNotNull { idx, mangaPage ->
                val pageProto = mangaPage.msg(1) ?: return@mapIndexedNotNull null
                val imageUrl = pageProto.str(1) ?: return@mapIndexedNotNull null
                val encKey = pageProto.str(5)?.takeIf { it.isNotBlank() }
                // Klíč kódujeme do URL fragmentu; MangaPlusImageFetcher ho při načítání dešifruje
                val finalUrl = if (encKey != null) "$imageUrl#mplus_key=$encKey" else imageUrl
                Page(index = idx, url = imageUrl, imageUrl = finalUrl)
            }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun ProtoMsg.toSManga(): SManga {
        val titleId = long(1)?.toString() ?: return SManga(id, "", "", null)
        return SManga(
            sourceId = id,
            url = titleId,
            title = str(2) ?: "",
            coverUrl = str(4),
        )
    }

    private fun ProtoMsg.toSChapter(mangaUrl: String): SChapter? {
        val chapterId = long(2)?.toString() ?: return null
        val name = str(3) ?: return null
        val subTitle = str(4)?.takeIf { it.isNotBlank() }
        val displayName = if (subTitle != null) "$name: $subTitle" else name
        return SChapter(
            sourceId = id,
            mangaUrl = mangaUrl,
            url = chapterId,
            name = displayName,
            chapterNumber = chapterId.toFloatOrNull() ?: 0f,
            dateUpload = (long(7) ?: 0L) * 1000L,
        )
    }

    /**
     * Vraci prazdne pole misto vyhozeni vyjimky, pokud odpoved neni uspesna
     * nebo nema telo (napr. expirovana/geoblokovana kapitola) - parseProto()
     * na prazdnem poli vrati prazdnou mapu a volajici uz na to maji fallback.
     */
    private fun get(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "okhttp/4.12.0")
            .header("X-Device-Type", "3")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use ByteArray(0)
            response.body?.bytes() ?: ByteArray(0)
        }
    }
}
