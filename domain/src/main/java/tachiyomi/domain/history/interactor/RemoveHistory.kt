package tachiyomi.domain.history.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository

@Inject
class RemoveHistory(
    private val repository: HistoryRepository,
) {

    suspend fun awaitAll(): Boolean {
        return repository.deleteAllHistory()
    }

    // KMK -->
    suspend fun await(historyIds: List<Long>) {
        // KUN -->
        // The rows are queued, not only reset: a removal is a `history` tombstone the server has to
        // be told about, and the row itself is the queue (history.pending_deletion).
        repository.queueHistoryDeletion(historyIds)
        // KUN <--
    }

    suspend fun awaitManga(mangaIds: List<Long>) {
        // KUN -->
        repository.queueHistoryDeletionByMangaIds(mangaIds)
        // KUN <--
    }
    // KMK <--
}
