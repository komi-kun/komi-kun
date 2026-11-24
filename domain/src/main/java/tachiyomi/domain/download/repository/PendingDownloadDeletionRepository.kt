package tachiyomi.domain.download.repository

import tachiyomi.domain.download.model.PendingDownloadDeletion
import kotlin.time.Instant

/**
 * The queue of downloaded chapters a persistent chapter filter has hidden from a library manga
 * (`pending_download_deletion`), and the reads the sweep that deletes them needs.
 *
 * Every delete is a set operation and a no-op for a row that is not there, so a caller may delete a
 * chapter or a manga whose row is already gone (a library sync removed it, or the previous sweep
 * did) without having to check first. Reads never join the chapter table, so a row whose chapter no
 * longer exists is still returned to its sweep, which then simply finds nothing to delete.
 */
interface PendingDownloadDeletionRepository {

    /**
     * Records every chapter of [pendingDeletions] as hidden, in one transaction.
     *
     * A chapter that is already queued keeps its original `firstHiddenAt`: the grace period runs from
     * the first time it was seen hidden, never from the latest pass.
     */
    suspend fun insertAll(pendingDeletions: List<PendingDownloadDeletion>)

    /** Drops the queue rows of [chapterIds]: those chapters are visible again, or already deleted. */
    suspend fun deleteByChapterIds(chapterIds: List<Long>)

    /** Drops every queue row of [mangaIds]: those mangas left the library, were deleted, or stopped hiding. */
    suspend fun deleteByMangaIds(mangaIds: List<Long>)

    suspend fun deleteAll()

    /** The queued chapters first seen hidden at or before [cutoff], i.e. past the grace period. */
    suspend fun selectExpired(cutoff: Instant): List<PendingDownloadDeletion>

    suspend fun selectAll(): List<PendingDownloadDeletion>
}
