package com.haise.jiyu.sync

import com.haise.jiyu.auth.AuthRepository
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Last-write-wins sloučení: kdo měnil naposled (skutečný čas změny, ne push-volání -
 * viz komentář u [ChapterSyncDto.updatedAt] výše), ten vyhrává. `null` = lokální stav
 * je novější nebo stejně starý, nic se nemění. Vytaženo jako čistá funkce mimo
 * [SyncRepository.pullFromCloud], aby šla otestovat bez Postgrest/síťové vrstvy.
 */
internal fun ChapterEntity.mergeWithRemote(remote: ChapterSyncDto): ChapterEntity? =
    if (remote.updatedAt > this.lastReadAt) {
        copy(read = remote.read, lastPageRead = remote.lastPageRead, lastReadAt = remote.updatedAt)
    } else null

/**
 * Velikost stránky při stahování z cloudu. Supabase (PostgREST) vrací na jeden dotaz nejvýš 1000 řádků, takže dotaz
 * bez stránkování by u větší knihovny tiše vrátil jen prvních 1000 (např. kapitoly).
 */
internal const val SYNC_PAGE_SIZE = 1000

/** Kolik řádků se posílá v jednom `upsert` - velké dávky by byly pomalé a mohly by narazit na limit požadavku. */
internal const val SYNC_PUSH_CHUNK = 500

/**
 * Stáhne všechny řádky po stránkách. [fetch] dostane rozsah `from..to` (včetně) a vrátí řádky té stránky; končí se, až
 * stránka není plná. Čistá funkce mimo [SyncRepository], aby šla otestovat bez síťové vrstvy.
 */
internal suspend fun <T> fetchAllPages(pageSize: Int = SYNC_PAGE_SIZE, fetch: suspend (from: Long, to: Long) -> List<T>): List<T> {
    val all = ArrayList<T>()
    var from = 0L
    while (true) {
        val page = fetch(from, from + pageSize - 1)
        all += page
        if (page.size < pageSize) return all
        from += pageSize
    }
}

/**
 * Které kapitoly poslat do cloudu: ty, které se na tomhle zařízení změnily od posledního úspěšného odeslání
 * ([lastPushAt]). Malá rezerva kryje nepřesnost hodin; nadbytečné odeslání je neškodné (upsert stejné hodnoty).
 * `lastPushAt == 0` = první odeslání, posílá se všechno.
 */
internal fun chaptersToPush(chapters: List<ChapterEntity>, lastPushAt: Long): List<ChapterEntity> =
    if (lastPushAt <= 0L) chapters else chapters.filter { it.lastReadAt >= lastPushAt - PUSH_CLOCK_SLACK_MS }

private const val PUSH_CLOCK_SLACK_MS = 60_000L

/** Odebrání z knihovny na JINÉM zařízení - viz komentář u volajícího místa v [SyncRepository.pullFromCloud]. */
internal fun MangaSyncDto?.removedFromLibraryRemotely(): Boolean = this?.inLibrary == false

/** Výsledek [decideOwnership]. */
internal enum class OwnershipDecision { PROCEED, ADOPT, CONFLICT }

/** Výsledek [SyncRepository.sync] - `OWNER_CONFLICT` = nic se neodeslalo ani nestáhlo, čeká se na rozhodnutí uživatele. */
enum class SyncOutcome { SYNCED, OWNER_CONFLICT }

/**
 * Komu patří lokální knihovna? Odhlášení knihovnu nemaže, takže po přihlášení JINÉHO účtu by ji push
 * nahrál pod ten účet (cizí data v cizím cloudu). Vlastník se proto pamatuje:
 * - vlastník sedí s přihlášeným účtem -> `PROCEED`;
 * - vlastník neznámý (starší verze appky) nebo lokální knihovna je prázdná -> `ADOPT` (zapíše se
 *   aktuální účet; u neznámého vlastníka je nejpravděpodobnější, že data patří účtu, který je přihlášený);
 * - jiný vlastník a neprázdná knihovna -> `CONFLICT`, uživatel musí rozhodnout.
 */
internal fun decideOwnership(ownerId: String?, currentUserId: String, libraryEmpty: Boolean): OwnershipDecision = when {
    ownerId == currentUserId -> OwnershipDecision.PROCEED
    ownerId == null || libraryEmpty -> OwnershipDecision.ADOPT
    else -> OwnershipDecision.CONFLICT
}

/**
 * Má se titul, který je lokálně v knihovně, kvůli vzdálenému "náhrobku" lokálně odebrat? Jen když
 * odebrání na cloudu je NOVĚJŠÍ než lokální přidání (`addedAt`) a titul není mezi lokálně čekajícími
 * na odeslání (tam by šlo o starý stav, který dnešní push stejně přepíše).
 */
internal fun shouldRemoveLocally(local: MangaEntity, remote: MangaSyncDto?, pendingRemovedIds: Set<String>): Boolean =
    remote != null && remote.removedFromLibraryRemotely() && local.id !in pendingRemovedIds && remote.updatedAt >= local.addedAt

/**
 * Řádky pro `manga_sync`: tituly v knihovně s `in_library=true` a lokálně odebrané tituly (jejichž
 * odebrání cloud ještě nezná) s `in_library=false` - "náhrobky". Bez nich cloud neví o odebrání a pull
 * odebraný titul při dalším syncu vrátí (audit nalez JIYU-SEC-1). Vytaženo jako čistá funkce kvůli testům.
 */
internal fun buildMangaSyncDtos(
    userId: String,
    libraryManga: List<MangaEntity>,
    removedManga: List<MangaEntity>,
    now: Long,
): List<MangaSyncDto> {
    val libraryIds = libraryManga.map { it.id }.toSet()
    val library = libraryManga.map { it.toSyncDto(userId, inLibrary = true, now = now) }
    val tombstones = removedManga.filter { it.id !in libraryIds }.map { it.toSyncDto(userId, inLibrary = false, now = now) }
    return library + tombstones
}

private fun MangaEntity.toSyncDto(userId: String, inLibrary: Boolean, now: Long) = MangaSyncDto(
    id = id,
    userId = userId,
    sourceId = sourceId,
    url = url,
    title = title,
    coverUrl = coverUrl,
    inLibrary = inLibrary,
    updatedAt = now,
)

@Serializable
data class MangaSyncDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("source_id") val sourceId: String,
    val url: String,
    val title: String,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("in_library") val inLibrary: Boolean,
    @SerialName("updated_at") val updatedAt: Long,
)

@Serializable
data class ChapterSyncDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("manga_id") val mangaId: String,
    val read: Boolean,
    @SerialName("last_page_read") val lastPageRead: Int,
    @SerialName("updated_at") val updatedAt: Long,
)

@Singleton
class SyncRepository @Inject constructor(
    private val supabase: SupabaseClient,
    private val authRepository: AuthRepository,
    private val mangaRepository: MangaRepository,
    private val settings: SettingsRepository,
) {

    /**
     * Pořadí pull → push (dřív push → pull): push vždy přepíše cloudový řádek stavem TOHOTO zařízení
     * (`in_library=true` u všeho, co tu je), takže by "náhrobek" z jiného zařízení přepsal dřív, než by
     * ho pull stihl přečíst, a odebrání se nikdy nešířilo. Pull-first ho nejdřív aplikuje lokálně.
     */
    suspend fun sync(): SyncOutcome {
        val userId = authRepository.currentUserId() ?: return SyncOutcome.SYNCED
        val decision = decideOwnership(
            ownerId = settings.localDataOwnerId.first(),
            currentUserId = userId,
            libraryEmpty = mangaRepository.getAllLibraryManga().isEmpty(),
        )
        if (decision == OwnershipDecision.CONFLICT) return SyncOutcome.OWNER_CONFLICT
        if (decision == OwnershipDecision.ADOPT) {
            settings.setLocalDataOwnerId(userId)
            settings.setSyncLastChapterPushAt(0L)
        }
        pullFromCloud()
        pushToCloud()
        return SyncOutcome.SYNCED
    }

    /** Uživatel potvrdil, že lokální knihovna patří přihlášenému účtu - nahraje se pod něj. */
    suspend fun syncClaimingLocalData(): SyncOutcome {
        val userId = authRepository.currentUserId() ?: return SyncOutcome.SYNCED
        settings.setLocalDataOwnerId(userId)
        settings.setSyncLastChapterPushAt(0L)
        return sync()
    }

    /**
     * Uživatel chce cloud přihlášeného účtu místo lokální knihovny: lokální tituly se odeberou
     * (včetně stažených kapitol) a čekající "náhrobky" se zahodí, aby se neodeslaly pod nový účet.
     */
    suspend fun syncDiscardingLocalData(): SyncOutcome {
        val userId = authRepository.currentUserId() ?: return SyncOutcome.SYNCED
        mangaRepository.getAllLibraryManga().forEach { mangaRepository.removeFromLibrary(it.id) }
        settings.clearPendingRemovedMangaIds(settings.pendingRemovedMangaIds.first())
        settings.setLocalDataOwnerId(userId)
        settings.setSyncLastChapterPushAt(0L)
        return sync()
    }

    suspend fun pushToCloud() {
        val userId = authRepository.currentUserId() ?: return
        val now = System.currentTimeMillis()

        val libraryManga = mangaRepository.getAllLibraryManga()
        val libraryIds = libraryManga.map { it.id }.toSet()
        // Titul, který byl mezitím znovu přidán, se z čekajících odebrání jen vyhodí (odešle se jako
        // in_library=true); titul, který už v DB není vůbec, nemá co posílat.
        val pending = settings.pendingRemovedMangaIds.first()
        val removedManga = (pending - libraryIds).mapNotNull { mangaRepository.getManga(it) }
        val mangaDtos = buildMangaSyncDtos(userId, libraryManga, removedManga, now)
        if (mangaDtos.isNotEmpty()) {
            supabase.from("manga_sync").upsert(mangaDtos)
        }
        if (pending.isNotEmpty()) settings.clearPendingRemovedMangaIds(pending)

        val lastPushAt = settings.syncLastChapterPushAt.first()
        val chapters = chaptersToPush(mangaRepository.getAllLibraryChapters(), lastPushAt)
        val chapterDtos = chapters.map { c ->
            ChapterSyncDto(
                id = c.id,
                userId = userId,
                mangaId = c.mangaId,
                read = c.read,
                lastPageRead = c.lastPageRead,
                // Skutečný čas POSLEDNÍ ZMĚNY čtení na tomhle zařízení (ChapterEntity.lastReadAt,
                // aktualizuje ho každé otočení stránky - viz ChapterDao.updateProgress/
                // updateScrollOffset), NE čas tohohle push volání. Kdyby se sem posílalo `now`,
                // periodický push by na serveru "posunul čas" i u kapitol, které se od
                // posledního pushe vůbec nezměnily - a pullFromCloud níž (poměr updatedAt vs.
                // lastReadAt) by pak mohl novější reálný postup na DRUHÉM zařízení tiše
                // přepsat starší hodnotou jen proto, že tohle zařízení zrovna udělalo prázdný push.
                updatedAt = c.lastReadAt,
            )
        }
        chapterDtos.chunked(SYNC_PUSH_CHUNK).forEach { chunk -> supabase.from("chapter_sync").upsert(chunk) }
        // Až po úspěšném odeslání všech dávek - při chybě se příště pošle znovu od původního času.
        settings.setSyncLastChapterPushAt(now)
    }

    suspend fun pullFromCloud() {
        val userId = authRepository.currentUserId() ?: return

        // Obnov mangu ze zálohy — bez toho na novém zařízení nikdy nezmizí prázdná knihovna
        val remoteManga = fetchAllPages { from, to ->
            supabase.from("manga_sync")
                .select { filter { eq("user_id", userId) }; order("id", Order.ASCENDING); range(from, to) }
                .decodeList<MangaSyncDto>()
        }

        val libraryManga = mangaRepository.getAllLibraryManga()
        val localIds = libraryManga.map { it.id }.toSet()

        // Odebrání z knihovny na JINÉM zařízení se dřív nikdy nepropagovalo zpátky - pull
        // uměl jen vkládat nové tituly, ne rušit staré. sync() volá tohle PŘED pushem, takže
        // "náhrobek" (in_library=false) z jiného zařízení tu ještě není přepsaný stavem tohohle
        // zařízení; odebere se jen pokud je novější než lokální přidání (viz shouldRemoveLocally).
        val pendingRemoved = settings.pendingRemovedMangaIds.first()
        val remoteMangaById = remoteManga.associateBy { it.id }
        libraryManga.forEach { local ->
            if (shouldRemoveLocally(local, remoteMangaById[local.id], pendingRemoved)) {
                mangaRepository.removeFromLibrary(local.id)
            }
        }

        // Vzdálený titul v knihovně, který lokálně v knihovně není: pokud tu řádek existuje (dřív
        // prohlížený nebo odebraný), jen se přepne inLibrary a zachová se všechna lokální metadata
        // (dřív full-row @Upsert stripnutou entitou přepsal popis, žánry, malId, hodnocení...);
        // úplně nový řádek se vloží. Titul, který uživatel právě lokálně odebral a cloud o tom ještě
        // neví, se nevrací.
        remoteManga
            .filter { it.inLibrary && it.id !in localIds && it.id !in pendingRemoved }
            .forEach { dto ->
                if (mangaRepository.getManga(dto.id) != null) {
                    mangaRepository.addExistingToLibrary(dto.id)
                } else {
                    mangaRepository.upsertAllManga(
                        listOf(
                            MangaEntity(
                                id = dto.id,
                                sourceId = dto.sourceId,
                                url = dto.url,
                                title = dto.title,
                                coverUrl = dto.coverUrl,
                                description = null,
                                status = null,
                                inLibrary = true,
                                addedAt = System.currentTimeMillis(),
                            ),
                        ),
                    )
                }
            }

        val remoteChapters = fetchAllPages { from, to ->
            supabase.from("chapter_sync")
                .select { filter { eq("user_id", userId) }; order("id", Order.ASCENDING); range(from, to) }
                .decodeList<ChapterSyncDto>()
        }

        val localChapters = mangaRepository.getAllLibraryChapters()
        val localMap = localChapters.associateBy { it.id }

        // Dřív se přejímal remote stav, jen když remote.read a lokálně to ještě přečtené
        // nebylo - jenže jednou přečtenou kapitolu (read=true) pak nešlo NIKDY dál
        // synchronizovat: postup v jejím dalším čtení na jednom zařízení se ke druhému
        // už nikdy nedostal, ať čas uplynul jakkoli. Teď se porovnává skutečný čas
        // poslední změny na obou stranách (remote.updatedAt viz pushToCloud výš vs. lokální
        // ChapterEntity.lastReadAt) - kdo měnil později, ten vyhrává, i uvnitř už přečtené
        // kapitoly (další stránka, případně i zpětné "označit nepřečteno").
        val toUpdate = remoteChapters.mapNotNull { remote ->
            val local = localMap[remote.id] ?: return@mapNotNull null
            local.mergeWithRemote(remote)
        }
        if (toUpdate.isNotEmpty()) {
            mangaRepository.upsertAllChapters(toUpdate)
        }
    }
}
