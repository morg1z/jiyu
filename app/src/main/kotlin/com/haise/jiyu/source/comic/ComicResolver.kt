package com.haise.jiyu.source.comic

import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.SourceManager
import com.haise.jiyu.source.comick.ResolvedCandidate
import com.haise.jiyu.source.interceptor.CloudflareInterceptor
import com.haise.jiyu.util.normalizeMangaTitle
import com.haise.jiyu.util.report
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.floor

/**
 * Komiksový protějšek [com.haise.jiyu.source.novel.NovelResolver] pro
 * agregovaný režim "Komiks" (AppMode.COMIC).
 *
 * Stejná logika jako NovelResolver:
 *  - vstupem je NÁZEV titulu ze sjednoceného katalogu (ComicHomeViewModel),
 *  - match je normalizovaný název,
 *  - prohledávají se JEN anglické COMIC zdroje (agregovaný režim je určený pro
 *    americké komiksy; es/tr kopie uživatel stejně nečte a sweep přes ně jen
 *    zdržuje rozlišení),
 *  - výstup se řadí ve ViewModelu (oblíbený zdroj + počet kapitol/čísel).
 */
@Singleton
class ComicResolver @Inject constructor(
    private val sourceManager: SourceManager,
    private val settings: SettingsRepository,
    private val cloudflareInterceptor: CloudflareInterceptor,
) {
    private data class CachedCandidate(val source: MangaSource, val manga: SManga, val chapters: List<SChapter>)

    // Stejná LRU sémantika jako ComicKChapterResolver (LinkedHashMap access-order,
    // čistý JDK - android.util.LruCache je v JVM testech no-op).
    private val cache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, List<CachedCandidate>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<CachedCandidate>>): Boolean =
                size > MAX_CACHED_TITLES
        },
    )

    /**
     * Prohledá všechny COMIC zdroje a emituje kandidáty průběžně (stejný vzor
     * jako NovelResolver.findCandidatesFlow). Klíč cache = normalizovaný název.
     */
    fun findCandidatesFlow(
        comicTitle: String,
        requestedChapterNumber: Float? = null,
    ): Flow<ResolvedCandidate> = channelFlow {
        val favorites = settings.favoriteSourceIds.first()
        val cacheKey = normalizeMangaTitle(comicTitle)
        val cached = cache.get(cacheKey)
        if (cached != null) {
            cached.forEach { send(toResolvedCandidate(it, favorites, requestedChapterNumber)) }
            return@channelFlow
        }
        val found = java.util.Collections.synchronizedList(mutableListOf<CachedCandidate>())
        searchAndFetchStreaming(comicTitle) { candidate ->
            val isNew = synchronized(found) {
                if (found.none { it.source.id == candidate.source.id }) {
                    found.add(candidate)
                    true
                } else false
            }
            if (isNew) send(toResolvedCandidate(candidate, favorites, requestedChapterNumber))
        }
        // Negativní výsledek se cachuje stejně jako pozitivní.
        cache.put(cacheKey, found.toList())
    }

    private fun toResolvedCandidate(c: CachedCandidate, favorites: Set<String>, requestedChapterNumber: Float?): ResolvedCandidate =
        ResolvedCandidate(
            source = c.source,
            manga = c.manga,
            matchedChapterCount = c.chapters.map { floor(it.chapterNumber).toInt() }.distinct().size,
            hasRequestedChapter = requestedChapterNumber == null ||
                c.chapters.any { abs(it.chapterNumber - requestedChapterNumber) < 0.01f },
            isFavorite = c.source.id in favorites,
            nearestChapterDistance = requestedChapterNumber?.let { target ->
                c.chapters.minOfOrNull { abs(it.chapterNumber - target) }
            },
            minChapterNumber = c.chapters.minOfOrNull { it.chapterNumber },
            maxChapterNumber = c.chapters.maxOfOrNull { it.chapterNumber },
        )

    /**
     * Paralelní sweep přes COMIC zdroje - semafor 5, timeout 8 s na zdroj,
     * rate-limited/chybové zdroje se přeskočí, Cloudflare dialog se potlačí.
     */
    private suspend fun searchAndFetchStreaming(
        comicTitle: String,
        onFound: suspend (CachedCandidate) -> Unit,
    ) = coroutineScope {
        cloudflareInterceptor.suppressInteractiveChallenge = true
        try {
            val semaphore = Semaphore(5)
            val normalizedTarget = normalizeMangaTitle(comicTitle)
            // includeInGlobalSearch se záměrně NEfiltruje - dedikovaný comic sweep
            // má z podstaty pokrýt VŠECHNY comic zdroje (stejná logika jako Novel).
            val eligible = sourceManager.getAllForCrossSourceSearch()
                .filter { it.contentType == "COMIC" && it.language == "en" }
                .filter { !it.isAdult }
            eligible.map { source ->
                launch {
                    semaphore.withPermit {
                        try {
                            withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS) {
                                val results = source.search(comicTitle, 1, MangaFilter())
                                val match = results.firstOrNull { normalizeMangaTitle(it.title) == normalizedTarget }
                                match?.let { m -> onFound(CachedCandidate(source, m, source.getChapterList(m))) }
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: com.haise.jiyu.source.SourceRateLimitedException) {
                        } catch (e: Exception) {
                            e.report("comic:resolver:${source.id}")
                        }
                    }
                }
            }.forEach { it.join() }
        } finally {
            cloudflareInterceptor.suppressInteractiveChallenge = false
        }
    }

    private companion object {
        const val MAX_CACHED_TITLES = 128
        const val PER_SOURCE_TIMEOUT_MS = 8_000L
    }
}
