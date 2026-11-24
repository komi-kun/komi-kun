// KUN -->
package eu.kanade.tachiyomi.data.download

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.download.FilteredDownloadPolicy
import eu.kanade.tachiyomi.source.Source
import exh.log.xLogE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mihon.core.metro.AppCoroutineScope
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.model.PendingDownloadDeletion
import tachiyomi.domain.download.repository.PendingDownloadDeletionRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Deletes the downloads of library manga chapters that a persistent chapter filter hides.
 *
 * The dwell time is the whole point: a chapter that vanishes from the list for a moment — the user
 * toggles a filter, an edit clears it again — keeps its file. One pass therefore only *records* what
 * is hidden now ([PendingDownloadDeletion] with the time it was first seen hidden), and a later pass
 * deletes a recorded chapter only once it has stayed hidden for [GRACE], re-verifying from the
 * current state that it is still hidden, still downloaded and still in the library. Every write is a
 * set operation on the queue; the files are removed by [DownloadManager] for the chapters of one
 * manga at a time.
 *
 * This is a periodic background job, so it is bounded: it reads the download index it is given (never
 * the filesystem), examines at most [DISCOVERY_BATCH] manga for new hidden chapters and at most
 * [EXPIRY_BATCH] mangas whose queue rows are due, and stops there. A pass that cannot finish is
 * simply continued by the next one — the queue rows survive until a pass gets to them.
 */
@Inject
@SingleIn(AppScope::class)
class FilteredDownloadCleanupService(
    private val downloadPreferences: DownloadPreferences,
    private val downloadManager: DownloadManager,
    private val downloadCache: DownloadCache,
    private val pendingDeletionRepository: PendingDownloadDeletionRepository,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val sourceManager: SourceManager,
    // The delete of the off switch is fire-and-forget from a main-thread callback.
    @AppCoroutineScope private val scope: CoroutineScope,
    // Injectable for deterministic tests; production uses the wall clock.
    private val now: () -> Instant = Clock.System::now,
) {

    /**
     * One sweep: record what is hidden now, then delete what has been hidden for [GRACE].
     *
     * Discovery is a **full recomputation** of its window on every pass: the batch is a rotating
     * window over the library's mangas with downloads (see [runWindow]), and each of them is
     * re-examined against its current filter flags — never "only what became hidden since the last
     * pass" — so a download a filter hid before this feature (or this switch) existed is stamped by
     * the first pass whose window reaches it.
     *
     * Nothing is backdated, though: a chapter that has been hidden for months is stamped with *this*
     * moment, so the grace period always runs from the first time a pass saw it hidden and turning the
     * feature on can never empty the downloads at once.
     *
     * @param discoverOnly stops after the discovery phase. This is the immediate pass of an off → on
     * toggle: it exists to start the clock for that backlog, and it never deletes anything — no expiry
     * read, no file, no queue row.
     */
    suspend fun run(discoverOnly: Boolean = false) {
        // The cheap exit, and deliberately the first statement: with the feature off a periodic pass
        // must cost one preference read and nothing else — no database query, no download-cache
        // lookup, no download manager call.
        if (!downloadPreferences.removeFilteredDownloads.get()) return

        val instant = now()
        // Read once for both phases: the library is what "still a favourite" is answered from.
        val favorites = mangaRepository.getFavorites()

        discover(instant, favorites)
        if (discoverOnly) return
        expire(instant)
    }

    /**
     * Empties the queue.
     *
     * Called when the setting is turned off, so that turning it back on cannot resurrect timestamps
     * from before the pause: they can be arbitrarily old, and the first pass would then delete that
     * whole backlog at once instead of one grace period after it was seen hidden again.
     */
    suspend fun clearPending() {
        pendingDeletionRepository.deleteAll()
    }

    /**
     * [clearPending] on the app scope.
     *
     * The off switch is a main-thread preference callback, so the delete is handed to the app's
     * IO-backed scope instead of running on the caller's thread.
     */
    fun clearPendingAsync() {
        scope.launch {
            try {
                clearPending()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                xLogE("Failed to clear the pending download deletions", e)
            }
        }
    }

    /**
     * Queues the chapters that a filter hides right now, and un-queues the ones it no longer hides.
     *
     * This is a full recomputation, not a delta: the manga batch is every favourite with downloads
     * (a rotating window of it, see [runWindow]), and each one's hidden set is recomputed from its
     * current flags and chapter list, so a backlog hidden before this pass existed is found here too.
     * Only mangas that have downloads in the in-memory index are examined: a manga without files has
     * nothing to lose, and walking the downloads directory to find out would defeat the point of the
     * index.
     */
    private suspend fun discover(now: Instant, favorites: List<Manga>) {
        val pending = pendingDeletionRepository.selectAll()

        // A manga that left the library (or was deleted) can never be verified again, so its whole
        // queue goes in one statement rather than one delete per row.
        val favoriteIds = favorites.mapTo(HashSet()) { it.id }
        val abandonedMangaIds = pending.asSequence()
            .map { it.mangaId }
            .filterNot { it in favoriteIds }
            .distinct()
            .toList()
        if (abandonedMangaIds.isNotEmpty()) pendingDeletionRepository.deleteByMangaIds(abandonedMangaIds)

        val pendingByManga = pending.groupBy { it.mangaId }
        val batch = downloadCache.getMangaWithDownloads(favorites.filterNot { it.isLocal() })
            .sortedBy { it.id }
            .runWindow(now, DISCOVERY_BATCH)
        if (batch.isEmpty()) return

        val mangaIds = batch.map { it.id }
        // One query for the whole batch, unfiltered: `applyFilter = true` drops the very rows the
        // policy needs to see (chapter.sq `getChaptersByMangaId`), so it could never be diffed
        // against. The hidden set is then recomputed in Kotlin by the pure policy.
        val chaptersByManga = chapterRepository.getChaptersByMangaIds(mangaIds).groupBy { it.mangaId }
        val excludedByManga = mangaRepository.getExcludedScanlatorsByMangaIds(mangaIds)
        val keepBookmarked = !downloadPreferences.removeBookmarkedChapters.get()

        val toInsert = mutableListOf<PendingDownloadDeletion>()
        val staleChapterIds = mutableListOf<Long>()
        batch.forEach { manga ->
            runCatching {
                val chapters = chaptersByManga[manga.id].orEmpty()
                // The non-renewing read: an hourly pass must never rebuild the downloads tree.
                val downloadedIds = downloadCache.getDownloadedChapterIdsNoRenew(chapters, manga)
                val hidden = FilteredDownloadPolicy.hiddenDownloadedChapters(
                    chapters = chapters,
                    manga = manga,
                    excludedScanlators = excludedByManga[manga.id].orEmpty().toSet(),
                    downloadedChapterIds = downloadedIds,
                    keepBookmarked = keepBookmarked,
                )
                val hiddenIds = hidden.mapTo(HashSet()) { it.id }
                // The row's timestamp is not refreshed here: the insert is an INSERT OR IGNORE, so a
                // chapter that is already queued keeps the moment it was *first* seen hidden and the
                // grace period never restarts.
                hidden.forEach { toInsert += PendingDownloadDeletion(it.id, manga.id, now) }
                // A row of this manga whose chapter is visible again, gone from the list, or no longer
                // downloaded is queued no more; it is dropped without touching any file.
                pendingByManga[manga.id].orEmpty()
                    .map { it.chapterId }
                    .filterNot { it in hiddenIds }
                    .forEach { staleChapterIds += it }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                xLogE("Failed to queue filtered downloads of manga ${manga.id}", e)
            }
        }

        // The clear that follows the switch runs asynchronously, so a pass already in flight could
        // otherwise write rows *after* it: those rows would carry a pre-pause timestamp and the first
        // pass after a re-enable would then delete that backlog at once. One extra read is the guard.
        if (!downloadPreferences.removeFilteredDownloads.get()) return

        // Both writes are per batch, not per row: one transaction for the inserts, one statement for
        // the queue rows that are no longer hidden.
        if (toInsert.isNotEmpty()) pendingDeletionRepository.insertAll(toInsert)
        if (staleChapterIds.isNotEmpty()) pendingDeletionRepository.deleteByChapterIds(staleChapterIds)
    }

    /**
     * Deletes the queued downloads that have been hidden for at least [GRACE].
     *
     * Every row is re-verified against the current state before its files are touched: the queue says
     * the chapter *was* hidden, and a grace period is long enough for the user to change their mind.
     */
    private suspend fun expire(now: Instant) {
        val expired = pendingDeletionRepository.selectExpired(now - GRACE)
        if (expired.isEmpty()) return

        val rowsByManga = expired.groupBy { it.mangaId }
        val mangaIds = rowsByManga.keys.sorted().runWindow(now, EXPIRY_BATCH)
        if (mangaIds.isEmpty()) return
        // Same guard as discovery, before this phase's first write: a pass that was already running
        // when the switch went off must not delete a file the user has just vetoed.
        if (!downloadPreferences.removeFilteredDownloads.get()) return

        val mangasById = mangaRepository.getMangaByIds(mangaIds).associateBy { it.id }
        val chaptersByManga = chapterRepository.getChaptersByMangaIds(mangaIds).groupBy { it.mangaId }
        val excludedByManga = mangaRepository.getExcludedScanlatorsByMangaIds(mangaIds)

        // Mangas that left the library, were deleted, or turned local are dropped together: nothing of
        // theirs is deleted, and their rows would never verify again.
        val abandonedMangaIds = mangaIds.filter { mangaId ->
            val manga = mangasById[mangaId]
            manga == null || !manga.favorite || manga.isLocal()
        }
        if (abandonedMangaIds.isNotEmpty()) pendingDeletionRepository.deleteByMangaIds(abandonedMangaIds)

        val keepBookmarked = !downloadPreferences.removeBookmarkedChapters.get()
        mangaIds.filterNot { it in abandonedMangaIds }.forEach { mangaId ->
            val manga = mangasById.getValue(mangaId)
            runCatching {
                val chapters = chaptersByManga[mangaId].orEmpty()
                // The non-renewing read: an hourly pass must never rebuild the downloads tree.
                val downloadedIds = downloadCache.getDownloadedChapterIdsNoRenew(chapters, manga)
                // The policy is the verification: it only returns chapters that are still downloaded
                // *and* still hidden by a persistent filter, so a row it does not return has stopped
                // being a candidate — whether the chapter became visible, was deleted, or its file is
                // already gone.
                val stillHidden = FilteredDownloadPolicy.hiddenDownloadedChapters(
                    chapters = chapters,
                    manga = manga,
                    excludedScanlators = excludedByManga[mangaId].orEmpty().toSet(),
                    downloadedChapterIds = downloadedIds,
                    keepBookmarked = keepBookmarked,
                ).associateBy { it.id }
                val rows = rowsByManga.getValue(mangaId)
                val staleChapterIds = rows.map { it.chapterId }.filterNot { it in stillHidden }
                if (staleChapterIds.isNotEmpty()) {
                    pendingDeletionRepository.deleteByChapterIds(staleChapterIds)
                }

                val toDelete = rows.mapNotNull { stillHidden[it.chapterId] }
                if (toDelete.isEmpty()) return@runCatching
                // An unavailable extension cannot produce the chapter directory names, so nothing is
                // guessed at: the rows stay queued and the next pass with the extension installed
                // deletes them.
                val source = sourceManager.get(manga.source) ?: return@runCatching
                deleteDownloads(toDelete, manga, source)
                pendingDeletionRepository.deleteByChapterIds(toDelete.map { it.id })
            }.onFailure { e ->
                if (e is CancellationException) throw e
                xLogE("Failed to delete filtered downloads of manga $mangaId", e)
            }
        }
    }

    /**
     * Removes the files of [chapters], which a persistent filter hides from the library list.
     *
     * `ignoreCategoryExclusion` is deliberate, and it is the one place this path differs from
     * [DownloadManager]'s read-driven deletion. `getChaptersToDelete` keeps the *unread* chapters of a
     * manga in an excluded category because that feature exists to reclaim space after reading, so
     * read state is what protects a file there. Here the file is unwanted for another reason — a
     * filter the user persisted for this manga hides it — and that has nothing to do with whether it
     * was read: a hidden chapter is deleted read or unread, excluded category or not.
     *
     * The bypass covers only that read-state carve-out. Bookmark retention still holds, but on this
     * call path it rests on [FilteredDownloadPolicy] alone, which excludes a bookmarked chapter while
     * `removeBookmarkedChapters` is off. The manager's own bookmark filter must not be relied on here:
     * `getChaptersToDelete` applies it only when `chapters.size > 1 || !ignoreCategoryExclusion`, and
     * `ignoreCategoryExclusion = true` with a single-chapter batch short-circuits it.
     */
    private suspend fun deleteDownloads(chapters: List<Chapter>, manga: Manga, source: Source) {
        downloadManager.deleteChapters(chapters, manga, source, ignoreCategoryExclusion = true)
    }

    /**
     * The part of this list a single pass examines.
     *
     * Taking the first [budget] items every pass would starve the tail of a large library forever: a
     * chapter that becomes hidden at the end of the list would never be queued, and a quarter of the
     * due rows would never be reached. The window rotates with the wall clock, advancing by one
     * window every [GRACE], so every item is examined within `ceil(size / budget)` passes while one
     * pass still stops early.
     */
    private fun <T> List<T>.runWindow(now: Instant, budget: Int): List<T> {
        if (size <= budget) return this
        val windows = now.toEpochMilliseconds() / GRACE.inWholeMilliseconds
        val start = ((windows * budget) % size).toInt()
        return List(budget) { this[(start + it) % size] }
    }

    companion object {
        /** How long a chapter must have stayed hidden before its download is deleted. */
        val GRACE = 1.hours

        /** Mangas examined for newly hidden chapters per pass; the rest wait for the next one. */
        const val DISCOVERY_BATCH = 25

        /** Mangas with due queue rows deleted per pass; the rest wait for the next one. */
        const val EXPIRY_BATCH = 25
    }
}
// KUN <--
