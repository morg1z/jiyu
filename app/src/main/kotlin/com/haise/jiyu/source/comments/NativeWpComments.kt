package com.haise.jiyu.source.comments

import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

/** Vestaveny (nejstarsi, pred-wpDiscuz) WordPress komentarovy system - jina struktura nez
 * wpDiscuz (viz [parseWpDiscuzComments]), proto samostatny parser. Podobne jako u wpDiscuz
 * `li.comment` zahrnuje i vnorene odpovedi (`ul.children` uvnitr) - plochy seznam je zamerny. */
fun parseNativeWpComments(doc: Document): List<ChapterComment> {
    val dateFormat = SimpleDateFormat("MMMM d, yyyy 'at' h:mm a", Locale.ENGLISH)
    return doc.select("li.comment").mapNotNull { li ->
        val body = li.selectFirst("article.comment-body") ?: li
        val author = body.selectFirst(".comment-author")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
        val content = body.select(".comment-content p").joinToString("\n") { it.text().trim() }.ifBlank { return@mapNotNull null }
        val id = li.attr("id").removePrefix("comment-").ifBlank { "$author:$content".hashCode().toString() }
        val createdAt = body.selectFirst(".comment-metadata a")?.text()?.trim()
            ?.let { runCatching { dateFormat.parse(it)?.time }.getOrNull() } ?: 0L
        val avatarUrl = body.selectFirst(".comment-avatar img")?.attr("src")?.trim()?.ifBlank { null }
        ChapterComment(id = id, author = author, content = content, createdAt = createdAt, avatarUrl = avatarUrl)
    }
}
