package com.haise.jiyu.sync

import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.MangaEntity
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

    private fun manga(id: String, inLibrary: Boolean = true, addedAt: Long = 0L) = MangaEntity(
        id = id, sourceId = "src", url = "/$id", title = "T-$id", coverUrl = null,
        description = null, status = null, inLibrary = inLibrary, addedAt = addedAt,
    )

    private fun tombstone(id: String, updatedAt: Long) = MangaSyncDto(
        id = id, userId = "u1", sourceId = "src", url = "/$id", title = "T-$id",
        inLibrary = false, updatedAt = updatedAt,
    )

    @Test
    fun `locally removed titles are pushed as in_library=false tombstones`() {
        val dtos = buildMangaSyncDtos(
            userId = "u1",
            libraryManga = listOf(manga("a")),
            removedManga = listOf(manga("b", inLibrary = false)),
            now = 5_000L,
        )

        assertEquals(listOf("a" to true, "b" to false), dtos.map { it.id to it.inLibrary })
        assertEquals(true, dtos.all { it.updatedAt == 5_000L })
    }

    @Test
    fun `a removed title that is back in the library is not pushed as a tombstone`() {
        val dtos = buildMangaSyncDtos(
            userId = "u1",
            libraryManga = listOf(manga("a")),
            removedManga = listOf(manga("a", inLibrary = false)),
            now = 5_000L,
        )

        assertEquals(listOf("a" to true), dtos.map { it.id to it.inLibrary })
    }

    @Test
    fun `a newer remote tombstone removes the local title`() {
        assertEquals(true, shouldRemoveLocally(manga("a", addedAt = 1_000L), tombstone("a", 2_000L), emptySet()))
    }

    @Test
    fun `a remote tombstone older than the local add does not remove a re-added title`() {
        assertEquals(false, shouldRemoveLocally(manga("a", addedAt = 3_000L), tombstone("a", 2_000L), emptySet()))
    }

    @Test
    fun `a title waiting to push its own removal is never removed by the pull`() {
        assertEquals(false, shouldRemoveLocally(manga("a", addedAt = 1_000L), tombstone("a", 2_000L), setOf("a")))
    }
    @Test
    fun `the account that owns the local library just proceeds`() {
        assertEquals(OwnershipDecision.PROCEED, decideOwnership("u1", "u1", libraryEmpty = false))
    }

    @Test
    fun `a library with an unknown owner is adopted by the signed in account`() {
        assertEquals(OwnershipDecision.ADOPT, decideOwnership(null, "u1", libraryEmpty = false))
    }

    @Test
    fun `a different account on an empty library adopts it without asking`() {
        assertEquals(OwnershipDecision.ADOPT, decideOwnership("u1", "u2", libraryEmpty = true))
    }

    @Test
    fun `a different account on a non-empty library is a conflict, nothing is pushed`() {
        assertEquals(OwnershipDecision.CONFLICT, decideOwnership("u1", "u2", libraryEmpty = false))
    }

    // ── stránkování stahování + přírůstkový push ─────────────────────────────

    @Test
    fun `fetchAllPages keeps asking until a page is not full`() = kotlinx.coroutines.runBlocking {
        val ranges = mutableListOf<Pair<Long, Long>>()
        val rows = (1..2500).toList()
        val all = fetchAllPages(pageSize = 1000) { from, to ->
            ranges += from to to
            rows.drop(from.toInt()).take((to - from + 1).toInt())
        }
        assertEquals(rows, all)
        assertEquals(listOf(0L to 999L, 1000L to 1999L, 2000L to 2999L), ranges)
    }

    @Test
    fun `fetchAllPages makes one more request when the last page is exactly full`() = kotlinx.coroutines.runBlocking {
        var calls = 0
        val all = fetchAllPages(pageSize = 2) { from, _ ->
            calls++
            if (from < 4) listOf(1, 2) else emptyList()
        }
        assertEquals(4, all.size)
        assertEquals(3, calls)
    }

    @Test
    fun `fetchAllPages of an empty table is a single request`() = kotlinx.coroutines.runBlocking {
        var calls = 0
        assertEquals(0, fetchAllPages<Int> { _, _ -> calls++; emptyList() }.size)
        assertEquals(1, calls)
    }

    @Test
    fun `the first push sends every chapter, later pushes only the changed ones`() {
        val a = local.copy(id = "a", lastReadAt = 100_000L)
        val b = local.copy(id = "b", lastReadAt = 500_000L)
        val untouched = local.copy(id = "c", lastReadAt = 0L)
        val all = listOf(a, b, untouched)

        assertEquals(listOf("a", "b", "c"), chaptersToPush(all, lastPushAt = 0L).map { it.id })
        assertEquals(listOf("b"), chaptersToPush(all, lastPushAt = 300_000L).map { it.id })
    }

    @Test
    fun `a chapter changed just before the last push is still resent within the clock slack`() {
        val edge = local.copy(id = "edge", lastReadAt = 299_000L)
        assertEquals(listOf("edge"), chaptersToPush(listOf(edge), lastPushAt = 300_000L).map { it.id })
    }
}
