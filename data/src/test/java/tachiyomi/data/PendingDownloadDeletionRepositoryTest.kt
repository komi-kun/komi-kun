// KUN -->
package tachiyomi.data

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import komikun.download.PendingDownloadDeletion
import komikun.download.PendingDownloadDeletionRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/**
 * The persisted queue of downloaded chapters a chapter filter hid (`pending_download_deletion`),
 * exercised through the real repository on a real in-memory database.
 *
 * The queue's whole value is that `first_hidden_at` is the *first* time: the sweep re-inserts every
 * chapter it still sees hidden, and only the original stamp may survive that. The deletes are the
 * other half — they are what keeps a chapter that became visible, or a manga that left the library,
 * out of the next sweep — and they must stay plain set operations because the table is deliberately
 * free of foreign keys and cascades.
 */
class PendingDownloadDeletionRepositoryTest {

    @Test
    fun `inserting a chapter that is still hidden keeps the first time it was seen hidden`(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.repository.insertAll(listOf(deletion(chapterId = 11L, hiddenAtMs = 1_000L)))
            fixture.repository.insertAll(
                listOf(deletion(chapterId = 11L, hiddenAtMs = 5_000L, chapterUrl = "https://reused/11")),
            )

            // catches a plain INSERT/replace: it would push the stamp forward and restart the grace
            // period on every pass, so a chapter that is only briefly hidden forever escapes deletion
            fixture.rows() shouldBe listOf(Triple(11L, 1L, 1_000L))
            // and the row keeps the url it was first queued with, not the later one: the identity the
            // sweep compares stays the one of the chapter that was actually seen hidden
            fixture.repository.selectAll().single().chapterUrl shouldBe "https://c11"
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a chapter url round-trips through selectAll and selectExpired unchanged`(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            // a url nothing could derive from the id: a mapper that dropped the column, or an insert
            // that bound an empty string or the wrong column in its place, would not give this back
            fixture.repository.insertAll(
                listOf(
                    deletion(
                        chapterId = 4242L,
                        hiddenAtMs = 1_000L,
                        chapterUrl = "https://example.test/chapter/4242?token=abc",
                    ),
                ),
            )

            fixture.repository.selectAll().map { it.chapterUrl } shouldBe
                listOf("https://example.test/chapter/4242?token=abc")
            fixture.repository.selectExpired(Instant.fromEpochMilliseconds(1_000L)).map { it.chapterUrl } shouldBe
                listOf("https://example.test/chapter/4242?token=abc")
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a batch insert writes every chapter it is given`(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.repository.insertAll(
                listOf(
                    deletion(chapterId = 11L, mangaId = 1L, hiddenAtMs = 1_000L),
                    deletion(chapterId = 12L, mangaId = 1L, hiddenAtMs = 2_000L),
                    deletion(chapterId = 21L, mangaId = 2L, hiddenAtMs = 3_000L),
                ),
            )

            fixture.rows() shouldBe listOf(
                Triple(11L, 1L, 1_000L),
                Triple(12L, 1L, 2_000L),
                Triple(21L, 2L, 3_000L),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `deleteByChapterIds removes exactly the chapters it names`(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.seed()
            fixture.rows().size shouldBe 3

            fixture.repository.deleteByChapterIds(listOf(12L))

            // catches a delete that took the manga's whole queue, or bound the wrong column
            fixture.rows() shouldBe listOf(Triple(11L, 1L, 1_000L), Triple(21L, 2L, 3_000L))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `deleteByMangaIds removes the rows of those mangas only`(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.seed()

            fixture.repository.deleteByMangaIds(listOf(1L))

            // both chapters of manga 1 go, the chapter of manga 2 stays
            fixture.rows() shouldBe listOf(Triple(21L, 2L, 3_000L))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `deleteAll empties the queue`(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.seed()

            fixture.repository.deleteAll()

            fixture.rows().shouldBeEmpty()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `selectExpired includes a row exactly at the cutoff and excludes one just after it`(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            // at the cutoff, a millisecond older than it, a millisecond younger than it
            fixture.repository.insertAll(
                listOf(
                    deletion(chapterId = 11L, mangaId = 1L, hiddenAtMs = 3_600_000L),
                    deletion(chapterId = 12L, mangaId = 1L, hiddenAtMs = 3_599_999L),
                    deletion(chapterId = 13L, mangaId = 1L, hiddenAtMs = 3_600_001L),
                ),
            )

            val expired = fixture.repository.selectExpired(Instant.fromEpochMilliseconds(3_600_000L))

            // catches a strict `<`, which would hold a chapter an extra pass at the exact boundary,
            // or a stamp rounded to seconds: the chapter and its stored millisecond travel together
            expired.map { it.chapterId to it.firstHiddenAt.toEpochMilliseconds() }.sortedBy { it.second } shouldBe
                listOf(12L to 3_599_999L, 11L to 3_600_000L)
            // the row a millisecond past the cutoff has not waited the whole period and stays queued
            fixture.rows().map { it.first } shouldBe listOf(11L, 12L, 13L)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a row whose chapter is gone is still queued and its delete is a no-op`(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.insertManga(1L)
            fixture.insertChapter(mangaId = 1L, chapterId = 11L)
            fixture.repository.insertAll(listOf(deletion(chapterId = 11L, hiddenAtMs = 1_000L)))
            // a library sync dropping the chapter, or its manga, leaves the row behind (no FK, no
            // cascade): the sweep still has to see and be able to drop it
            fixture.deleteChapter(11L)

            fixture.repository.selectAll().map { it.chapterId } shouldBe listOf(11L)
            fixture.repository.deleteByChapterIds(listOf(11L))
            fixture.repository.selectAll().shouldBeEmpty()
        } finally {
            fixture.close()
        }
    }

    private class Fixture {
        private val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        private val database = DatabaseBindings.providesDatabase(driver)
        val repository = PendingDownloadDeletionRepositoryImpl(database)

        init {
            runBlocking {
                // the app's driver enables them, and the queue has to be unaffected by them
                driver.execute(null, "PRAGMA foreign_keys = ON", 0).await()
                Database.Schema.create(driver).await()
            }
        }

        fun close() = driver.close()

        suspend fun seed() {
            repository.insertAll(
                listOf(
                    deletion(chapterId = 11L, mangaId = 1L, hiddenAtMs = 1_000L),
                    deletion(chapterId = 12L, mangaId = 1L, hiddenAtMs = 2_000L),
                    deletion(chapterId = 21L, mangaId = 2L, hiddenAtMs = 3_000L),
                ),
            )
        }

        suspend fun insertManga(id: Long) {
            driver.execute(
                null,
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, remote_update_strategy, remote_memo, user_notes, user_reader_flags, user_chapter_flags, state_chapter_fetch_interval, state_initialized, remote_cover, state_cover_last_modified, last_modified_at, version, is_syncing, user_favorite_at, favorite_modified_at) " +
                    "VALUES ($id, 7, 'https://m$id', 'Title', 0, 0, '{}', '', 0, 0, 0, 1, NULL, 123, 10, 1, 0, 1000, 500)",
                0,
            ).await()
        }

        suspend fun insertChapter(mangaId: Long, chapterId: Long) {
            driver.execute(
                null,
                "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_chapter_number, remote_order, remote_memo, user_read, user_bookmark, user_last_page_read, state_date_fetch) " +
                    "VALUES ($chapterId, $mangaId, 'https://c$chapterId', 'Chapter', 1.0, 1, '{}', 0, 0, 0, 2000)",
                0,
            ).await()
        }

        suspend fun deleteChapter(chapterId: Long) {
            driver.execute(null, "DELETE FROM chapter WHERE id = $chapterId", 0).await()
        }

        /** `(chapter id, manga id, first hidden at in ms)` per queue row, read straight from the table. */
        fun rows(): List<Triple<Long, Long, Long>> = driver.executeQuery(
            null,
            "SELECT chapter_id, manga_id, first_hidden_at FROM pending_download_deletion ORDER BY chapter_id",
            { cursor ->
                val values = mutableListOf<Triple<Long, Long, Long>>()
                while (cursor.next().value) {
                    values += Triple(cursor.getLong(0)!!, cursor.getLong(1)!!, cursor.getLong(2)!!)
                }
                QueryResult.Value<List<Triple<Long, Long, Long>>>(values.toList())
            },
            0,
        ).value
    }
}

private fun deletion(
    chapterId: Long,
    mangaId: Long = 1L,
    hiddenAtMs: Long,
    chapterUrl: String = "https://c$chapterId",
) = PendingDownloadDeletion(
    chapterId = chapterId,
    mangaId = mangaId,
    firstHiddenAt = Instant.fromEpochMilliseconds(hiddenAtMs),
    chapterUrl = chapterUrl,
)
// KUN <--
