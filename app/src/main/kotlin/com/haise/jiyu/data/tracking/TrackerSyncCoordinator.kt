package com.haise.jiyu.data.tracking

import com.haise.jiyu.anilist.AniListRepository
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.repository.ComickProgressTarget
import com.haise.jiyu.settings.ComickSyncMode
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.util.report
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
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
    private val comickRepository: ComickRepository,
    private val comickAuthManager: ComickAuthManager,
    private val settings: SettingsRepository,
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
        manga.comickHid?.let { hid ->
            // x1000 -> fractional kapitoly ("12.5") zustanou monotonicke i v Int klici; ostatni
            // trackery dostavaji schvalne zaokrouhlene toInt() (jejich API fractional neumi).
            if (shouldPush("comick:$hid", (chapter.chapterNumber * 1000).toInt()))
            launch {
                safely("reader:comick:updateProgress") {
                    if (!canPushComick()) return@safely
                    // Comick-source kapitoly maji v url chapter HID - presny zaznam i pres prejmenovani.
                    val chapterHid = chapter.url.removePrefix(COMICK_CHAPTER_URL_PREFIX)
                        .takeIf { it != chapter.url }
                    comickRepository.updateProgress(hid, chapter.chapterNumber, chapterHid)
                }
            }
        }
    }

    /**
     * Push postupu na Comick pro katalogove rodice resolved kapitoly. Reader cte realny
     * zdroj (jehoz manga `comickHid` nema - ten ma jen comick katalogovy radek), takze
     * hlavni [syncReadProgress] Comick v pripade importovane knihovny nikdy nezasahne.
     * Cile dodava `MangaRepository.propagateReadProgressToLinkedChapters`, ktery comick
     * rodice pri cteni uz stejne hleda kvuli propagaci read-stavu.
     */
    suspend fun syncComickParentProgress(targets: List<ComickProgressTarget>) = coroutineScope {
        for (target in targets) {
            if (!shouldPush("comick:${target.mangaHid}", (target.chapterNumber * 1000).toInt())) continue
            launch {
                safely("reader:comick:updateProgress") {
                    if (!canPushComick()) return@safely
                    comickRepository.updateProgress(target.mangaHid, target.chapterNumber, target.chapterHid)
                }
            }
        }
    }

    /** Twoway + skutecne udeleny write scope (user ho mohl na consentu odmitnout). */
    private suspend fun canPushComick(): Boolean =
        settings.comickSyncMode.first() == ComickSyncMode.TWOWAY && comickAuthManager.hasWriteScope.first()

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
