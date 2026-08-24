package com.haise.jiyu.source

import com.haise.jiyu.data.db.CustomSourceDao
import com.haise.jiyu.source.comizy.ComizySource
import com.haise.jiyu.source.hivetoons.HiveToonsSource
import com.haise.jiyu.source.mangaworld.MangaWorldSource
import com.haise.jiyu.source.voidscans.VoidScansSource
import com.haise.jiyu.source.hostednovel.HostedNovelSource
import com.haise.jiyu.source.mangadenizi.MangaDeniziSource
import com.haise.jiyu.source.manhuabuddy.ManhuaBuddySource
import com.haise.jiyu.source.woopread.WoopReadSource
import com.haise.jiyu.source.dynasty.DynastySource
import com.haise.jiyu.source.hitomi.HitomiSource
import com.haise.jiyu.source.mangafire.MangaFireSource
import com.haise.jiyu.source.mangago.MangagoSource
import com.haise.jiyu.source.asurascans.AsuraScansSource
import com.haise.jiyu.source.flamecomics.FlameComicsSource
import com.haise.jiyu.source.comic.BatCaveSource
import com.haise.jiyu.source.comic.ComicBookPlusSource
import com.haise.jiyu.source.comic.ReadFreeComicsOnlineSource
import com.haise.jiyu.source.comicskingdom.ComicsKingdomSource
import com.haise.jiyu.source.novelfull.NovelFullSource
import com.haise.jiyu.source.freewebnovel.FreeWebNovelSource
import com.haise.jiyu.source.nhentai.NhentaiSource
import com.haise.jiyu.source.madara.MadaraSelectors
import com.haise.jiyu.source.madara.MadaraSource
import com.haise.jiyu.source.mangadex.MangaDexSource
import com.haise.jiyu.source.mangaplus.MangaPlusSource
import com.haise.jiyu.source.webtoon.WebtoonSource
import com.haise.jiyu.source.royalroad.RoyalRoadSource
import com.haise.jiyu.source.weebcentral.WeebCentralSource
import com.haise.jiyu.source.vortexscans.VortexScansSource
import com.haise.jiyu.source.mangak.MangaKSource
import com.haise.jiyu.source.i18n.JapscanSource
import com.haise.jiyu.source.i18n.AnimeSamaSource
import com.haise.jiyu.source.i18n.ScanVFSource
import com.haise.jiyu.source.mangadotnet.MangaDotNetSource
import com.haise.jiyu.source.kaliscan.KaliScanSource
import com.haise.jiyu.source.mangacloud.MangaCloudSource
import com.haise.jiyu.source.galaxymanga.GalaxyMangaSource
import com.haise.jiyu.source.kuramanga.KuraMangaSource
import com.haise.jiyu.source.novelfire.NovelFireSource
import com.haise.jiyu.source.wuxiabox.WuxiaBoxSource
import com.haise.jiyu.source.novelcool.NovelCoolSource
import com.haise.jiyu.source.novelhall.NovelHallSource
import com.haise.jiyu.source.mangakatana.MangaKatanaSource
import com.haise.jiyu.source.baozimanhua.BaoziManhuaSource
import com.haise.jiyu.source.mangapill.MangapillSource
import com.haise.jiyu.source.mangatown.MangaTownSource
import com.haise.jiyu.source.novelbuddy.NovelBuddySource
import com.haise.jiyu.source.mangahome.MangaHomeSource
import com.haise.jiyu.source.nihonkuni.NihonKuniSource
import com.haise.jiyu.source.hachirumi.HachirumiSource
import com.haise.jiyu.source.kingofshojo.KingofshojoSource
import com.haise.jiyu.source.manga18fx.Manga18fxSource
import com.haise.jiyu.source.hentai20.Hentai20Source
import com.haise.jiyu.source.demonicscans.DemonicScansSource
import com.haise.jiyu.source.likemanga.LikeMangaSource
import com.haise.jiyu.source.mangageko.MangaGekoSource
import com.haise.jiyu.source.hachiraw.HachirawSource
import com.haise.jiyu.source.fanfox.FanFoxSource
import com.haise.jiyu.source.mangaraw4u.MangaRaw4uSource
import com.haise.jiyu.source.mangarawbest.MangaRawBestSource
import com.haise.jiyu.source.weloma.WeLoMaSource
import com.haise.jiyu.source.mangadoom.MangaDoomSource
import com.haise.jiyu.source.projectsuki.ProjectSukiSource
import com.haise.jiyu.source.rokaricomics.RokariComicsSource
import com.haise.jiyu.source.silentquill.KDTScansSource
import com.haise.jiyu.source.mangamikan.MangaMikanSource
import com.haise.jiyu.source.mangacherri.MangaCherriSource
import com.haise.jiyu.source.todaymanga.TodaymangaSource
import com.haise.jiyu.source.mangack.MangackSource
import com.haise.jiyu.source.luacomic.LuaComicSource
import com.haise.jiyu.source.raw1001.Raw1001Source
import com.haise.jiyu.source.twmanga.TwmangaSource
import com.haise.jiyu.source.dankemoe.DankeMoeSource
import com.haise.jiyu.source.comick.ComicKSource
import com.haise.jiyu.source.toongod.ToongodSource
import com.haise.jiyu.source.webtooni.WebtooniSource
import com.haise.jiyu.source.manhwabuddy.ManhwaBuddySource
import com.haise.jiyu.source.manga18club.Manga18ClubSource
import com.haise.jiyu.source.manhwasusu.ManhwaSusuSource
import com.haise.jiyu.source.ehentai.EHentaiSource
import com.haise.jiyu.source.asmhentai.AsmHentaiSource
import com.haise.jiyu.source.hentainexus.HentaiNexusSource
import com.haise.jiyu.source.pururin.PururinSource
import com.haise.jiyu.source.hdoujin.HDoujinSource
import com.haise.jiyu.source.hentaihand.HentaiHandSource
import com.haise.jiyu.source.hentai3.Hentai3Source
import com.haise.jiyu.source.cinguru.CinGuruSource
import com.haise.jiyu.source.hentaifox.HentaiFoxSource
import com.haise.jiyu.source.imhentai.ImHentaiSource
import com.haise.jiyu.source.yaoimangaonline.YaoiMangaOnlineSource
import com.haise.jiyu.source.hentaipaw.HentaiPawSource
import com.haise.jiyu.source.doujiva.DoujivaSource
import com.haise.jiyu.source.hentaizap.HentaiZapSource
import com.haise.jiyu.source.omegascans.OmegaScansSource
import com.haise.jiyu.source.eahentai.EAHentaiSource
import com.haise.jiyu.source.simplyhentai.SimplyHentaiSource
import com.haise.jiyu.source.oppaistream.OppaiStreamSource
import com.haise.jiyu.source.thunderscans.ThunderscansSource
import com.haise.jiyu.source.evascans.EvaScansSource
import com.haise.jiyu.source.scythescans.ScytheScansSource
import com.haise.jiyu.source.vcomics.VComicsSource
import com.haise.jiyu.source.hadesscans.HadesScansSource
import com.haise.jiyu.source.astratoons.AstraToonsSource
import com.haise.jiyu.source.utoon.UtoonSource
import com.haise.jiyu.source.kscans.KScansSource
import com.haise.jiyu.source.lagoonscans.LagoonScansSource
import com.haise.jiyu.source.meowingtoons.MeowingToonsSource
import com.haise.jiyu.source.valirscans.ValirScansSource
import com.haise.jiyu.source.coloredmanga.ColoredMangaSource
import com.haise.jiyu.source.kiryuu.KiryuuSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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
    webtoonSource: WebtoonSource,
    dynastySource: DynastySource,
    mangaFireSource: MangaFireSource,
    novelFullSource: NovelFullSource,
    freeWebNovelSource: FreeWebNovelSource,
    mangagoSource: MangagoSource,
    asuraScansSource: AsuraScansSource,
    flameComicsSource: FlameComicsSource,
    comicBookPlusSource: ComicBookPlusSource,
    readFreeComicsOnlineSource: ReadFreeComicsOnlineSource,
    batCaveSource: BatCaveSource,
    comicsKingdomSource: ComicsKingdomSource,
    royalRoadSource: RoyalRoadSource,
    weebCentralSource: WeebCentralSource,
    vortexScansSource: VortexScansSource,
    mangaKSource: MangaKSource,
    japscanSource: JapscanSource,
    animeSamaSource: AnimeSamaSource,
    scanVFSource: ScanVFSource,
    mangaDotNetSource: MangaDotNetSource,
    kaliScanSource: KaliScanSource,
    mangaCloudSource: MangaCloudSource,
    galaxyMangaSource: GalaxyMangaSource,
    kuraMangaSource: KuraMangaSource,
    novelFireSource: NovelFireSource,
    wuxiaBoxSource: WuxiaBoxSource,
    novelCoolSource: NovelCoolSource,
    novelHallSource: NovelHallSource,
    mangaKatanaSource: MangaKatanaSource,
    baoziManhuaSource: BaoziManhuaSource,
    mangapillSource: MangapillSource,
    mangaTownSource: MangaTownSource,
    novelBuddySource: NovelBuddySource,
    mangaHomeSource: MangaHomeSource,
    nihonKuniSource: NihonKuniSource,
    hachirumiSource: HachirumiSource,
    kingofshojoSource: KingofshojoSource,
    manga18fxSource: Manga18fxSource,
    hentai20Source: Hentai20Source,
    demonicScansSource: DemonicScansSource,
    comizySource: ComizySource,
    hiveToonsSource: HiveToonsSource,
    mangaWorldSource: MangaWorldSource,
    voidScansSource: VoidScansSource,
    hostedNovelSource: HostedNovelSource,
    manhuaBuddySource: ManhuaBuddySource,
    woopReadSource: WoopReadSource,
    mangaDeniziSource: MangaDeniziSource,
    likeMangaSource: LikeMangaSource,
    mangaGekoSource: MangaGekoSource,
    hachirawSource: HachirawSource,
    fanFoxSource: FanFoxSource,
    mangaRaw4uSource: MangaRaw4uSource,
    mangaRawBestSource: MangaRawBestSource,
    weLoMaSource: WeLoMaSource,
    mangaDoomSource: MangaDoomSource,
    projectSukiSource: ProjectSukiSource,
    rokariComicsSource: RokariComicsSource,
    kdtScansSource: KDTScansSource,
    mangaMikanSource: MangaMikanSource,
    mangaCherriSource: MangaCherriSource,
    todaymangaSource: TodaymangaSource,
    mangackSource: MangackSource,
    luaComicSource: LuaComicSource,
    raw1001Source: Raw1001Source,
    twmangaSource: TwmangaSource,
    dankeMoeSource: DankeMoeSource,
    toongodSource: ToongodSource,
    webtooniSource: WebtooniSource,
    manhwaBuddySource: ManhwaBuddySource,
    manga18ClubSource: Manga18ClubSource,
    manhwaSusuSource: ManhwaSusuSource,
    eHentaiSource: EHentaiSource,
    asmHentaiSource: AsmHentaiSource,
    hentaiNexusSource: HentaiNexusSource,
    pururinSource: PururinSource,
    hDoujinSource: HDoujinSource,
    hentaiHandSource: HentaiHandSource,
    hentai3Source: Hentai3Source,
    cinGuruSource: CinGuruSource,
    hentaiFoxSource: HentaiFoxSource,
    imHentaiSource: ImHentaiSource,
    yaoiMangaOnlineSource: YaoiMangaOnlineSource,
    hentaiPawSource: HentaiPawSource,
    doujivaSource: DoujivaSource,
    hentaiZapSource: HentaiZapSource,
    omegaScansSource: OmegaScansSource,
    eaHentaiSource: EAHentaiSource,
    simplyHentaiSource: SimplyHentaiSource,
    oppaiStreamSource: OppaiStreamSource,
    thunderscansSource: ThunderscansSource,
    evaScansSource: EvaScansSource,
    scytheScansSource: ScytheScansSource,
    hadesScansSource: HadesScansSource,
    astraToonsSource: AstraToonsSource,
    utoonSource: UtoonSource,
    kScansSource: KScansSource,
    lagoonScansSource: LagoonScansSource,
    valirScansSource: ValirScansSource,
    coloredMangaSource: ColoredMangaSource,
    kiryuuSource: KiryuuSource,
    private val customSourceDao: CustomSourceDao,
    private val client: OkHttpClient,
    private val settings: com.haise.jiyu.settings.SettingsRepository,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _cache = MutableStateFlow<List<MangaSource>>(emptyList())

    private val staticSources: List<MangaSource> = listOf(
        mangaDexSource,
        mangaPlusSource,
        // ComicK (api.comick.dev) bylo 2026-07-27 vypnuto, protože web i API fungují jen
        // jako "tracker" (odkazuje na oficiální licencované platformy) a getPageList()
        // nevrací použitelné obrázky - jako běžný ČTECÍ zdroj proto pořád nefunguje.
        // Znovu zapojeno 2026-08-05 jako podklad pro ComicK agregovaný režim (viz
        // docs/superpowers/specs/2026-08-05-comick-aggregated-mode-design.md) - appka
        // ho používá k prohlížení katalogu/metadat/skupin, NE ke čtení. Ochrana proti
        // omylem otevřené nečitelné kapitole je úkol Sub-projektu 3 (motor pro křížové
        // vyhledání skutečného zdroje) - do té doby otevření kapitoly u ComicK titulu
        // skončí chybou v čtečce, ne pádem appky.
        comicKSource,
        hitomiSource,
        nhentaiSource,
        // MangaFire ZNOVU PŘIDÁNO 2026-08-17 - původně odstraněno 2026-07-27 (viz
        // docs/source-audit-2026-07-26.md sekce 8b), protože /api/titles vracelo 403
        // s vlastním XSRF-TOKEN mechanismem řešeným až klientským JS po neviditelné
        // Cloudflare Turnstile výzvě. Ověřeno živě (2026-08-17): teď je to standardní
        // Cloudflare "Managed Challenge" (hlavička "Cf-Mitigated: challenge", cType:
        // 'managed') přímo na úrovni Cloudflare, ne vlastní aplikační vrstva navíc -
        // přesně to, co CloudflareInterceptor (mezitím přidaný do appky) umí řešit
        // automaticky přes WebView. Samotná MangaFireSource.kt (API parsing) beze
        // změny, jen znovu zaregistrována.
        mangaFireSource,
        // Bato.to odstraněno 2026-07-27 - z vývojářského stroje šlo jen o "connection
        // timed out" (možná blokace datacenter IP), ale uživatel potvrdil, že appka na
        // reálném telefonu Bato.to taky nenačte. Viz BatoToSource.kt (ponecháno pro
        // případ, že by se to v budoucnu vrátilo).
        webtoonSource,
        dynastySource,
        // MangaPark odstraněno 2026-08-24 - domena mangapark.page uz neni skutecny
        // manga web, ale SEO/AI-generovana "content farm" stranka (masivni klicovkovy
        // text "MangaPark vs MangaDex vs Manganelo", genericke FAQ, zadne skutecne
        // odkazy na kapitoly) - proto "zadne vysledky" hlasene uzivatelem. Viz
        // MangaParkSource.kt (ponechano pro pripad navratu na funkcni domenu).
        novelFullSource,
        freeWebNovelSource,
        mangagoSource,
        asuraScansSource,
        flameComicsSource,
        comicBookPlusSource,
        readFreeComicsOnlineSource,
        batCaveSource,
        comicsKingdomSource,
        royalRoadSource,
        weebCentralSource,
        vortexScansSource,
        mangaKSource,
        // ── Manhua (čínské komiksy) ──────────────────────────────────────────
        // manhuafast/manhuaus: uživatel 2026-07-27 upozornil, že oba weby v appce
        // Kotatsu fungují bez problémů, a Kotatsu parser je (na rozdíl od
        // ImmortalUpdates) neoznačuje jako @Broken. Kvůli tomu byly dočasně
        // vráceny zpět a přetestovány ŽIVĚ v appce na emulátoru (ne jen curlem):
        // - manhuafast: GET archiv (`/manga/page/1/?m_orderby=`) → 403 i po
        //   úspěšném interaktivním vyřešení Cloudflare Turnstile výzvy (stejný
        //   TLS/HTTP-otisk mismatch jako u evilmanga). Zkusen i Kotatsu přístup -
        //   AJAX POST na `/wp-admin/admin-ajax.php` (`action=madara_load_more`,
        //   stejný endpoint, který appka používá pro getChapterList) - výsledek
        //   byl HTTP 525 (Cloudflare SSL handshake failed), reprodukováno 3x.
        // - manhuaus: stejný AJAX POST přístup → HTTP 403 rovnou, bez zobrazení
        //   Turnstile dialogu (tvrdé WAF pravidlo, ne jen chybějící cookie).
        // Obě selhání potvrzena PŘÍMO V APPCE (ne curlem), takže nejde o
        // TLS/JA3 fingerprint artefakt tohoto Windows stroje - jde o reálnou
        // ochranu, kterou appka nemá jak obejít. Kotatsu zjevně používá jiný
        // mechanismus (pravděpodobně routuje celé requesty přes WebView/systémový
        // prohlížeč, ne jen řešení výzvy + OkHttp replay) - jeho @Broken flag
        // pro tyhle dva zjevně jen není aktuální. Znovu odstraněno, viz
        // docs/source-audit-2026-07-26.md sekce 9. AJAX-archiv mechanismus v
        // MadaraSource.kt byl po tomto zjištění zase odstraněn (nepoužitá
        // komplexita, nikam jinam se nehodí).
        MadaraSource("manhuaplus",    "Manhuaplus",         "https://manhuaplus.com",       client, contentTypeOverride = "MANHUA"),
        // ── Manhwa scanlation skupiny ────────────────────────────────────────
        MadaraSource("manhwatop",     "Manhwatop",          "https://manhwatop.com",        client, contentTypeOverride = "MANHWA"),
        // wuxiaworldsite: audit 2026-07-27 zjistil, ze vychozi "/manga/page/N/"
        // archiv vraci 404 - web ma vlastni taxonomy slug pro novely.
        MadaraSource(
            "wuxiaworldsite", "Wuxiaworld.site", "https://wuxiaworld.site", client,
            contentTypeOverride = "NOVEL",
            popularUrl = { root, page, orderby -> "$root/novels-list/page/$page/?m_orderby=$orderby" },
        ),
        // "demonscans" (demonscans.net) odstraneno 2026-07-24 - domena uplne prestala
        // existovat (DNS nerozresolvuje), nahrazeno DemonicScansSource (jiny tym/branding,
        // vlastni sablona - viz komentar ve tride).
        //
        // Audit 2026-07-26: 48 z 73 Madara zdrojů odstraněno po plošné kontrole (curl +
        // ruční ověření obsahu). Kategorie:
        //  1) DNS/timeout mrtvé domény: astrascan, cosmicscans, isekaiscan, magicscans,
        //     mangaeffects, mangafuture, mangakiss, mangapt, mangarosie, manhuaonline,
        //     manhuarock, okumangas, trillerscans, tempestmanga
        //  2) Zaparkované domény / affiliate redirect (Sedo parking, ad-lander, meta-refresh
        //     na mrtvý cíl): azuremanga, mangayo, mangatube, zeroscans, mangamotto, manhwade
        //  3) Doména žije, ale vrací malvertising/anti-adblock "Redirecting..." nebo JS
        //     bot-gate stránku místo Madara obsahu (proto appka hlásila chybu při otevření):
        //     zinmanga, manhuaes, manhuascan, drakescans, realmscans, leviathanscans,
        //     infernalvoid, manga68, topmanhua, disasterscans, freakscans, mm-scans,
        //     reaperscanseu, suryascans, mangatx, manhuacat
        //  4) Web žije a má reálný obsah, ale přestal používat Madara šablonu (redesign na
        //     vlastní frontend) - generické Madara selektory proto nic nenašly ("tu nic
        //     není"). Vyřešeno vlastními MangaSource třídami: mangabuddy -> comizy.io,
        //     hivecomic -> hivetoons.org, mangaworld -> mangaworld.mx, voidscans,
        //     hostednovel, manhuabuddy, woopread a mangadenizi (Nuxt SPA bez dat v HTML,
        //     ale s plně funkčním interním REST API - reverzováno z JS bundlu), viz
        //     ComizySource / HiveToonsSource / MangaWorldSource / VoidScansSource /
        //     HostedNovelSource / ManhuaBuddySource / WoopReadSource / MangaDeniziSource.
        //  5) Doplňkový audit 2026-07-26 při hledání náhrad ke skupině 4 odhalil další
        //     nebezpečné/mrtvé případy, odstraněny bez náhrady:
        //     creativenovels (creativenovels.com) - kompromitovaný web: listing
        //     stránky (/browse-new/, /latest-releases/) servírují gambling spam
        //     (title "PANENTOTO"/"EMON777") místo obsahu, i když jednotlivé
        //     /novel/{slug}/ stránky ještě fungují.
        //     xcalibrscans (xcalibrscans.com) - stejný "Redirecting..." malvertising
        //     vzor jako skupina 3 výše (Windows Defender obsah karanténoval).
        //     mangatoto (mangatoto.com) - doména vypršela a byla zabrána
        //     spekulantem, teď je to obecný thajský WordPress SEO blog bez
        //     jakéhokoliv manga obsahu.
        MadaraSource("manhuahot",     "Manhua Hot",         "https://manhuahot.com",        client, contentTypeOverride = "MANHUA"),
        // manhuarm (manhuarmtl.com) odstraněno 2026-08-04 - web žije a vrací plnou
        // stránku (ne parking/blok), ale katalog je prázdný: žádná karta na "/manga/",
        // "/manga/?m_orderby=views" ani "/listing-big-thumbnail/". Ne chyba selektoru -
        // tam prostě není co najít.
        // ── Manga — další populární weby ─────────────────────────────────────
        // toonily/mangagg: audit 2026-07-27 zjistil, ze vychozi "/manga/page/N/"
        // archiv vraci 404 - vlastni taxonomy slug ("/webtoons/", "/comic/").
        MadaraSource(
            "toonily", "Toonily", "https://toonily.com", client,
            contentTypeOverride = "MANHWA",
            popularUrl = { root, page, orderby -> "$root/webtoons/page/$page/?m_orderby=$orderby" },
        ),
        MadaraSource("mangazin",      "Mangazin",           "https://mangazin.org",         client, contentTypeOverride = "MANHUA"),
        MadaraSource("cocomic",       "Cocomic",            "https://cocomic.co",           client, contentTypeOverride = "MANHWA"),
        MadaraSource(
            "mangagg", "MangaGG", "https://mangagg.com", client,
            contentTypeOverride = "MANHUA",
            popularUrl = { root, page, orderby -> "$root/comic/page/$page/?m_orderby=$orderby" },
        ),
        MadaraSource("mangaread",     "MangaRead",          "https://www.mangaread.org",    client, contentTypeOverride = "MANGA"),
        // CoffeManga odstraneno 2026-08-24 - coffeemanga.ink vraci 404 i na hlavni
        // strance (overeno zive), cela domena je mrtva.
        MadaraSource("mangasushi",    "Mangasushi",         "https://mangasushi.org",       client, contentTypeOverride = "MANGA"),
        // Manhwatoon (manhwatoon.me) odstraneno 2026-08-24 - domena je Google Safe
        // Browsing oznacena jako nebezpecna ("Web, ktery byl oznamen jako nebezpecny"),
        // Chrome navigaci na ni rovnou blokuje. Bezpecnostni riziko, ne technicka
        // prekazka - neresit obchazenim, jen odstranit.
        // mangalink.site vraci Cloudflare 522 (origin nedostupny) - mrtvy web, nepridavat.
        // Mangalink (linkmanga.com, jina domena nez vyse) - genuine Madara, vychozi cesty
        // funguji beze zmeny (/manga/{slug}/, wp-json, manga_get_chapters ajax).
        MadaraSource("linkmanga",     "Mangalink",          "https://linkmanga.com",        client, contentTypeOverride = "MANGA"),
        // Lilymanga (lilymanga.net) - GL/Yuri zamerene, genuine Madara ale vlastni
        // permalink "/gl/{slug}/" misto "/manga/{slug}/" - proto vlastni popularUrl.
        // Vychozi WP hledani (?s=) vraci prazdno pro vetsinu dotazu (na strane webu,
        // ne chyba selektoru) - browse/cteni funguji spolehlive.
        MadaraSource(
            "lilymanga", "Lilymanga", "https://lilymanga.net", client,
            contentTypeOverride = "MANGA",
            popularUrl = { root, page, orderby -> "$root/gl/page/$page/?m_orderby=$orderby" },
        ),
        // pawmanga (pawmanga.com) odstraněno 2026-08-04 - doména je zaparkovaná
        // (FingerprintJS tracking/redirect skript, žádný manga obsah).
        // LikeManga (mgread.io) NENÍ Madara - "madara207" v HTML je jen jméno
        // uploadera, web běží na jiném WP pluginu (wp-theme-init-manga).
        // Vyžadovalo by vlastní MangaSource, viz project_jiyu_american_comics_audit
        // / manga source audit poznámky - zatím nepřidáno.
        // Manhwaz odstraneno 2026-08-24 - manhwaz.com neodpovida (TCP timeout, overeno
        // curlem i realnym prohlizecem) - server je nedostupny, ne bot-detekce.
        // ── Francouzské zdroje 🇫🇷 ──────────────────────────────────────────
        japscanSource,
        // animesama: doména anime-sama.fr mrtvá, přesunuto na anime-sama.to.
        // Web byl kompletně přepsán a seznam kapitol/stránek se negeneruje
        // ve statickém HTML - AnimeSamaSource proto místo Jsoup selektorů
        // volá interní JSON API webu (/s2/scans/get_nb_chap_et_img.php),
        // kterou používá jeho vlastní JS reader. Viz komentář u třídy.
        animeSamaSource,
        scanVFSource,
        // ── Španělské a portugalské zdroje 🇪🇸🇧🇷 ──────────────────────────
        // tmo (lectortmo.com) odstraněno 2026-07-26 - doména mrtvá (DNS), nová
        // lectortmo.net existuje ale je to čistě klientský SPA shell (Vite bundle) -
        // obsah by šel získat jen přes JS/API, ne přes Jsoup HTML parsing.
        // mangaleer (mangaleer.com) odstraněno 2026-07-26 - doména expirovala,
        // přesměrovává na expireddomains.com marketplace nabídku.
        // unionmangas (unionmangas.xyz) odstraněno 2026-07-26 - zaparkovaná doména,
        // reklamní JS redirect na "/lander".
        // inmanga (inmanga.com) odstraněno 2026-07-27 - archiv je čistě JS/AJAX
        // renderovaný (AngularJS "Factory/Controller" SPA), reálný POST endpoint
        // "/manga/GetMangasConsultResult" existuje, ale přesný JSON tvar
        // "filterSettings" parametru se nepodařilo v rozumném čase zjistit
        // (needs bigger investigation). Viz InMangaSource.kt (ponecháno).
        // ── Noví kandidáti (jednoduchý vlastní scraping) ─────────────────────
        mangaDotNetSource,
        kaliScanSource,
        mangaCloudSource,
        galaxyMangaSource,
        kuraMangaSource,
        // ── Novely (nový vlastní scraping) ───────────────────────────────────
        // LightNovelWorld odstraneno 2026-08-24 - web se oficialne zavrel a presunul
        // na chikari.moe (jina domena/struktura, potreba samostatny prepis).
        novelFireSource,
        wuxiaBoxSource,
        // Ranobes odstraneno 2026-08-24 - web ma interaktivni Cloudflare Turnstile
        // "I'm not a robot" vyzvu, ne jen automaticky JS challenge - tu appka (ani
        // scraper) nedokaze projit bez skutecneho lidskeho kliknuti.
        novelCoolSource,
        // Adult zdroj, pridano na vyslovne prani uzivatele (viz konverzace 2026-07-18)
        novelHallSource,
        mangaKatanaSource,
        baoziManhuaSource,
        mangapillSource,
        mangaTownSource,
        novelBuddySource,
        mangaHomeSource,
        nihonKuniSource,
        hachirumiSource,
        kingofshojoSource,
        // Adult davka #2, pridano na vyslovne prani uzivatele (viz konverzace 2026-07-19).
        // Nezavisle overeno pres Chrome pred implementaci - zadny malvertising redirect
        // na chapter readeru (na rozdil od trvale zamitnutych ComicLand/VyManga).
        manga18fxSource,
        hentai20Source,
        demonicScansSource,
        // MangaBuddy (mangabuddy.com) prebrandovano 2026-07-26 na comizy.io -
        // kompletni Next.js redesign, viz ComizySource (parsuje __NEXT_DATA__
        // JSON misto HTML selektoru, viz docs/source-audit-2026-07-26.md).
        comizySource,
        // Hive Scans (hivescans.com) prebrandovano 2026-07-26 na hivetoons.org -
        // kompletni redesign (Astro + schema.org microdata), viz HiveToonsSource.
        hiveToonsSource,
        // MangaWorld (IT, mangaworld.ac -> mangaworld.mx) nikdy nebylo Madara -
        // vlastni Laravel frontend, viz MangaWorldSource.
        mangaWorldSource,
        // Void Scans (voidscans.net) - maly staticky Hugo web, nikdy nebyl Madara.
        voidScansSource,
        // HostedNovel (hostednovel.com) - vlastni Laravel/Vue frontend, nikdy Madara.
        hostedNovelSource,
        // ManhuaBuddy (manhuabuddy.com) - vlastni PHP frontend, nikdy nebylo Madara.
        manhuaBuddySource,
        // WoopRead (woopread.com) - textovy light-novel web, vlastni Next.js
        // App Router frontend, nikdy nebyl Madara.
        woopReadSource,
        // MangaDenizi (mangadenizi.net, TR) - nikdy nebylo Madara. Nuxt SPA bez
        // dat ve statickem HTML, ale ma plne funkcni interni REST API (viz
        // komentar u tridy) - vcetne rozskladani zamichanych "tiled-v1"
        // dlazdic, viz TileScramble/TileScrambleBitmap.
        mangaDeniziSource,
        // LikeManga (likemanga.ink) - vlastni sablona (ne Madara), kapitoly
        // strankovane pres AJAX (load_list_chapter), obrazky na like.mgread.io.
        likeMangaSource,
        // MangaGeko (mgeko.cc) - vlastni sablona, katalog na /jumbo/manga/?results=N
        // (ne standardni "page" parametr), primo obrazky bez tokenu na imgsrv5.com.
        mangaGekoSource,
        // Hachiraw (hachiraw.win) - WordPress, RAW (japonske) manga bez prekladu.
        // Kapitoly v <table class="table-hover">, obrazky pres data-src lazy-load
        // (hostovane na TikTok CDN, primo bez tokenu).
        hachirawSource,
        // FanFox (fanfox.net, drive mangafox.me) - velka zavedena knihovna, katalog
        // i seznam kapitol plne server-rendered. Skutecne URL obrazku jsou za
        // chapterfun.ashx endpointem vracejicim JS obfuskovany pres Dean Edwards
        // "packer" - viz JsPacker.kt. Kazda stranka kapitoly = samostatny
        // chapterfun.ashx pozadavek (stejne jako u realneho webu), proto se resi
        // lazy pres getImageUrl, ne najednou v getPageList.
        fanFoxSource,
        // Dalsi kolo RAW zdroju (2026-08-09): MangaRaw4u a WeLoMa jsou plne
        // server-rendered vcetne cteni (WeLoMa navic base64 koduje primo URL
        // obrazku v data-img, ne token - viz WeLoMaSource.kt). MangaRawBest ma
        // stejne primo cteni, ale Alpine.js frontend neexponuje zanr/autora/
        // hledani zadnym staticky parsovatelnym zpusobem.
        mangaRaw4uSource,
        mangaRawBestSource,
        weLoMaSource,
        // Ctvrte kolo everythingmoe auditu (2026-08-09): Lilymanga registrovana
        // vyse jako MadaraSource. MangaDoom a Project Suki - obe maji reader
        // stránkovany po jedne obrazku na pozadavek (presne jak funguje web),
        // Project Suki navic nema zadny "pocet stranek" indikator primo na
        // strance - resi se pozadavkem na vysoke cislo stranky (9999), web
        // odpovi redirectem na skutecnou posledni stranku.
        mangaDoomSource,
        projectSukiSource,
        // Rokari Comics (rokaricomics.com) - stejna Mangathemesia/"Mangastream"
        // sablona jako GalaxyMangaSource/RawKumaSource, ale status/typ jsou v
        // <table><tr><td> radcich misto div.imptdt.
        rokariComicsSource,
        kdtScansSource,
        // MangaMikan a MangaCherri - vlastni sablony (2026-08-09 kolo).
        // MangaMikan obrazky maji uz hotovy podepsany token primo v HTML.
        // MangaCherri odkazy na kapitoly jsou relativni BEZ uvodniho lomitka -
        // Jsoup.parse se proto vola s explicitni base URI (viz komentar ve tride).
        mangaMikanSource,
        mangaCherriSource,
        // Druhe kolo revize "nedokoncenych" zdroju (2026-08-09) - vetsina z nich
        // se ukazala byt zpusobena chybami v drivejsim zkoumani (neuvozene
        // atributy, chybejici URL-encoding), ne skutecnou technickou prekazkou.
        todaymangaSource,
        mangackSource,
        luaComicSource,
        raw1001Source,
        twmangaSource,
        dankeMoeSource,
        // 2026-07-27 (čtvrté kolo auditu) - hromadné odstranění zdrojů se skutečnou,
        // architektonicky neřešitelnou Cloudflare Turnstile ochranou. Živě v appce
        // ověřeno na evilmanga: tichý WebView solve i viditelný interaktivní dialog
        // (CloudflareChallengeBridge) OBA úspěšně získají platný cf_clearance cookie,
        // ale OkHttp replay s tímto cookie je Cloudflare ORIGIN serverem STEJNĚ
        // odmítnut (403, "Just a moment...") - jde o mismatch TLS/HTTP otisku mezi
        // WebView (Chromium engine, řeší výzvu) a OkHttp (jiný klient, replayuje
        // cookie), na což je Turnstile navržen reagovat blokací. Bez zásadní
        // přestavby (routovat VŠECHNY requesty přes WebView síťovou vrstvu) appka
        // tyhle weby nemůže nikdy přečíst, i když prohlížení/hledání může naživo
        // vypadat funkčně. curl 2026-07-27 reconfirmed real "Just a moment..." (403)
        // na všech níže - stejná kategorie ochrany jako evilmanga/kunmanga:
        // webtoonxyz, aquareader, foxaholic, immortalupdates, scribblehub,
        // manganato (natomanga.com - nově chráněno, dřív fungovalo bez ochrany),
        // manhuafast, manhuaus (viz komentář u sekce "Manhua" výše - dočasně
        // vráceny 2026-07-27 na uživatelův popud, ale živý test v appce/emulátoru
        // potvrdil stejné selhání jako u evilmanga i s Kotatsu-style AJAX
        // archivem, takže odstraněny znovu).
        // Viz *Source.kt třídy jednotlivých zdrojů (ponechány pro případ, že by
        // appka v budoucnu routovala requesty přes WebView).
        //
        // madaradex: NEODSTRANĚN jako "CF-gated" web, ale CDN subdoména
        // (cdn.madaradex.org) sama vrací 403 i se správným Refererem (vlastní WAF
        // pravidlo na obrázcích) - archiv/hledání by fungovalo, ale žádná kapitola
        // by se nedala přečíst, proto odstraněno taky. Viz MadaraDex v sekci 6d.
        //
        // mangahub: OVĚŘENO ŽIVĚ V APPCE (ne jen curl) - GraphQL API
        // (api.mghubcdn.com/graphql) vrací HTTP 200, ale tělo je anti-adblock/bot
        // "Redirecting..." JS interstitial (ne Cloudflare - jiný, nebrandovaný bot
        // gate), takže JSONObject parsing tiše selže a getPopular() vrátí prázdno.
        // Appka nemá infrastrukturu pro řešení tohoto typu JS gate. Viz MangaHubSource.kt.
        //
        // rawkuma: web se přestěhoval na rawkuma.net s kompletně jinou strukturou
        // (WordPress + htmx, archiv karty se dohrávají přes skrytý JS lazyload
        // mechanismus, ne standardní hx-get), navíc opakované curl requesty
        // narazily na skutečný Cloudflare "you have been blocked" hard-block -
        // vyžadovalo by kompletní přepis srovnatelný s mangadenizi. Viz RawKumaSource.kt.
        //
        // mangaboomers: manga-boomers.cz je Vue SPA, seznam titulů jde přes
        // "/api/mangalist" (funguje), ale detail/kapitoly ("/api/mangaInfo",
        // "/api/loadChapters") vyžadují neznámý tvar POST parametru - vyzkoušeny
        // běžné varianty (id, mangaId, manga_id, JSON body, cookie session), žádná
        // nefunguje. Bez čitelné kapitoly by appka jen "prohlížela", proto odstraněno.
        // Viz MangaBoomersSource.kt.
        //
        // mangablaze: web běží na hluboce přetémovaném/bespoke Madara (vlastní
        // a.acard/.ac-t karty, detail/kapitola nesedí na žádný výchozí Madara
        // selektor) - vyžadovalo by vlastní MangaSource třídu srovnatelnou
        // náročností s mangadenizi, nestihnuto v tomto kole (byl to jen
        // MadaraSource("mangablaze", ...) s výchozími selektory, ne vlastní třída).
        //
        // ranovel: NOVEL oprava (6e) zůstává v MadaraSource.kt (prospěje případným
        // budoucím Madara NOVEL zdrojům), ale samotný ranovel odstraněn - stránky
        // KAPITOL (ne archiv/detail) jsou za stejnou neřešitelnou Cloudflare Turnstile
        // ochranou jako výše, čtení by tedy stejně nikdy nefungovalo.
        //
        // ── Dávka 2026-08-13 (rozsáhlý manhwa/hentai audit) - manhwa/webtoon ──────
        toongodSource,
        webtooniSource,
        manhwaBuddySource,
        // ManhwaRaw18 odstraneno 2026-08-24 - manhwaraw18.com nema DNS zaznam (domena
        // uz neexistuje, overeno curlem).
        manga18ClubSource,
        manhwaSusuSource,
        // ── Dávka 2026-08-13 - hentai/doujin zdroje (isAdult=true) ────────────────
        eHentaiSource,
        asmHentaiSource,
        hentaiNexusSource,
        pururinSource,
        hDoujinSource,
        hentaiHandSource,
        hentai3Source,
        cinGuruSource,
        hentaiFoxSource,
        imHentaiSource,
        yaoiMangaOnlineSource,
        // ── Dávka 2026-08-13, doplnění zbylých 10 - 4 z nich jsou taky Madara ─────
        // Pornhwaz - vlastni permalink "/webtoon/{slug}" (ne "/manga/{slug}"),
        // hledani (?s=) funguje na vychozi ceste beze zmeny.
        MadaraSource(
            "pornhwaz", "Pornhwaz", "https://www.pornhwaz.com", client,
            contentTypeOverride = "MANHWA", isAdultOverride = true,
            popularUrl = { root, page, orderby -> "$root/webtoon/page/$page/?m_orderby=$orderby" },
        ),
        // Manga District - vlastni permalink "/series/{slug}" pro tituly i archiv,
        // hledani je na vychozi ceste.
        MadaraSource(
            "mangadistrict", "Manga District", "https://mangadistrict.com", client,
            contentTypeOverride = "MANHWA", isAdultOverride = true,
            popularUrl = { root, page, orderby -> "$root/series/page/$page/?m_orderby=$orderby" },
        ),
        // Manhwa18 (manhwa18.today) - plne vychozi Madara cesty, zadny prepis netreba.
        MadaraSource(
            "manhwa18today", "Manhwa18", "https://www.manhwa18.today", client,
            contentTypeOverride = "MANHWA", isAdultOverride = true,
        ),
        // ManhwaDen - plne vychozi Madara cesty, zadny prepis netreba.
        MadaraSource(
            "manhwaden", "ManhwaDen", "https://www.manhwaden.com", client,
            contentTypeOverride = "MANHWA", isAdultOverride = true,
        ),
        // HenTalk (hentalk.com) VYNECHÁN - doména mezitím zparkována, homepage
        // je jen `<script>window.onload=...location.href="/lander"</script>`
        // (klasický ad-lander vzor, žádný obsah).
        hentaiPawSource,
        doujivaSource,
        hentaiZapSource,
        // Caitlin.top VYNECHÁN - reader ("route=comic/readOnline") vůbec
        // neobsahuje obrázky stránek ve statickém HTML a vkládá podezřelý
        // skript z domény ("ndejhe73jslaw093.com") vypadající jako
        // malvertising/ad-injection - stejná kategorie jako dříve zamítnuté
        // xcalibrscans apod. Bez čitelné kapitoly by appka jen "prohlížela",
        // proto nepřidáno.
        //
        // Hentai2Read (hentai2read.com, sesterský web hentaihere.com/
        // hentai2w.com, sdílí hentaicdn.com backend) VYNECHÁN - reader
        // ("#arf-reader"/"#js-reader") je v initial HTML prázdný kontejner,
        // obrázky stránek se dohrávají čistě přes JS (arf-reader_v105.js)
        // bez zjevného API endpointu zjistitelného statickou analýzou -
        // stejná architektonická překážka jako u evilmanga/mangahub (viz
        // komentáře výše).

        // ── Dávka 2026-08-13 - dříve odložené "MOŽNÁ" kandidáty ───────────────────
        omegaScansSource,
        eaHentaiSource,
        simplyHentaiSource,
        oppaiStreamSource,
        // XToonhub (xtoonhub.com) VYNECHÁN - doména byla přesměrována na
        // reklamní/podvodný redirect řetězec ("302 → survey-smiles.com",
        // Cowboy server, žádný skutečný obsah) - stejná kategorie jako dřívější
        // zparkované/hijacknuté domény (HenTalk apod.), ne technická překážka.

        // Thunderscans - POZOR: hlavní doména thunderscans.com je k datu přidání (2026-08-17)
        // HIJACKNUTÁ (stejný vzor jako XToonhub výše - Cowboy server, JS fingerprinting
        // redirect na cizí doménu misto obsahu, overeno zive PowerShell Invoke-WebRequest).
        // Skutečný/anglický mirror skupiny je en-thunderscans.com (WordPress "mangareader"
        // téma, viz ThunderscansSource) - tu appka používá.
        thunderscansSource,
        // Davka 2026-08-17 (Eva Scans / Scythe Scans / Kayn Scan / Ken Scans / Hades
        // Scans) - overeno zive (PowerShell Invoke-WebRequest + rucni rozbor markupu),
        // viz komentare primo v jednotlivych tridach.
        evaScansSource,
        scytheScansSource,
        // Kayn Scan a Ken Scans bezi na stejne sdilene komercni Astro sablone "vcomics"
        // (build cesta /_vcomics/..., identicka struktura dat) - overeno zive na obou,
        // proto spolecna generic trida VComicsSource misto dvou skoro identickych kopii.
        VComicsSource("kaynscan", "Kayn Scan", "https://kaynscan.org", client),
        // Ken Scans presunuto na kencomics.com 2026-08-24 - kenscans.org presmerovava
        // (overeno zive), ale primy zapis odolnejsi nez spolehani na redirect.
        VComicsSource("kenscans", "Ken Scans", "https://kencomics.com", client),
        hadesScansSource,
        // Dragon Tea (dragontea.ink) - puvodne pridano s contentTypeOverride = "NOVEL" (odhad
        // podle nazvu vlastni taxonomy "novel-genre" ve URL, web byl pri pridavani blokovany
        // Cloudflare Managed Challenge, takze skutecna struktura nesla overit). Uzivatel zivym
        // testem potvrdil (2026-08-24), ze zdroj ve skutecnosti obsahuje manga/komiksovy obsah -
        // NOVEL filtr ho tak nespravne zobrazoval mezi novelami. contentTypeOverride odstranen,
        // MadaraSource defaultuje na MANGA (viz [MangaSource.contentType]).
        MadaraSource(
            "dragontea", "Dragon Tea", "https://dragontea.ink", client,
        ),
        // AstraToons (astratoons.com, pt-BR) - overeno zive: cisty Laravel JSON API
        // pro katalog/hledani/detail (/api/comics), jen seznam kapitol je HTML
        // fragment z Alpine.js sablony (viz komentar ve tride).
        astraToonsSource,
        // Utoon (utoon.us) - overeno zive: vlastni "mangaverse" WP motiv (ne Madara),
        // "Nacist dalsi" pres AJAX s WP nonce (viz komentar ve tride).
        utoonSource,
        // Hunters Scans - domena huntersscan.xyz mezitim EXPIROVALA a byla znovu
        // prodana/obsazena uplne cizim webem ("Holy City Fishing Charters", GoDaddy
        // Website Builder) - overeno zive, NEPOUZIVAT. Skutecny web tymu je
        // readhunters.xyz (pt-BR, "ReadHunter"), genuine nezmeneny Madara motiv
        // (wp-manga/page-item-detail potvrzeno), jen vlastni permalink "/comics/"
        // misto vychoziho "/manga/" (ten vraci zavrenou/resetnutou spojeni misto
        // 404 - vypada jako WAF pravidlo na neexistujici cestu, ne technicka
        // prekazka zdroje samotneho).
        MadaraSource(
            "readhunters", "Hunters Scans", "https://readhunters.xyz", client,
            popularUrl = { root, page, orderby -> "$root/comics/page/$page/?m_orderby=$orderby" },
        ),
        // kScans (kscans.xyz) - misto "SacIND" (nedohledatelne, uzivatel nemel presnou
        // URL) najit uzivatelem misto toho tenhle NOVEL zdroj - overeno zive, vlastni
        // bespoke motiv, viz komentar ve tride.
        kScansSource,
        // Lagoon Scans - MangaThemesia jako Thunderscans, jen jina info-karta
        // (table.infotable misto div.imptdt), viz komentar ve tride.
        lagoonScansSource,
        // ManhuaNext - genuine nezmeneny Madara, zadny prepis netreba.
        MadaraSource("manhuanext", "ManhuaNext", "https://manhuanext.com", client, contentTypeOverride = "MANHUA"),
        // Timeless Toons a Genz Toons bezi na stejne sdilene komercni sablone (CDN
        // "cdn.meowing.org", identicka struktura) - overeno zive na obou, proto
        // spolecna generic trida MeowingToonsSource. ManhwaFreak (v seznamu pozadavku)
        // NEPRIDAN ZVLAST - puvodni domena manhwafreak.com uz vubec neexistuje
        // (rebranding potvrzen i mimo appku), skupina ted zije pod Utoon (utoon.us),
        // ktery uz appka ma.
        MeowingToonsSource("timelesstoons", "Timeless Toons", "https://timelesstoons.org", client),
        MeowingToonsSource("genztoons", "Genz Toons", "https://genztoons.org", client),
        // Dusk Scans (duskscans.com) VYNECHÁN - plne klientsky renderovana Next.js
        // App Router aplikace (React Server Components), zadny obsah ani API
        // endpoint viditelny ve staticky stazenem HTML (overeno zive) - vyzadovalo
        // by hlubsi rozbor minifikovanych JS bundlu, nez je pro tuhle davku unosne.
        //
        // brainrotcomics.com VYNECHÁN - blokuje opravdove interaktivni Cloudflare
        // Managed Challenge (Cf-Mitigated: challenge, overeno zive) a appka nema
        // zadny strukturalni naznak sablony (na rozdil od Dragon Tea), takze by
        // pridani bylo cistě naslepo bez zakladu.
        //
        // ValirScans (valirscans.org) - taky Next.js App Router, ale na rozdil od Dusk
        // Scans ma ciste JSON API pro katalog/hledani (`/api/series`) a detail/kapitoly
        // jdou vytahnout z ld+json + RSC payloadu primo v HTML (viz komentar ve tride).
        // Mixuje manga/manhwa/manhua/novel v jednom katalogu - kazda SManga ma vlastni
        // contentType podle API pole "type".
        valirScansSource,
        // Samurai Scan - puvodni domena samuraiscan.com je "coming soon" stranka
        // (WP SeedProd plugin), ktera odkazuje na docasnou domenu "samurai.j5z.xyz"
        // (spanelsky obsah, robots noindex/nofollow - vypada jako prechodne/testovaci
        // hostovani, ne finalni adresa). Overeno zive: genuine nezmeneny Madara motiv
        // (wp-manga/page-item-detail/reading-content potvrzeno), 1000+ kapitol na
        // nekterych titulech, takze obsahove funkcni - jen URL muze byt nestabilni.
        //
        // Oprava 2026-08-21 (uzivatelske hlaseni "404 pri nacitani"): web nema vychozi
        // Madara permalink "/manga/" (ten vraci 404), archiv katalogu bezi na vlastnim
        // slugu "/son/" (overeno zive: /son/page/N/?m_orderby=... vraci 200 s
        // page-item-detail polozkami). Vyhledavani ("/page/N/?s=...&post_type=wp-manga")
        // je nezavisle na permalink slugu a funguje s vychozi hodnotou beze zmeny.
        MadaraSource(
            "samuraiscan", "Samurai Scan", "https://samurai.j5z.xyz", client,
            popularUrl = { root, page, orderby -> "$root/son/page/$page/?m_orderby=$orderby" },
        ),
        // mangademon.com i mangademon.org VYNECHANY - oba stejny skodlivy ad-fraud
        // "Redirecting..." vzor jako drivejsi nalezy (mangayabu.top, kaiscans.org):
        // JWT session redirect -> fingerprint (adblock/GPU/timezone/webdriver) ->
        // document.write + auto-click na "router.parklogic.com" (domain parking sit).
        // mangademon.org navic v base64 payloadu prozradil "domainApex":"comicdemons.com" -
        // potvrzuje, ze jde o parkovanou/preprodanou domenu, ne skutecny web.
        //
        // Neox Scanlator (neoxscans.net) VYNECHÁN - puvodni domena expirovala 2024-10-02
        // a byla znovu zaregistrovana; Windows Defender pri ulozeni stazene stranky na
        // disk nahlasil virus/PUA (stejny tvrdy bezpecnostni signal jako u mangayabu.top
        // a kaiscans.org drive) - nepouzivat.
        //
        // Quegna Traduction Team VYNECHÁN - nema vlastni ctecku, jen obecne ForumFree
        // forum (qtt.forumcommunity.net), navic potvrzeno neaktivni (>6 mesicu bez
        // vydani) - neni co strukturovane scrapovat.
        //
        // Colored Manga (colorizedmangas.com) - na vyslovne uzivatelovo prani pridano i
        // presto, ze neni klasicka scanlation skupina (jen obarveny mainstream, ~50
        // titulu). Detail kapitol/stranky viz komentar ve tride.
        coloredMangaSource,
        // Kiryuu - kiryuu.id/.co/.io jsou jen anti-adblock "brana" (zadny skutecny obsah
        // ve statickem HTML), skutecne aktualni zrcadlo zjisteno pres jejich vlastni
        // "/domain" stranku - overeno zive, viz komentar ve tride.
        kiryuuSource,
        // Manga Inferno VYNECHÁN - jen Facebook stranka, zadny vlastni ctecka web.
        //
        // Newbie VYNECHÁN - prilis obecny nazev, nedohledatelna konkretni skupina (jen
        // titulz s "Newbie" v nazvu od jinych skupin, zadna skupina primo tak jmenovana).
        //
        // Jaimini's~box~ VYNECHÁN - tym ukoncil cinnost 2020, puvodni jaiminisbox.com ted
        // divne servíruje nesouvisejici obsah Google Play (overeno zive) - domena zjevne
        // znovu vyuzita/spatne nasmerovana.
        //
        // Assley Team VYNECHÁN - rusky prekladatelsky tym bez vlastniho webu, publikuje
        // jen pres cizi agregatory (manga-books.com, seimanga.me, inkstory.net).
        //
        // Bossque Scans VYNECHÁN - bossquescans.blogspot.com zije, ale sam nehostuje
        // obsah (jen par odkazu na CIZI blogspot ucty s kapitolami) - neni co
        // strukturovane scrapovat z jednoho zdroje.
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

    /** Čeká na první NEprázdnou emisi cache (start appky, než se static+custom zdroje slijí do jedné množiny) - beze změny obsahu, jen "chvíli počkej". */
    private suspend fun rawSources(): List<MangaSource> = _cache.filter { it.isNotEmpty() }.first()

    /**
     * Zdroje pro OBJEVOVÁNÍ (Procházet mřížka, GlobalSearch) - respektuje
     * [SettingsRepository.showAdultSources] a v Klasickém režimu skrývá ComicK
     * (viz [SettingsRepository.appMode]) - ComicK se sice pořád registruje (aby
     * fungoval getById i v Klasickém režimu), ale jako řádný zdroj do Procházet/
     * hledání patří jen v ComicK agregovaném režimu; jinak by šlo omylem otevřít
     * titul, který neumí přečíst žádnou kapitolu. Záměrně NEPOUŽÍVÁ [getById]
     * (ten zůstává nefiltrovaný), aby vypnutí adult zdrojů/přepnutí režimu
     * neschovalo/nerozbilo mangu, kterou uživatel už má v knihovně z dřívějška -
     * jen ji skryje z NOVÉHO objevování.
     */
    fun observeAll(): Flow<List<MangaSource>> =
        combine(_cache, settings.showAdultSources, settings.appMode) { all, showAdult, appMode ->
            all
                .let { if (showAdult) it else it.filterNot { s -> s.isAdult } }
                .let { if (appMode == com.haise.jiyu.settings.AppMode.COMICK) it else it.filterNot { s -> s.id == "comick" } }
        }

    suspend fun getAll(): List<MangaSource> {
        val all = rawSources()
        val adultFiltered = if (settings.showAdultSources.first()) all else all.filterNot { it.isAdult }
        return if (settings.appMode.first() == com.haise.jiyu.settings.AppMode.COMICK) adultFiltered else adultFiltered.filterNot { it.id == "comick" }
    }

    /** Nefiltrované podle isAdult - viz komentář u [observeAll]. */
    suspend fun getById(id: String): MangaSource? = rawSources().find { it.id == id }

    /**
     * Synchronní varianta [getById] - pro Compose kartičky, co zobrazují obálku a potřebují
     * `homepageUrl` zdroje jako Referer hlavičku (viz BrowseMangaCard), bez zavádění suspend/
     * LaunchedEffect jen kvůli téhle jedné hodnotě. Bezpečné, protože `_cache` je v okamžiku
     * vykreslování manga kartiček už vždy naplněný (ty kartičky samy pocházejí ze zdroje v
     * tomhle cache) - `.value` tak nikdy nevrátí prázdno v situaci, kdy by na tom zaleželo.
     */
    fun getByIdSync(id: String): MangaSource? = _cache.value.find { it.id == id }

    /**
     * Zdroje pro [com.haise.jiyu.source.comick.ComicKChapterResolver] - narozdíl od
     * [getAll] NEaplikuje globální [SettingsRepository.showAdultSources] toggle. O
     * zahrnutí isAdult zdrojů do křížového hledání se tam rozhoduje per titul (podle
     * content_rating konkrétní ComicK mangy - safe/suggestive nikdy neprohledávají
     * isAdult zdroje, erotica/pornographic je vždy zahrnou), ne globálním nastavením.
     */
    suspend fun getAllForCrossSourceSearch(): List<MangaSource> = rawSources()
}
