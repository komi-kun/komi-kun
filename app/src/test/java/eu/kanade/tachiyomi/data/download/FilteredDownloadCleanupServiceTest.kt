// KUN -->
package eu.kanade.tachiyomi.data.download

import eu.kanade.tachiyomi.source.Source
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.model.PendingDownloadDeletion
import tachiyomi.domain.download.repository.PendingDownloadDeletionRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Guards the two passes of [FilteredDownloadCleanupService]: what it queues while a filter hides a
 * downloaded chapter, what it refuses to queue or delete, and what it does once a queued chapter has
 * stayed hidden for the grace period.
 *
 * The pure decision itself is [eu.kanade.domain.download.FilteredDownloadPolicy]'s test; this one pins
 * what the engine does with its answer — the cheap exit, the queue bookkeeping, the re-verification
 * before a file is touched, and the read-state bypass of the deletion call.
 *
 * The stubs encode the contracts of the collaborators rather than fixed values: the bulk queries
 * return the rows of the ids they were asked for, and `getDownloadedChapterIds` returns the chapters
 * of the manga it was asked about (all of them unless a test says otherwise), so a test that forgets
 * to make a chapter downloaded really exercises the "no longer downloaded" path.
 *
 * `DownloadManager`, `DownloadCache`, `SourceManager` and the two repositories cannot run on a host
 * JVM: they need an Android `Context`, a database, or both, so the files are never really deleted
 * here. What is asserted is the call the manager receives, including the `ignoreCategoryExclusion`
 * argument; the manager's own behaviour behind it is covered by `DownloadManager`'s callers and is
 * not re-tested through a mock.
 */
class FilteredDownloadCleanupServiceTest {

    private val downloadPreferences = DownloadPreferences(InMemoryPreferenceStore())
    private val downloadManager = mockk<DownloadManager>(relaxed = true)
    private val downloadCache = mockk<DownloadCache>(relaxed = true)
    private val mangaRepository = mockk<MangaRepository>(relaxed = true)
    private val chapterRepository = mockk<ChapterRepository>(relaxed = true)
    private val sourceManager = mockk<SourceManager>(relaxed = true)

    private val pendingRepository = FakePendingDownloadDeletionRepository()

    private var favourites: List<Manga> = emptyList()
    private var mangasInDb: List<Manga> = emptyList()
    private var chaptersByManga: Map<Long, List<Chapter>> = emptyMap()
    private var excludedScanlators: Map<Long, List<String>> = emptyMap()
    private var mangaWithDownloadsIds: Set<Long> = setOf(MANGA_ID)
    private var source: Source? = mockk(relaxed = true)

    /** Per-manga override of the download index; the default is "every chapter of the manga". */
    private val downloadedChapterIds = mutableMapOf<Long, Set<Long>>()

    /**
     * The sweep's notion of "now". Advanced by the tests that need a second pass to happen later, so
     * "past the grace period" is deterministic instead of a real hour of waiting.
     */
    private var currentTime: Instant = Clock.System.now()

    /** Only [FilteredDownloadCleanupService.clearPendingAsync] uses it, and no test takes that path. */
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    init {
        coEvery { mangaRepository.getFavorites() } answers { favourites }
        coEvery { mangaRepository.getMangaByIds(any()) } answers {
            val ids = firstArg<List<Long>>()
            mangasInDb.filter { it.id in ids }
        }
        coEvery { mangaRepository.getExcludedScanlatorsByMangaIds(any()) } answers {
            val ids = firstArg<List<Long>>()
            excludedScanlators.filterKeys { it in ids }
        }
        coEvery { chapterRepository.getChaptersByMangaIds(any()) } answers {
            val ids = firstArg<List<Long>>()
            ids.flatMap { chaptersByManga[it].orEmpty() }
        }
        every { downloadManager.getDownloadedChapterIds(any(), any()) } answers {
            downloadedChapterIds[secondArg<Manga>().id]
                ?: firstArg<List<Chapter>>().mapTo(mutableSetOf()) { it.id }
        }
        every { downloadCache.getMangaWithDownloads(any()) } answers {
            val mangas = firstArg<List<Manga>>()
            mangas.filter { it.id in mangaWithDownloadsIds }
        }
        coEvery { sourceManager.get(any()) } answers { source }
    }

    private fun service() = FilteredDownloadCleanupService(
        downloadPreferences = downloadPreferences,
        downloadManager = downloadManager,
        downloadCache = downloadCache,
        pendingDeletionRepository = pendingRepository,
        mangaRepository = mangaRepository,
        chapterRepository = chapterRepository,
        sourceManager = sourceManager,
        scope = testScope,
        now = { currentTime },
    )

    private fun sweep() = runBlocking { service().run() }

    private fun discoverOnlySweep() = runBlocking { service().run(discoverOnly = true) }

    private fun manga(
        id: Long = MANGA_ID,
        favorite: Boolean = true,
        chapterFlags: Long = Manga.SHOW_ALL,
    ) = Manga.create().copy(
        id = id,
        source = SOURCE_ID,
        favorite = favorite,
        chapterFlags = chapterFlags,
    )

    private fun chapter(
        id: Long = CHAPTER_ID,
        mangaId: Long = MANGA_ID,
        read: Boolean = false,
        bookmark: Boolean = false,
        scanlator: String? = null,
    ) = Chapter.create().copy(
        id = id,
        mangaId = mangaId,
        read = read,
        bookmark = bookmark,
        scanlator = scanlator,
    )

    private fun given(
        manga: Manga = manga(),
        chapters: List<Chapter> = listOf(chapter(scanlator = EXCLUDED_SCANLATOR)),
        excludedScanlators: List<String> = listOf(EXCLUDED_SCANLATOR),
    ) {
        favourites = listOf(manga)
        mangasInDb = listOf(manga)
        chaptersByManga = chapters.groupBy { it.mangaId }
        this.excludedScanlators = mapOf(manga.id to excludedScanlators)
    }

    private fun queued(chapterId: Long = CHAPTER_ID, mangaId: Long = MANGA_ID, age: Long = 2L) =
        PendingDownloadDeletion(chapterId, mangaId, currentTime - age.hours)

    @Test
    fun `a disabled setting exits before touching any dependency`() {
        downloadPreferences.removeFilteredDownloads.set(false)
        given()
        pendingRepository.rows = listOf(queued())

        sweep()

        // The battery guard: nothing may be read, queued or deleted while the feature is off.
        coVerify(exactly = 0) {
            mangaRepository.getFavorites()
            mangaRepository.getMangaByIds(any())
            mangaRepository.getExcludedScanlatorsByMangaIds(any())
            chapterRepository.getChaptersByMangaIds(any())
            sourceManager.get(any())
            downloadManager.deleteChapters(any(), any(), any(), any())
        }
        verify(exactly = 0) {
            downloadManager.getDownloadedChapterIds(any(), any())
            downloadCache.getMangaWithDownloads(any())
        }
        pendingRepository.calls shouldBe emptyList()
    }

    @Test
    fun `a hidden downloaded chapter is queued with the moment it was first seen`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        given()

        sweep()

        val inserted = pendingRepository.inserted.single()
        inserted.chapterId shouldBe CHAPTER_ID
        inserted.mangaId shouldBe MANGA_ID
        // The grace period starts now, not when the chapter was downloaded.
        inserted.firstHiddenAt shouldBe currentTime
        // Nothing was deleted and nothing expired: the grace period has only just started.
        pendingRepository.deletedChapterIds shouldBe emptyList()
        coVerify(exactly = 0) { downloadManager.deleteChapters(any(), any(), any(), any()) }
    }

    @Test
    fun `a chapter that is still hidden is queued again so the stored first time survives`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        given()
        val original = queued(age = 0L)
        pendingRepository.rows = listOf(original)

        sweep()

        // The engine re-sends the chapter with the new time on purpose: the repository's INSERT OR
        // IGNORE keeps the row's original `firstHiddenAt`, so the grace period runs from the first
        // time the chapter was seen hidden. What the engine must not do is drop that row, which is
        // the only way the stored time could be lost.
        pendingRepository.inserted.map { it.chapterId } shouldBe listOf(CHAPTER_ID)
        pendingRepository.deletedChapterIds shouldBe emptyList()
        pendingRepository.rows shouldBe listOf(original)
        coVerify(exactly = 0) { downloadManager.deleteChapters(any(), any(), any(), any()) }
    }

    @Test
    fun `a chapter that is visible again drops its row and keeps its file`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        given(chapters = listOf(chapter()), excludedScanlators = emptyList())
        pendingRepository.rows = listOf(queued())

        sweep()

        pendingRepository.deletedChapterIds shouldBe listOf(CHAPTER_ID)
        pendingRepository.inserted.shouldBeEmpty()
        coVerify(exactly = 0) { downloadManager.deleteChapters(any(), any(), any(), any()) }
    }

    @Test
    fun `an expired hidden chapter is deleted and its queue row removed`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        given()
        pendingRepository.rows = listOf(queued())

        sweep()

        coVerify(exactly = 1) {
            downloadManager.deleteChapters(
                match { it.map { chapter -> chapter.id } == listOf(CHAPTER_ID) },
                any(),
                any(),
                ignoreCategoryExclusion = true,
            )
        }
        pendingRepository.deletedChapterIds shouldBe listOf(CHAPTER_ID)
        pendingRepository.rows shouldBe emptyList()
    }

    @Test
    fun `an expired chapter that is visible again is not deleted but its row is`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        given(chapters = listOf(chapter()), excludedScanlators = emptyList())
        pendingRepository.rows = listOf(queued())

        sweep()

        coVerify(exactly = 0) { downloadManager.deleteChapters(any(), any(), any(), any()) }
        pendingRepository.deletedChapterIds shouldBe listOf(CHAPTER_ID)
        pendingRepository.rows shouldBe emptyList()
    }

    @Test
    fun `an expired chapter that is no longer downloaded is not deleted but its row is`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        given()
        downloadedChapterIds[MANGA_ID] = emptySet()
        pendingRepository.rows = listOf(queued())

        sweep()

        coVerify(exactly = 0) { downloadManager.deleteChapters(any(), any(), any(), any()) }
        pendingRepository.deletedChapterIds shouldBe listOf(CHAPTER_ID)
        pendingRepository.rows shouldBe emptyList()
    }

    @Test
    fun `an expired chapter of a manga that left the library is not deleted`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        // The manga was a favourite when the pass started — discovery therefore leaves its rows
        // alone — and is not one any more when the due rows are verified. The expiry guard has to
        // refuse on its own.
        given()
        mangasInDb = listOf(manga(favorite = false))
        pendingRepository.rows = listOf(queued())

        sweep()

        coVerify(exactly = 0) { downloadManager.deleteChapters(any(), any(), any(), any()) }
        pendingRepository.deletedMangaIds shouldBe listOf(MANGA_ID)
        pendingRepository.rows shouldBe emptyList()
    }

    @Test
    fun `a hidden unread chapter is deleted even though read state would keep it`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        // The manga sits in a category the user excluded from read-driven deletion, and the chapter
        // was never read. `getChaptersToDelete` would keep it for both reasons; the sweep's reason is
        // the filter, so it must ask for the read-state carve-out to be skipped.
        downloadPreferences.removeExcludeCategories.set(setOf(EXCLUDED_CATEGORY_ID))
        given(chapters = listOf(chapter(read = false, scanlator = EXCLUDED_SCANLATOR)))
        pendingRepository.rows = listOf(queued())

        sweep()

        coVerify(exactly = 1) {
            downloadManager.deleteChapters(
                match { chapters -> chapters.single().read.not() },
                any(),
                any(),
                ignoreCategoryExclusion = true,
            )
        }
        pendingRepository.deletedChapterIds shouldBe listOf(CHAPTER_ID)
    }

    @Test
    fun `a bookmarked chapter is never queued or deleted while bookmark retention is on`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        downloadPreferences.removeBookmarkedChapters.set(false)
        given(chapters = listOf(chapter(bookmark = true, scanlator = EXCLUDED_SCANLATOR)))
        pendingRepository.rows = listOf(queued())

        sweep()

        pendingRepository.inserted.shouldBeEmpty()
        coVerify(exactly = 0) { downloadManager.deleteChapters(any(), any(), any(), any()) }
        // Its row is stale now: the chapter is no longer a candidate, so it is dropped un-deleted.
        pendingRepository.deletedChapterIds shouldBe listOf(CHAPTER_ID)
    }

    @Test
    fun `a bookmarked chapter is queued and deleted once bookmark retention is off`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        downloadPreferences.removeBookmarkedChapters.set(true)
        given(chapters = listOf(chapter(bookmark = true, scanlator = EXCLUDED_SCANLATOR)))
        pendingRepository.rows = listOf(queued())

        sweep()

        pendingRepository.inserted.map { it.chapterId } shouldBe listOf(CHAPTER_ID)
        coVerify(exactly = 1) {
            downloadManager.deleteChapters(
                match { it.map { chapter -> chapter.id } == listOf(CHAPTER_ID) },
                any(),
                any(),
                ignoreCategoryExclusion = true,
            )
        }
        pendingRepository.deletedChapterIds shouldBe listOf(CHAPTER_ID)
    }

    @Test
    fun `the downloaded filter never makes a downloaded chapter a candidate`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        // "Show only not downloaded" hides every download in the chapter list. It is a view switch,
        // not a statement that the download is unwanted, so it must not queue or delete anything.
        given(manga = manga(chapterFlags = Manga.CHAPTER_SHOW_NOT_DOWNLOADED), excludedScanlators = emptyList())
        pendingRepository.rows = listOf(queued())

        sweep()

        pendingRepository.inserted.shouldBeEmpty()
        coVerify(exactly = 0) { downloadManager.deleteChapters(any(), any(), any(), any()) }
        pendingRepository.deletedChapterIds shouldBe listOf(CHAPTER_ID)
    }

    @Test
    fun `the read filter never deletes a chapter it hides`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        // "Show only read" hides this unread chapter, which is a consequence of not having read it
        // yet, not a reason to delete the file.
        given(manga = manga(chapterFlags = Manga.CHAPTER_SHOW_READ), excludedScanlators = emptyList())
        pendingRepository.rows = listOf(queued())

        sweep()

        pendingRepository.inserted.shouldBeEmpty()
        coVerify(exactly = 0) { downloadManager.deleteChapters(any(), any(), any(), any()) }
        pendingRepository.deletedChapterIds shouldBe listOf(CHAPTER_ID)
        pendingRepository.rows shouldBe emptyList()
    }

    @Test
    fun `an unavailable source keeps the row instead of deleting blind`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        given()
        source = null
        pendingRepository.rows = listOf(queued())

        sweep()

        coVerify(exactly = 0) { downloadManager.deleteChapters(any(), any(), any(), any()) }
        // The chapter is still a candidate, so the row stays queued for the next pass.
        pendingRepository.deletedChapterIds shouldBe emptyList()
        pendingRepository.rows.map { it.chapterId } shouldBe listOf(CHAPTER_ID)
    }

    @Test
    fun `a pass examines only the discovery budget of mangas`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        val mangas = (1L..(FilteredDownloadCleanupService.DISCOVERY_BATCH + 5L)).map { id -> manga(id = id) }
        favourites = mangas
        mangasInDb = mangas
        chaptersByManga = mangas.associate { it.id to listOf(chapter(id = it.id * 10, mangaId = it.id, scanlator = EXCLUDED_SCANLATOR)) }
        excludedScanlators = mangas.associate { it.id to listOf(EXCLUDED_SCANLATOR) }
        mangaWithDownloadsIds = mangas.mapTo(mutableSetOf()) { it.id }

        sweep()

        // The remainder is left for the next pass rather than read now; a larger batch would slip
        // through only if the bound were removed.
        pendingRepository.inserted.map { it.mangaId }.distinct().size shouldBe FilteredDownloadCleanupService.DISCOVERY_BATCH
    }

    @Test
    fun `a backlog hidden before the feature existed is stamped now and only deleted a grace period later`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        // An upgrade: the manga is hidden and downloaded, and the queue table is empty because no pass
        // has ever run. Nothing was ever stamped, so this chapter has no history to backdate from.
        given()
        pendingRepository.rows shouldBe emptyList()

        discoverOnlySweep()

        // The retrofit pass stamps it at the moment it first sees it — months of being hidden count for
        // nothing — and deletes nothing.
        val stamped = pendingRepository.inserted.single()
        stamped.chapterId shouldBe CHAPTER_ID
        stamped.firstHiddenAt shouldBe currentTime
        coVerify(exactly = 0) { downloadManager.deleteChapters(any(), any(), any(), any()) }
        // No expiry read at all: the pass stopped after discovery.
        pendingRepository.lastCutoff shouldBe null
        pendingRepository.deletedChapterIds shouldBe emptyList()

        // A later periodic pass, once the grace period has passed, is the one that deletes.
        currentTime += 2.hours
        sweep()

        coVerify(exactly = 1) {
            downloadManager.deleteChapters(
                match { it.map { chapter -> chapter.id } == listOf(CHAPTER_ID) },
                any(),
                any(),
                ignoreCategoryExclusion = true,
            )
        }
        pendingRepository.rows shouldBe emptyList()
    }

    @Test
    fun `discovery is idempotent and never moves a stored first time`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        given()

        sweep()
        val firstStamped = pendingRepository.rows.single().firstHiddenAt

        currentTime += 10.minutes
        sweep()

        // The engine re-sends the chapter on every pass; the repository's INSERT OR IGNORE keeps the
        // first row, so the stored time does not move even though the second insert carried a later one.
        pendingRepository.inserted.map { it.chapterId } shouldBe listOf(CHAPTER_ID, CHAPTER_ID)
        pendingRepository.inserted.last().firstHiddenAt shouldBe currentTime
        pendingRepository.rows.single().firstHiddenAt shouldBe firstStamped
        coVerify(exactly = 0) { downloadManager.deleteChapters(any(), any(), any(), any()) }
    }

    @Test
    fun `becoming visible drops the row and hiding again restarts the clock`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        given()

        sweep()
        val firstStamped = pendingRepository.rows.single().firstHiddenAt

        // Visible again: the row is dropped without touching the file.
        currentTime += 20.minutes
        chaptersByManga = mapOf(MANGA_ID to listOf(chapter()))
        sweep()

        pendingRepository.rows shouldBe emptyList()
        coVerify(exactly = 0) { downloadManager.deleteChapters(any(), any(), any(), any()) }

        // Hidden again later: a fresh row, from this moment — not the old timestamp resurrected.
        currentTime += 20.minutes
        chaptersByManga = mapOf(MANGA_ID to listOf(chapter(scanlator = EXCLUDED_SCANLATOR)))
        sweep()

        val restamped = pendingRepository.rows.single()
        restamped.firstHiddenAt shouldBe currentTime
        (restamped.firstHiddenAt > firstStamped) shouldBe true
        coVerify(exactly = 0) { downloadManager.deleteChapters(any(), any(), any(), any()) }
    }

    @Test
    fun `a discover-only pass never deletes, even with an expired row waiting`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        given()
        pendingRepository.rows = listOf(queued(age = 3L))

        discoverOnlySweep()

        coVerify(exactly = 0) { downloadManager.deleteChapters(any(), any(), any(), any()) }
        // No expiry phase ran, so the due row was neither read nor removed.
        pendingRepository.lastCutoff shouldBe null
        pendingRepository.deletedChapterIds shouldBe emptyList()
        pendingRepository.rows.map { it.chapterId } shouldBe listOf(CHAPTER_ID)
    }

    @Test
    fun `clearing the queue empties the table`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        given()
        pendingRepository.rows = listOf(queued(age = 1L), queued(chapterId = 11L, age = 0L))

        runBlocking { service().clearPending() }

        pendingRepository.rows shouldBe emptyList()
        pendingRepository.calls shouldBe listOf("deleteAll")
    }

    private companion object {
        const val MANGA_ID = 1L
        const val SOURCE_ID = 2L
        const val CHAPTER_ID = 10L
        const val EXCLUDED_SCANLATOR = "Excluded"
        const val EXCLUDED_CATEGORY_ID = "3"
    }
}

/** Records what the engine asked for, and applies the deletes so a pass sees the effect of its own writes. */
private class FakePendingDownloadDeletionRepository : PendingDownloadDeletionRepository {

    var rows: List<PendingDownloadDeletion> = emptyList()

    val calls = mutableListOf<String>()
    val inserted = mutableListOf<PendingDownloadDeletion>()
    val deletedChapterIds = mutableListOf<Long>()
    val deletedMangaIds = mutableListOf<Long>()
    var lastCutoff: Instant? = null

    override suspend fun insertAll(pendingDeletions: List<PendingDownloadDeletion>) {
        calls += "insertAll"
        inserted += pendingDeletions
        // INSERT OR IGNORE: a chapter that is already queued keeps the row it had, so a second pass
        // cannot move its `firstHiddenAt`.
        val queuedIds = rows.mapTo(mutableSetOf()) { it.chapterId }
        rows = rows + pendingDeletions.filterNot { it.chapterId in queuedIds }
    }

    override suspend fun deleteByChapterIds(chapterIds: List<Long>) {
        calls += "deleteByChapterIds"
        deletedChapterIds += chapterIds
        rows = rows.filterNot { it.chapterId in chapterIds }
    }

    override suspend fun deleteByMangaIds(mangaIds: List<Long>) {
        calls += "deleteByMangaIds"
        deletedMangaIds += mangaIds
        rows = rows.filterNot { it.mangaId in mangaIds }
    }

    override suspend fun deleteAll() {
        calls += "deleteAll"
        rows = emptyList()
    }

    override suspend fun selectExpired(cutoff: Instant): List<PendingDownloadDeletion> {
        calls += "selectExpired"
        lastCutoff = cutoff
        return rows.filter { it.firstHiddenAt <= cutoff }
    }

    override suspend fun selectAll(): List<PendingDownloadDeletion> {
        calls += "selectAll"
        return rows
    }
}
// KUN <--
