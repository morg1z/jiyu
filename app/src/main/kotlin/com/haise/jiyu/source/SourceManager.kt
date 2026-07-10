package com.haise.jiyu.source

import com.haise.jiyu.data.db.CustomSourceDao
import com.haise.jiyu.source.batoto.BatoToSource
import com.haise.jiyu.source.comick.ComicKSource
import com.haise.jiyu.source.dynasty.DynastySource
import com.haise.jiyu.source.hitomi.HitomiSource
import com.haise.jiyu.source.mangafire.MangaFireSource
import com.haise.jiyu.source.mangapark.MangaParkSource
import com.haise.jiyu.source.mangareader.MangaReaderSource
import com.haise.jiyu.source.boxnovel.BoxNovelSource
import com.haise.jiyu.source.evilmanga.EvilMangaSource
import com.haise.jiyu.source.mangaboomers.MangaBoomersSource
import com.haise.jiyu.source.mangago.MangagoSource
import com.haise.jiyu.source.lightnovelworld.LightNovelWorldSource
import com.haise.jiyu.source.asurascans.AsuraScansSource
import com.haise.jiyu.source.flamecomics.FlameComicsSource
import com.haise.jiyu.source.reaperscans.ReaperScansSource
import com.haise.jiyu.source.mangalek.MangaLekSource
import com.haise.jiyu.source.rawkuma.RawKumaSource
import com.haise.jiyu.source.comic.ReadComicOnlineSource
import com.haise.jiyu.source.comic.ReadAllComicsSource
import com.haise.jiyu.source.comic.ViewComicSource
import com.haise.jiyu.source.comic.XoxoComicsSource
import com.haise.jiyu.source.comic.ZipComicSource
import com.haise.jiyu.source.comic.ComicPunchSource
import com.haise.jiyu.source.comic.ComicBookPlusSource
import com.haise.jiyu.source.comic.GetComicsSource
import com.haise.jiyu.source.comic.GoComicsSource
import com.haise.jiyu.source.comic.GlobalComixSource
import com.haise.jiyu.source.comic.ComicKingdomSource
import com.haise.jiyu.source.mangalife.MangaLifeSource
import com.haise.jiyu.source.novelfull.NovelFullSource
import com.haise.jiyu.source.mangasee.MangaSeeSource
import com.haise.jiyu.source.nhentai.NhentaiSource
import com.haise.jiyu.source.madara.MadaraSelectors
import com.haise.jiyu.source.madara.MadaraSource
import com.haise.jiyu.source.mangadex.MangaDexSource
import com.haise.jiyu.source.mangaplus.MangaPlusSource
import com.haise.jiyu.source.webtoon.WebtoonSource
import com.haise.jiyu.source.manganato.MangaNatoSource
import com.haise.jiyu.source.royalroad.RoyalRoadSource
import com.haise.jiyu.source.scribblehub.ScribbleHubSource
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
    mangaSeeSource: MangaSeeSource,
    mangaLifeSource: MangaLifeSource,
    mangaParkSource: MangaParkSource,
    mangaReaderSource: MangaReaderSource,
    boxNovelSource: BoxNovelSource,
    novelFullSource: NovelFullSource,
    lightNovelWorldSource: LightNovelWorldSource,
    evilMangaSource: EvilMangaSource,
    mangaBoomersSource: MangaBoomersSource,
    mangagoSource: MangagoSource,
    asuraScansSource: AsuraScansSource,
    flameComicsSource: FlameComicsSource,
    reaperScansSource: ReaperScansSource,
    mangaLekSource: MangaLekSource,
    rawKumaSource: RawKumaSource,
    readComicOnlineSource: ReadComicOnlineSource,
    readAllComicsSource: ReadAllComicsSource,
    viewComicSource: ViewComicSource,
    xoxoComicsSource: XoxoComicsSource,
    zipComicSource: ZipComicSource,
    comicPunchSource: ComicPunchSource,
    comicBookPlusSource: ComicBookPlusSource,
    getComicsSource: GetComicsSource,
    goComicsSource: GoComicsSource,
    globalComixSource: GlobalComixSource,
    comicKingdomSource: ComicKingdomSource,
    mangaNatoSource: MangaNatoSource,
    royalRoadSource: RoyalRoadSource,
    scribbleHubSource: ScribbleHubSource,
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
        mangaSeeSource,
        mangaLifeSource,
        mangaParkSource,
        mangaReaderSource,
        boxNovelSource,
        novelFullSource,
        lightNovelWorldSource,
        evilMangaSource,
        mangaBoomersSource,
        mangagoSource,
        asuraScansSource,
        flameComicsSource,
        reaperScansSource,
        mangaLekSource,
        rawKumaSource,
        readComicOnlineSource,
        readAllComicsSource,
        viewComicSource,
        xoxoComicsSource,
        zipComicSource,
        comicPunchSource,
        comicBookPlusSource,
        getComicsSource,
        goComicsSource,
        globalComixSource,
        comicKingdomSource,
        mangaNatoSource,
        royalRoadSource,
        scribbleHubSource,
        // ── Manhua (čínské komiksy) ──────────────────────────────────────────
        MadaraSource("manhuafast",  "ManhuaFast",      "https://manhuafast.com",  client, contentTypeOverride = "MANHUA"),
        MadaraSource("manhuaplus",  "Manhuaplus",      "https://manhuaplus.com",  client, contentTypeOverride = "MANHUA"),
        MadaraSource("zinmanga",    "ZinManga",        "https://zinmanga.com",    client, contentTypeOverride = "MANHUA"),
        MadaraSource("manhuaes",    "ManhuaES",        "https://manhuaes.com",    client, contentTypeOverride = "MANHUA"),
        // ── Manhwa scanlation skupiny ────────────────────────────────────────
        MadaraSource("1stkissmanga", "1st Kiss Manga", "https://1stkissmanga.io", client, contentTypeOverride = "MANHWA"),
        MadaraSource("cosmicscans", "Cosmic Scans",    "https://cosmicscans.org", client, contentTypeOverride = "MANHWA"),
        MadaraSource("nightscans",  "Night Scans",     "https://nightscans.net",  client, contentTypeOverride = "MANHWA"),
        MadaraSource("manhwatop",   "Manhwatop",       "https://manhwatop.com",   client, contentTypeOverride = "MANHWA"),
        MadaraSource("mangaclash",  "MangaClash",      "https://mangaclash.com",  client, contentTypeOverride = "MANGA"),
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
