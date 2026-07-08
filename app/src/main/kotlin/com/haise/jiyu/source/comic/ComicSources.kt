package com.haise.jiyu.source.comic

import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.Page
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

// ── ReadComicOnline ───────────────────────────────────────────────────────────
@Singleton
class ReadComicOnlineSource @Inject constructor(client: OkHttpClient) : ComicSiteSource(
    id = "readcomiconline",
    name = "ReadComicOnline",
    base = "https://readcomiconline.li",
    client = client,
) {
    override val popularPath = "/ComicList/MostPopular"
    override val comicItemSelector = "ul.list-comic li"
    override val comicLinkSelector = "a"
    override val comicCoverSelector = "img"
    override val chapterItemSelector = "ul.list-chapter li a"
    override val pageImgSelector = "div#divImage img, #divImage img"
    override val searchPath = "/Search/Comics?keyword="
    override val searchResultSelector = "ul.list-comic li"
    override val paginatedPopular = true
    override val popularPageParam = "?page="
}

// ── ReadAllComics ─────────────────────────────────────────────────────────────
@Singleton
class ReadAllComicsSource @Inject constructor(client: OkHttpClient) : ComicSiteSource(
    id = "readallcomics",
    name = "ReadAllComics",
    base = "https://readallcomics.com",
    client = client,
) {
    override val popularPath = "/category/popular-comics/"
    override val comicItemSelector = "ul.listing.updates li, article"
    override val comicLinkSelector = "a"
    override val comicCoverSelector = "img"
    override val chapterItemSelector = "ul.listing li a"
    override val pageImgSelector = "div#chapter_container img, .chapter-content img"
    override val searchPath = "/?s="
    override val searchResultSelector = "ul.listing li, article"
}

// ── ViewComic ─────────────────────────────────────────────────────────────────
@Singleton
class ViewComicSource @Inject constructor(client: OkHttpClient) : ComicSiteSource(
    id = "viewcomic",
    name = "ViewComic",
    base = "https://viewcomic.com",
    client = client,
) {
    override val popularPath = "/"
    override val comicItemSelector = "li.wp-manga-series"
    override val comicLinkSelector = "a"
    override val comicCoverSelector = "img"
    override val chapterItemSelector = "li.wp-manga-chapter a"
    override val pageImgSelector = "div.reading-content img, .page-break img"
    override val searchPath = "/?s="
}

// ── XoxoComics ────────────────────────────────────────────────────────────────
@Singleton
class XoxoComicsSource @Inject constructor(client: OkHttpClient) : ComicSiteSource(
    id = "xoxocomics",
    name = "XoxoComics",
    base = "https://xoxocomics.com",
    client = client,
) {
    override val popularPath = "/comic-list"
    override val comicItemSelector = "li.col-lg-2"
    override val comicLinkSelector = "a"
    override val comicCoverSelector = "img"
    override val chapterItemSelector = "ul.chapter-list li a"
    override val pageImgSelector = ".chapter-content img, .reading-content img"
    override val searchPath = "/search?keyword="
    override val searchResultSelector = "li.col-lg-2"
}

// ── ZipComic ──────────────────────────────────────────────────────────────────
@Singleton
class ZipComicSource @Inject constructor(client: OkHttpClient) : ComicSiteSource(
    id = "zipcomic",
    name = "ZipComic",
    base = "https://www.zipcomic.com",
    client = client,
) {
    override val popularPath = "/comics-list/"
    override val comicItemSelector = "div.bsx"
    override val comicLinkSelector = "a"
    override val comicCoverSelector = "img"
    override val chapterItemSelector = "ul.clstyle li a"
    override val pageImgSelector = "div.reading-content img"
    override val searchPath = "/?s="
}

// ── ComicPunch ────────────────────────────────────────────────────────────────
@Singleton
class ComicPunchSource @Inject constructor(client: OkHttpClient) : ComicSiteSource(
    id = "comicpunch",
    name = "ComicPunch",
    base = "https://comicpunch.net",
    client = client,
) {
    override val popularPath = "/comics/"
    override val comicItemSelector = "div.bsx"
    override val comicLinkSelector = "a"
    override val comicCoverSelector = "img"
    override val chapterItemSelector = "ul.clstyle li a"
    override val pageImgSelector = "div.reading-content img"
    override val searchPath = "/?s="
}

// ── ComicBookPlus (Golden Age — public domain) ────────────────────────────────
@Singleton
class ComicBookPlusSource @Inject constructor(client: OkHttpClient) : ComicSiteSource(
    id = "comicbookplus",
    name = "ComicBookPlus",
    base = "https://comicbookplus.com",
    client = client,
) {
    override val popularPath = "/?dlid=all&sort=popular"
    override val comicItemSelector = "table.cbplus-table tr"
    override val comicLinkSelector = "td a"
    override val comicCoverSelector = "img"
    override val chapterItemSelector = "table.cbplus-table tr td a"
    override val pageImgSelector = "img#mainimage, img.page-img"
    override val searchPath = "/?find="
    override val searchResultSelector = "table.cbplus-table tr"
    override val descriptionSelector = "div.comicinfo, p.description"
}

// ── GetComics (download site — links to CBR/PDF) ─────────────────────────────
@Singleton
class GetComicsSource @Inject constructor(client: OkHttpClient) : ComicSiteSource(
    id = "getcomics",
    name = "GetComics",
    base = "https://getcomics.org",
    client = client,
) {
    override val popularPath = "/"
    override val comicItemSelector = "article"
    override val comicLinkSelector = "h1.post-title a, h2.post-title a"
    override val comicCoverSelector = "img.wp-post-image"
    override val searchPath = "/?s="
    override val searchResultSelector = "article"
    override val descriptionSelector = "div.post-content p"

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        listOf(
            SChapter(
                sourceId = id,
                mangaUrl = manga.url,
                url = manga.url,
                name = "Download",
                chapterNumber = 1f,
                dateUpload = 0L,
            )
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        val doc = Jsoup.parse(get("$base${chapter.url}"))
        val coverUrl = doc.selectFirst("img.wp-post-image")?.attr("src") ?: ""
        listOf(Page(0, coverUrl))
    }
}

// ── GoComics (newspaper strips) ───────────────────────────────────────────────
@Singleton
class GoComicsSource @Inject constructor(client: OkHttpClient) : ComicSiteSource(
    id = "gocomics",
    name = "GoComics",
    base = "https://www.gocomics.com",
    client = client,
) {
    override val popularPath = "/comics/most-popular"
    override val comicItemSelector = "li.media-list-item"
    override val comicLinkSelector = "a.media-list-item-header"
    override val comicCoverSelector = "img.media-list-item-img"
    override val chapterItemSelector = "ul.calendar li a, .comic-strip-header a"
    override val pageImgSelector = "picture.item-comic-image img, .comic-strip-container img"
    override val searchPath = "/search?q="
    override val searchResultSelector = "li.media-list-item"
    override val descriptionSelector = "div.about-feature p"
}

// ── GlobalComix ───────────────────────────────────────────────────────────────
@Singleton
class GlobalComixSource @Inject constructor(client: OkHttpClient) : ComicSiteSource(
    id = "globalcomix",
    name = "GlobalComix",
    base = "https://globalcomix.com",
    client = client,
) {
    override val popularPath = "/comics/trending"
    override val comicItemSelector = "div.comic-card"
    override val comicLinkSelector = "a.comic-card__link"
    override val comicCoverSelector = "img.comic-card__cover"
    override val chapterItemSelector = "ul.chapter-list li a"
    override val pageImgSelector = "img.page-image, .reader-page img"
    override val searchPath = "/search?q="
    override val searchResultSelector = "div.comic-card"
}

// ── ComicKingdom ──────────────────────────────────────────────────────────────
@Singleton
class ComicKingdomSource @Inject constructor(client: OkHttpClient) : ComicSiteSource(
    id = "comickingdom",
    name = "ComicKingdom",
    base = "https://comickingdom.com",
    client = client,
) {
    override val popularPath = "/comics"
    override val comicItemSelector = "div.comic-cover"
    override val comicLinkSelector = "a"
    override val comicCoverSelector = "img"
    override val chapterItemSelector = "ul.comic-strip-list li a, div.feature-list a"
    override val pageImgSelector = "img.strip-image, .comic-page img"
    override val searchPath = "/search?query="
    override val searchResultSelector = "div.comic-cover"
}
