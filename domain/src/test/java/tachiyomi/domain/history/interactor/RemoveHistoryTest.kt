// KUN -->
package tachiyomi.domain.history.interactor

import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.history.repository.HistoryRepository

/**
 * The user's removal paths have to queue the rows, not only reset them: a reset alone is invisible
 * to the SyncYomi deletions request, which reads `history.pending_deletion`, so the removal would
 * never reach the server and the next restore would bring the entries back.
 */
class RemoveHistoryTest {

    private val repository = mockk<HistoryRepository>(relaxed = true)
    private val removeHistory = RemoveHistory(repository)

    @Test
    fun `removing a selection queues exactly the selected rows`() = runTest {
        removeHistory.await(listOf(11L, 12L))

        coVerify(exactly = 1) { repository.queueHistoryDeletion(listOf(11L, 12L)) }
        // a plain reset leaves nothing for the deletions request to send
        coVerify(exactly = 0) { repository.resetHistory(any()) }
    }

    @Test
    fun `removing a manga from history queues every row of it`() = runTest {
        removeHistory.awaitManga(listOf(7L, 8L))

        coVerify(exactly = 1) { repository.queueHistoryDeletionByMangaIds(listOf(7L, 8L)) }
        coVerify(exactly = 0) { repository.resetHistoryByMangaIds(any()) }
    }

    @Test
    fun `clearing all history reports whether the queue write succeeded`() = runTest {
        // the History tab only reports "cleared" on a successful write
        removeHistory.awaitAll()

        coVerify(exactly = 1) { repository.deleteAllHistory() }
    }
}
// KUN <--
