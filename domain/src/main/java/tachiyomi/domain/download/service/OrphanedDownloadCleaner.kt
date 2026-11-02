package tachiyomi.domain.download.service

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * Deletes the downloaded files of chapters that no longer exist in the database.
 *
 * Implemented in the app module, where the download tree lives.
 */
interface OrphanedDownloadCleaner {

    /**
     * Removes the downloaded files of [removedChapters] that no surviving chapter of [manga] can
     * still resolve to.
     *
     * Must be called **after** the removed chapter rows are actually gone (i.e. after the caller's
     * database transaction has committed): the implementation re-reads the surviving chapter list
     * itself, and a row that still exists would make it keep the file.
     */
    suspend fun cleanupAfterChapterRemoval(removedChapters: List<Chapter>, manga: Manga)
}
