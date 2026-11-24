// KUN -->
package eu.kanade.domain.download

import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.source.local.isLocal

/**
 * The single answer to "which of this library manga's downloaded chapters does a persistent chapter
 * filter hide?".
 *
 * It is deliberately pure — a list in, a list out, with no coroutine, database, download-cache or
 * settings access — so the decision can be pinned by a unit test and reused by whatever persists the
 * result for the grace period before the files are deleted.
 *
 * Only the bookmark filter and the manga's excluded scanlators are hiding causes. The read/unread
 * filter and the downloaded/not filter are deliberately ignored in both directions — neither can make a
 * chapter a candidate, and neither can exempt one — because both are commonly used in exactly the
 * direction that hides the very files this feature deletes ("show unread only" hides read chapters,
 * "show not downloaded" hides every download). A filter that hides a chapter for a reason the manga
 * record does not persist (a category exclusion, for example) belongs to the deletion path, not here.
 */
object FilteredDownloadPolicy {

    /**
     * The [chapters] whose id is in [downloadedChapterIds] and that a persistent filter of [manga]
     * hides, in input order.
     *
     * @param excludedScanlators the manga's excluded scanlators (`GetExcludedScanlators`).
     * @param downloadedChapterIds the ids of the manga's chapters that are actually downloaded.
     * @param keepBookmarked `true` when the user keeps bookmarked chapters
     * (`DownloadPreferences.removeBookmarkedChapters` is off); a bookmarked chapter is then never a
     * candidate, whichever filter hides it.
     */
    fun hiddenDownloadedChapters(
        chapters: List<Chapter>,
        manga: Manga,
        excludedScanlators: Set<String>,
        downloadedChapterIds: Collection<Long>,
        keepBookmarked: Boolean,
    ): List<Chapter> {
        // Library only: a manga that is not in the library gets its chapter filters reset to their
        // defaults every time a screen opens it, so they say nothing durable about the user's wishes.
        if (!manga.favorite) return emptyList()
        // A local manga's chapters are files the user owns and manages; nothing was downloaded to delete.
        if (manga.isLocal()) return emptyList()
        if (downloadedChapterIds.isEmpty()) return emptyList()

        val downloaded = downloadedChapterIds as? Set<Long> ?: downloadedChapterIds.toHashSet()
        val bookmarkedFilter = manga.bookmarkedFilter

        return chapters.filter { chapter ->
            chapter.id in downloaded &&
                !(keepBookmarked && chapter.bookmark) &&
                isHiddenByFilter(chapter, bookmarkedFilter, excludedScanlators)
        }
    }

    private fun isHiddenByFilter(
        chapter: Chapter,
        bookmarkedFilter: TriState,
        excludedScanlators: Set<String>,
    ): Boolean {
        // Mirrors the bookmark arm of `List<Chapter>.applyFilters`
        // (eu/kanade/domain/chapter/model/ChapterFilter.kt:29): a chapter survives a filter when
        // `applyFilter(filter) { predicate }` is true, and a filter that hides it is a candidate.
        val hiddenByBookmark = !applyFilter(bookmarkedFilter) { chapter.bookmark }
        // The scanlator filter is applied in SQL for the chapter list (libraryView.sq joins
        // `excluded_scanlator`), which never matches a null scanlator.
        val hiddenByScanlator = chapter.scanlator != null && chapter.scanlator in excludedScanlators
        return hiddenByBookmark || hiddenByScanlator
    }
}
// KUN <--
