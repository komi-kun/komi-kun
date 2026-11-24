// KUN -->
package eu.kanade.domain.download

import tachiyomi.domain.chapter.model.Chapter

/**
 * The single answer to "may this chapter's download files be deleted without taking another
 * chapter's files with them?".
 *
 * `DownloadManager.deleteChapters` deletes by *directory name*: it resolves each chapter to its
 * candidate names and removes every matching directory, with no check that a chapter which survives
 * the sweep does not resolve to the same directory. Two chapters with the same name and no scanlator
 * (duplicates, truncated long names) can therefore share a directory, and deleting the hidden one
 * would destroy the kept one's file. This is the targeted-delete counterpart of the guard
 * `deleteOrphanedChapters` already applies through `OrphanedDownloads`.
 *
 * It is deliberately pure — a list in, a list out, with no coroutine, database, download-cache,
 * settings or filesystem access — so the decision can be pinned by a unit test; the caller supplies
 * the candidate directory names as a function.
 */
object FilteredDownloadDeletionGuard {

    /**
     * Drops, from [toDelete], every chapter that could resolve to the same download
     * directory as a chapter that is being kept.
     *
     * The guard is intentionally conservative: a false drop only retains a file, so it can never
     * cause a deletion. A chapter whose [dirNames] set is empty resolves to no directory at all and so
     * never collides with anything. Only [kept] protects; chapters in [toDelete] are free to share
     * names with each other, because they are all leaving anyway.
     *
     * @param toDelete the chapters the sweep intends to delete
     * @param kept every other chapter of the same manga (the ones that stay on disk)
     * @param dirNames the candidate directory names for a chapter
     *   (`DownloadProvider.getValidChapterDirNames` in production); the helper takes it
     *   as a function so it stays pure and testable.
     */
    fun safeToDelete(
        toDelete: List<Chapter>,
        kept: List<Chapter>,
        dirNames: (Chapter) -> Set<String>,
    ): List<Chapter> {
        if (toDelete.isEmpty() || kept.isEmpty()) return toDelete

        val keptDirNames = HashSet<String>()
        kept.forEach { keptDirNames.addAll(dirNames(it)) }
        if (keptDirNames.isEmpty()) return toDelete

        return toDelete.filterNot { chapter -> dirNames(chapter).any { it in keptDirNames } }
    }
}
// KUN <--
