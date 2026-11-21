package tachiyomi.domain.history.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.model.HistoryWithRelations
import kotlin.time.Instant

// KUN -->
/**
 * One history row of a library manga with the chapter url its `history` tombstone key is built from.
 * A full-snapshot sync compares these against the server's per-manga lists, so it needs one
 * projection instead of every chapter of every manga.
 */
data class HistoryChapterEntry(
    val historyId: Long,
    val mangaId: Long,
    val chapterUrl: String,
    val readAt: Instant?,
)

/**
 * One history row the user removed on this device whose `history` tombstone the server has not
 * confirmed yet, with the urls that tombstone's key is built from (the server keys a history entry
 * by its chapter).
 */
data class PendingHistoryDeletion(
    val historyId: Long,
    val sourceId: Long,
    val mangaUrl: String,
    val chapterUrl: String,
)
// KUN <--

interface HistoryRepository {

    fun getHistory(
        query: String,
        // KMK -->
        unfinishedManga: Boolean?,
        unfinishedChapter: Boolean?,
        nonLibraryEntries: Boolean?,
        // KMK <--
        // KUN -->
        includedCategories: List<Long>,
        excludedCategories: List<Long>,
        inclusionWins: Boolean,
        // KUN <--
    ): Flow<List<HistoryWithRelations>>

    suspend fun getTotalReadDuration(): Long

    suspend fun getHistoryByMangaId(mangaId: Long): List<History>

    suspend fun getHistoryByMangaIds(mangaIds: List<Long>): Map<Long, List<History>>

    // KUN -->
    /** [HistoryChapterEntry] rows of [mangaIds], in one query joining each row to its chapter. */
    suspend fun getHistoryChapterEntriesByMangaIds(mangaIds: List<Long>): List<HistoryChapterEntry>

    /**
     * Queue [historyIds] for a server tombstone: each row loses its read date (which is what hides
     * it from every history reader) and is marked pending. Rows without a read date are untouched.
     */
    suspend fun queueHistoryDeletion(historyIds: List<Long>)

    /** The per-manga removal: queues every row of [mangaIds] that still has a read date. */
    suspend fun queueHistoryDeletionByMangaIds(mangaIds: List<Long>)

    /**
     * At most [limit] rows still awaiting a server tombstone, ordered by row id, starting after
     * [afterId]. Paged so a "clear all" never has to hold the whole removed history at once.
     */
    suspend fun getPendingHistoryDeletions(limit: Int, afterId: Long = 0): List<PendingHistoryDeletion>

    /** Drops the queue flag of the rows the server confirmed; their read date stays as it is. */
    suspend fun clearPendingHistoryDeletion(historyIds: List<Long>)
    // KUN <--

    // KMK -->
    suspend fun resetHistory(historyIds: List<Long>)

    suspend fun resetHistoryByMangaIds(mangaIds: List<Long>)
    // KMK <--

    suspend fun deleteAllHistory(): Boolean

    suspend fun deleteResetHistory()

    suspend fun upsertHistory(historyUpdate: HistoryUpdate)

    // SY -->
    suspend fun upsertHistory(historyUpdates: List<HistoryUpdate>)
    // SY <--
}
