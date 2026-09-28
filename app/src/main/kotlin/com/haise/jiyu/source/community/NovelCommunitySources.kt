package com.haise.jiyu.source.community

import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.freewebnovel.FreeWebNovelEngine
import com.haise.jiyu.source.ifreedom.IFreedomSource
import com.haise.jiyu.source.lightnovelwp.LightNovelWpSource
import com.haise.jiyu.source.madara.MadaraSelectors
import com.haise.jiyu.source.madara.MadaraSource
import com.haise.jiyu.source.novelcool.NovelCoolEngine
import com.haise.jiyu.source.ranobescom.RanobesComSource
import com.haise.jiyu.source.readnovelfull.ReadNovelFullSource
import com.haise.jiyu.source.readwn.ReadWnSource
import com.haise.jiyu.source.wprest.WpRestNovelSource
import okhttp3.OkHttpClient
import java.util.Locale

/**
 * Katalog novelových zdrojů z auditu `jiyu_novel_sources_audit_2026-09-20.xlsx` -
 * jen řádky s údaji o webu nad sdílenými šablonami ([MadaraSource], [LightNovelWpSource],
 * [ReadWnSource], [IFreedomSource], [WpRestNovelSource], [FreeWebNovelEngine],
 * [NovelCoolEngine], [ReadNovelFullSource], [RanobesComSource]).
 *
 * Každá položka byla před přidáním ověřená živě (výpis, žánry, seznam kapitol,
 * text kapitoly). Loga se neřeší - UI je odvozuje z `homepageUrl` (favicon).
 * Záměrně VYNECHÁNO: daoist.quest (hijacknutý spam), bllate.org (placené kapitoly
 * za CSRF tickety), penguin-squad.com + kdtnovels.net (Next.js/RSC - křehké),
 * hangulplanet/mtl-novel/wordexcerpt/araznovel (audit špatně označil za Madara,
 * jsou to custom enginy), novel-lucky.com (timeouty), cherrymist.cafe (Fictioneer,
 * neověřený markup), e-kitaplar.com + re-library.com (WP REST nesedí modelu
 * kategorie=série), foxaholic.com + novelnice.com (Cloudflare Turnstile -
 * architektonicky neřešitelné pro OkHttp, viz SourceManager), dragontea.ink
 * (už registrované jako MANGA zdroj "dragontea", ne novel).
 */
object NovelCommunitySources {

    fun build(client: OkHttpClient): List<MangaSource> {

        // ── Madara (NOVEL) ──────────────────────────────────────────────────
        // listPath/tagPrefix ověřené živě proti archivním stránkám každého webu.
        fun m(
            id: String, name: String, url: String, lang: String,
            list: String = "novel", tag: String = "genre",
            date: String? = null, adult: Boolean = false,
            selectors: MadaraSelectors = MadaraSelectors.DEFAULT,
            ua: String = com.haise.jiyu.source.SourceHttp.USER_AGENT_DESKTOP,
            tags: Boolean = true,
        ) = MadaraSource(
            id = id, name = name, baseUrl = url, client = client,
            contentTypeOverride = "NOVEL", isAdultOverride = adult,
            listPath = list, tagPrefix = tag, languageOverride = lang,
            datePattern = date, inGlobalSearch = false, selectors = selectors,
            userAgent = ua, supportsTags = tags,
        )

        // ── LightNovelWP (LNReader rodina "lightnovelwp") ───────────────────
        fun lnwp(
            id: String, name: String, url: String, lang: String,
            archive: String = "series", dateLocale: Locale = Locale.ENGLISH,
        ) = LightNovelWpSource(
            id = id, name = name, baseUrl = url, client = client,
            archivePath = archive, languageOverride = lang, dateLocale = dateLocale,
        )

        // ── WordPress REST (kategorie = série, posty = kapitoly) ────────────
        fun wp(id: String, name: String, url: String, apiBase: String, lang: String) =
            WpRestNovelSource(id = id, name = name, baseUrl = url, apiBase = apiBase,
                client = client, languageOverride = lang)

        return listOf(
            // Madara - ověřené listPath/tagPrefix z živých probů
            m("ext:arnovel", "ArNovel", "https://ar-no.com", "ar", list = "novel", tag = "novel-genre"),
            m("ext:riwyat", "Riwyat", "https://cenele.com", "ar", list = "novel", tag = "cont-genre",
                // Madara child-theme "nhv" - archiv ma vlastni karty misto
                // page-item-detail, ale vysledky hledani vraci STANDARDNI
                // c-tabs-item markup - selektor proto musi pokryt oboji.
                selectors = MadaraSelectors.DEFAULT.copy(
                    listItem = ".nhv-library-card, div.c-tabs-item__content",
                    titleLink = ".nhv-library-card__title a, a.nhv-library-card__cover, .post-title a, a[href]",
                )),
            // novelnice.com (BoxNovel) VYNECHÁNO - živý probe vrátil Cloudflare
            // challenge (stejná neřešitelná kategorie jako foxaholic výše).
            m("ext:citrusaurora", "Citrus Aurora", "https://citrusaurora.com", "en", list = "series", tag = "series-genre"),
            // Etude zadnou zanrovou taxonomii nevystavuje (/search/ 404, v archivu
            // zadne novel-genre odkazy) - tagovy filtr by ukazal prazdny seznam.
            m("ext:etudetranslations", "Etude Translations", "https://etudetranslations.com", "en", list = "novel", tag = "novel-genre", tags = false),
            // foxaholic.com VYNECHÁNO - v SourceManager zdokumentováno jako odstraněné:
            // Cloudflare Turnstile je architektonicky neřešitelná (OkHttp replay s
            // cf_clearance cookie origin server stejně odmítne 403).
            m("ext:hiraethtranslation", "Hiraeth Translation", "https://hiraethtranslation.com", "en", list = "novel", tag = "novel-genre"),
            // "/novel/" se presmerovava na "/novel-list/" (a zahodi /page/N/ - proto
            // audit hlasil DUP); detailni stranky titulu jsou ale pod "/series/".
            m("ext:lightnovelheaven", "LightNovelHeaven", "https://lightnovelheaven.com", "en", list = "novel-list", tag = "genre"),
            // Noice/Sleepy za Cloudflare blokuji desktop UA (403 interactive
            // challenge), mobilni UA pusti rovnou - overeno zive 2026-09-23.
            // U noicetranslations je navic archiv "/manga/" ("/novel/" 301
            // presmerovava na konkretni titul).
            m("ext:noicetranslations", "Noice Translations", "https://noicetranslations.com", "en",
                list = "manga", tag = "manga-genre", ua = com.haise.jiyu.source.SourceHttp.USER_AGENT_ANDROID),
            m("ext:novelshort", "Novel Short", "https://novel-short.com", "en", list = "novel", tag = "novel-genre"),
            m("ext:novelpdf", "Novel PDF", "https://novelpdf.xyz", "th", list = "novel", tag = "novel-genre"),
            // Sleepy /search/ vraci 404 i s mobilnim UA - web zanry nevystavuje.
            m("ext:sleepytranslations", "SleepyTranslations", "https://sleepytranslations.com", "en",
                list = "series", ua = com.haise.jiyu.source.SourceHttp.USER_AGENT_ANDROID, tags = false),
            m("ext:sonicmtl", "SonicMTL", "https://www.sonicmtl.com", "en", list = "novel", tag = "novel-genre"),
            m("ext:translatinotaku", "TranslatinOtaku", "https://translatinotaku.net", "en", list = "novel", tag = "manga-genre"),
            m("ext:meionovel", "MeioNovel", "https://meionovels.com", "id", list = "novel", tag = "novel-genre"),
            // vanovel.com ODSTRANENO (audit 2026-09-23): /novel/ presmerovava na
            // /novels-type/, Madara archiv v listingu vraci 0 polozek.
            // wbnovel.com ODSTRANENO (audit): zije, ale cely katalog ma 5 titulu - bez hodnoty.
            // Turkce: archiv je "/light-novel/" ("/manga/" 301 na homepage, proto
            // audit videl jen ~10 titulu). Taxonomie "/manga-etiketi/" existuje,
            // ale vsechny jeji archivy vraci "no-results" (zanry nejsou titulkum
            // prirazene) - tagovy filtr proto vypnuty.
            m("ext:turkcelightnovels", "TurkceLightNovels", "https://turkcelightnovels.com", "tr",
                list = "light-novel", tag = "manga-etiketi", tags = false),
            m("ext:webnoveloku", "WebNovelOku", "https://www.webnoveloku.com", "tr", list = "manga", tag = "manga-genre"),
            // dragontea.ink VYNECHÁNO - už registrované ve staticSources jako
            // MadaraSource("dragontea", ...) a uživatel 2026-08-24 živě ověřil,
            // že obsah je MANGA, ne novel (audit ho špatně klasifikoval).

            // LightNovelWP - archiv /{archive}/?genre[]=&order=&page=
            lnwp("ext:kolnovel", "Kol Novel", "https://kolnovel.com", "ar", archive = "series",
                dateLocale = Locale.forLanguageTag("ar")),
            // novelsparadise.site ODSTRANENO (audit 2026-09-23): web vraci 403.
            lnwp("ext:blumeverse", "BlumeVerse", "https://blume-verse.com", "en"),
            lnwp("ext:dobynovels", "DobyNovels", "https://dobynovels.com", "en"),
            lnwp("ext:hyacinthbloom", "Hyacinth in Bloom", "https://hyacinthbloom.com", "en"),
            lnwp("ext:lazygirltranslations", "Lazy Girl Translations", "https://lazygirltranslations.com", "en"),
            lnwp("ext:transweaver", "Translation Weaver", "https://transweaver.com", "en"),
            lnwp("ext:teamchman", "TC & Sega", "https://teamchmantranslations.com", "es",
                dateLocale = Locale.forLanguageTag("es")),
            lnwp("ext:namevt", "Namevt", "https://namevt.com", "tr", archive = "seri",
                dateLocale = Locale.forLanguageTag("tr")),

            // ReadWN engine (fanmtl/wuxiaspot)
            ReadWnSource("ext:fanmtl", "FanNovel (FanMTL)", "https://www.fanmtl.com", client),
            ReadWnSource("ext:wuxiaspot", "Wuxia Space", "https://www.wuxiaspot.com", client),

            // ReadNovelFull engine
            ReadNovelFullSource("ext:readnovelfull", "ReadNovelFull", "https://readnovelfull.com", client),

            // FreeWebNovel engine - novgo.net (jiné cesty výpisů, JS-only search)
            FreeWebNovelEngine(
                client = client, id = "ext:novgo", name = "AllNovelFull (Novgo)",
                base = "https://novgo.net",
                popularPath = "most-popular", latestPath = "latest-release-novel",
                pageStyle = "query", searchSupported = false,
                contentSelector = "div#chapter-content",
            ),

            // NovelCool RU mirror (stejný engine jako www.novelcool.com)
            NovelCoolEngine(
                client = client, id = "ext:novelcool_ru", name = "NovelCool (RU)",
                base = "https://ru.novelcool.com", languageOverride = "ru",
            ),

            // Ifreedom/bookhamster rodina (ru) - VIP kapitoly se filtrují
            IFreedomSource("ext:ifreedom", "Svobodny Mir Ranobe (iFreedom)", "https://ifreedom.su", client),
            IFreedomSource(
                id = "ext:bookhamster", name = "Bookhamster", baseUrl = "https://bookhamster.ru",
                client = client,
                itemSelector = ".one-book-home", linkSelector = ".title-home a",
                chapterItemSelector = ".li-ranobe .li-col1-ranobe a",
                contentSelector = ".entry-content",
            ),

            // Ranobes.com (DLE engine - odlisny od ranobes.net)
            RanobesComSource(client = client),

            // WordPress REST - kategorie = serie (overeny posty s plnym textem)
            // peachpuff.in ODSTRANENO (audit 2026-09-23): domena mrtva (timeout).
            // novel7s.com ODSTRANENO (audit): WP REST zije, ale je to NovelTells
            // promo web - "serie" jsou marketingove kategorie, ne cisteci tituly.
            wp("ext:hasutl", "Hasu Translations", "https://hasutl.wordpress.com",
                "https://public-api.wordpress.com/wp/v2/sites/hasutl.wordpress.com", "es"),
            wp("ext:oasistranslations", "Oasis Translations", "https://oasistranslations.wordpress.com",
                "https://public-api.wordpress.com/wp/v2/sites/oasistranslations.wordpress.com", "es"),
        )
    }
}
