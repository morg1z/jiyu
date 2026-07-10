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
                updatedAt = now,
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

        val localIds = mangaRepository.getAllLibraryManga().map { it.id }.toSet()
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

        val toUpdate = remoteChapters.mapNotNull { remote ->
            val local = localMap[remote.id] ?: return@mapNotNull null
            if (remote.read && !local.read) local.copy(read = true, lastPageRead = remote.lastPageRead)
            else null
        }
        if (toUpdate.isNotEmpty()) {
            mangaRepository.upsertAllChapters(toUpdate)
        }
    }
}
