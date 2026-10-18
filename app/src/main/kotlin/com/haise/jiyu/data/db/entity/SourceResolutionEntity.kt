package com.haise.jiyu.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Zapamatovaná resoluce ComicK titulu na reálný zdroj - uživatelský požadavek
 * "zapamatovat na pár dní, hledat znovu jen když přibyla kapitola".
 *
 * Původní mechanismus ([MangaEntity.resolvedMangaId]) si pamatoval jen vítěze a
 * při KAŽDÉM otevření kapitoly refreshoval jeho seznam kapitol, aby ověřil, že
 * nepřestal vydávat. Se záznamem tady: dokud je resoluce čerstvá ([resolvedAt]
 * < ~3 dny) a ComicK neeviduje novější kapitolu než zdroj při poslední kontrole
 * ([resolvedLastChapter] >= aktuální max ComicK titulu), otevření kapitoly
 * nepotřebuje ani jeden síťový request.
 *
 * [candidatesJson] drží seznam všech zdrojů, kde se titul našel - používá ho
 * přepínač zdrojů v čtečce, takže ruční přepnutí nemusí sweep spouštět znovu.
 */
@Entity(tableName = "source_resolution")
data class SourceResolutionEntity(
    @PrimaryKey val comickMangaId: String,
    /** manga.id aktuálně zvoleného reálného zdroje (držený v sync s MangaEntity.resolvedMangaId). */
    val resolvedMangaId: String,
    /** JSON pole kandidátů - viz SourceResolutionCodec (sourceId, mangaUrl, titul, rozsah kapitol). */
    val candidatesJson: String,
    /** Nejvyšší číslo EN kapitoly, kterou ComicK titul evidoval při resolvování. */
    val comicKLastChapter: Float,
    /** Nejvyšší číslo kapitoly resolved zdroje při poslední ověřené kontrole. */
    val resolvedLastChapter: Float,
    val resolvedAt: Long,
)
