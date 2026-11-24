// KUN -->
package eu.kanade.tachiyomi.data.download

import android.util.Log
import eu.kanade.tachiyomi.source.Source
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.model.PendingDownloadDeletion
import tachiyomi.domain.download.repository.PendingDownloadDeletionRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MergedEntry
import tachiyomi.domain.manga.repository.MangaMergeRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import kotlin.time.Clock
import kotlin.time.Duration
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
 * return the rows of the ids they were asked for, and `getDownloadedChapterIdsNoRenew` returns the
 * chapters of the manga it was asked about (all of them unless a test says otherwise), so a test that
 * forgets to make a chapter downloaded really exercises the "no longer downloaded" path.
 *
 * `DownloadManager`, `DownloadCache`, `SourceManager`, `DownloadProvider` and the repositories cannot
 * run on a host JVM: they need an Android `Context`, a database, or both, so the files are never really
 * deleted here. What is asserted is the call the manager receives, including the
 * `ignoreCategoryExclusion` argument; the manager's own behaviour behind it is covered by
 * `DownloadManager`'s callers and is not re-tested through a mock.
 */
class FilteredDownloadCleanupServiceTest {

    private val downloadPreferences = DownloadPreferences(InMemoryPreferenceStore())
    private val downloadManager = mockk<DownloadManager>(relaxed = true)
    private val downloadCache = mockk<DownloadCache>(relaxed = true)
    private val downloadProvider = mockk<DownloadProvider>(relaxed = true)
    private val mangaRepository = mockk<MangaRepository>(relaxed = true)
    private val chapterRepository = mockk<ChapterRepository>(relaxed = true)
    private val mangaMergeRepository = mockk<MangaMergeRepository>(relaxed = true)
    private val sourceManager = mockk<SourceManager>(relaxed = true)

    private val pendingRepository = FakePendingDownloadDeletionRepository()

    private var favourites: List<Manga> = emptyList()
    private var mangasInDb: List<Manga> = emptyList()
    private var chaptersByManga: Map<Long, List<Chapter>> = emptyMap()
    private var excludedScanlators: Map<Long, List<String>> = emptyMap()
    private var mangaWithDownloadsIds: Set<Long> = setOf(MANGA_ID)
    private var source: Source? = mockk(relaxed = true)

    /** Per-manga override of the download index; the default is "every chapter of the manga". */
    private val downloadedChapterIds = mutableMapOf<Long, List<Long>>()

    /**
     * Whether the download index counts as built. True by default so an ordinary test exercises a real
     * sweep; a test that sets it to false models a process whose cache has not been populated yet, where
     * `getMangaWithDownloads` and `getDownloadedChapterIdsNoRenew` answer as if nothing were downloaded.
     */
    private var indexBuilt = true

    /**
     * What `merged_entry` holds when it is read. A queue rather than a value, because each phase reads
     * the table for itself: it is how a test models a merge created between the two phases of one
     * sweep, which is the only way a due row of a merged manga can reach the expiry phase.
     */
    private val mergedEntryReads = ArrayDeque<List<MergedEntry>>()

    /** Chapters the manager really removed; the fake index stops reporting them, like the real one. */
    private val removedChapterIds = mutableSetOf<Long>()

    /**
     * Chapters the manager was handed but did *not* delete — its own bookmark filter refuses a single
     * chapter (`getChaptersToDelete`), so a call is not proof that the file went.
     */
    private val refusedChapterIds = mutableSetOf<Long>()

    /** One-shot seam: run once, inside the next source lookup, before it answers. */
    private var onNextSourceLookup: (() -> Unit)? = null

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
        every { downloadCache.getDownloadedChapterIdsNoRenew(any(), any()) } answers {
            // An index nothing has built yet answers every lookup as if nothing were downloaded: that
            // is the answer the sweep must refuse to sweep on, so the fake has to be just as empty.
            if (!indexBuilt) return@answers emptyList()
            val chapters = firstArg<List<Chapter>>()
            val indexed = downloadedChapterIds[secondArg<Manga>().id] ?: chapters.map { it.id }
            // A chapter the manager really deleted is no longer in the index: the re-read after a
            // delete is the only witness of what the call actually did, so the fake has to be wrong
            // in the same direction the real index would be.
            indexed.filterNot { it in removedChapterIds }
        }
        every { downloadCache.getMangaWithDownloads(any()) } answers {
            if (!indexBuilt) return@answers emptyList()
            val mangas = firstArg<List<Manga>>()
            mangas.filter { it.id in mangaWithDownloadsIds }
        }
        every { downloadCache.isIndexBuilt() } answers { indexBuilt }
        // Stands in for `DownloadProvider.getValidChapterDirNames`: one candidate name per chapter, so
        // two chapters share a directory exactly when they share a name (and a scanlator, which the
        // fixture encodes in the name when it matters).
        every { downloadProvider.getValidChapterDirNames(any(), any(), any()) } answers {
            listOf(firstArg<String>())
        }
        // The deletion itself: a refusal leaves the chapter in the index, a deletion takes it out.
        coEvery { downloadManager.deleteChapters(any(), any(), any(), any()) } answers {
            firstArg<List<Chapter>>().forEach { chapter ->
                if (chapter.id !in refusedChapterIds) removedChapterIds += chapter.id
            }
        }
        coEvery { mangaMergeRepository.getAllEntries() } answers {
            mergedEntryReads.removeFirstOrNull().orEmpty()
        }
        coEvery { sourceManager.get(any()) } answers {
            val answer = source
            val sideEffect = onNextSourceLookup
            onNextSourceLookup = null
            sideEffect?.invoke()
            answer
        }
    }

    /**
     * The sweep reports through `xLogE`/`xLogD`, which fall back to `android.util.Log` — a stub on the
     * JVM that throws instead of writing. Stubbed here so a failure path (which logs) can run at all;
     * nothing asserts on the output, only on what the pass did around it.
     */
    @BeforeEach
    fun stubAndroidLog() {
        mockkStatic(Log::class)
        every { Log.println(any(), any(), any()) } returns 0
        every { Log.getStackTraceString(any()) } returns ""
    }

    @AfterEach
    fun unstubAndroidLog() {
        unmockkStatic(Log::class)
    }

    private fun service() = FilteredDownloadCleanupService(
        downloadPreferences = downloadPreferences,
        downloadManager = downloadManager,
        downloadCache = downloadCache,
        downloadProvider = downloadProvider,
        pendingDeletionRepository = pendingRepository,
        mangaRepository = mangaRepository,
        chapterRepository = chapterRepository,
        mangaMergeRepository = mangaMergeRepository,
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
        name: String = "Chapter $id",
        url: String = "https://example.org/$id",
    ) = Chapter.create().copy(
        id = id,
        mangaId = mangaId,
        read = read,
        bookmark = bookmark,
        scanlator = scanlator,
        name = name,
        url = url,
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

    private fun queued(
        chapterId: Long = CHAPTER_ID,
        mangaId: Long = MANGA_ID,
        age: Duration = 2.hours,
        // The url is the row's identity token; by default it is the one `chapter()` gives the same id,
        // so a row only mismatches when a test says the id now names another chapter.
        chapterUrl: String = "https://example.org/$chapterId",
    ) = PendingDownloadDeletion(chapterId, mangaId, currentTime - age, chapterUrl)

    private fun mergedEntry(mangaId: Long = MANGA_ID, mergeId: Long = PARENT_ID) = MergedEntry(
        id = 1L,
        mergeId = mergeId,
        mangaId = mangaId,
        fetchChapterUpdates = true,
        downloadChapters = true,
        chapterPriority = 0,
    )

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
            mangaMergeRepository.getAllEntries()
            sourceManager.get(any())
            downloadManager.deleteChapters(any(), any(), any(), any())
        }
        verify(exactly = 0) {
            downloadCache.getDownloadedChapterIdsNoRenew(any(), any())
            downloadCache.getMangaWithDownloads(any())
            downloadProvider.getValidChapterDirNames(any(), any(), any())
        }
        pendingRepository.calls shouldBe emptyList()
    }

    @Test
    fun `an unbuilt index asks for one renewal and touches nothing else`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        given()
        // A process whose cache has not been populated: both non-renewing lookups answer "nothing is
        // downloaded", which is exactly what expiry reads as "this due row is stale".
        indexBuilt = false
        pendingRepository.rows = listOf(queued())

        val sweepService = service()
        runBlocking {
            sweepService.run()
            // A second pass can run before the asynchronous renewal lands, and must not ask again: the
            // service is app-scoped, so one request per instance is one request per process.
            sweepService.run()
        }

        verify(exactly = 1) { downloadCache.invalidateCache() }
        // The guard sits before every read and write of the queue path, so nothing of it ran.
        pendingRepository.calls shouldBe emptyList()
        coVerify(exactly = 0) {
            mangaRepository.getFavorites()
            chapterRepository.getChaptersByMangaIds(any())
            mangaMergeRepository.getAllEntries()
            downloadManager.deleteChapters(any(), any(), any(), any())
        }
        verify(exactly = 0) { downloadCache.getMangaWithDownloads(any()) }
        // The point of the guard: the due row survives a pass that cannot tell an unbuilt index from an
        // empty downloads folder. Without it, expiry drops this row as "no longer downloaded".
        pendingRepository.rows.map { it.chapterId } shouldBe listOf(CHAPTER_ID)
    }

    @Test
    fun `a built but empty index still runs a normal pass`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        given()
        // Built, and genuinely nothing downloaded. "Built" is not "non-empty", so the guard must not
        // fire: a user who deleted every download keeps the sweep.
        mangaWithDownloadsIds = emptySet()
        downloadedChapterIds[MANGA_ID] = emptyList()
        pendingRepository.rows = listOf(queued())

        sweep()

        verify(exactly = 0) { downloadCache.invalidateCache() }
        // Both phases ran: the queue was read and expiry considered the row against the real index.
        pendingRepository.calls shouldBe listOf("selectAll", "selectExpired", "deleteByChapterIds")
        pendingRepository.lastCutoff shouldBe currentTime - FilteredDownloadCleanupService.GRACE
        // Today's behaviour for a due row whose chapter the built index does not have: the row goes,
        // no file is touched.
        coVerify(exactly = 0) { downloadManager.deleteChapters(any(), any(), any(), any()) }
        pendingRepository.deletedChapterIds shouldBe listOf(CHAPTER_ID)
        pendingRepository.rows shouldBe emptyList()
    }

    @Test
    fun `an Error inside a manga is not swallowed by the sweep`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        given()
        // Not a per-manga data problem: an OOM or a failed assertion must reach the worker instead of
        // being logged and reported as a successful pass.
        every { downloadCache.getDownloadedChapterIdsNoRenew(any(), any()) } throws AssertionError("failed hard")

        val thrown = assertThrows<AssertionError> { sweep() }

        thrown.message shouldBe "failed hard"
    }

    @Test
    fun `an ordinary exception in one manga does not stop the ones after it`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        // Two mangas with downloads, each hiding one chapter: the first one's lookups fail, and the
        // second must still be examined and queued.
        val failing = manga()
        val next = manga(id = GONE_MANGA_ID)
        favourites = listOf(failing, next)
        mangasInDb = listOf(failing, next)
        chaptersByManga = mapOf(
            failing.id to listOf(chapter(id = CHAPTER_ID, scanlator = EXCLUDED_SCANLATOR)),
            next.id to listOf(chapter(id = GONE_CHAPTER_ID, mangaId = next.id, scanlator = EXCLUDED_SCANLATOR)),
        )
        excludedScanlators = mapOf(
            failing.id to listOf(EXCLUDED_SCANLATOR),
            next.id to listOf(EXCLUDED_SCANLATOR),
        )
        mangaWithDownloadsIds = setOf(failing.id, next.id)
        every { downloadCache.getDownloadedChapterIdsNoRenew(any(), any()) } answers {
            if (secondArg<Manga>().id == failing.id) throw IllegalStateException("broken manga")
            firstArg<List<Chapter>>().map { it.id }
        }

        sweep()

        pendingRepository.inserted.map { it.mangaId } shouldBe listOf(next.id)
    }

    @Test
    fun `a cancellation in discovery is rethrown`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        given()
        every { downloadCache.getDownloadedChapterIdsNoRenew(any(), any()) } throws CancellationException("cancelled")

        val thrown = assertThrows<CancellationException> { sweep() }

        thrown.message shouldBe "cancelled"
    }

    @Test
    fun `a cancellation in expiry is rethrown`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        given()
        pendingRepository.rows = listOf(queued())
        // Discovery's lookups succeed; the expiry phase's read is the one that is cancelled, so this
        // pins the second per-manga block as well.
        var reads = 0
        every { downloadCache.getDownloadedChapterIdsNoRenew(any(), any()) } answers {
            if (++reads > 1) throw CancellationException("cancelled during expiry")
            firstArg<List<Chapter>>().map { it.id }
        }

        val thrown = assertThrows<CancellationException> { sweep() }

        thrown.message shouldBe "cancelled during expiry"
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
        val original = queued(age = 0.hours)
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
        downloadedChapterIds[MANGA_ID] = emptyList()
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
        //
        // This preference cannot change the assertion below: `Manga.create()` carries no category
        // membership and `DownloadManager` is mocked, so the carve-out is only provable at the
        // argument boundary it is asserted on (`ignoreCategoryExclusion = true`). It is set anyway so
        // the fixture reads like the situation it describes.
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
    fun `a merged id is excluded before the window so it takes no slot and starves nothing`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        // 30 favourites with downloads, every one of them hidden by an excluded scanlator, and the
        // *lowest* id is a merge parent: it sorts first in the batch the service examines, so it is
        // exactly the one that would eat a window slot if the exclusion ran after the window.
        val mangas = (1L..(FilteredDownloadCleanupService.DISCOVERY_BATCH + 5L)).map { id -> manga(id = id) }
        favourites = mangas
        mangasInDb = mangas
        chaptersByManga = mangas.associate {
            it.id to listOf(chapter(id = it.id * 10, mangaId = it.id, scanlator = EXCLUDED_SCANLATOR))
        }
        excludedScanlators = mangas.associate { it.id to listOf(EXCLUDED_SCANLATOR) }
        mangaWithDownloadsIds = mangas.mapTo(mutableSetOf()) { it.id }
        // The parent role only: id 1 is the entry's *merge id*, and the child it names is outside this
        // fixture, so the exclusion cannot come from the child half of the row.
        mergedEntryReads.add(listOf(mergedEntry(mangaId = OUTSIDE_LIBRARY_MANGA_ID, mergeId = 1L)))
        // `runWindow` starts at `(hourIndex * DISCOVERY_BATCH) % size`; this hour index is a multiple
        // of the 29 mangas that survive the exclusion, so the window is exactly the first 25 of them
        // and none of the assertions below depends on the wall clock. `getMangaWithDownloads` answers in
        // the order of `favourites` (ids ascending here) and the service sorts by id, so the batch is
        // ascending too.
        currentTime = Instant.fromEpochMilliseconds(29L * 25_517L * 3_600_000L)

        sweep()

        val queuedIds = pendingRepository.inserted.map { it.mangaId }.distinct()
        // The merged id is never queued: the 25 slots went to the unmerged ids only, from the head.
        queuedIds shouldBe (2L..26L).toList()
        queuedIds.size shouldBe FilteredDownloadCleanupService.DISCOVERY_BATCH
        // The starvation pin: id 26 is the last manga of the window, and it is reachable only because
        // id 1 consumed no slot. A window taken over the merged id first would end at 25 and leave 26,
        // which is "still hidden, still downloaded", unexamined for this whole pass.
        queuedIds.last() shouldBe 26L
        // The tail beyond the window is not examined now; the next pass reaches it (the rotation test
        // above pins that it does).
        pendingRepository.inserted.map { it.mangaId }.filter { it > 26L }.shouldBeEmpty()
        coVerify(exactly = 0) { downloadManager.deleteChapters(any(), any(), any(), any()) }
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
        pendingRepository.rows = listOf(queued(age = 3.hours))

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
        pendingRepository.rows = listOf(queued(age = 1.hours), queued(chapterId = 11L, age = 0.hours))

        runBlocking { service().clearPending() }

        pendingRepository.rows shouldBe emptyList()
        pendingRepository.calls shouldBe listOf("deleteAll")
    }

    @Test
    fun `only the hidden chapter of a manga is handed to the deletion call`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        // One manga, two downloaded chapters: only the one an excluded scanlator hides is due.
        given(
            chapters = listOf(
                chapter(id = CHAPTER_ID, scanlator = EXCLUDED_SCANLATOR),
                chapter(id = VISIBLE_CHAPTER_ID),
            ),
        )
        pendingRepository.rows = listOf(queued())

        sweep()

        // The whole manga's chapter list goes in as the input to the decision; the deletion call must
        // receive only the hidden one, or a visible download would go with it.
        coVerify(exactly = 1) {
            downloadManager.deleteChapters(
                match { chapters -> chapters.map { it.id } == listOf(CHAPTER_ID) },
                any(),
                any(),
                ignoreCategoryExclusion = true,
            )
        }
        pendingRepository.deletedChapterIds shouldBe listOf(CHAPTER_ID)
    }

    @Test
    fun `a chapter hidden for just under the grace period is not deleted yet`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        given()
        pendingRepository.rows = listOf(queued(age = 59.minutes))

        sweep()

        coVerify(exactly = 0) { downloadManager.deleteChapters(any(), any(), any(), any()) }
        // Still queued, with its original timestamp: the very next pass is the one that may delete it.
        pendingRepository.deletedChapterIds shouldBe emptyList()
        pendingRepository.rows.map { it.chapterId } shouldBe listOf(CHAPTER_ID)
    }

    @Test
    fun `consecutive passes examine different mangas`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        // Five mangas more than a window holds: the tail is only ever reached if the window moves.
        val mangas = (1L..(FilteredDownloadCleanupService.DISCOVERY_BATCH + 5L)).map { id -> manga(id = id) }
        favourites = mangas
        mangasInDb = mangas
        chaptersByManga = mangas.associate {
            it.id to listOf(chapter(id = it.id * 10, mangaId = it.id, scanlator = EXCLUDED_SCANLATOR))
        }
        excludedScanlators = mangas.associate { it.id to listOf(EXCLUDED_SCANLATOR) }
        mangaWithDownloadsIds = mangas.mapTo(mutableSetOf()) { it.id }

        sweep()
        val firstWindow = pendingRepository.inserted.map { it.mangaId }.toSet()

        currentTime += 1.hours
        sweep()
        val examined = pendingRepository.inserted.map { it.mangaId }.toSet()

        firstWindow.size shouldBe FilteredDownloadCleanupService.DISCOVERY_BATCH
        // The second pass moved on to the mangas the first one could not fit — a plain `take(N)` would
        // have re-read the same head forever and starved them.
        (examined - firstWindow).size shouldBe 5
    }

    @Test
    fun `an expiry pass deletes only the expiry budget of mangas and the next window reaches the rest`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        val mangas = (1L..(FilteredDownloadCleanupService.EXPIRY_BATCH + 5L)).map { id -> manga(id = id) }
        favourites = mangas
        mangasInDb = mangas
        chaptersByManga = mangas.associate {
            it.id to listOf(chapter(id = it.id * 10, mangaId = it.id, scanlator = EXCLUDED_SCANLATOR))
        }
        excludedScanlators = mangas.associate { it.id to listOf(EXCLUDED_SCANLATOR) }
        mangaWithDownloadsIds = mangas.mapTo(mutableSetOf()) { it.id }
        pendingRepository.rows = mangas.map { queued(chapterId = it.id * 10, mangaId = it.id) }

        sweep()

        coVerify(exactly = FilteredDownloadCleanupService.EXPIRY_BATCH) {
            downloadManager.deleteChapters(any(), any(), any(), any())
        }
        pendingRepository.rows.size shouldBe 5

        currentTime += 1.hours
        sweep()

        // Every due manga is reached across the two passes, none of them twice.
        coVerify(exactly = FilteredDownloadCleanupService.EXPIRY_BATCH + 5) {
            downloadManager.deleteChapters(any(), any(), any(), any())
        }
    }

    @Test
    fun `the read filter never deletes a chapter it hides in the other direction`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        // "Show only unread" hides this *read* chapter: being read is what puts it out of view, and the
        // regression this pins would delete the downloads of everything the user has already read.
        given(
            manga = manga(chapterFlags = Manga.CHAPTER_SHOW_UNREAD),
            chapters = listOf(chapter(read = true)),
            excludedScanlators = emptyList(),
        )
        pendingRepository.rows = listOf(queued())

        sweep()

        pendingRepository.inserted.shouldBeEmpty()
        coVerify(exactly = 0) { downloadManager.deleteChapters(any(), any(), any(), any()) }
        pendingRepository.deletedChapterIds shouldBe listOf(CHAPTER_ID)
        pendingRepository.rows shouldBe emptyList()
    }

    @Test
    fun `turning the setting off during discovery drops the pass before it writes`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        // One chapter a filter hides, so discovery has something to queue, and one it does not, so the
        // row already queued for that one is stale and discovery has something to drop. Both writes of
        // the phase are therefore loaded before it starts.
        given(
            chapters = listOf(
                chapter(id = CHAPTER_ID, scanlator = EXCLUDED_SCANLATOR),
                chapter(id = VISIBLE_CHAPTER_ID),
            ),
        )
        val staleRow = queued(chapterId = VISIBLE_CHAPTER_ID)
        pendingRepository.rows = listOf(staleRow)
        // The switch goes off inside the pass, after the reads that open discovery and before any of
        // its writes: that is what a pass still in flight sees when the user turns the feature off,
        // because the queue clear that follows the switch runs asynchronously.
        pendingRepository.onNextSelectAll = { downloadPreferences.removeFilteredDownloads.set(false) }

        sweep()

        // Neither write is issued, so no row stamped with a pre-pause time reaches the queue and the row
        // that was queued before the pass survives it: the guard has to precede both writes, not just
        // the first one.
        pendingRepository.inserted.shouldBeEmpty()
        pendingRepository.deletedChapterIds.shouldBeEmpty()
        pendingRepository.rows shouldBe listOf(staleRow)
        downloadPreferences.removeFilteredDownloads.get() shouldBe false
    }

    @Test
    fun `turning the setting off during expiry deletes nothing`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        // Two due rows with different fates, so both writes of the expiry phase are loaded: the first
        // manga is still in the library and still downloaded, so its chapter would be handed to the
        // deletion call; the second is not in the database any more, so its row would be dropped.
        val keptManga = manga()
        val goneManga = manga(id = GONE_MANGA_ID)
        favourites = listOf(keptManga, goneManga)
        mangasInDb = listOf(keptManga)
        chaptersByManga = mapOf(
            keptManga.id to listOf(chapter(id = CHAPTER_ID, scanlator = EXCLUDED_SCANLATOR)),
            goneManga.id to listOf(chapter(id = GONE_CHAPTER_ID, mangaId = GONE_MANGA_ID, scanlator = EXCLUDED_SCANLATOR)),
        )
        excludedScanlators = mapOf(
            keptManga.id to listOf(EXCLUDED_SCANLATOR),
            goneManga.id to listOf(EXCLUDED_SCANLATOR),
        )
        mangaWithDownloadsIds = setOf(keptManga.id, goneManga.id)
        val keptRow = queued(chapterId = CHAPTER_ID)
        val goneRow = queued(chapterId = GONE_CHAPTER_ID, mangaId = GONE_MANGA_ID)
        pendingRepository.rows = listOf(keptRow, goneRow)
        // The same seam, on the read that opens expiry: discovery has already run and queued both rows
        // (they were queued already, so the insert-or-ignore left them as they were).
        pendingRepository.onNextSelectExpired = { downloadPreferences.removeFilteredDownloads.set(false) }

        sweep()

        coVerify(exactly = 0) { downloadManager.deleteChapters(any(), any(), any(), any()) }
        pendingRepository.deletedChapterIds.shouldBeEmpty()
        pendingRepository.deletedMangaIds.shouldBeEmpty()
        pendingRepository.rows shouldBe listOf(keptRow, goneRow)
        downloadPreferences.removeFilteredDownloads.get() shouldBe false
    }

    @Test
    fun `a merged manga is never a candidate even when its own filter hides the download`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        downloadPreferences.removeBookmarkedChapters.set(true)
        // The merged chapter list applies the *merge parent's* `user_chapter_flags`, not the child's
        // (`chapter.sq` joins `manga` on the merge id), so the child's "hide bookmarked" flag says
        // nothing about what the user sees there. Without the exclusion this chapter — visible in the
        // merged list, hidden in the child's own — would be deleted.
        val child = manga(chapterFlags = Manga.CHAPTER_SHOW_NOT_BOOKMARKED)
        val parent = manga(id = PARENT_ID)
        favourites = listOf(child, parent)
        mangasInDb = listOf(child, parent)
        chaptersByManga = mapOf(MANGA_ID to listOf(chapter(bookmark = true, scanlator = EXCLUDED_SCANLATOR)))
        excludedScanlators = mapOf(MANGA_ID to listOf(EXCLUDED_SCANLATOR))
        mangaWithDownloadsIds = setOf(MANGA_ID, PARENT_ID)
        mergedEntryReads.add(listOf(mergedEntry()))
        pendingRepository.rows = listOf(queued())

        sweep()

        pendingRepository.inserted.shouldBeEmpty()
        coVerify(exactly = 0) { downloadManager.deleteChapters(any(), any(), any(), any()) }
        // The row it already had is dropped, and dropped without a file.
        pendingRepository.deletedMangaIds shouldBe listOf(MANGA_ID)
        pendingRepository.deletedChapterIds.shouldBeEmpty()
        pendingRepository.rows shouldBe emptyList()
    }

    @Test
    fun `an expired row of a manga that became merged mid-sweep is dropped without a delete`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        given()
        pendingRepository.rows = listOf(queued())
        // A merge created between the two phases of one sweep: discovery read the merge table before it
        // existed, so the row got through discovery. Expiry reads it again and drops the row
        // defensively — a merged list must never expire anything, whatever a stale row says.
        mergedEntryReads.add(emptyList())
        mergedEntryReads.add(listOf(mergedEntry()))

        sweep()

        coVerify(exactly = 0) { downloadManager.deleteChapters(any(), any(), any(), any()) }
        pendingRepository.deletedMangaIds shouldBe listOf(MANGA_ID)
        pendingRepository.rows shouldBe emptyList()
    }

    @Test
    fun `the merge parent role alone keeps a manga from being a candidate`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        downloadPreferences.removeBookmarkedChapters.set(true)
        // The entry names this manga as its *merge id*, not as its child: `chapter.sq` joins `manga`
        // on the merge id, so the parent's own chapter flags are what the merged list applies, and the
        // manga is merged even though no row names it as `mangaId`. Without the parent half of the
        // exclusion its downloaded bookmarked chapter — which its own "hide bookmarked" flag hides —
        // would be queued and, a grace period later, deleted.
        val child = manga(id = MANGA_ID)
        val parent = manga(id = PARENT_ID, chapterFlags = Manga.CHAPTER_SHOW_NOT_BOOKMARKED)
        favourites = listOf(child, parent)
        mangasInDb = listOf(parent)
        chaptersByManga = mapOf(
            PARENT_ID to listOf(chapter(id = PARENT_CHAPTER_ID, mangaId = PARENT_ID, bookmark = true)),
        )
        excludedScanlators = emptyMap()
        mangaWithDownloadsIds = setOf(PARENT_ID)
        // Read by both phases: the merge exists for the whole sweep.
        mergedEntryReads.add(listOf(mergedEntry(mangaId = MANGA_ID, mergeId = PARENT_ID)))
        mergedEntryReads.add(listOf(mergedEntry(mangaId = MANGA_ID, mergeId = PARENT_ID)))
        pendingRepository.rows = listOf(queued(chapterId = PARENT_CHAPTER_ID, mangaId = PARENT_ID))

        sweep()

        // Not queued, never handed to a delete, and not even read: the batch is empty once the merged
        // id is filtered out.
        pendingRepository.inserted.shouldBeEmpty()
        coVerify(exactly = 0) { chapterRepository.getChaptersByMangaIds(any()) }
        coVerify(exactly = 0) { downloadManager.deleteChapters(any(), any(), any(), any()) }
        // The row it already had goes with the discovery drop, un-deleted.
        pendingRepository.deletedMangaIds shouldBe listOf(PARENT_ID)
        pendingRepository.deletedChapterIds.shouldBeEmpty()
        pendingRepository.rows shouldBe emptyList()
    }

    @Test
    fun `an expired row of a merge parent that became merged mid-sweep is dropped without a delete`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        // The same mid-sweep merge as the child case, but the entry names this manga as its *merge
        // id*: the parent is merged too, and a due row of it must be dropped, not acted on.
        val parent = manga(id = PARENT_ID)
        favourites = listOf(parent)
        mangasInDb = listOf(parent)
        chaptersByManga = mapOf(
            PARENT_ID to listOf(chapter(id = PARENT_CHAPTER_ID, mangaId = PARENT_ID, scanlator = EXCLUDED_SCANLATOR)),
        )
        excludedScanlators = mapOf(PARENT_ID to listOf(EXCLUDED_SCANLATOR))
        mangaWithDownloadsIds = setOf(PARENT_ID)
        pendingRepository.rows = listOf(queued(chapterId = PARENT_CHAPTER_ID, mangaId = PARENT_ID))
        // Discovery read the merge table before the merge existed, so the row got through it — and is
        // still re-sent by the INSERT OR IGNORE. Expiry reads the table again and drops it.
        mergedEntryReads.add(emptyList())
        mergedEntryReads.add(listOf(mergedEntry(mangaId = MANGA_ID, mergeId = PARENT_ID)))

        sweep()

        pendingRepository.inserted.map { it.chapterId } shouldBe listOf(PARENT_CHAPTER_ID)
        coVerify(exactly = 0) { downloadManager.deleteChapters(any(), any(), any(), any()) }
        pendingRepository.deletedMangaIds shouldBe listOf(PARENT_ID)
        pendingRepository.deletedChapterIds.shouldBeEmpty()
        pendingRepository.rows shouldBe emptyList()
    }

    @Test
    fun `a hidden chapter sharing a directory name with a visible sibling is not deleted`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        downloadPreferences.removeBookmarkedChapters.set(true)
        // Same name and no scanlator: both chapters resolve to the same download directory, and the
        // manager deletes by directory name. Deleting the hidden one would take the visible one's file
        // with it, so the guard keeps it and only its queue row goes.
        given(
            manga = manga(chapterFlags = Manga.CHAPTER_SHOW_NOT_BOOKMARKED),
            chapters = listOf(
                chapter(id = CHAPTER_ID, bookmark = true, name = "Chapter 1"),
                chapter(id = VISIBLE_CHAPTER_ID, name = "Chapter 1"),
            ),
            excludedScanlators = emptyList(),
        )
        pendingRepository.rows = listOf(queued())

        sweep()

        coVerify(exactly = 0) { downloadManager.deleteChapters(any(), any(), any(), any()) }
        pendingRepository.deletedChapterIds shouldBe listOf(CHAPTER_ID)
        pendingRepository.rows shouldBe emptyList()
    }

    @Test
    fun `turning the setting off inside the expiry loop stops it before any delete`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        // Two due rows in different mangas, both genuinely deletable. Discovery runs first; the switch
        // goes off while the expiry loop is working on the first manga — after the guard that opens the
        // phase, which is exactly the window the loop itself leaves open.
        val first = manga()
        val second = manga(id = GONE_MANGA_ID)
        favourites = listOf(first, second)
        mangasInDb = listOf(first, second)
        chaptersByManga = mapOf(
            first.id to listOf(chapter(id = CHAPTER_ID, scanlator = EXCLUDED_SCANLATOR)),
            second.id to listOf(chapter(id = GONE_CHAPTER_ID, mangaId = second.id, scanlator = EXCLUDED_SCANLATOR)),
        )
        excludedScanlators = mapOf(
            first.id to listOf(EXCLUDED_SCANLATOR),
            second.id to listOf(EXCLUDED_SCANLATOR),
        )
        mangaWithDownloadsIds = setOf(first.id, second.id)
        val firstRow = queued(chapterId = CHAPTER_ID)
        val secondRow = queued(chapterId = GONE_CHAPTER_ID, mangaId = GONE_MANGA_ID)
        pendingRepository.rows = listOf(firstRow, secondRow)
        onNextSourceLookup = { downloadPreferences.removeFilteredDownloads.set(false) }

        sweep()

        coVerify(exactly = 0) { downloadManager.deleteChapters(any(), any(), any(), any()) }
        // The second manga is never taken on at all: the loop re-reads the veto for every manga.
        coVerify(exactly = 1) { sourceManager.get(any()) }
        pendingRepository.deletedChapterIds.shouldBeEmpty()
        pendingRepository.rows shouldBe listOf(firstRow, secondRow)
    }

    @Test
    fun `a row whose chapter url no longer matches is dropped without a delete`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        given()
        // `chapter.id` has no AUTOINCREMENT, so after a delete/insert cycle the id can name a different
        // chapter of the same manga. The queued url is the identity that survives that reuse; when it
        // does not match, the row was never about this chapter and nothing may be deleted for it.
        pendingRepository.rows = listOf(queued(chapterUrl = "https://example.org/an-older-chapter"))

        sweep()

        coVerify(exactly = 0) { downloadManager.deleteChapters(any(), any(), any(), any()) }
        pendingRepository.deletedChapterIds shouldBe listOf(CHAPTER_ID)
        pendingRepository.rows shouldBe emptyList()
    }

    @Test
    fun `a row survives a delete the manager refused and goes for one it removed`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        // The manager applies its own bookmark filter (`getChaptersToDelete`), so it can hand nothing
        // back for a chapter this sweep found to be a candidate. Dropping that chapter's row anyway
        // would leave a file that nothing ever re-queues.
        val refused = manga()
        val honoured = manga(id = GONE_MANGA_ID)
        favourites = listOf(refused, honoured)
        mangasInDb = listOf(refused, honoured)
        chaptersByManga = mapOf(
            refused.id to listOf(chapter(id = CHAPTER_ID, scanlator = EXCLUDED_SCANLATOR)),
            honoured.id to listOf(chapter(id = GONE_CHAPTER_ID, mangaId = honoured.id, scanlator = EXCLUDED_SCANLATOR)),
        )
        excludedScanlators = mapOf(
            refused.id to listOf(EXCLUDED_SCANLATOR),
            honoured.id to listOf(EXCLUDED_SCANLATOR),
        )
        mangaWithDownloadsIds = setOf(refused.id, honoured.id)
        refusedChapterIds += CHAPTER_ID
        pendingRepository.rows = listOf(
            queued(chapterId = CHAPTER_ID),
            queued(chapterId = GONE_CHAPTER_ID, mangaId = GONE_MANGA_ID),
        )

        sweep()

        // Both were handed over; only one of them is gone.
        coVerify(exactly = 2) { downloadManager.deleteChapters(any(), any(), any(), any()) }
        pendingRepository.rows.map { it.chapterId } shouldBe listOf(CHAPTER_ID)
    }

    @Test
    fun `a row stamped in the future is re-stamped to now and nothing is deleted`() {
        downloadPreferences.removeFilteredDownloads.set(true)
        given()
        // The clock moved backwards after the row was written, so its dwell only starts when the clock
        // catches up — which after a large jump can be never. Discovery re-stamps it instead.
        pendingRepository.rows = listOf(queued(age = -1.hours))

        sweep()

        // The repository has no update query, so the re-stamp is a delete + insert of the same row:
        // the delete is the mechanism, and the assertion that matters is the stamp that came back.
        pendingRepository.deletedChapterIds shouldBe listOf(CHAPTER_ID)
        pendingRepository.rows.single().firstHiddenAt shouldBe currentTime
        coVerify(exactly = 0) { downloadManager.deleteChapters(any(), any(), any(), any()) }
    }

    private companion object {
        const val MANGA_ID = 1L
        const val SOURCE_ID = 2L
        const val GONE_MANGA_ID = 3L
        const val PARENT_ID = 4L
        const val CHAPTER_ID = 10L
        const val VISIBLE_CHAPTER_ID = 11L
        const val GONE_CHAPTER_ID = 12L
        const val PARENT_CHAPTER_ID = 13L
        const val OUTSIDE_LIBRARY_MANGA_ID = 999L
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

    /**
     * Runs once, inside the next [selectAll], after the call is recorded and before it returns its rows.
     * It is how a test lets the feature be turned off *while* a pass is in flight — between the pass'
     * reads and its writes — without having to fake the preference itself.
     */
    var onNextSelectAll: (() -> Unit)? = null

    /** The same one-shot seam for [selectExpired], the read that opens the expiry phase. */
    var onNextSelectExpired: (() -> Unit)? = null

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
        val sideEffect = onNextSelectExpired
        onNextSelectExpired = null
        sideEffect?.invoke()
        return rows.filter { it.firstHiddenAt <= cutoff }
    }

    override suspend fun selectAll(): List<PendingDownloadDeletion> {
        calls += "selectAll"
        val sideEffect = onNextSelectAll
        onNextSelectAll = null
        sideEffect?.invoke()
        return rows
    }
}
// KUN <--
