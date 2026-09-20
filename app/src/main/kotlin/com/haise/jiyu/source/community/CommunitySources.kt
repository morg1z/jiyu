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
        ) = MadaraSource(
            id = id, name = name, baseUrl = url, client = client, contentTypeOverride = type, isAdultOverride = adult,
            listPath = list, tagPrefix = tag, languageOverride = lang, datePattern = date, inGlobalSearch = false,
        )

        fun t(
            id: String, name: String, url: String, lang: String,
            type: String = "MANGA", adult: Boolean = false, list: String = "manga", date: String? = null,
            netShield: Boolean = false,
        ) = MangaThemesiaSource(
            id = id, name = name, baseUrl = url, client = client, contentTypeOverride = type, isAdultOverride = adult,
            languageOverride = lang, listPath = list, datePattern = date, inGlobalSearch = false,
            netShield = netShield, jsRunner = jsRunner,
        )

        fun z(
            id: String, name: String, url: String, lang: String,
            type: String = "MANGA", adult: Boolean = false, cat: String = "Series",
            page: String = ZeistMangaSource.DEFAULT_SELECT_PAGE, tags: String = ZeistMangaSource.DEFAULT_SELECT_TAGS,
        ) = ZeistMangaSource(
            id = id, name = name, baseUrl = url, client = client, contentTypeOverride = type, isAdultOverride = adult,
            languageOverride = lang, mangaCategory = cat, selectPage = page, selectTags = tags,
        )

        return listOf(
        m("ext:allporn_comic", "AllPornComic", "https://allporncomic.com", "en", adult = true, list = "porncomic", tag = "porncomic-cat", date = "MMMM dd, yyyy"),
        m("ext:apoll_comics", "ApollComics", "https://apollcomics.es", "es", adult = true, tag = "manga-genre"),
        m("ext:arabtoons", "ArabToons", "https://arabtoons.net", "ar", adult = true, tag = "manga-genre", date = "dd-MM-yyyy"),
        m("ext:asqorg", "3Asq", "https://3asq.org", "ar", tag = "manga-genre", date = "d MMMM، yyyy"),
        m("ext:beyondtheataraxia", "Beyond The Ataraxia", "https://www.beyondtheataraxia.com", "it", tag = "manga-genre", date = "d MMMM yyyy"),
        m("ext:borutoexplorer", "BorutoExplorer", "https://leitor.borutoexplorer.com.br", "pt", tag = "manga-genre", date = "dd 'de' MMMMM 'de' yyyy"),
        m("ext:cat_300", "Cat300", "https://cat-300.com", "th", adult = true, tag = "manga-genre", date = "MMMM dd, yyyy"),
        m("ext:comicsvalley", "ComicsValley", "https://comicsvalley.com", "en", adult = true, list = "adult-comics", tag = "comic-genre", date = "dd/MM/yyyy"),
        m("ext:decadencescans", "DecadenceScans", "https://reader.decadencescans.com", "en", adult = true, tag = "manga-genre"),
        m("ext:diamondfansub", "DiamondFansub", "https://diamondfansub.com", "tr", list = "seri", tag = "seri-turu", date = "d MMMM"),
        m("ext:gdscans", "GdScans", "https://gdscans.com", "en", tag = "webtoon-genre"),
        m("ext:gedecomix", "GedeComix", "https://gedecomix.com", "en", adult = true, list = "porncomic", tag = "comics-tag"),
        m("ext:harmonyscan", "HarmonyScan", "https://harmony-scan.fr", "fr", tag = "manga-genre"),
        m("ext:hentaixyuri", "HentaiXYuri", "https://hentaixyuri.com", "en", adult = true, tag = "manga-genre"),
        m("ext:hhentaifr", "H-Hentai", "https://hhentai.fr", "fr", adult = true, tag = "manga-genre"),
        m("ext:ksgroupscans", "KsGroupScans", "https://ksgroupscans.com", "en", tag = "manga-genre"),
        m("ext:lhtranslation", "LhTranslation", "https://lhtranslation.net", "en", tag = "manga-genre"),
        m("ext:manga18x", "Manga18x", "https://manga18x.net", "en", adult = true, tag = "manga-genre"),
        m("ext:mangacrazy", "MangaCrazy", "https://mangacrazy.net", "en", adult = true, tag = "manga-genre"),
        m("ext:mangaeclipse", "MangaEclipse", "https://mangaeclipse.com", "en", tag = "manga-genre"),
        m("ext:mangaforfree", "MangaForFree", "https://mangaforfree.com", "en", adult = true, tag = "manga-genre"),
        m("ext:mangahub", "MangaHub", "https://mangahub.fr", "fr", adult = true, tag = "manga-genre", date = "d MMMM yyyy"),
        m("ext:mangamaniacs", "MangaManiacs", "https://mangamaniacs.org", "en", adult = true, tag = "manga-genre"),
        m("ext:manhwa68", "Manhwa68", "https://manhwa68.com", "en", adult = true, tag = "manga-genre"),
        m("ext:manhwatoon", "ManhwaToon", "https://www.manhwatoon.com", "en", adult = true, tag = "manga-genre"),
        m("ext:marmota", "Marmota", "https://marmota.me", "es", type = "COMIC", list = "comic", tag = "genero", date = "d 'de' MMMMM 'de' yyyy"),
        m("ext:mundo_manhwa", "MundoManhwa", "https://mundomanhwa.com", "es", tag = "manga-genre"),
        m("ext:paritehaber", "Paritehaber", "https://www.paritehaber.com", "en", adult = true, tag = "manga-genre"),
        m("ext:petrotechsociety", "Petrotech Society", "https://www.petrotechsociety.org", "en", adult = true, tag = "manga-genre"),
        m("ext:ragnarokscanlation", "RagnarokScanlation", "https://ragnarokscanlation.org", "es", tag = "manga-genre"),
        m("ext:richtoscan", "RichtoScan", "https://r1.richtoon.top", "es", tag = "manga-generos"),
        m("ext:s2manga", "S2Read", "https://s2read.com", "en", tag = "manga-genre", date = "MMMM dd, yyyy"),
        m("ext:samuraiscan", "SamuraiScan", "https://samuraiscan.com", "es", list = "read", tag = "manga-genre"),
        m("ext:tankouhentai", "TankouHentai", "https://tankouhentai.com", "pt", adult = true, tag = "manga-genre", date = "dd 'de' MMMMM 'de' yyyy"),
        m("ext:toonizy", "Toonizy", "https://toonizy.com", "en", adult = true, list = "webtoon", tag = "manga-genre", date = "MMM d, yy"),
        m("ext:topmanhua", "ManhuaTop", "https://manhuatop.org", "en", list = "manhua", tag = "manhua-genre", date = "MM/dd/yyyy"),
        m("ext:tortugaceviri", "TortugaCeviri", "https://tortugaceviri.com", "tr", tag = "manga-genre"),
        m("ext:trmangaoku", "TrMangaOku", "https://trmangaoku.com", "tr", tag = "tur"),
        t("ext:arenascans", "Arenascans", "https://arenascan.com", "en"),
        t("ext:asialotuss", "AsiaLotuss", "https://asialotuss.com", "es"),
        t("ext:bymichiby", "Bymichiby", "https://bymichiby.com", "es", adult = true),
        t("ext:dojing", "Dojing", "https://dojing.net", "id", adult = true, date = "MMM d, yyyy"),
        t("ext:ecchidoujin", "EcchiDoujin", "https://ecchi-doujin.com", "th", adult = true, list = "doujin"),
        t("ext:elftoon", "Elftoon", "https://elftoon.com", "en"),
        t("ext:gaiatoon", "GaiaToon", "https://gaiatoon.com", "tr"),
        t("ext:hijalacom", "Hijalacom", "https://hijala.com", "ar"),
        t("ext:inumanga", "InuManga", "https://www.inu-manga.com", "th"),
        t("ext:izanamiscans", "IzanamiScans", "https://izanamiscans.my.id", "id"),
        t("ext:kanzenin", "Kanzenin", "https://kanzenin.info", "id", adult = true),
        t("ext:kofiscans", "KofiScans", "https://isekaikomik.com", "id"),
        t("ext:kombatch", "KomBatch", "https://kombatch.cc", "id", adult = true),
        t("ext:komikindo_live", "Komikindo.live", "https://komikindo.live", "id", adult = true, date = "MMMM d, yyyy"),
        t("ext:komikstation", "KomikStation", "https://komikstation.org", "id", date = "MMM d, yyyy"),
        t("ext:lamimanga", "LamiManga", "https://mangalami.com", "th"),
        t("ext:luvyaa", "Luvyaa", "https://v4.luvyaa.co", "id", adult = true, date = "MMM d, yyyy"),
        t("ext:makimaaaaa", "Makimaaaaa", "https://makimaaaaa.com", "th"),
        t("ext:mangakimi", "MangaKimi", "https://www.mangakimi.com", "th"),
        t("ext:mangatx_cc", "MangaTx.cc", "https://mangatx.cc", "en", list = "manga-list", date = "dd-MM-yyyy"),
        t("ext:miauscan", "LectorMiau", "https://leemiau.com", "es"),
        t("ext:nirvanamanga", "NirvanaManga", "https://nirvanamanga.com", "tr"),
        t("ext:noromax", "Noromax", "https://noromax02.my.id", "id"),
        t("ext:ntrmanga", "NtrManga", "https://www.ntr-manga.com", "th", adult = true),
        t("ext:origamiorpheans", "Origami Orpheans", "https://origami-orpheans.com", "pt"),
        t("ext:rackusreads", "RackusReads", "https://rackusreads.com", "en"),
        t("ext:ravenscans", "RavenScans", "https://ravenscans.org", "en", date = "MMM d, yyyy"),
        t("ext:reapertrans", "ReaperTrans", "https://reapertrans.com", "th"),
        t("ext:sasangeyou", "Sasangeyou", "https://sasangeyou.net", "id", adult = true),
        t("ext:sektedoujin", "SekteDoujin", "https://sektedoujin.cc", "id", adult = true, date = "MMM d, yyyy"),
        t("ext:sereinscan", "SereinScan", "https://sereinscan.com", "tr"),
        t("ext:shijiescans", "ShijieScans", "https://shijiescans.com", "tr", list = "seri"),
        t("ext:shojoscans", "ShojoScans", "https://violetscans.com", "en", list = "comics"),
        t("ext:sodsaime", "สดใสเมะ", "https://www.xn--l3c0azab5a2gta.com", "th"),
        t("ext:sushiscanfr", "SushiScan.fr", "https://sushiscan.fr", "fr", list = "catalogue"),
        t("ext:tanukimanga", "TanukiManga", "https://www.tanuki-manga.com", "th"),
        t("ext:thaimanga", "ThaiManga", "https://www.thaimanga.net", "th"),
        t("ext:toomtammanga", "ToomtamManga", "https://toomtam-manga.com", "th", adult = true),
        t("ext:toonhunter", "ToonHunter", "https://toonhunter.com", "th", adult = true, date = "MMM d, yyyy"),
        t("ext:tsundoku", "Tsundoku", "https://tsundoku.com.br", "pt", date = "MMM d, yyyy"),
        z("ext:datgarscanlation", "DatgarScanlation", "https://datgarscanlation.blogspot.com", "es"),
        z("ext:duoscanlators", "DuoScanlators", "https://duoscanlators.blogspot.com", "pt"),
        z("ext:heckscans", "HeckScans", "https://heckscans.blogspot.com", "pt"),
        z("ext:ler999", "Ler999", "https://ler999.blogspot.com", "pt"),
        z("ext:lonertl", "LonerTranslations", "https://loner-tl.blogspot.com", "ar"),
        z("ext:ngamenkomik", "NgamenKomik", "https://ngamenkomik05.blogspot.com", "id"),
        z("ext:okyykomik", "OkyyKomik", "https://www.okyykomik.my.id", "id"),
        z("ext:shadowceviri", "ShadowCeviri", "https://shadowceviri.blogspot.com", "tr", type = "COMIC"),
        z("ext:wolfscanbr", "WolfScanBr", "https://wolfscanbr.blogspot.com", "pt"),
        z("ext:xsanomanga", "XsanoManga", "https://www.xsano-manga.com", "ar"),
        )
    }
}
