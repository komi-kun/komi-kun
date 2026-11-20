package eu.kanade.tachiyomi.data.sync

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupDeletedItem
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.sync.SyncRepositoryImpl
import tachiyomi.domain.category.interactor.PruneCategoryReferences
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.service.HistoryPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.upcoming.service.UpcomingPreferences
import tachiyomi.domain.updates.service.UpdatesPreferences

/**
 * The explicit tombstone list a SyncYomi v2 delta carries in `Backup.deletions` (field `11000`).
 *
 * The deletions run against the real repositories over an in-memory database rather than mocks, so
 * the foreign-key cascades, the soft history reset and the category reference pruning are the ones
 * the app actually performs. Only the preference store is a stand-in ([InMemoryPreferenceStore], a
 * production class), which leaves `PruneCategoryReferences` real too.
 */
class SyncDeletionsTest {

    private fun item(kind: String, key: String, seq: Long = 1) = BackupDeletedItem(kind = kind, key = key, seq = seq)

    private fun ByteArray.containsSequence(sequence: ByteArray): Boolean =
        indices.any { start -> sequence.indices.all { offset -> getOrNull(start + offset) == sequence[offset] } }

    // region serialisation

    @Test
    fun `a deletion survives a protobuf round trip at tag 11000`() {
        val backup = Backup(
            backupManga = listOf(BackupManga(source = 1, url = "/m", title = "/m")),
            deletions = listOf(item("manga", "1|/m", seq = 42)),
        )

        val bytes = ProtoBuf.encodeToByteArray(Backup.serializer(), backup)

        // (11000 shl 3) or 2, the varint field header of `repeated BackupDeletedItem deletions = 11000`
        assertTrue(bytes.containsSequence(byteArrayOf(0xC2.toByte(), 0xAF.toByte(), 0x05)))
        val decoded = ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes)
        decoded.deletions shouldBe listOf(BackupDeletedItem(kind = "manga", key = "1|/m", seq = 42))
    }

    @Test
    fun `an old server without the list still feeds the snapshot fallback`(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.insertManga(id = 7L, sourceId = 1L, url = "/gone", favorite = true)
            val uploaded = listOf(
                BackupManga(source = 1, url = "/gone", title = "/gone", favorite = true),
                BackupManga(source = 1, url = "/kept", title = "/kept", favorite = true),
            )
            val remote = ProtoBuf.decodeFromByteArray(
                Backup.serializer(),
                ProtoBuf.encodeToByteArray(Backup.serializer(), Backup(backupManga = listOf(uploaded[1]))),
            )

            // a payload written before the field existed decodes with no tombstones at all
            remote.deletions.shouldBeEmpty()
            deletionPlan(remote.deletions).manga.shouldBeEmpty()
            // so the full-snapshot heuristic is the only thing left that can drop the removed manga
            mangaIdsMissingFromFullSnapshot(
                localManga = fixture.syncRepository.getAllManga(),
                remoteManga = remote.backupManga,
                uploadedManga = uploaded,
                fullSnapshot = true,
            ) shouldBe listOf(7L)
        } finally {
            fixture.close()
        }
    }

    // endregion

    // region parsing

    @Test
    fun `parses every kind this client applies`() {
        val plan = deletionPlan(
            listOf(
                item("manga", "1|/m"),
                // only the first separator splits: a url may hold one of its own
                item("manga", "3|/a|b"),
                item("chapter", "1|/m\u001f/m/ch1"),
                item("history", "2|/other\u001f/other/ch"),
                item("category", "uid:12"),
                item("category", "name:Reading"),
            ),
        )

        plan.manga shouldBe listOf(1L to "/m", 3L to "/a|b")
        plan.chapters shouldBe listOf(ChapterTombstone(sourceId = 1, mangaUrl = "/m", chapterUrl = "/m/ch1"))
        plan.history shouldBe listOf(ChapterTombstone(sourceId = 2, mangaUrl = "/other", chapterUrl = "/other/ch"))
        plan.categories shouldBe listOf(CategoryTombstone(uid = 12), CategoryTombstone(name = "Reading"))
        plan.skippedKinds shouldBe emptySet()
    }

    @Test
    fun `unreadable keys become no local deletions`(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.insertManga(id = 7L, sourceId = 1L, url = "/m")
            fixture.insertChapter(mangaId = 7L, chapterId = 107L, url = "/m/ch1")

            val plan = deletionPlan(
                listOf(
                    item("manga", "no-separator"),
                    item("manga", "1|"),
                    item("manga", "abc|/m"),
                    // a chapter key has to carry its chapter url too
                    item("chapter", "1|/m"),
                    item("history", "1|/m"),
                    item("category", "other:1"),
                    item("category", "uid:x"),
                    item("category", "name:"),
                ),
            )

            plan.manga.shouldBeEmpty()
            plan.chapters.shouldBeEmpty()
            plan.history.shouldBeEmpty()
            plan.categories.shouldBeEmpty()
            plan.skippedKinds shouldBe emptySet()

            fixture.apply(plan) shouldBe emptySet()

            // none of those keys named a row, so a matching local manga and chapter survive
            fixture.mangaIds() shouldBe listOf(7L)
            fixture.chapterIds() shouldBe listOf(107L)
        } finally {
            fixture.close()
        }
    }

    // endregion

    // region applying

    @Test
    fun `a manga tombstone deletes the row and its chapters and history with it`(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.insertManga(id = 7L, sourceId = 1L, url = "/m")
            fixture.insertChapter(mangaId = 7L, chapterId = 107L, url = "/m/ch1")
            fixture.insertHistory(mangaId = 7L, chapterId = 107L, historyId = 1007L)

            fixture.apply(deletionPlan(listOf(item("manga", "1|/m")))) shouldBe emptySet()

            fixture.mangaIds().shouldBeEmpty()
            // the foreign keys carry the children: no separate chapter or history call is needed
            fixture.chapterIds().shouldBeEmpty()
            fixture.historyIds().shouldBeEmpty()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a chapter tombstone deletes only that chapter`(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.insertManga(id = 7L, sourceId = 1L, url = "/m")
            fixture.insertChapter(mangaId = 7L, chapterId = 107L, url = "/m/ch1")
            fixture.insertChapter(mangaId = 7L, chapterId = 207L, url = "/m/ch2", index = 2)

            fixture.apply(deletionPlan(listOf(item("chapter", "1|/m\u001f/m/ch1")))) shouldBe emptySet()

            // the sibling chapter and the manga itself stay
            fixture.mangaIds() shouldBe listOf(7L)
            fixture.chapterIds() shouldBe listOf(207L)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a history tombstone clears only that history entry`(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.insertManga(id = 7L, sourceId = 1L, url = "/m")
            fixture.insertChapter(mangaId = 7L, chapterId = 107L, url = "/m/ch1")
            fixture.insertChapter(mangaId = 7L, chapterId = 207L, url = "/m/ch2", index = 2)
            fixture.insertHistory(mangaId = 7L, chapterId = 107L, historyId = 1007L)
            fixture.insertHistory(mangaId = 7L, chapterId = 207L, historyId = 2007L)

            fixture.apply(deletionPlan(listOf(item("history", "1|/m\u001f/m/ch1")))) shouldBe emptySet()

            // the tombstone names a chapter, so the history id has to come from that chapter's row:
            // clearing 2007 instead of 1007 is the mistake a chapter/history id mix-up makes
            fixture.readAtOf(historyId = 1007L) shouldBe null
            fixture.readAtOf(historyId = 2007L) shouldBe 3000L
            // a history tombstone removes no chapter and no manga
            fixture.chapterIds() shouldBe listOf(107L, 207L)
            fixture.mangaIds() shouldBe listOf(7L)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `category tombstones resolve by uid and by name and prune the stale filter ids`(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            // is_syncing = 1 keeps the schema's uid trigger from replacing the explicit uids
            fixture.insertCategory(id = 3L, name = "Reading", order = 0L, uid = 12L)
            fixture.insertCategory(id = 4L, name = "Old name", order = 1L, uid = 13L)
            fixture.insertCategory(id = 5L, name = "Untouched", order = 2L, uid = 14L)
            // the library and History filters store local category ids; a deleted id must not linger
            fixture.libraryPreferences.filterCategoriesInclude.set(setOf("3", "5"))

            fixture.apply(
                deletionPlan(
                    listOf(
                        item("category", "uid:12"),
                        item("category", "name:Old name"),
                        // the system category has uid 0 and is never a tombstone target
                        item("category", "uid:0"),
                        item("category", "name:"),
                    ),
                ),
            ) shouldBe emptySet()

            // id 0 is the seeded system category and 5 was never tombstoned
            fixture.categoryIds() shouldBe listOf(0L, 5L)
            fixture.libraryPreferences.filterCategoriesInclude.get() shouldBe setOf("5")
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a tombstone of a row this device never had is a no-op`(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.insertCategory(id = 3L, name = "Reading", order = 0L, uid = 12L)

            fixture.apply(
                deletionPlan(
                    listOf(
                        item("manga", "1|/unknown"),
                        item("chapter", "1|/unknown\u001f/unknown/ch"),
                        item("history", "1|/unknown\u001f/unknown/ch"),
                        item("category", "uid:404"),
                    ),
                ),
            ) shouldBe emptySet()

            fixture.mangaIds().shouldBeEmpty()
            fixture.categoryIds() shouldBe listOf(0L, 3L)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `section kinds have no local deletion and are reported as skipped`(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.insertManga(id = 7L, sourceId = 1L, url = "/m")

            val skipped = fixture.apply(deletionPlan(unappliedTombstoneKinds.map { item(it, "1|/m") }))

            skipped shouldBe unappliedTombstoneKinds
            // a settings or source tombstone must never reach a deletion path by accident, even when
            // its key happens to read as a manga key
            fixture.mangaIds() shouldBe listOf(7L)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `sections the user disabled are not deleted on the server's word`(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            // manga 7 carries the chapter tombstone, manga 8 the history one, so the chapter cascade
            // cannot hide whether the history tombstone was applied
            fixture.insertManga(id = 7L, sourceId = 1L, url = "/m")
            fixture.insertChapter(mangaId = 7L, chapterId = 107L, url = "/m/ch1")
            fixture.insertManga(id = 8L, sourceId = 1L, url = "/n")
            fixture.insertChapter(mangaId = 8L, chapterId = 108L, url = "/n/ch1")
            fixture.insertHistory(mangaId = 8L, chapterId = 108L, historyId = 1008L)

            val skipped = fixture.apply(
                deletionPlan(
                    listOf(
                        item("manga", "1|/m"),
                        item("history", "1|/n\u001f/n/ch1"),
                        item("chapter", "1|/m\u001f/m/ch1"),
                    ),
                ),
                librarySyncEnabled = false,
                historySyncEnabled = false,
            )

            skipped shouldBe setOf("manga", "history")
            fixture.mangaIds() shouldBe listOf(7L, 8L)
            fixture.readAtOf(historyId = 1008L) shouldBe 3000L
            // a chapter tombstone stands on its own, it does not need library sync to remove one stale row
            fixture.chapterIds() shouldBe listOf(108L)
        } finally {
            fixture.close()
        }
    }

    // endregion

    // region the early return

    @Test
    fun `a delta whose only change is a tombstone is not skipped`() {
        val deletion = item("manga", "1|/m")

        assertFalse(
            skipRemoteResponse(
                ownBackup = false,
                protocolV2 = true,
                changed = false,
                deletions = listOf(deletion),
            ),
        )
        // nothing changed and nothing tombstoned: the restore can be skipped
        assertTrue(
            skipRemoteResponse(ownBackup = false, protocolV2 = true, changed = false, deletions = emptyList()),
        )
    }

    @Test
    fun `our own backup is skipped even when it carries a deletion`() {
        assertTrue(
            skipRemoteResponse(
                ownBackup = true,
                protocolV2 = true,
                changed = false,
                deletions = listOf(item("manga", "1|/m")),
            ),
        )
    }

    // endregion

    /** Real repositories over an in-memory database, plus the real report-path preference store. */
    private class Fixture {
        private val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        private val database = DatabaseBindings.providesDatabase(driver)
        private val preferenceStore = InMemoryPreferenceStore()

        val syncRepository = SyncRepositoryImpl(database)
        val libraryPreferences = LibraryPreferences(preferenceStore)

        private val chapterRepository = ChapterRepositoryImpl(database)
        private val historyRepository = HistoryRepositoryImpl(database)
        private val categoryRepository = CategoryRepositoryImpl(database)
        private val pruneCategoryReferences = PruneCategoryReferences(
            libraryPreferences = libraryPreferences,
            downloadPreferences = DownloadPreferences(preferenceStore),
            historyPreferences = HistoryPreferences(preferenceStore),
            updatesPreferences = UpdatesPreferences(preferenceStore),
            upcomingPreferences = UpcomingPreferences(preferenceStore),
        )

        init {
            runBlocking {
                // The app's driver sets `isForeignKeyConstraintsEnabled = true` (AppBindings), and the
                // deletions rely on the cascades it enables; JdbcSqliteDriver leaves them off.
                driver.execute(null, "PRAGMA foreign_keys = ON", 0).await()
                Database.Schema.create(driver).await()
            }
        }

        fun close() = driver.close()

        suspend fun apply(
            plan: DeletionPlan,
            librarySyncEnabled: Boolean = true,
            historySyncEnabled: Boolean = true,
        ): Set<String> = applyDeletions(
            plan = plan,
            syncRepository = syncRepository,
            chapterRepository = chapterRepository,
            historyRepository = historyRepository,
            categoryRepository = categoryRepository,
            pruneCategoryReferences = pruneCategoryReferences,
            librarySyncEnabled = librarySyncEnabled,
            historySyncEnabled = historySyncEnabled,
        )

        fun mangaIds(): List<Long> = longs("SELECT id FROM manga ORDER BY id")

        fun chapterIds(): List<Long> = longs("SELECT id FROM chapter ORDER BY id")

        fun historyIds(): List<Long> = longs("SELECT id FROM history ORDER BY id")

        fun categoryIds(): List<Long> = longs("SELECT id FROM category ORDER BY id")

        fun readAtOf(historyId: Long): Long? =
            nullableLongs("SELECT read_at FROM history WHERE id = $historyId").firstOrNull()

        private fun longs(sql: String): List<Long> = nullableLongs(sql).filterNotNull()

        private fun nullableLongs(sql: String): List<Long?> = driver.executeQuery(
            null,
            sql,
            { cursor ->
                val values = mutableListOf<Long?>()
                while (cursor.next().value) values += cursor.getLong(0)
                QueryResult.Value<List<Long?>>(values.toList())
            },
            0,
        ).value

        suspend fun insertManga(id: Long, sourceId: Long, url: String, favorite: Boolean = false) {
            driver.execute(
                null,
                """
                INSERT INTO manga(
                    id, source_id, remote_url, remote_title, remote_status, remote_update_strategy,
                    remote_memo, user_notes, user_reader_flags, user_chapter_flags,
                    state_chapter_fetch_interval, state_initialized, remote_cover,
                    state_cover_last_modified, last_modified_at, version, is_syncing,
                    user_favorite_at, favorite_modified_at
                ) VALUES (
                    $id, $sourceId, '$url', 'Title', 0, 0,
                    '{}', '', 0, 0,
                    0, 1, NULL,
                    123, 10, 1, 0,
                    ${if (favorite) 1000 else "NULL"}, ${if (favorite) 500 else "NULL"}
                )
                """.trimIndent(),
                0,
            ).await()
        }

        suspend fun insertChapter(mangaId: Long, chapterId: Long, url: String, index: Int = 1) {
            driver.execute(
                null,
                """
                INSERT INTO chapter(
                    id, manga_id, remote_url, remote_name, remote_chapter_number,
                    remote_order, remote_memo, user_read, user_bookmark,
                    user_last_page_read, state_date_fetch,
                    remote_date_upload, last_modified_at, version, is_syncing
                ) VALUES (
                    $chapterId, $mangaId, '$url', 'Chapter $index', 1.0,
                    1, '{}', 0, 0,
                    0, 2000,
                    2000, 10, 1, 0
                )
                """.trimIndent(),
                0,
            ).await()
        }

        suspend fun insertHistory(mangaId: Long, chapterId: Long, historyId: Long, readAt: Long = 3000L) {
            driver.execute(
                null,
                "INSERT INTO history(id, chapter_id, manga_id, read_at, read_duration) " +
                    "VALUES ($historyId, $chapterId, $mangaId, $readAt, 60)",
                0,
            ).await()
        }

        suspend fun insertCategory(id: Long, name: String, order: Long, uid: Long) {
            driver.execute(
                null,
                "INSERT INTO category(id, name, `order`, flags, hidden, version, uid, last_modified_at, is_syncing) " +
                    "VALUES ($id, '$name', $order, 0, 0, 0, $uid, 0, 1)",
                0,
            ).await()
        }
    }
}
