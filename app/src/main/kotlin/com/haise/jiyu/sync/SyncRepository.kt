package com.haise.jiyu.sync

import com.haise.jiyu.auth.AuthRepository
import com.haise.jiyu.data.repository.MangaRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

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
) {

    suspend fun pushToCloud() {
        val userId = authRepository.currentUserId() ?: return
        val now = System.currentTimeMillis()

        val libraryManga = mangaRepository.getAllLibraryManga()
        val mangaDtos = libraryManga.map { m ->
            MangaSyncDto(
                id = m.id,
                userId = userId,
                sourceId = m.sourceId,
                url = m.url,
                title = m.title,
                coverUrl = m.coverUrl,
                inLibrary = m.inLibrary,
                updatedAt = now,
            )
        }
        if (mangaDtos.isNotEmpty()) {
            supabase.from("manga_sync").upsert(mangaDtos)
        }

        val chapters = mangaRepository.getAllLibraryChapters()
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
        if (chapterDtos.isNotEmpty()) {
            supabase.from("chapter_sync").upsert(chapterDtos)
        }
    }

    suspend fun pullFromCloud() {
        val userId = authRepository.currentUserId() ?: return

        // Obnov mangu ze zálohy — bez toho na novém zařízení nikdy nezmizí prázdná knihovna
        val remoteManga = supabase.from("manga_sync")
            .select { filter { eq("user_id", userId) } }
            .decodeList<MangaSyncDto>()

        val libraryManga = mangaRepository.getAllLibraryManga()
        val localIds = libraryManga.map { it.id }.toSet()

        // Odebrání z knihovny na JINÉM zařízení se dřív nikdy nepropagovalo zpátky - pull
        // uměl jen vkládat nové tituly, ne rušit staré. syncNow() (jediné volající místo)
        // vždy nejdřív pushToCloud() a až pak tohle, takže remote v tuhle chvíli už odráží
        // aktuální lokální knihovnu TOHOTO zařízení - pokud přesto říká inLibrary=false u
        // titulu, co tu pořád je, odebral ho prokazatelně jiný přístroj.
        val remoteMangaById = remoteManga.associateBy { it.id }
        libraryManga.forEach { local ->
            if (remoteMangaById[local.id]?.inLibrary == false) {
                mangaRepository.removeFromLibrary(local.id)
            }
        }

        val toInsertManga = remoteManga
            .filter { it.inLibrary && it.id !in localIds }
            .map { dto ->
                com.haise.jiyu.data.db.entity.MangaEntity(
                    id = dto.id,
                    sourceId = dto.sourceId,
                    url = dto.url,
                    title = dto.title,
                    coverUrl = dto.coverUrl,
                    description = null,
                    status = null,
                    inLibrary = true,
                )
            }
        if (toInsertManga.isNotEmpty()) mangaRepository.upsertAllManga(toInsertManga)

        val remoteChapters = supabase.from("chapter_sync")
            .select { filter { eq("user_id", userId) } }
            .decodeList<ChapterSyncDto>()

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
            if (remote.updatedAt > local.lastReadAt) {
                local.copy(read = remote.read, lastPageRead = remote.lastPageRead, lastReadAt = remote.updatedAt)
            } else null
        }
        if (toUpdate.isNotEmpty()) {
            mangaRepository.upsertAllChapters(toUpdate)
        }
    }
}
