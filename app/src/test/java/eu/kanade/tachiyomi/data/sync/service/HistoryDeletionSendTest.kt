// KUN -->
package eu.kanade.tachiyomi.data.sync.service

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.history.HistoryRepositoryImpl
import kotlin.time.Clock

/**
 * The send side of the history tombstone queue.
 *
 * `SyncYomiSyncService.submitDeletedItems` pages `pending_history_deletion` by queue row id, builds
 * the keys with [historyTombstones] and deletes a page only when [acknowledgedDeletions] accounts for
 * every entry of the request. The requests themselves go over HTTP, which these tests do not touch:
 * what they drive is the repository the loop reads and deletes through, plus the acknowledgement it
 * decides with, over a real in-memory database.
 */
class HistoryDeletionSendTest {

    @Test
    fun `a page the server confirms is deleted and leaves the removal standing`(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.queueThreeEntries()

            val page = fixture.repository.getPendingHistoryDeletions(limit = 3)
            val keys = historyTombstones(page).map { it.key }
            // every entry of the request is accounted for, which is what the loop requires
            val acknowledged = acknowledgedDeletions("""{"acknowledged":3,"applied":2}""", emptySet(), emptySet(), keys)
            assertEquals(keys, acknowledged.histories.toList())
            fixture.repository.clearPendingHistoryDeletion(page.map { it.id })

            // the tombstone has been delivered: nothing is left queued, the removals still stand
            assertTrue(fixture.repository.getPendingHistoryDeletions(limit = 10).isEmpty())
            assertEquals(3L, fixture.count("SELECT count(*) FROM history WHERE read_at IS NULL"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a page the server only partly applied stays queued`(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.queueThreeEntries()

            val page = fixture.repository.getPendingHistoryDeletions(limit = 3)
            val keys = historyTombstones(page).map { it.key }
            // `applied` counts changed rows, so a page of three that changed two is not a
            // confirmation: deleting here would lose the tombstone the server never looked at
            val acknowledged = acknowledgedDeletions("""{"acknowledged":2,"applied":2}""", emptySet(), emptySet(), keys)
            // the loop ends the run instead of clearing, so the whole page is still queued
            assertTrue(acknowledged.histories.isEmpty())
            assertEquals(3, fixture.repository.getPendingHistoryDeletions(limit = 10).size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a legacy acknowledgement keeps the whole history page queued`(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.queueThreeEntries()

            val page = fixture.repository.getPendingHistoryDeletions(limit = 3)
            val keys = historyTombstones(page).map { it.key }
            // a pre-#335 server 2xxes and ignores `items`: it only read the legacy chapter list, so
            // clearing the history rows here would drop removals the server never applied
            val acknowledged = acknowledgedDeletions("""{"acknowledged":3}""", emptySet(), emptySet(), keys)
            assertTrue(acknowledged.histories.isEmpty())
            assertEquals(3, fixture.repository.getPendingHistoryDeletions(limit = 10).size)
        } finally {
            fixture.close()
        }
    }

    private class Fixture {
        private val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        private val database = DatabaseBindings.providesDatabase(driver)
        val repository = HistoryRepositoryImpl(database)

        init {
            runBlocking { Database.Schema.create(driver).await() }
        }

        fun close() = driver.close()

        /** One manga, three read chapters, all three removals queued while sync could deliver them. */
        suspend fun queueThreeEntries() {
            driver.execute(
                null,
                """
                INSERT INTO manga(
                    id, source_id, remote_url, remote_title, remote_status, remote_update_strategy,
                    remote_memo, user_notes, user_reader_flags, user_chapter_flags,
                    state_chapter_fetch_interval, state_initialized, remote_cover,
                    state_cover_last_modified, last_modified_at, version, is_syncing,
                    user_favorite_at, favorite_modified_at
                ) VALUES (1, 7, '/m', 'Title', 0, 0, '{}', '', 0, 0, 0, 1, NULL, 123, 10, 1, 0, 1000, 500)
                """.trimIndent(),
                0,
            ).await()
            listOf(11L, 12L, 13L).forEach { chapterId ->
                driver.execute(
                    null,
                    """
                    INSERT INTO chapter(
                        id, manga_id, remote_url, remote_name, remote_chapter_number,
                        remote_order, remote_memo, user_read, user_bookmark,
                        user_last_page_read, state_date_fetch,
                        remote_date_upload, last_modified_at, version, is_syncing
                    ) VALUES ($chapterId, 1, '/m/ch${chapterId - 10}', 'Chapter', 1.0, 1, '{}', 1, 0, 0, 2000, 2000, 10, 1, 0)
                    """.trimIndent(),
                    0,
                ).await()
                driver.execute(
                    null,
                    "INSERT INTO history(id, manga_id, chapter_id, read_at, read_duration) " +
                        "VALUES (${chapterId + 90}, 1, $chapterId, ${Clock.System.now().toEpochMilliseconds()}, 60)",
                    0,
                ).await()
            }
            repository.removeHistory(queueForSync = true, historyIds = listOf(101L, 102L, 103L))
        }

        fun count(sql: String): Long = driver.executeQuery(
            null,
            sql,
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else 0L) },
            0,
        ).value!!
    }
}
// KUN <--
