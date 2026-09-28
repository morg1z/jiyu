package com.haise.jiyu.util

import com.haise.jiyu.source.SManga

/**
 * Odvozená sekce pro agregované Domů obrazovky (Novela/Komiks). ComicK Home má
 * několik horizontálních řad (Nově přidané/Dokončené, oblíbené v čase, recenze) -
 * agregované katalogy tahle server-side data nemají, takže sekce stavíme z polí,
 * co listingy zdrojů reálně nesou: `status` (Dokončené) a `genres` (žánrové řady).
 */
data class AggregateSection<T>(
    val kind: Kind,
    /** Původní label žánru ze zdroje (pro Kind.GENRE), jinak null. */
    val genre: String? = null,
    val items: List<T>,
) {
    enum class Kind { COMPLETED, LONGEST, GENRE }
}

/**
 * Ze sjednoceného katalogu (už deduplikované entry) odvodí extra sekce.
 * Sekce bez dostatečného počtu položek se neemitují - řídký sweep nenechá
 * na půlce obrazovky prázdné řady.
 *
 * Pořadí: Dokončené, pak žánrové řady od nejfrekventovanějšího žánru.
 *
 * Každá položka se smí objevit nejvýše v JEDNÉ odvozené sekci (a už vůbec ne v
 * žádné, pokud ji už ukazuje horní "Populární/Nejnovější" řada - viz
 * [excludeKeys]). Bez toho měly žánrové řady skoro identický obsah - populární
 * tituly sdílejí víc žánrů najednou (Batman = action+adventure+superhero), takže
 * každá řada filtrovaná ze stejného seřazeného katalogu začínala stejnými
 * obálkami (hlášený bug: "v každé kategorii ty stejné tituly"). Sekce, která po
 * odečtení obsazených položek nedosáhne [MIN_SECTION_ITEMS] čerstvých titulů,
 * se přeskočí - a žánrové kandidáty zkoušíme dál za hranicí [MAX_GENRE_SECTIONS],
 * aby místo duplicitní řady mohla vzniknout řada skutečně jiného žánru.
 *
 * @param excludeKeys klíče položek už viditelných na obrazovce (hero řada)
 * @param key stabilní identita položky pro deduplikaci napříč sekcemi;
 *   výchozí = normalizovaný název (stejný klíč, jakým se deduplikuje katalog)
 */
fun <T> deriveAggregateSections(
    entries: List<T>,
    maxItemsPerSection: Int = 12,
    excludeKeys: Set<String> = emptySet(),
    key: (T) -> String = { normalizeMangaTitle(manga(it).title) },
    manga: (T) -> SManga,
): List<AggregateSection<T>> {
    if (entries.isEmpty()) return emptyList()
    val out = mutableListOf<AggregateSection<T>>()
    val claimed = HashSet<String>(excludeKeys)

    // Prázdný výsledek = sekce se nemá emitovat (málo čerstvých položek).
    fun take(row: List<T>): List<T> {
        val fresh = row.filter { key(it) !in claimed }
        if (fresh.size < MIN_SECTION_ITEMS) return emptyList()
        return fresh.take(maxItemsPerSection)
    }

    val completed = take(entries.filter { e -> manga(e).status?.let { isCompletedStatus(it) } == true })
    if (completed.isNotEmpty()) {
        out += AggregateSection(kind = AggregateSection.Kind.COMPLETED, items = completed)
        completed.forEach { claimed += key(it) }
    }

    // Nejdelší série - lastChapter hlásí listing u zdrojů, co znají počet čísel
    // (GlobalComix total_releases, ComicK last_chapter). Sekce je pravdivá jen tam,
    // kde ta čísla skutečně jsou - ostatní položky se prostě nezapočítají.
    val longest = take(
        entries.filter { (manga(it).lastChapter ?: 0f) > 0f }
            .sortedByDescending { manga(it).lastChapter }
    )
    if (longest.isNotEmpty()) {
        out += AggregateSection(kind = AggregateSection.Kind.LONGEST, items = longest)
        longest.forEach { claimed += key(it) }
    }

    // Žánrové řady: top žánry podle frekvence v katalogu. Obalové/kontejnerové
    // žánry ("Comic", "Fiction", "Web Novel"...) přeskakujeme - řada "Fiction"
    // obsahující půlku katalogu nic neříká.
    val genreCount = HashMap<String, Int>()
    val genreLabel = HashMap<String, String>()
    for (e in entries) {
        for (g in manga(e).genres) {
            val label = g.trim()
            val key = label.lowercase()
            if (key.length < 3 || key in GENERIC_GENRES) continue
            genreCount[key] = (genreCount[key] ?: 0) + 1
            genreLabel.putIfAbsent(key, label)
        }
    }
    val topGenres = genreCount.entries
        .asSequence()
        .filter { it.value >= MIN_GENRE_SUPPORT }
        .sortedByDescending { it.value }
        .toList()
    var emittedGenres = 0
    for (g in topGenres) {
        if (emittedGenres >= MAX_GENRE_SECTIONS) break
        val row = take(entries.filter { e -> manga(e).genres.any { it.trim().lowercase() == g.key } })
        if (row.isNotEmpty()) {
            out += AggregateSection(
                kind = AggregateSection.Kind.GENRE,
                genre = genreLabel[g.key],
                items = row,
            )
            row.forEach { claimed += key(it) }
            emittedGenres++
        }
    }
    return out
}

/** Volný "dokončeno" match - zdroje používají různé tvary ("Completed",
 * "Complete", "Dokončeno", "finished", "ended"...), ale ne "Ongoing"/"Hiatus".
 * Sdílený i pro katalogový filtr na Domů obrazovkách. */
fun isCompletedStatus(status: String): Boolean {
    val s = status.lowercase().trim()
    return s.contains("complet") || s.contains("dokon") || s == "done" ||
        s == "finished" || s == "ended" || s == "end"
}

private const val MIN_SECTION_ITEMS = 4
private const val MIN_GENRE_SUPPORT = 4
private const val MAX_GENRE_SECTIONS = 3

private val GENERIC_GENRES = setOf(
    "comic", "comics", "graphic novel", "graphic novels", "novel", "novels",
    "fiction", "webnovel", "web novel", "web-novel", "manga", "manhwa", "manhua",
    "series", "original", "literature", "book", "books", "light novel",
    "light novels", "official", "fanfic", "fanfiction",
)
