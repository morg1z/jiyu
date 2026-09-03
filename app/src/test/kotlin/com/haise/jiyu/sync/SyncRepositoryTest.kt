package com.haise.jiyu.sync

import com.haise.jiyu.data.db.entity.ChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regresní testy na last-write-wins slučovací logiku ([mergeWithRemote]) a propagaci
 * odebrání z knihovny ([removedFromLibraryRemotely]) - viz komentáře u jejich definic
 * v [SyncRepository.kt]. Dřív se remote stav přejímal, jen když remote.read a lokálně
 * to ještě přečtené nebylo - jednou přečtenou kapitolu pak nešlo nikdy dál synchronizovat.
 */
class SyncRepositoryTest {

    private val local = ChapterEntity(
        id = "ch1", mangaId = "m1", sourceId = "src", url = "/ch1",
        name = "Chapter 1", chapterNumber = 1f, dateUpload = 0L, pageCount = 10,
        read = true, lastPageRead = 5, lastReadAt = 1_000L,
    )

    private fun remoteDto(read: Boolean, lastPageRead: Int, updatedAt: Long) = ChapterSyncDto(
        id = "ch1", userId = "u1", mangaId = "m1",
        read = read, lastPageRead = lastPageRead, updatedAt = updatedAt,
    )

    @Test
    fun `a strictly newer remote change overwrites local, even inside an already-read chapter`() {
        val remote = remoteDto(read = true, lastPageRead = 9, updatedAt = 2_000L)

        val merged = local.mergeWithRemote(remote)

        assertEquals(9, merged?.lastPageRead)
        assertEquals(2_000L, merged?.lastReadAt)
    }

    @Test
    fun `an older remote timestamp never overwrites a newer local change`() {
        val remote = remoteDto(read = true, lastPageRead = 9, updatedAt = 500L)

        val merged = local.mergeWithRemote(remote)

        assertNull("starsi remote zmena nesmi prepsat novejsi lokalni stav", merged)
    }

    @Test
    fun `an equal timestamp is treated as no new information, local wins`() {
        val remote = remoteDto(read = true, lastPageRead = 9, updatedAt = local.lastReadAt)

        val merged = local.mergeWithRemote(remote)

        assertNull(merged)
    }

    @Test
    fun `a newer remote can mark an already-read chapter back to unread`() {
        // Regrese k puvodnimu bugu: driv se prebiral remote stav jen kdyz remote.read==true,
        // takze "oznacit zpet jako neprectene" na jinem zarizeni se nikdy nesynchronizovalo.
        val remote = remoteDto(read = false, lastPageRead = 0, updatedAt = 2_000L)

        val merged = local.mergeWithRemote(remote)

        assertEquals(false, merged?.read)
    }

    @Test
    fun `manga still marked in_library=true remotely is never removed locally`() {
        val stillInLibrary = MangaSyncDto(
            id = "m1", userId = "u1", sourceId = "src", url = "/m1", title = "Test",
            inLibrary = true, updatedAt = 1_000L,
        )

        assertEquals(false, stillInLibrary.removedFromLibraryRemotely())
    }

    @Test
    fun `manga marked in_library=false remotely is flagged for local removal`() {
        val removedElsewhere = MangaSyncDto(
            id = "m1", userId = "u1", sourceId = "src", url = "/m1", title = "Test",
            inLibrary = false, updatedAt = 1_000L,
        )

        assertEquals(true, removedElsewhere.removedFromLibraryRemotely())
    }

    @Test
    fun `a manga with no remote row at all is never removed locally`() {
        // null = manga jeste nikdy nebyla pushnuta na server (napr. jina synchronizacni
        // vetev), ne ze byla explicitne odebrana - nesmi se zamenit se skutecnym inLibrary=false.
        val noRemoteRow: MangaSyncDto? = null

        assertEquals(false, noRemoteRow.removedFromLibraryRemotely())
    }
}
