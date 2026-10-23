// KUN -->
package tachiyomi.data.updates

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import kotlin.time.Instant

/**
 * The Updates tab and the home-screen widget read the plain read/unread queries rather than the
 * filtered one. Both must drop chapters whose scanlator is excluded on their manga, otherwise they
 * disagree with the library update count and notifications, which already ignore those chapters.
 */
class UpdatesRepositoryExcludedScanlatorsTest {

    @Test
    fun `unread updates hide chapters from scanlators excluded on the manga`() = runBlocking<Unit> {
        val driver = newDriver()
        try {
            val database = DatabaseBindings.providesDatabase(driver)
            val repository = UpdatesRepositoryImpl(database)
            driver.insertManga()
            driver.insertChapter(id = 1L, scanlator = "Excluded")
            driver.insertChapter(id = 2L, scanlator = "Kept")
            driver.insertExcludedScanlator("Excluded")

            repository.unreadChapterIds(hideExcludedScanlators = true) shouldContainExactlyInAnyOrder listOf(2L)
            repository.unreadChapterIds(hideExcludedScanlators = false) shouldContainExactlyInAnyOrder listOf(1L, 2L)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `read updates hide chapters from scanlators excluded on the manga`() = runBlocking<Unit> {
        val driver = newDriver()
        try {
            val database = DatabaseBindings.providesDatabase(driver)
            val repository = UpdatesRepositoryImpl(database)
            driver.insertManga()
            driver.insertChapter(id = 1L, scanlator = "Excluded", read = true)
            driver.insertChapter(id = 2L, scanlator = "Kept", read = true)
            driver.insertExcludedScanlator("Excluded")

            repository.readChapterIds(hideExcludedScanlators = true) shouldContainExactlyInAnyOrder listOf(2L)
            repository.readChapterIds(hideExcludedScanlators = false) shouldContainExactlyInAnyOrder listOf(1L, 2L)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `hasExcludedUpdates only reports exclusions that hide a recent update`() = runBlocking<Unit> {
        val driver = newDriver()
        try {
            val database = DatabaseBindings.providesDatabase(driver)
            val repository = UpdatesRepositoryImpl(database)
            driver.insertManga()
            driver.insertChapter(id = 1L, scanlator = "Excluded")

            repository.hasExcludedUpdates(AFTER).first() shouldBe false

            // A scanlator that is not used by any chapter does not change the answer.
            driver.insertExcludedScanlator("Other")
            repository.hasExcludedUpdates(AFTER).first() shouldBe false

            driver.insertExcludedScanlator("Excluded")
            repository.hasExcludedUpdates(AFTER).first() shouldBe true
        } finally {
            driver.close()
        }
    }

    private suspend fun UpdatesRepositoryImpl.unreadChapterIds(hideExcludedScanlators: Boolean): List<Long> {
        return subscribeWithRead(
            read = false,
            after = AFTER,
            limit = 50,
            hideExcludedScanlators = hideExcludedScanlators,
        )
            .first()
            .map { it.chapterId }
    }

    private suspend fun UpdatesRepositoryImpl.readChapterIds(hideExcludedScanlators: Boolean): List<Long> {
        return subscribeWithRead(
            read = true,
            after = AFTER,
            limit = 50,
            hideExcludedScanlators = hideExcludedScanlators,
        )
            .first()
            .map { it.chapterId }
    }

    private suspend fun newDriver(): SqlDriver {
        return JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
            Database.Schema.create(it).await()
        }
    }

    private suspend fun SqlDriver.insertManga() {
        execute(
            null,
            """
            INSERT INTO manga(
                id, source_id, remote_url, remote_title, remote_status, remote_update_strategy,
                remote_memo, user_notes, user_reader_flags, user_chapter_flags,
                state_chapter_fetch_interval, state_initialized, remote_cover,
                state_cover_last_modified, last_modified_at, version, is_syncing,
                user_favorite_at, favorite_modified_at
            ) VALUES (
                1, 1, '/manga/1', 'Title', 0, 0,
                '{}', '', 0, 0,
                0, 1, 'https://example.com/cover.jpg',
                123, 0, 0, 0,
                1000, 500
            )
            """.trimIndent(),
            0,
        ).await()
    }

    private suspend fun SqlDriver.insertChapter(
        id: Long,
        scanlator: String,
        read: Boolean = false,
    ) {
        execute(
            null,
            """
            INSERT INTO chapter(
                id, manga_id, remote_url, remote_name, remote_scanlator, remote_chapter_number,
                remote_date_upload, remote_order, remote_memo, user_read, user_bookmark,
                user_last_page_read, state_date_fetch
            ) VALUES (
                $id, 1, '/chapter/$id', 'Chapter $id', '$scanlator', $id,
                ${AFTER.toEpochMilliseconds() + 1}, 0, '{}', ${if (read) 1 else 0}, 0,
                0, 2000
            )
            """.trimIndent(),
            0,
        ).await()
    }

    private suspend fun SqlDriver.insertExcludedScanlator(scanlator: String) {
        execute(
            null,
            "INSERT INTO excluded_scanlator(manga_id, scanlator) VALUES (1, '$scanlator')",
            0,
        ).await()
    }

    private companion object {
        val AFTER = Instant.fromEpochSeconds(5_000)
    }
}
// KUN <--
