package com.haise.jiyu.source.comments

import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * wpDiscuz je WordPress komentarovy plugin pouzivany napric RUZNYMI sablonami webu (Madara i
 * MangaThemesia - overeno zive na 8 ruznych zdrojich, viz spec) - proto samostatna funkce,
 * ne metoda vazana na jednu tridu. `.wpd-comment-wrap` zahrnuje i vnorene odpovedi (jsou
 * DOM-potomky sveho rodicovskeho komentare) - `doc.select(...)` vrati VSECHNY urovne naraz,
 * coz je pro plochy seznam (viz ChapterComment - zadne vnorene odpovedi v v1) zamerne v poradku.
 */
fun parseWpDiscuzComments(doc: Document): List<ChapterComment> {
    val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ENGLISH)
    return doc.select("div.wpd-comment-wrap").mapNotNull { wrap ->
        val right = wrap.selectFirst(".wpd-comment-right") ?: wrap
        val author = right.selectFirst(".wpd-comment-author")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
        val content = (right.selectFirst(".wpd-comment-text")?.select("p") ?: emptyList())
            .joinToString("\n") { it.text().trim() }.ifBlank { return@mapNotNull null }
        val id = right.attr("id").removePrefix("comment-").ifBlank { "$author:$content".hashCode().toString() }
        val createdAt = right.selectFirst(".wpd-comment-date")?.attr("title")
            ?.let { runCatching { dateFormat.parse(it)?.time }.getOrNull() } ?: 0L
        val avatarUrl = wrap.selectFirst(".wpd-avatar img")?.attr("src")?.trim()?.ifBlank { null }
        ChapterComment(id = id, author = author, content = content, createdAt = createdAt, avatarUrl = avatarUrl)
    }
}
