// KUN -->
package tachiyomi.data

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import tachiyomi.data.backup.RestoreRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.track.TrackRepositoryImpl
import tachiyomi.domain.backup.model.RestoredHistory
import tachiyomi.domain.backup.model.RestoredManga
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.toMangaUpdate
import kotlin.time.Instant

/**
 * History restore semantics: a winning sync payload is authoritative (exact timestamps and
 * tombstones), while a manual restore or a losing payload only max-merges and never removes.
 * A removal is stored as `read_at IS NULL`, never epoch 0.
 */
class RestoreRepositoryHistoryTest {

    @Test
    fun `a winning tombstone clears read_at without writing epoch 0 and keeps the duration`() {
        runBlocking {
            val driver = newDriver()
            try {
                val database = DatabaseBindings.providesDatabase(driver)
                val mangaRepository = MangaRepositoryImpl(database)
                val chapterRepository = ChapterRepositoryImpl(database)
                val repository = restoreRepository(database, mangaRepository, chapterRepository)
                driver.insertManga(version = 5L, lastModifiedAt = 10L)
                val chapters = chapterRepository.addAll(listOf(chapter("/c1")))
                driver.insertHistory(chapters[0].id, readAt = 5_000L, readDuration = 100L)

                restore(
                    repository = repository,
                    chapters = listOf(chapter("/c1")),
                    // backup lastRead == 0 => readAt == null, i.e. removed.
                    history = listOf(RestoredHistory("/c1", readAt = null, readDuration = 40L)),
                )

                val row = historyRows(database).getValue(chapters[0].id)
                row.read_at shouldBe null
                row.read_duration shouldBe 100L
            } finally {
                driver.close()
            }
        }
    }

    @Test
    fun `a winning sync payload writes the exact timestamp and keeps the longest duration`() {
        runBlocking {
            val driver = newDriver()
            try {
                val database = DatabaseBindings.providesDatabase(driver)
                val mangaRepository = MangaRepositoryImpl(database)
                val chapterRepository = ChapterRepositoryImpl(database)
                val repository = restoreRepository(database, mangaRepository, chapterRepository)
                driver.insertManga(version = 5L, lastModifiedAt = 10L)
                val chapters = chapterRepository.addAll(listOf(chapter("/c1")))
                driver.insertHistory(chapters[0].id, readAt = 9_000L, readDuration = 100L)

                restore(
                    repository = repository,
                    chapters = listOf(chapter("/c1")),
                    // An older read still wins because the payload owns the value on a sync.
                    history = listOf(RestoredHistory("/c1", Instant.fromEpochMilliseconds(5_000), 40L)),
                )

                val row = historyRows(database).getValue(chapters[0].id)
                row.read_at shouldBe Instant.fromEpochMilliseconds(5_000)
                row.read_duration shouldBe 100L
            } finally {
                driver.close()
            }
        }
    }

    @Test
    fun `a local row absent from the payload is removed unless it was read during the sync`() {
        runBlocking {
            val driver = newDriver()
            try {
                val database = DatabaseBindings.providesDatabase(driver)
                val mangaRepository = MangaRepositoryImpl(database)
                val chapterRepository = ChapterRepositoryImpl(database)
                val repository = restoreRepository(database, mangaRepository, chapterRepository)
                driver.insertManga(version = 5L, lastModifiedAt = 10L)
                val chapters = chapterRepository.addAll(
                    listOf(chapter("/c1"), chapter("/c2"), chapter("/c3")),
                )
                driver.insertHistory(chapters[0].id, readAt = 500L, readDuration = 10L)
                // Removed on another device: read before the snapshot, absent from the payload.
                driver.insertHistory(chapters[1].id, readAt = 500L, readDuration = 20L)
                // Read after the snapshot (sync in flight): absent from the payload but must stay.
                driver.insertHistory(chapters[2].id, readAt = 2_000_000L, readDuration = 30L)

                restore(
                    repository = repository,
                    chapters = listOf(chapter("/c1"), chapter("/c2"), chapter("/c3")),
                    history = listOf(RestoredHistory("/c1", Instant.fromEpochMilliseconds(900), 10L)),
                    // Epoch seconds. Rows read at/after 1_000_000 ms survive.
                    syncSnapshotAt = 1_000L,
                )

                val rows = historyRows(database)
                rows.getValue(chapters[0].id).read_at shouldBe Instant.fromEpochMilliseconds(900)
                rows.getValue(chapters[1].id).read_at shouldBe null
                // Duration is preserved so the removal can be re-published.
                rows.getValue(chapters[1].id).read_duration shouldBe 20L
                rows.getValue(chapters[2].id).read_at shouldBe Instant.fromEpochMilliseconds(2_000_000)
            } finally {
                driver.close()
            }
        }
    }

    @Test
    fun `an empty winning payload tombstones every row`() {
        runBlocking {
            val driver = newDriver()
            try {
                val database = DatabaseBindings.providesDatabase(driver)
                val mangaRepository = MangaRepositoryImpl(database)
                val chapterRepository = ChapterRepositoryImpl(database)
                val repository = restoreRepository(database, mangaRepository, chapterRepository)
                driver.insertManga(version = 5L, lastModifiedAt = 10L)
                val chapters = chapterRepository.addAll(listOf(chapter("/c1"), chapter("/c2")))
                driver.insertHistory(chapters[0].id, readAt = 500L, readDuration = 10L)
                driver.insertHistory(chapters[1].id, readAt = 500L, readDuration = 20L)

                restore(
                    repository = repository,
                    chapters = listOf(chapter("/c1"), chapter("/c2")),
                    history = emptyList(),
                    syncSnapshotAt = 1_000L,
                )

                historyRows(database).values.forEach { it.read_at shouldBe null }
            } finally {
                driver.close()
            }
        }
    }

    @Test
    fun `a losing sync payload max merges and never removes`() {
        runBlocking {
            val driver = newDriver()
            try {
                val database = DatabaseBindings.providesDatabase(driver)
                val mangaRepository = MangaRepositoryImpl(database)
                val chapterRepository = ChapterRepositoryImpl(database)
                val repository = restoreRepository(database, mangaRepository, chapterRepository)
                driver.insertManga(version = 6L, lastModifiedAt = 20L)
                val chapters = chapterRepository.addAll(listOf(chapter("/c1"), chapter("/c2")))
                driver.insertHistory(chapters[0].id, readAt = 9_000L, readDuration = 100L)
                driver.insertHistory(chapters[1].id, readAt = 5_000L, readDuration = 50L)

                restore(
                    repository = repository,
                    version = 5L,
                    lastModifiedAt = 10L,
                    chapters = listOf(chapter("/c1"), chapter("/c2")),
                    history = listOf(
                        // A removed entry in a stale payload must not clear the local read.
                        RestoredHistory("/c1", readAt = null, readDuration = 10L),
                        // An older read keeps the local (later) timestamp.
                        RestoredHistory("/c2", Instant.fromEpochMilliseconds(3_000), 10L),
                    ),
                    syncSnapshotAt = 1_000L,
                )

                val rows = historyRows(database)
                rows.getValue(chapters[0].id).read_at shouldBe Instant.fromEpochMilliseconds(9_000)
                rows.getValue(chapters[0].id).read_duration shouldBe 100L
                rows.getValue(chapters[1].id).read_at shouldBe Instant.fromEpochMilliseconds(5_000)
                rows.getValue(chapters[1].id).read_duration shouldBe 50L
            } finally {
                driver.close()
            }
        }
    }

    @Test
    fun `a manual restore max merges and never writes a tombstone`() {
        runBlocking {
            val driver = newDriver()
            try {
                val database = DatabaseBindings.providesDatabase(driver)
                val mangaRepository = MangaRepositoryImpl(database)
                val chapterRepository = ChapterRepositoryImpl(database)
                val repository = restoreRepository(database, mangaRepository, chapterRepository)
                driver.insertManga(version = 5L, lastModifiedAt = 10L)
                val chapters = chapterRepository.addAll(listOf(chapter("/c1"), chapter("/c2")))
                driver.insertHistory(chapters[0].id, readAt = 5_000L, readDuration = 100L)
                driver.insertHistory(chapters[1].id, readAt = null, readDuration = 50L)

                restore(
                    repository = repository,
                    isSync = false,
                    chapters = listOf(chapter("/c1"), chapter("/c2")),
                    history = listOf(
                        RestoredHistory("/c1", readAt = null, readDuration = 10L),
                        RestoredHistory("/c2", Instant.fromEpochMilliseconds(3_000), 10L),
                    ),
                    // A manual restore may not delete absent rows even with a snapshot present.
                    syncSnapshotAt = 1_000L,
                )

                val rows = historyRows(database)
                rows.getValue(chapters[0].id).read_at shouldBe Instant.fromEpochMilliseconds(5_000)
                rows.getValue(chapters[0].id).read_duration shouldBe 100L
                rows.getValue(chapters[1].id).read_at shouldBe Instant.fromEpochMilliseconds(3_000)
                rows.getValue(chapters[1].id).read_duration shouldBe 50L
            } finally {
                driver.close()
            }
        }
    }

    @Test
    fun `an unknown chapter url is ignored`() {
        runBlocking {
            val driver = newDriver()
            try {
                val database = DatabaseBindings.providesDatabase(driver)
                val mangaRepository = MangaRepositoryImpl(database)
                val chapterRepository = ChapterRepositoryImpl(database)
                val repository = restoreRepository(database, mangaRepository, chapterRepository)
                driver.insertManga(version = 5L, lastModifiedAt = 10L)
                val chapters = chapterRepository.addAll(listOf(chapter("/c1")))
                driver.insertHistory(chapters[0].id, readAt = 5_000L, readDuration = 100L)

                restore(
                    repository = repository,
                    chapters = listOf(chapter("/c1")),
                    history = listOf(RestoredHistory("/unknown", Instant.fromEpochMilliseconds(9_000), 10L)),
                    syncSnapshotAt = 1_000L,
                )

                val rows = historyRows(database)
                rows.keys shouldBe setOf(chapters[0].id)
                // /c1 is absent from the payload, but it was read before the snapshot: tombstoned.
                rows.getValue(chapters[0].id).read_at shouldBe null
            } finally {
                driver.close()
            }
        }
    }

    private suspend fun restore(
        repository: RestoreRepositoryImpl,
        isSync: Boolean = true,
        version: Long = 5L,
        lastModifiedAt: Long = 10L,
        chapters: List<Chapter>,
        history: List<RestoredHistory>,
        syncSnapshotAt: Long? = null,
    ) {
        repository.restoreManga(
            entries = listOf(
                RestoredManga(
                    manga = manga(version = version, lastModifiedAt = lastModifiedAt),
                    chapters = chapters,
                    categoryIds = null,
                    history = history,
                    tracks = emptyList(),
                    excludedScanlators = emptyList(),
                ),
            ),
            isSync = isSync,
            restoreCategories = false,
            restoreTracking = true,
            restoreHistory = true,
            restoreCustomInfo = true,
            removeMissingChaptersBefore = syncSnapshotAt,
            update = { it.toMangaUpdate() },
        )
    }

    private suspend fun historyRows(database: Database) = database.historyQueries
        .getHistoryByMangaId(1L)
        .awaitAsList()
        .associateBy { it.chapter_id }

    private fun restoreRepository(
        database: Database,
        mangaRepository: MangaRepositoryImpl,
        chapterRepository: ChapterRepositoryImpl,
    ) = RestoreRepositoryImpl(
        database = database,
        mangaRepository = mangaRepository,
        chapterRepository = chapterRepository,
        trackRepository = TrackRepositoryImpl(database),
        mangaMetadataRepository = mockk(relaxed = true),
        customMangaRepository = mockk(relaxed = true),
    )

    private fun manga(version: Long, lastModifiedAt: Long) = Manga.create().copy(
        id = 1L,
        source = 1L,
        favorite = true,
        dateAdded = Instant.fromEpochMilliseconds(1_000),
        url = "/manga/1",
        ogTitle = "Title",
        ogArtist = null,
        ogAuthor = null,
        ogThumbnailUrl = null,
        ogDescription = null,
        ogGenre = null,
        ogStatus = 0L,
        updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
        initialized = true,
        lastModifiedAt = lastModifiedAt,
        favoriteModifiedAt = 500L,
        version = version,
        notes = "",
        memo = JsonObject(emptyMap()),
    )

    private fun chapter(url: String) = Chapter.create().copy(
        mangaId = 1L,
        url = url,
        name = url,
        dateFetch = Instant.fromEpochMilliseconds(1_000),
        version = 1L,
        lastModifiedAt = 10L,
    )

    private suspend fun newDriver(): SqlDriver {
        return JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
            Database.Schema.create(it).await()
        }
    }

    private suspend fun SqlDriver.insertManga(version: Long, lastModifiedAt: Long) {
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
                123, $lastModifiedAt, $version, 0,
                1000, 500
            )
            """.trimIndent(),
            0,
        ).await()
    }

    private suspend fun SqlDriver.insertHistory(chapterId: Long, readAt: Long?, readDuration: Long) {
        execute(
            null,
            """
            INSERT INTO history(manga_id, chapter_id, read_at, read_duration)
            VALUES (1, $chapterId, ${readAt ?: "NULL"}, $readDuration)
            """.trimIndent(),
            0,
        ).await()
    }
}
// KUN <--
