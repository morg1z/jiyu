package com.haise.jiyu.source.comments

/** Jeden komentar ke KONKRETNI kapitole (ne k celemu titulu - to resi ComicKSource.getComments).
 * Zadne vnorene odpovedi v prvni verzi (YAGNI) - MangaK/Comizy JSON stejne nedava obsah odpovedi,
 * jen pocet (viz replies_count), a zbyle 2 formaty (wpDiscuz, nativni WP) sice vnorene odpovedi
 * v HTML maji, ale plosseni by pridalo slozitost bez jasne uzivatelske potreby zatim. */
data class ChapterComment(
    val id: String,
    val author: String,
    val content: String,
    /** Epoch millis, 0 = nezname/nepodarilo se naparsovat. */
    val createdAt: Long,
    val avatarUrl: String? = null,
)
