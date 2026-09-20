package com.haise.jiyu.source.mangaplus

import com.haise.jiyu.util.rethrowIfControl
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MANGA Plus by Shueisha (mangaplus.shueisha.co.jp) — zdarma, legální.
 * Zobrazuje první + nejnovější kapitoly titulů Shueisha (One Piece, Naruto, Jujutsu Kaisen…).
 *
 * Puvodni endpointy (jumpg-webapi.tokyo-cdn.com/api/{allV2,title_detail,...})
 * ted vraci "Account Banned" pro kazdy pozadavek, nezavisle na User-Agent/
 * hlavickach - neni to fingerprinting, ale chybejici registrace zarizeni.
 * Skutecny host aplikace je `jumpg-api.tokyo-cdn.com` a kazdy pozadavek
 * (krome sameho registrovani) musi nest query parametr `secret` ziskany
 * jednorazovou registraci zarizeni:
 *   device_token  = MD5(nahodne device_id)
 *   security_key  = MD5(device_token + "4Kin9vGg")
 *   PUT /api/register?device_token=...&security_key=...&os=android&os_ver=..&app_ver=..
 *   -> success.registerationData.deviceSecret
 * (reverse-engineerovano a zdokumentovano v github.com/akitaonrails/frank_mangaplus
 * a github.com/hyugogirubato/mangaplus, oboje overeno zivym testem proti API).
 *
 * API vrací protobuf binary; parsujeme ručně bez external závislosti (viz MangaPlusProto.kt).
 * Pole response.success = field 1, uvnitř:
 *   field 35 → SearchView (title_list/all_v3 novy endpoint vraci seznam titulu
 *              zabaleny tady, ne uz v AllTitlesViewV2 jako stary allV2 endpoint;
 *              SearchView.allTitlesGroup = field 3 pouziva ale stejnou vnitrni
 *              strukturu jako drive)
 *   field  8 → TitleDetailView (detail + seznam kapitol, kapitoly na poli 38 -
 *              chapterListV2, ne uz 9/10 jako stary title_detail endpoint)
 *   field 10 → MangaViewer (stránky kapitoly)
 */
@Singleton
class MangaPlusSource @Inject constructor(
    private val client: OkHttpClient,
) : MangaSource {

    override val id = "mangaplus"
    override val name = "MANGA Plus"
    override val homepageUrl get() = "https://mangaplus.shueisha.co.jp"

    private val apiBase = "https://jumpg-api.tokyo-cdn.com/api"
    private val appVersion = 237
    private val osVersion = 35

    private companion object {
        /** Viz [ensureSecret] doc - jak dlouho po selhané registraci appka další pokus odloží. */
        const val REGISTER_FAILURE_COOLDOWN_MS = 30_000L
    }

    @Volatile private var deviceSecret: String? = null
    /** Kdy naposledy selhala registrace (viz [ensureSecret]) - 0 = jeste nikdy. */
    @Volatile private var lastRegisterFailureAt: Long = 0L

    private fun md5(s: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun rawGet(url: String, method: String = "GET"): ByteArray {
        // PUT/POST vyzaduji neprazdne (i kdyz treba nulove delky) telo pozadavku.
        val body = if (method == "PUT" || method == "POST") ByteArray(0).toRequestBody() else null
        // Bez rucne nastavene Accept-Encoding hlavicky - OkHttp si gzip odpovedi
        // dekomprimuje automaticky sam. Kdyby si ji nastavil klient rucne (jak
        // to delal puvodni kod), OkHttp tuhle automatickou dekompresi vypne a
        // parseProto() by dostal syrove gzipovane bajty misto protobufu.
        val request = Request.Builder()
            .url(url)
            .method(method, body)
            .header("User-Agent", "okhttp/4.12.0")
            .build()
        return client.newCall(request).execute().use { response ->
            // IOException, ne tiche ByteArray(0) - jinak nejde rozlisit "server/secret ma
            // problem" od "opravdu prazdna odpoved", a jednou ziskany deviceSecret platil
            // navzdy i po banu (nahlaseno v auditu). Navic tim MangaPlus zapadne do sdileneho
            // RetryInterceptoru (chyta jen IOException).
            if (!response.isSuccessful) throw java.io.IOException("MangaPlus API chyba ${response.code}: $url")
            response.body?.bytes() ?: ByteArray(0)
        }
    }

    /**
     * Registruje nahodne "zarizeni" a vrati free-tier deviceSecret; vysledek se cachuje po
     * dobu behu appky. Cooldown po chybe brani tomu, aby kazdy dalsi pozadavek behem
     * vypadku/rate-limitu MangaPlus API znovu spustil cely `PUT /register` (spam registrace
     * vuci jejich API presne v okamziku, kdy uz maji problem).
     */
    private fun ensureSecret(): String? {
        deviceSecret?.let { return it }
        synchronized(this) {
            deviceSecret?.let { return it }
            val now = System.currentTimeMillis()
            if (now - lastRegisterFailureAt < REGISTER_FAILURE_COOLDOWN_MS) return null
            val deviceId = UUID.randomUUID().toString()
            val deviceToken = md5(deviceId)
            val securityKey = md5(deviceToken + "4Kin9vGg")
            return try {
                val bytes = rawGet(
                    "$apiBase/register?device_token=$deviceToken&security_key=$securityKey&os=android&os_ver=$osVersion&app_ver=$appVersion",
                    method = "PUT",
                )
                val secret = bytes.parseProto().msg(1)?.msg(2)?.str(1)
                if (secret == null) lastRegisterFailureAt = now else deviceSecret = secret
                secret
            } catch (e: java.io.IOException) {
                lastRegisterFailureAt = now
                null
            }
        }
    }

    private fun get(path: String, params: String): ByteArray {
        val secret = ensureSecret()
        val base = "$apiBase/$path?$params&os=android&os_ver=$osVersion&app_ver=$appVersion"
        val url = if (secret != null) "$base&secret=$secret" else base
        return rawGet(url)
    }

    override suspend fun getPopular(page: Int, filter: com.haise.jiyu.source.MangaFilter): List<SManga> = withContext(Dispatchers.IO) {
        try {
            if (filter.sortBy == "latest") {
                // title_list/updated vraci "Updates" feed (tituly serazene podle casu
                // posledni aktualizace, pole 20 -> repeated UpdatedTitle{Title title=1,
                // string updated_at=2}) - overeno zive: poradi je jine nez u
                // title_list/all_v3 (ten je razeny do AllTitlesGroup, ne dle aktualnosti).
                val bytes = get("title_list/updated", "lang=eng&clang=eng")
                val success = bytes.parseProto().msg(1) ?: return@withContext emptyList()
                val updated = success.msg(20) ?: return@withContext emptyList()
                updated.msgs(1)                     // repeated UpdatedTitle
                    .mapNotNull { it.msg(1) }        // Title
                    .distinctBy { it.long(1) }
                    .map { it.toSManga() }
                    .filter { it.url.isNotEmpty() }
            } else {
                val bytes = get("title_list/all_v3", "type=serializing&lang=eng&clang=eng")
                val success = bytes.parseProto().msg(1) ?: return@withContext emptyList()
                // title_list/all_v3 vraci obsah zabaleny v SearchView (field 35), ne uz
                // v AllTitlesViewV2 (field 25) jako stary allV2 endpoint - AllTitlesGroup
                // uvnitr je ale stejna struktura (SearchView.allTitlesGroup = field 3).
                val view = success.msg(35) ?: return@withContext emptyList()
                view.msgs(3)                       // repeated AllTitlesGroup
                    .flatMap { it.msgs(2) }        // repeated Title
                    .distinctBy { it.long(1) }
                    .map { it.toSManga() }
                    .filter { it.url.isNotEmpty() }
            }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun search(query: String, page: Int, filter: com.haise.jiyu.source.MangaFilter): List<SManga> {
        if (page > 1) return emptyList()
        return getPopular().filter { it.title.contains(query, ignoreCase = true) }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        val bytes = get("title_detailV3", "title_id=${manga.url}&lang=eng&clang=eng")
        val success = bytes.parseProto().msg(1) ?: return@withContext manga
        val view = success.msg(8) ?: return@withContext manga
        view.msg(1)?.toSManga() ?: manga
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            val bytes = get("title_detailV3", "title_id=${manga.url}&lang=eng&clang=eng")
            val success = bytes.parseProto().msg(1) ?: return@withContext emptyList()
            val view = success.msg(8) ?: return@withContext emptyList()
            // title_detailV3 vraci kapitoly na poli 38 (chapterListV2), ne uz na
            // 9/10 (firstChapterList/lastChapterList) jako stary title_detail endpoint.
            view.msgs(38)
                .distinctBy { it.long(2) }
                .sortedByDescending { it.long(6) ?: 0L }
                .mapNotNull { it.toSChapter(manga.url) }
        } catch (e: Exception) { e.rethrowIfControl(); emptyList() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        val bytes = get("manga_viewer", "chapter_id=${chapter.url}&split=yes&img_quality=high")
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
        // "name" je casto ve tvaru "#001" - skutecne cislo kapitoly, na rozdil
        // od chapterId (nesouvisejici interni DB id, napr. 1029917 - proto se sem NIKDY
        // nefallbackuje, i kdyz by toFloatOrNull() na nem uspel, viz nahlaseny bug).
        val chapterNumber = Regex("""(\d+(?:\.\d+)?)""").find(name)?.value?.toFloatOrNull() ?: 0f
        return SChapter(
            sourceId = id,
            mangaUrl = mangaUrl,
            url = chapterId,
            name = displayName,
            chapterNumber = chapterNumber,
            dateUpload = (long(6) ?: 0L) * 1000L,
        )
    }
}
