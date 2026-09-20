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
    /** Odešle číslo přečtené [chapter] do všech trackerů, na které je [manga] napojená; běží souběžně. */
    suspend fun syncReadProgress(manga: MangaEntity, chapter: ChapterEntity) = coroutineScope {
        launch { safely("reader:anilist:updateProgress") { aniListRepository.updateProgress(chapter.mangaId, manga.title, chapter.chapterNumber) } }
        manga.malId?.let { malId ->
            launch {
                safely("reader:mal:updateMangaStatus") {
                    malRepository.updateMangaStatus(malId = malId, status = "reading", numChaptersRead = chapter.chapterNumber.toInt())
                }
            }
        }
        manga.kitsuId?.let { kitsuId ->
            launch { safely("reader:kitsu:updateProgress") { kitsuRepository.updateProgress(kitsuId, chapter.chapterNumber.toInt()) } }
        }
        manga.mangaUpdatesId?.let { seriesId ->
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
