package com.haise.jiyu.source

import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.repository.deserializeAltTitles
import com.haise.jiyu.source.interceptor.InteractiveChallengePolicy
import com.haise.jiyu.util.normalizeMangaTitle
import com.haise.jiyu.util.report
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Syrový nález cross-source sweepu pro relink titulu na jiný zdroj. Dekorace
 * (oblíbenost, kolize s existující knihovní entou, počet shod kapitol) se dělá ve
 * ViewModelu, který zná data z DB - tahle třída se drží čistě síťové vrstvy.
 */
data class RelinkSeed(
    val source: MangaSource,
    val manga: SManga,
    val chapters: List<SChapter>,
)

/**
 * Kandidát pro relink tak, jak ho ukazuje UI - [RelinkSeed] obohacený o DB/UX údaje.
 * [matchedChapterCount] = kolik ULOŽENÝCH kapitol se na kandidátovi páruje podle čísla
 * (silnější signál "stejný titul" než holý název); [alreadyInLibrary] = na source+url už
 * ukazuje jiná knihovní entita - takového kandidáta UI zobrazí, ale zamkne (relocate by
 * narazil na duplicitu - viz MangaRepository.relinkMangaToSource).
 */
data class RelinkCandidate(
    val source: MangaSource,
    val manga: SManga,
    val chapters: List<SChapter>,
    val matchedChapterCount: Int,
    val isFavorite: Boolean,
    val alreadyInLibrary: Boolean,
)

/**
 * Hledá stejný titul na OSTATNÍCH zdrojích - doplněk [com.haise.jiyu.data.repository.MangaRepository.recoverMangaLink]
 * pro případ, že umřel celý zdroj (seiznutá doména, zaniklý web), ne jen URL titulu.
 *
 * Rozdíly proti ComicK resolveru ([com.haise.jiyu.source.comick.ComicKChapterResolver]):
 * - žádný katalogový vrchol (ComicK titleInfo, slug-probe mirroru) - výchozí bod je
 *   uložená [MangaEntity],
 * - jazykový filtr je jazyk PŮVODNÍHO zdroje (resolver tvrdě hledá jen anglické, protože
 *   ComicK je anglický katalog; přesun cs titulu na ru zdroj by dal nečitelný výsledek),
 * - shoda názvu je vždy přesná po normalizaci ([normalizeMangaTitle]) napříč `title` +
 *   `alternateTitles` - slabší heuristiky typu "jediný kandidát" se u cizích webů nehodí,
 *   výsledek si stejně vždy potvrzuje uživatel v UI.
 */
@Singleton
class CrossSourceSearch @Inject constructor(
    private val sourceManager: SourceManager,
) {

    /**
     * Emituje každého nalezeného kandidáta hned, jak ho najde (stejný streaming vzor jako
     * resolver). Kandidát bez kapitol se neemituje - přesun na zdroj bez kapitol nemá smysl.
     *
     * [originalSource] je nullable - zdroj mohl z appky ZMIZET úplně (ne jen mít mrtvý web),
     * tehdy už ho SourceManager nezná. Bez něj neznáme jazyk ani isAdult původního webu:
     * jazykový filtr se přeskočí (přesná shoda názvu pořád platí) a isAdult zdroje se
     * konzervativně vyloučí (titul mohl být adult, ale bezpečnější je výsledek nenajít
     * než omylem přesunout na 18+ web).
     */
    fun seeds(manga: MangaEntity, originalSource: MangaSource?): Flow<RelinkSeed> = channelFlow {
        // Potlačení interaktivní Cloudflare výzvy je scope-bound na korutinu sweepu
        // (ThreadLocal v InteractiveChallengePolicy) - hromadný sweep nesmí vyvolat dialog
        // od zdroje, který uživatel zrovna neprohlíží, ale jeho vlastní foreground requesty
        // to nikdy nezasáhne (na rozdíl od dřívějšího globálního flagu - audit SRC-2).
        InteractiveChallengePolicy.suppressed {
            sweep(manga, originalSource) { send(it) }
        }
    }

    private suspend fun sweep(
        manga: MangaEntity,
        originalSource: MangaSource?,
        onFound: suspend (RelinkSeed) -> Unit,
    ) = coroutineScope {
        val semaphore = Semaphore(5)
        val titles = (listOf(manga.title) + deserializeAltTitles(manga.alternateTitles))
        val normalizedTargets = titles
            .map { normalizeMangaTitle(it) }
            .filter { it.isNotBlank() }
            .toSet()
        if (normalizedTargets.isEmpty()) return@coroutineScope
        // Dotaz se zkousi s kazdym nazvem titulu (hlavni + alternativni), ne jen s hlavnim -
        // cilovy zdroj muze titul evidovat pod alternativou, se kterou search(manga.title)
        // nic nenajde (audit SRC-3). Omezeno na MAX_QUERY_TITLES v ramci per-source timeoutu,
        // takze cena sweepu zustava stejne ohranicena jako driv.
        val queryTitles = titles
            .filter { normalizeMangaTitle(it).isNotBlank() }
            .distinctBy { normalizeMangaTitle(it) }
            .take(MAX_QUERY_TITLES)
        val eligible = sourceManager.getAllForCrossSourceSearch()
            .filter { it.id != manga.sourceId && it.id != "comick" && it.includeInGlobalSearch && !it.isBroken }
            .filter { isSameContentGroup(it.contentType, manga.contentType) }
            .filter { originalSource == null || sameLanguage(it.language, originalSource.language) }
            // Titul z adult zdroje se hledá i na adult zdrojích; z ne-adult (nebo neznámého -
            // zdroj zmizel z appky) zdroje nikdy neleze na adult (analogie potvrzeného/ne-adult
            // ratingu v resolveru - zde rating neznáme, proxy je isAdult původního zdroje).
            .filter { originalSource?.isAdult == true || !it.isAdult }
        eligible.map { source ->
            launch {
                semaphore.withPermit {
                    try {
                        withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS) {
                            var match: SManga? = null
                            for (query in queryTitles) {
                                val results = source.search(query)
                                match = results.firstOrNull { normalizeMangaTitle(it.title) in normalizedTargets }
                                if (match != null) break
                            }
                            val found = match ?: return@withTimeoutOrNull
                            val chapters = source.getChapterList(found)
                            if (chapters.isNotEmpty()) onFound(RelinkSeed(source, found, chapters))
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: SourceRateLimitedException) {
                        // 429 - zdroj pro tenhle pokus přeskočit, ostatní běží dál.
                    } catch (e: Exception) {
                        e.report("relink:sweep:${source.id}")
                    }
                }
            }
        }.forEach { it.join() }
    }

    /** Stejný primární jazykový subtag ("en" ≈ "en-US") - viz doc třídy. */
    private fun sameLanguage(a: String, b: String): Boolean =
        a.substringBefore('-').lowercase() == b.substringBefore('-').lowercase()

    private companion object {
        /** Na zdroj - sweep běží paralelně (5), ale jeden visící web nesmí zdržet celek. */
        const val PER_SOURCE_TIMEOUT_MS = 8_000L
        /** Kolik ruznych nazvu titulu se na jeden zdroj zkusi jako dotaz (viz SRC-3). */
        const val MAX_QUERY_TITLES = 4
    }
}
