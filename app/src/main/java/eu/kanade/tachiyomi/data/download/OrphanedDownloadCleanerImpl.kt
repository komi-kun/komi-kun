package eu.kanade.tachiyomi.data.download

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import exh.log.xLogE
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.download.service.OrphanedDownloadCleaner
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager

/**
 * App-scoped implementation of [OrphanedDownloadCleaner]: reads the surviving chapter list and hands
 * the actual file work to [DownloadManager].
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OrphanedDownloadCleanerImpl(
    private val downloadPreferences: DownloadPreferences,
    private val downloadManager: DownloadManager,
    private val chapterRepository: ChapterRepository,
    private val mangaRepository: MangaRepository,
    private val downloadProvider: DownloadProvider,
    private val sourceManager: SourceManager,
) : OrphanedDownloadCleaner {

    override suspend fun cleanupAfterChapterRemoval(removedChapters: List<Chapter>, manga: Manga) {
        // Never let a filesystem or extension failure break a chapter-list sync or a sync restore:
        // the worst case is that the orphaned files stay on disk a while longer. A cancelled sync or
        // restore is not a failure though: it must stop deleting and propagate.
        runCatching {
            if (!OrphanedDownloads.shouldRun(downloadPreferences.removeOrphanedDownloads.get(), removedChapters)) {
                return@runCatching
            }
            // An unavailable extension cannot produce the chapter dir names, so we cannot tell an
            // orphan from a file a surviving chapter still uses. Do nothing rather than guess.
            val source = sourceManager.get(manga.source) ?: return@runCatching
            // A removed chapter whose file must be kept (a bookmarked one while bookmark retention is
            // off) is never handed over for deletion: `getChaptersToDelete` keeps it for the same reason.
            val removeBookmarkedChapters = downloadPreferences.removeBookmarkedChapters.get()
            val removableChapters = OrphanedDownloads.removableChapters(removedChapters, removeBookmarkedChapters)
            if (removableChapters.isEmpty()) return@runCatching
            // The kept ones still name files that must survive, so they join the survivor set below:
            // two removed chapters can resolve to the same directory (duplicate chapter names; the url
            // hash is off by default), and the kept chapter's own file is that directory's file. Leaving
            // it out would let the other removed chapter's deletion take the bookmarked chapter's file.
            val keptChapters = if (removeBookmarkedChapters) {
                emptyList()
            } else {
                removedChapters.filter { it.bookmark }
            }
            // Read the survivors here, after the caller's transaction committed, so the removed rows
            // are really gone and a replacement chapter is really in. The folder is
            // `<source dir name>/<manga dir name>`, and neither name is unique: two installed
            // extensions can render the same source dir name (their display names can be identical,
            // or identical after `buildValidFilename` truncates them), and two manga entries of one
            // source can share a manga dir name. Every manga resolving to this folder therefore
            // keeps its files. Folder identity ignores case, exactly like `OrphanedDownloadScanner`:
            // on a case-insensitive volume two names differing only in case are one folder, and the
            // other one's chapters are just as live.
            val sourceDirName = downloadProvider.getSourceDirName(source)
            val mangaDirName = downloadProvider.getMangaDirName(manga.ogTitle)
            val survivorIds = sourceManager.getAll()
                .filter { downloadProvider.getSourceDirName(it).equals(sourceDirName, ignoreCase = true) }
                .flatMap { mangaRepository.getMangaBySourceId(it.id) }
                .filter { downloadProvider.getMangaDirName(it.ogTitle).equals(mangaDirName, ignoreCase = true) }
                .map { it.id }
                .distinct()
            // One bulk read for every manga of the folder, not one query per manga. With no survivor
            // there is nothing to keep the files for, and an empty id list must not reach SQL — the
            // generated query cannot bind `IN ()`.
            val remainingChapters = if (survivorIds.isEmpty()) {
                keptChapters
            } else {
                chapterRepository.getChaptersByMangaIds(survivorIds) + keptChapters
            }
            downloadManager.deleteOrphanedChapters(removableChapters, remainingChapters, manga, source)
        }.onFailure { e ->
            if (e is CancellationException) throw e
            xLogE("Failed to clean up orphaned downloads of manga ${manga.id}", e)
        }
    }
}
