package com.haise.jiyu.community

import com.haise.jiyu.auth.AuthRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class PublicMangaEntry(
    val id: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("manga_id") val mangaId: String = "",
    @SerialName("manga_title") val mangaTitle: String = "",
    @SerialName("manga_cover") val mangaCover: String? = null,
    @SerialName("is_public") val isPublic: Boolean = false,
)

@Singleton
class CommunityRepository @Inject constructor(
    private val supabase: SupabaseClient,
    private val authRepository: AuthRepository,
) {
    suspend fun getPublicLists(): List<PublicMangaEntry> = try {
        supabase.from("public_manga_lists")
            .select { filter { eq("is_public", true) } }
            .decodeList()
    } catch (_: Exception) { emptyList() }

    suspend fun togglePublic(mangaId: String, mangaTitle: String, coverUrl: String?, isPublic: Boolean) {
        val userId = authRepository.currentUserId() ?: return
        try {
            if (isPublic) {
                supabase.from("public_manga_lists").upsert(
                    PublicMangaEntry(
                        userId = userId,
                        mangaId = mangaId,
                        mangaTitle = mangaTitle,
                        mangaCover = coverUrl,
                        isPublic = true,
                    )
                )
            } else {
                supabase.from("public_manga_lists").delete {
                    filter {
                        eq("user_id", userId)
                        eq("manga_id", mangaId)
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
