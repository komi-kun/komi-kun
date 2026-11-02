package eu.kanade.tachiyomi.data.download

import tachiyomi.domain.chapter.model.Chapter
import java.util.TreeSet

/**
 * The decision half of the orphaned-download cleanup, kept free of Android and [com.hippo.unifile.UniFile]
 * types so it can be unit tested on the JVM.
 */
object OrphanedDownloads {

    /**
     * Every file/folder name the given chapters can resolve to on disk.
     *
     * A removed chapter's file is only orphaned when none of these names matches it: a replacement
     * chapter with the same name, scanlator and url hash legitimately reuses the same file.
     *
     * [namesOf] receives the three chapter fields `DownloadProvider.getValidChapterDirNames` takes —
     * name, scanlator, url, in that order — so a call site can pass the method reference instead of
     * repeating the field order, where a swap would silently produce names nothing ever matches.
     *
     * The set compares names **ignoring case**, exactly like [DownloadProvider.findChapterDirs]
     * resolves them: a case-insensitive volume or a storage access framework tree hands `X.cbz` to
     * a chapter named `x.cbz`, so an exact comparison would delete a file a survivor still reaches.
     */
    fun reachableFileNames(
        chapters: List<Chapter>,
        namesOf: (name: String, scanlator: String?, url: String) -> List<String>,
    ): Set<String> {
        val names = TreeSet(String.CASE_INSENSITIVE_ORDER)
        chapters.forEach { chapter ->
            names += namesOf(chapter.name, chapter.scanlator, chapter.url)
        }
        return names
    }

    /**
     * Whether the on-disk name [fileName] is reachable by no surviving chapter, and may be deleted.
     *
     * A `null` name means the name could not be read at all, which is never a reason to delete: an
     * unknown entry is kept.
     */
    fun isOrphaned(fileName: String?, reachable: Set<String>): Boolean {
        return fileName != null && fileName !in reachable
    }

    /**
     * The entries of [items] that no surviving chapter can reach any more.
     *
     * The single deletion decision of the cleanup: the automatic path hands it the chapter directories
     * of a removed chapter ([nameOf] = `UniFile::name`) and the manual scan hands it the entries of a
     * manga folder, so both delete exactly when no name in [reachable] matches. An entry whose name
     * cannot be read returns `null` and is kept.
     */
    fun <T> orphaned(items: List<T>, nameOf: (T) -> String?, reachable: Set<String>): List<T> {
        return items.filter { item -> isOrphaned(nameOf(item), reachable) }
    }

    /** Whether a cleanup is worth attempting: the feature is on and something was actually removed. */
    fun shouldRun(enabled: Boolean, removedChapters: List<Chapter>): Boolean {
        return enabled && removedChapters.isNotEmpty()
    }

    /**
     * The removed chapters whose files may be deleted.
     *
     * A removed chapter that is still bookmarked keeps its file while bookmarked chapters are
     * protected, exactly as `DownloadManager.getChaptersToDelete` decides for the removal flows.
     * Category exclusion is deliberately not applied here: the chapters are already gone, so their
     * categories no longer say anything about what the user wants to keep.
     */
    fun removableChapters(removedChapters: List<Chapter>, removeBookmarkedChapters: Boolean): List<Chapter> {
        return if (removeBookmarkedChapters) removedChapters else removedChapters.filterNot { it.bookmark }
    }
}
