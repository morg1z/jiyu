package com.haise.jiyu.data.tracking

import com.haise.jiyu.anilist.AniListRepository
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.util.report
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Jediné místo, které posílá postup čtení do trackerů (AniList, MAL, Kitsu, MangaUpdates). Dřív tuhle logiku
 * včetně čtyř injektovaných repozitářů nesl přímo `ReaderViewModel`. Každý tracker selhává samostatně - pád
 * jednoho (síť, vypršený token) nesmí zabránit odeslání do ostatních.
 */
@Singleton
class TrackerSyncCoordinator @Inject constructor(
    private val aniListRepository: AniListRepository,
    private val malRepository: MalRepository,
    private val kitsuRepository: KitsuRepository,
    private val muRepository: MangaUpdatesRepository,
) {
    /**
     * Nejvetsi postup odeslany behem teto session per tracker+manga. Bez nej by soubezne pushy
     * (oznacit ch10, pak ch5 - nebo dva requesty doleti v opacnem poradi) mohly na trackeru
     * SNIZIT progress: posledni dokonceny zapis vyhrava. Guard propusti jen vyssi cislo;
     * pri selhani push zustane "odeslano" - dalsi vyssi kapitola to dorovna (audit).
     */
    private val lastPushed = java.util.Collections.synchronizedMap(mutableMapOf<String, Int>())

    /** Vrati true, kdyz [value] je vyssi nez dosud odeslana hodnota (a tu atomicky zalozi). */
    private fun shouldPush(key: String, value: Int): Boolean = synchronized(lastPushed) {
        if (value > (lastPushed[key] ?: -1)) { lastPushed[key] = value; true } else false
    }

    /** Odešle číslo přečtené [chapter] do všech trackerů, na které je [manga] napojená; běží souběžně. */
    suspend fun syncReadProgress(manga: MangaEntity, chapter: ChapterEntity) = coroutineScope {
        if (shouldPush("anilist:${manga.id}", chapter.chapterNumber.toInt()))
            launch { safely("reader:anilist:updateProgress") { aniListRepository.updateProgress(chapter.mangaId, manga.title, chapter.chapterNumber) } }
        manga.malId?.let { malId ->
            if (shouldPush("mal:$malId", chapter.chapterNumber.toInt()))
            launch {
                safely("reader:mal:updateMangaStatus") {
                    malRepository.updateMangaStatus(malId = malId, status = "reading", numChaptersRead = chapter.chapterNumber.toInt())
                }
            }
        }
        manga.kitsuId?.let { kitsuId ->
            if (shouldPush("kitsu:$kitsuId", chapter.chapterNumber.toInt()))
            launch { safely("reader:kitsu:updateProgress") { kitsuRepository.updateProgress(kitsuId, chapter.chapterNumber.toInt()) } }
        }
        manga.mangaUpdatesId?.let { seriesId ->
            if (shouldPush("mu:$seriesId", chapter.chapterNumber.toInt()))
            launch { safely("reader:mangaupdates:updateProgress") { muRepository.updateProgress(seriesId, chapter.chapterNumber.toInt()) } }
        }
    }

    private suspend fun safely(context: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.report(context)
        }
    }
}
