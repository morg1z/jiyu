package com.haise.jiyu.source

import com.haise.jiyu.data.db.CustomSourceDao
import com.haise.jiyu.source.batoto.BatoToSource
import com.haise.jiyu.source.comick.ComicKSource
import com.haise.jiyu.source.dynasty.DynastySource
import com.haise.jiyu.source.hitomi.HitomiSource
import com.haise.jiyu.source.mangafire.MangaFireSource
import com.haise.jiyu.source.mangapark.MangaParkSource
import com.haise.jiyu.source.evilmanga.EvilMangaSource
import com.haise.jiyu.source.mangaboomers.MangaBoomersSource
import com.haise.jiyu.source.mangago.MangagoSource
import com.haise.jiyu.source.asurascans.AsuraScansSource
import com.haise.jiyu.source.flamecomics.FlameComicsSource
import com.haise.jiyu.source.reaperscans.ReaperScansSource
import com.haise.jiyu.source.mangalek.MangaLekSource
import com.haise.jiyu.source.rawkuma.RawKumaSource
import com.haise.jiyu.source.comic.ComicBookPlusSource
import com.haise.jiyu.source.comic.GetComicsSource
import com.haise.jiyu.source.comic.ReadFreeComicsOnlineSource
import com.haise.jiyu.source.novelfull.NovelFullSource
import com.haise.jiyu.source.freewebnovel.FreeWebNovelSource
import com.haise.jiyu.source.nhentai.NhentaiSource
import com.haise.jiyu.source.madara.MadaraSelectors
import com.haise.jiyu.source.madara.MadaraSource
import com.haise.jiyu.source.mangadex.MangaDexSource
import com.haise.jiyu.source.mangaplus.MangaPlusSource
import com.haise.jiyu.source.webtoon.WebtoonSource
import com.haise.jiyu.source.manganato.MangaNatoSource
import com.haise.jiyu.source.royalroad.RoyalRoadSource
import com.haise.jiyu.source.scribblehub.ScribbleHubSource
import com.haise.jiyu.source.mangahub.MangaHubSource
import com.haise.jiyu.source.mangafreak.MangaFreakSource
import com.haise.jiyu.source.weebcentral.WeebCentralSource
import com.haise.jiyu.source.vortexscans.VortexScansSource
import com.haise.jiyu.source.mangak.MangaKSource
import com.haise.jiyu.source.i18n.JapscanSource
import com.haise.jiyu.source.i18n.AnimeSamaSource
import com.haise.jiyu.source.i18n.ScanVFSource
import com.haise.jiyu.source.i18n.TMOSource
import com.haise.jiyu.source.i18n.InMangaSource
import com.haise.jiyu.source.i18n.MangaLeerSource
import com.haise.jiyu.source.i18n.UnionMangasSource
import com.haise.jiyu.source.mangadotnet.MangaDotNetSource
import com.haise.jiyu.source.kaliscan.KaliScanSource
import com.haise.jiyu.source.mangacloud.MangaCloudSource
import com.haise.jiyu.source.galaxymanga.GalaxyMangaSource
import com.haise.jiyu.source.kuramanga.KuraMangaSource
import com.haise.jiyu.source.lightnovelworld.LightNovelWorldSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centrální registr zdrojů. Statické zdroje (MangaDex, MANGA Plus, ComicK)
 * jsou pevně dané; k nim se přidávají uživatelem nakonfigurované generické
 * Madara zdroje z `CustomSourceDao` - proto je seznam reaktivní (Flow),
 * ne statický snapshot.
 */
@Singleton
class SourceManager @Inject constructor(
    mangaDexSource: MangaDexSource,
    mangaPlusSource: MangaPlusSource,
    comicKSource: ComicKSource,
    hitomiSource: HitomiSource,
    nhentaiSource: NhentaiSource,
    mangaFireSource: MangaFireSource,
    batoToSource: BatoToSource,
    webtoonSource: WebtoonSource,
    dynastySource: DynastySource,
    mangaParkSource: MangaParkSource,
    novelFullSource: NovelFullSource,
    freeWebNovelSource: FreeWebNovelSource,
    evilMangaSource: EvilMangaSource,
    mangaBoomersSource: MangaBoomersSource,
    mangagoSource: MangagoSource,
    asuraScansSource: AsuraScansSource,
    flameComicsSource: FlameComicsSource,
    reaperScansSource: ReaperScansSource,
    mangaLekSource: MangaLekSource,
    rawKumaSource: RawKumaSource,
    comicBookPlusSource: ComicBookPlusSource,
    getComicsSource: GetComicsSource,
    readFreeComicsOnlineSource: ReadFreeComicsOnlineSource,
    mangaNatoSource: MangaNatoSource,
    royalRoadSource: RoyalRoadSource,
    scribbleHubSource: ScribbleHubSource,
    mangaHubSource: MangaHubSource,
    mangaFreakSource: MangaFreakSource,
    weebCentralSource: WeebCentralSource,
    vortexScansSource: VortexScansSource,
    mangaKSource: MangaKSource,
    japscanSource: JapscanSource,
    animeSamaSource: AnimeSamaSource,
    scanVFSource: ScanVFSource,
    tmoSource: TMOSource,
    inMangaSource: InMangaSource,
    mangaLeerSource: MangaLeerSource,
    unionMangasSource: UnionMangasSource,
    mangaDotNetSource: MangaDotNetSource,
    kaliScanSource: KaliScanSource,
    mangaCloudSource: MangaCloudSource,
    galaxyMangaSource: GalaxyMangaSource,
    kuraMangaSource: KuraMangaSource,
    lightNovelWorldSource: LightNovelWorldSource,
    private val customSourceDao: CustomSourceDao,
    private val client: OkHttpClient,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _cache = MutableStateFlow<List<MangaSource>>(emptyList())

    private val staticSources: List<MangaSource> = listOf(
        mangaDexSource,
        mangaPlusSource,
        comicKSource,
        hitomiSource,
        nhentaiSource,
        mangaFireSource,
        batoToSource,
        webtoonSource,
        dynastySource,
        mangaParkSource,
        novelFullSource,
        freeWebNovelSource,
        evilMangaSource,
        mangaBoomersSource,
        mangagoSource,
        asuraScansSource,
        flameComicsSource,
        reaperScansSource,
        mangaLekSource,
        rawKumaSource,
        comicBookPlusSource,
        getComicsSource,
        readFreeComicsOnlineSource,
        mangaNatoSource,
        royalRoadSource,
        scribbleHubSource,
        mangaHubSource,
        mangaFreakSource,
        weebCentralSource,
        vortexScansSource,
        mangaKSource,
        // ── Manhua (čínské komiksy) ──────────────────────────────────────────
        MadaraSource("manhuafast",    "ManhuaFast",         "https://manhuafast.com",       client, contentTypeOverride = "MANHUA"),
        MadaraSource("manhuaplus",    "Manhuaplus",         "https://manhuaplus.com",       client, contentTypeOverride = "MANHUA"),
        MadaraSource("zinmanga",      "ZinManga",           "https://zinmanga.com",         client, contentTypeOverride = "MANHUA"),
        MadaraSource("manhuaes",      "ManhuaES",           "https://manhuaes.com",         client, contentTypeOverride = "MANHUA"),
        MadaraSource("kunmanga",      "Kunmanga",           "https://kunmanga.com",         client, contentTypeOverride = "MANHUA"),
        MadaraSource("manhuascan",    "ManhuaScan",         "https://manhuascan.com",       client, contentTypeOverride = "MANHUA"),
        MadaraSource("mangayo",       "MangaYo",            "https://mangayo.com",          client, contentTypeOverride = "MANHUA"),
        MadaraSource("manhuarock",    "ManhuaRock",         "https://manhuarock.cc",        client, contentTypeOverride = "MANHUA"),
        // ── Manhwa scanlation skupiny ────────────────────────────────────────
        MadaraSource("cosmicscans",   "Cosmic Scans",       "https://cosmicscans.org",      client, contentTypeOverride = "MANHWA"),
        MadaraSource("nightscans",    "Night Scans",        "https://nightscans.net",       client, contentTypeOverride = "MANHWA"),
        MadaraSource("manhwatop",     "Manhwatop",          "https://manhwatop.com",        client, contentTypeOverride = "MANHWA"),
        MadaraSource("voidscans",     "Void Scans",         "https://voidscans.net",        client, contentTypeOverride = "MANHWA"),
        MadaraSource("drakescans",    "Drake Scans",        "https://drakescans.com",       client, contentTypeOverride = "MANHWA"),
        MadaraSource("realmscans",    "Realm Scans",        "https://realmscans.xyz",       client, contentTypeOverride = "MANHWA"),
        MadaraSource("leviathanscans","Leviathan Scans",    "https://leviathanscans.com",   client, contentTypeOverride = "MANHWA"),
        MadaraSource("immortalupdates","Immortal Updates",  "https://immortalupdates.com",  client, contentTypeOverride = "MANHWA"),
        MadaraSource("astrascan",     "Astra Scans",        "https://astra-scans.net",      client, contentTypeOverride = "MANHWA"),
        MadaraSource("mangaeffects",  "MangaEffects",       "https://mangaeffects.com",     client, contentTypeOverride = "MANHWA"),
        MadaraSource("isekaiscan",    "IsekaiScan",         "https://isekaiscan.eu",        client, contentTypeOverride = "MANHWA"),
        MadaraSource("infernalvoid",  "Infernal Void Scans","https://infernalvoidscans.com",client, contentTypeOverride = "MANHWA"),
        MadaraSource("zeroscans",     "Zero Scans",         "https://zeroscans.com",        client, contentTypeOverride = "MANHWA"),
        // ── Manga agregátory a scanlation ────────────────────────────────────
        MadaraSource("mangatx",       "MangaTx",            "https://mangatx.com",          client, contentTypeOverride = "MANGA"),
        MadaraSource("mangakiss",     "MangaKiss",          "https://mangakiss.net",        client, contentTypeOverride = "MANGA"),
        MadaraSource("manga68",       "Manga68",            "https://manga68.com",          client, contentTypeOverride = "MANGA"),
        MadaraSource("mangabuddy",    "MangaBuddy",         "https://mangabuddy.com",       client, contentTypeOverride = "MANGA"),
        // ── Novely (Madara/WordPress varianta) ───────────────────────────────
        MadaraSource("foxaholic",     "Foxaholic",          "https://foxaholic.com",        client, contentTypeOverride = "NOVEL"),
        MadaraSource("hostednovel",   "HostedNovel",        "https://hostednovel.com",      client, contentTypeOverride = "NOVEL"),
        MadaraSource("creativenovels","Creative Novels",    "https://creativenovels.com",   client, contentTypeOverride = "NOVEL"),
        // ── Manhwa — další populární scanlace ───────────────────────────────
        MadaraSource("azuremanga",    "Azure Manga",        "https://azuremanga.com",       client, contentTypeOverride = "MANHWA"),
        MadaraSource("demonscans",    "Demon Scans",        "https://demonscans.net",       client, contentTypeOverride = "MANHWA"),
        MadaraSource("disasterscans", "Disaster Scans",     "https://disasterscans.com",    client, contentTypeOverride = "MANHWA"),
        MadaraSource("freakscans",    "Freak Scans",        "https://freakscans.com",       client, contentTypeOverride = "MANHWA"),
        MadaraSource("hivecomic",     "Hive Scans",         "https://hivescans.com",        client, contentTypeOverride = "MANHWA"),
        MadaraSource("magicscans",    "Magic Scans",        "https://magicscans.net",       client, contentTypeOverride = "MANHWA"),
        MadaraSource("mangamotto",    "MangaMotto",         "https://mangamoto.com",        client, contentTypeOverride = "MANHWA"),
        MadaraSource("mm-scans",      "MM Scans",           "https://mm-scans.org",         client, contentTypeOverride = "MANHWA"),
        MadaraSource("reaperscanseu", "Reaper Scans EU",    "https://reapercomics.com",     client, contentTypeOverride = "MANHWA"),
        MadaraSource("suryascans",    "Surya Scans",        "https://suryascans.com",       client, contentTypeOverride = "MANHWA"),
        MadaraSource("tempestmanga",  "Tempest Manga",      "https://tempestmanga.com",     client, contentTypeOverride = "MANHWA"),
        MadaraSource("trillerscans",  "Triller Scans",      "https://trillercans.com",      client, contentTypeOverride = "MANHWA"),
        MadaraSource("xcalibrscans",  "Xcalibr Scans",      "https://xcalibrscans.com",     client, contentTypeOverride = "MANHWA"),
        // ── Manhua — další ────────────────────────────────────────────────────
        MadaraSource("manhuabuddy",   "ManhuaBuddy",        "https://manhuabuddy.com",      client, contentTypeOverride = "MANHUA"),
        MadaraSource("manhuacat",     "ManhuaCat",          "https://manhuacat.com",        client, contentTypeOverride = "MANHUA"),
        MadaraSource("manhuaonline",  "ManhuaOnline",       "https://manhuaonline.co",      client, contentTypeOverride = "MANHUA"),
        MadaraSource("topmanhua",     "TopManhua",          "https://topmanhua.com",        client, contentTypeOverride = "MANHUA"),
        // ── Manga — další populární weby ─────────────────────────────────────
        MadaraSource("mangarosie",    "MangaRosie",         "https://mangarosie.in",        client, contentTypeOverride = "MANGA"),
        MadaraSource("mangapt",       "MangaPT",            "https://mangapt.com",          client, contentTypeOverride = "MANGA"),
        MadaraSource("mangatoto",     "MangaToto",          "https://mangatoto.com",        client, contentTypeOverride = "MANGA"),
        MadaraSource("woopread",      "WoopRead",           "https://woopread.com",         client, contentTypeOverride = "MANGA"),
        MadaraSource("toonily",       "Toonily",            "https://toonily.com",          client, contentTypeOverride = "MANHWA"),
        MadaraSource("madaradex",     "MadaraDex",          "https://madaradex.org",        client, contentTypeOverride = "MANGA"),
        MadaraSource("mangazin",      "Mangazin",           "https://mangazin.org",         client, contentTypeOverride = "MANHUA"),
        MadaraSource("cocomic",       "Cocomic",            "https://cocomic.co",           client, contentTypeOverride = "MANHWA"),
        MadaraSource("mangagg",       "MangaGG",            "https://mangagg.com",          client, contentTypeOverride = "MANHUA"),
        // manhwaz.com pouziva vlastni permalinky ("/webtoon/{slug}" misto
        // "/manga/{slug}", "/genre/manga?page=N" pro archiv, "/search?s=..."
        // pro hledani) - proto vlastni popularUrl/searchUrl misto vychozich.
        MadaraSource(
            "manhwaz", "Manhwaz", "https://manhwaz.com", client,
            contentTypeOverride = "MANHWA",
            popularUrl = { root, page, _ -> "$root/genre/manga?page=$page" },
            searchUrl = { root, query, page -> "$root/search?s=$query&page=$page" },
        ),
        MadaraSource(
            "aquareader", "Aqua Manga", "https://aquareader.org", client,
            selectors = MadaraSelectors(
                listItem = "article.aqua-archive-card",
                titleLink = "h3.aqua-archive-card__title a",
                description = "div.aqua-series-synopsis",
                status = "span.aqua-series-meta__status",
                chapterList = "a.aqua-ch-item",
            ),
            contentTypeOverride = "MANGA",
        ),
        // ── Italské zdroje 🇮🇹 ───────────────────────────────────────────────
        MadaraSource("mangaworld",    "MangaWorld (IT)",    "https://www.mangaworld.ac",    client, contentTypeOverride = "MANGA"),
        MadaraSource("mangafuture",   "MangaFuture (IT)",   "https://www.mangafuture.it",   client, contentTypeOverride = "MANGA"),
        // ── Německé zdroje 🇩🇪 ───────────────────────────────────────────────
        MadaraSource("mangatube",     "MangaTube (DE)",     "https://www.mangatube.net",    client, contentTypeOverride = "MANGA"),
        MadaraSource("manhwade",      "ManhwaDE",           "https://manhwa.de",            client, contentTypeOverride = "MANHWA"),
        // ── Turecké zdroje 🇹🇷 ───────────────────────────────────────────────
        MadaraSource("mangadenizi",   "MangaDenizi (TR)",   "https://mangadenizi.net",      client, contentTypeOverride = "MANGA"),
        MadaraSource("okumangas",     "OkuManga (TR)",      "https://okumangas.com",        client, contentTypeOverride = "MANGA"),
        // ── Francouzské zdroje 🇫🇷 ──────────────────────────────────────────
        japscanSource,
        animeSamaSource,
        scanVFSource,
        // ── Španělské a portugalské zdroje 🇪🇸🇧🇷 ──────────────────────────
        tmoSource,
        inMangaSource,
        mangaLeerSource,
        unionMangasSource,
        // ── Noví kandidáti (jednoduchý vlastní scraping) ─────────────────────
        mangaDotNetSource,
        kaliScanSource,
        mangaCloudSource,
        galaxyMangaSource,
        kuraMangaSource,
        // ── Novely (nový vlastní scraping) ───────────────────────────────────
        lightNovelWorldSource,
    )

    init {
        scope.launch {
            customSourceDao.observeAll().collect { customs ->
                _cache.value = staticSources + customs.map { custom ->
                    val defaults = MadaraSelectors.DEFAULT
                    MadaraSource(
                        id = "madara:${custom.id}",
                        name = custom.name,
                        baseUrl = custom.baseUrl,
                        client = client,
                        selectors = MadaraSelectors(
                            listItem = custom.listItemSelector?.ifBlank { null } ?: defaults.listItem,
                            titleLink = custom.titleLinkSelector?.ifBlank { null } ?: defaults.titleLink,
                            description = custom.descriptionSelector?.ifBlank { null } ?: defaults.description,
                            status = custom.statusSelector?.ifBlank { null } ?: defaults.status,
                            chapterList = custom.chapterListSelector?.ifBlank { null } ?: defaults.chapterList,
                            pageImage = custom.pageImageSelector?.ifBlank { null } ?: defaults.pageImage,
                        ),
                        contentTypeOverride = custom.contentType,
                    )
                }
            }
        }
    }

    fun observeAll(): Flow<List<MangaSource>> = _cache

    suspend fun getAll(): List<MangaSource> = _cache.filter { it.isNotEmpty() }.first()

    suspend fun getById(id: String): MangaSource? = getAll().find { it.id == id }
}
