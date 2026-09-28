package com.haise.jiyu.source.community

import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.madara.MadaraSource
import com.haise.jiyu.source.mangathemesia.MangaThemesiaSource
import com.haise.jiyu.source.zeistmanga.ZeistMangaSource
import okhttp3.OkHttpClient

/**
 * Rozšířený katalog webů na sdílených šablonách Madara, MangaThemesia a ZeistManga (38 + 40 + 10). Jsou to jen řádky s údaji
 * o webu (adresa, jazyk, typ, 18+ a odchylky v URL/datu) nad našimi vlastními šablonami [MadaraSource] a
 * [MangaThemesiaSource] - žádný parser navíc. Seznam vznikl porovnáním veřejně známých webů na těchto
 * šablonách a každý zápis se před přidáním ověřil živým během (`LiveSourceSmokeTest`, výsledek v
 * `docs/community-sources.md`); rozbité a za Cloudflare výzvou uzamčené weby v něm nejsou.
 *
 * Tyhle zdroje se nepoužívají v globálním hledání ani při hledání zdroje pro ComicK (stovky webů by
 * každý dotaz zatížily) - jsou dostupné v Procházet a filtrují se podle jazyka a typu.
 */
object CommunitySources {

    fun build(client: OkHttpClient, jsRunner: com.haise.jiyu.util.JsRunner? = null): List<MangaSource> {
        fun m(
            id: String, name: String, url: String, lang: String,
            type: String = "MANGA", adult: Boolean = false, list: String = "manga", tag: String = "genre", date: String? = null,
            tags: Boolean = true, pagination: Boolean = true, sorts: Boolean = true,
        ) = MadaraSource(
            id = id, name = name, baseUrl = url, client = client, contentTypeOverride = type, isAdultOverride = adult,
            listPath = list, tagPrefix = tag, languageOverride = lang, datePattern = date, inGlobalSearch = false,
            supportsTags = tags, hasPagination = pagination, supportsSorts = sorts,
        )

        fun t(
            id: String, name: String, url: String, lang: String,
            type: String = "MANGA", adult: Boolean = false, list: String = "manga", date: String? = null,
            netShield: Boolean = false, tags: Boolean = true, pagination: Boolean = true,
        ) = MangaThemesiaSource(
            id = id, name = name, baseUrl = url, client = client, contentTypeOverride = type, isAdultOverride = adult,
            languageOverride = lang, listPath = list, datePattern = date, inGlobalSearch = false,
            netShield = netShield, jsRunner = jsRunner, supportsTags = tags, hasPagination = pagination,
        )

        fun z(
            id: String, name: String, url: String, lang: String,
            type: String = "MANGA", adult: Boolean = false, cat: String = "Series",
            page: String = ZeistMangaSource.DEFAULT_SELECT_PAGE, tags: String = ZeistMangaSource.DEFAULT_SELECT_TAGS,
            tagFilter: Boolean = true,
        ) = ZeistMangaSource(
            id = id, name = name, baseUrl = url, client = client, contentTypeOverride = type, isAdultOverride = adult,
            languageOverride = lang, mangaCategory = cat, selectPage = page, selectTags = tags,
            supportsTags = tagFilter,
        )

        return listOf(
        m("ext:allporn_comic", "AllPornComic", "https://allporncomic.com", "en", adult = true, list = "porncomic", tag = "porncomic-cat", date = "MMMM dd, yyyy"),
        m("ext:apoll_comics", "ApollComics", "https://apollcomics.es", "es", adult = true, tag = "manga-genre", tags = false),
        // ArabToons - zanrova taxonomie je pod "/archives/manga-genre/{slug}/?page=N"
        // (vychozi "/manga-genre/{slug}/page/N/" vraci 404 - audit 2026-10).
        MadaraSource(
            id = "ext:arabtoons", name = "ArabToons", baseUrl = "https://arabtoons.net", client = client,
            isAdultOverride = true, tagPrefix = "manga-genre", languageOverride = "ar", datePattern = "dd-MM-yyyy",
            inGlobalSearch = false,
            genreUrl = { root, slug, page -> "$root/archives/manga-genre/$slug/?page=$page" },
        ),
        // 3Asq - 3asq.org 301-> 3asq.online (audit 2026-11, Madara markup overen).
        // Server odpovida mimoradne pomalu (~30 s na request), sdileny
        // readTimeout 30 s je na hrane - odvozeny klient s delsim limitem
        // (sdili connection pool i dispatcher, jen prodlouzi timeouty).
        MadaraSource(
            id = "ext:asqorg", name = "3Asq", baseUrl = "https://3asq.online",
            client = client.newBuilder()
                .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
            tagPrefix = "manga-genre", languageOverride = "ar", datePattern = "d MMMM، yyyy",
            inGlobalSearch = false,
        ),
        m("ext:beyondtheataraxia", "Beyond The Ataraxia", "https://www.beyondtheataraxia.com", "it", tag = "manga-genre", date = "d MMMM yyyy"),
        m("ext:borutoexplorer", "BorutoExplorer", "https://leitor.borutoexplorer.com.br", "pt", tag = "manga-genre", date = "dd 'de' MMMMM 'de' yyyy"),
        m("ext:cat_300", "Cat300", "https://cat-300.com", "th", adult = true, tag = "manga-genre", date = "MMMM dd, yyyy"),
        // ext:comicsvalley odstranen 2026-11 - comicsvalley.com vraci HTTP 522
        // (Cloudflare origin down), mrtvy web.
        m("ext:decadencescans", "DecadenceScans", "https://reader.decadencescans.com", "en", adult = true, tag = "manga-genre"),
        m("ext:diamondfansub", "DiamondFansub", "https://diamondfansub.com", "tr", list = "seri", tag = "seri-turu", date = "d MMMM", tags = false),
        // ext:gdscans odstranen 2026-10 - gdscans.com opakovane timeout (30s+), mrtvy web.
        m("ext:gedecomix", "GedeComix", "https://gedecomix.com", "en", adult = true, list = "porncomic", tag = "comics-tag", tags = false),
        m("ext:harmonyscan", "HarmonyScan", "https://harmony-scan.fr", "fr", tag = "manga-genre"),
        m("ext:hentaixyuri", "HentaiXYuri", "https://hentaixyuri.com", "en", adult = true, tag = "manga-genre"),
        m("ext:hhentaifr", "H-Hentai", "https://hhentai.fr", "fr", adult = true, tag = "manga-genre"),
        m("ext:ksgroupscans", "KsGroupScans", "https://ksgroupscans.com", "en", tag = "manga-genre"),
        m("ext:lhtranslation", "LhTranslation", "https://lhtranslation.net", "en", tag = "manga-genre"),
        m("ext:manga18x", "Manga18x", "https://manga18x.net", "en", adult = true, tag = "manga-genre"),
        m("ext:mangacrazy", "MangaCrazy", "https://mangacrazy.net", "en", adult = true, tag = "manga-genre"),
        m("ext:mangaeclipse", "MangaEclipse", "https://mangaeclipse.com", "en", tag = "manga-genre"),
        m("ext:mangaforfree", "MangaForFree", "https://mangaforfree.com", "en", adult = true, tag = "manga-genre"),
        m("ext:mangahub", "MangaHub", "https://mangahub.fr", "fr", adult = true, tag = "manga-genre", date = "d MMMM yyyy", tags = false),
        m("ext:mangamaniacs", "MangaManiacs", "https://mangamaniacs.org", "en", adult = true, tag = "manga-genre"),
        m("ext:manhwa68", "Manhwa68", "https://manhwa68.com", "en", adult = true, tag = "manga-genre"),
        // (ext:manhwatoon odstranen 2026-10: manhwatoon.com 301-> manhwatoon.me, ktery je nedostupny - mrtvy web.)
        m("ext:marmota", "Marmota", "https://marmota.me", "es", type = "COMIC", list = "comic", tag = "genero", date = "d 'de' MMMMM 'de' yyyy"),
        m("ext:mundo_manhwa", "MundoManhwa", "https://mundomanhwa.com", "es", tag = "manga-genre"),
        m("ext:paritehaber", "Paritehaber", "https://www.paritehaber.com", "en", adult = true, tag = "manga-genre"),
        m("ext:petrotechsociety", "Petrotech Society", "https://www.petrotechsociety.org", "en", adult = true, tag = "manga-genre"),
        m("ext:ragnarokscanlation", "RagnarokScanlation", "https://ragnarokscanlation.org", "es", tag = "manga-genre"),
        m("ext:richtoscan", "RichtoScan", "https://r1.richtoon.top", "es", tag = "manga-generos", tags = false),
        m("ext:s2manga", "S2Read", "https://s2read.com", "en", tag = "manga-genre", date = "MMMM dd, yyyy"),
        // SamuraiScan - archivni strankovani i razeni web ignoruje (page=N i m_orderby
        // vraci identicky obsah - audit DUP + latest==popular).
        m("ext:samuraiscan", "SamuraiScan", "https://samuraiscan.com", "es", list = "read", tag = "manga-genre", tags = false, pagination = false, sorts = false),
        m("ext:tankouhentai", "TankouHentai", "https://tankouhentai.com", "pt", adult = true, tag = "manga-genre", date = "dd 'de' MMMMM 'de' yyyy", tags = false),
        m("ext:toonizy", "Toonizy", "https://toonizy.com", "en", adult = true, list = "webtoon", tag = "manga-genre", date = "MMM d, yy"),
        m("ext:topmanhua", "ManhuaTop", "https://manhuatop.org", "en", list = "manhua", tag = "manhua-genre", date = "MM/dd/yyyy"),
        m("ext:tortugaceviri", "TortugaCeviri", "https://tortugaceviri.com", "tr", tag = "manga-genre", tags = false),
        m("ext:trmangaoku", "TrMangaOku", "https://trmangaoku.com", "tr", tag = "tur", tags = false),
        t("ext:arenascans", "Arenascans", "https://arenascan.com", "en"),
        t("ext:asialotuss", "AsiaLotuss", "https://asialotuss.com", "es"),
        t("ext:bymichiby", "Bymichiby", "https://bymichiby.com", "es", adult = true),
        t("ext:dojing", "Dojing", "https://dojing.net", "id", adult = true, date = "MMM d, yyyy"),
        t("ext:elftoon", "Elftoon", "https://elftoon.com", "en"),
        t("ext:gaiatoon", "GaiaToon", "https://gaiatoon.com", "tr"),
        t("ext:hijalacom", "Hijalacom", "https://hijala.com", "ar"),
        t("ext:izanamiscans", "IzanamiScans", "https://izanamiscans.my.id", "id"),
        t("ext:kanzenin", "Kanzenin", "https://kanzenin.info", "id", adult = true),
        // KofiScans - isekaikomik.com 301 presmerovava vsechno na
        // ch1.isekaikomik.site (audit 2026-10); primy zapis odolnejsi.
        t("ext:kofiscans", "KofiScans", "https://ch1.isekaikomik.site", "id"),
        t("ext:kombatch", "KomBatch", "https://kombatch.cc", "id", adult = true),
        t("ext:komikindo_live", "Komikindo.live", "https://komikindo.live", "id", adult = true, date = "MMMM d, yyyy"),
        // KomikStation - WAF blokuje klasicke "?s=" (403), ale path search
        // "/search/{q}/" funguje (overeno zive, audit 2026-10).
        MangaThemesiaSource(
            "ext:komikstation", "KomikStation", "https://komikstation.org", client,
            languageOverride = "id", datePattern = "MMM d, yyyy", inGlobalSearch = false,
            searchUrl = { root, q, p -> if (p <= 1) "$root/search/$q/" else "$root/search/$q/page/$p/" },
        ),
        t("ext:lamimanga", "LamiManga", "https://mangalami.com", "th"),
        t("ext:luvyaa", "Luvyaa", "https://v4.luvyaa.co", "id", adult = true, date = "MMM d, yyyy"),
        // ext:makimaaaaa odstranen 2026-11 - web vypnul archiv "/manga/" uplne
        // (404 i bez parametru); prochazi jen pres "?s=" a "/genres/{slug}/",
        // coz sablona jako vychozi vypis nedokaze.
        // ext:mangatx_cc odstranen 2026-11 - mangatx.cc ma rozbitou SSL
        // konfiguraci (hostname not verified), domena mrtva.
        t("ext:miauscan", "LectorMiau", "https://leemiau.com", "es"),
        t("ext:nirvanamanga", "NirvanaManga", "https://nirvanamanga.com", "tr"),
        t("ext:noromax", "Noromax", "https://noromax02.my.id", "id"),
        t("ext:origamiorpheans", "Origami Orpheans", "https://origami-orpheans.com", "pt"),
        t("ext:rackusreads", "RackusReads", "https://rackusreads.com", "en"),
        t("ext:ravenscans", "RavenScans", "https://ravenscans.org", "en", date = "MMM d, yyyy"),
        t("ext:reapertrans", "ReaperTrans", "https://reapertrans.com", "th"),
        t("ext:sasangeyou", "Sasangeyou", "https://sasangeyou.net", "id", adult = true),
        t("ext:sektedoujin", "SekteDoujin", "https://sektedoujin.cc", "id", adult = true, date = "MMM d, yyyy"),
        t("ext:sereinscan", "SereinScan", "https://sereinscan.com", "tr"),
        t("ext:shijiescans", "ShijieScans", "https://shijiescans.com", "tr", list = "seri"),
        t("ext:shojoscans", "ShojoScans", "https://violetscans.com", "en", list = "comics"),
        t("ext:toomtammanga", "ToomtamManga", "https://toomtam-manga.com", "th", adult = true),
        t("ext:toonhunter", "ToonHunter", "https://toonhunter.com", "th", adult = true, date = "MMM d, yyyy"),
        t("ext:tsundoku", "Tsundoku", "https://tsundoku.com.br", "pt", date = "MMM d, yyyy"),
        z("ext:datgarscanlation", "DatgarScanlation", "https://datgarscanlation.blogspot.com", "es", tagFilter = false),
        // DuoScanlators - blog vubec nepouziva Blogger labely (filtr vraci
        // identicky listing, protoze label hodnota nikam nevede) - audit 2026-10.
        z("ext:duoscanlators", "DuoScanlators", "https://duoscanlators.blogspot.com", "pt", tagFilter = false),
        z("ext:heckscans", "HeckScans", "https://heckscans.blogspot.com", "pt"),
        z("ext:ler999", "Ler999", "https://ler999.blogspot.com", "pt"),
        z("ext:lonertl", "LonerTranslations", "https://loner-tl.blogspot.com", "ar", tagFilter = false),
        z("ext:ngamenkomik", "NgamenKomik", "https://ngamenkomik05.blogspot.com", "id", tagFilter = false),
        z("ext:shadowceviri", "ShadowCeviri", "https://shadowceviri.blogspot.com", "tr", type = "COMIC", tagFilter = false),
        z("ext:wolfscanbr", "WolfScanBr", "https://wolfscanbr.blogspot.com", "pt", tagFilter = false),
        z("ext:xsanomanga", "XsanoManga", "https://www.xsano-manga.com", "ar", tagFilter = false),
        )
    }
}
