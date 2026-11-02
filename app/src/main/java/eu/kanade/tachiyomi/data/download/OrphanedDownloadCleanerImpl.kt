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
            if (!OrphanedDownloads.shouldRun(downloadPreferences.removeOrphanedDownloads().get(), removedChapters)) {
                return@runCatching
            }
            // An unavailable extension cannot produce the chapter dir names, so we cannot tell an
            // orphan from a file a surviving chapter still uses. Do nothing rather than guess.
            val source = sourceManager.get(manga.source) ?: return@runCatching
            // Read the survivors here, after the caller's transaction committed, so the removed rows
            // are really gone and a replacement chapter is really in. The download folder is
            // `<manga dir name>`, which is not unique per manga: two entries of the same source with
            // the same title share it, so every manga resolving to this folder has to keep its files.
            // Folder identity ignores case: on a case-insensitive volume two titles differing only in
            // case share one folder, and the other one's chapters are just as live.
            val folder = downloadProvider.getMangaDirName(manga.ogTitle)
            val remainingChapters = mangaRepository.getMangaBySourceId(manga.source)
                .filter { downloadProvider.getMangaDirName(it.ogTitle).equals(folder, ignoreCase = true) }
                .flatMap { chapterRepository.getChapterByMangaId(it.id) }
            downloadManager.deleteOrphanedChapters(removedChapters, remainingChapters, manga, source)
        }.onFailure { e ->
            if (e is CancellationException) throw e
            xLogE("Failed to clean up orphaned downloads of manga ${manga.id}", e)
        }
    }
}
