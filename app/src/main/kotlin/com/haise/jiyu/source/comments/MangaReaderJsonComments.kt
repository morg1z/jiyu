package com.haise.jiyu.source.comments

import org.json.JSONObject
import java.time.Instant

/**
 * Sdilena Next.js "manga reader" platforma - overeno zive, MangaK a Comizy maji BIT-PRESNE
 * stejnou strukturu `initialChapter.latest_comments` (`id`/`content`/`user.name`/`created_at`),
 * i kdyz jde o nezavisle tridy (ruzne domeny). Zadne vnorene odpovedi - JSON dava jen
 * `replies_count`, ne obsah odpovedi.
 */
fun parseMangaReaderJsonComments(initialChapter: JSONObject): List<ChapterComment> {
    val arr = initialChapter.optJSONArray("latest_comments") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val c = arr.optJSONObject(i) ?: return@mapNotNull null
        val id = c.optString("id").ifBlank { return@mapNotNull null }
        val content = c.optString("content").trim().ifBlank { return@mapNotNull null }
        val author = c.optJSONObject("user")?.optString("name")?.ifBlank { null } ?: "?"
        val createdAt = runCatching { Instant.parse(c.optString("created_at")).toEpochMilli() }.getOrDefault(0L)
        ChapterComment(id = id, author = author, content = content, createdAt = createdAt)
    }
}
