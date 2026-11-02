package eu.kanade.tachiyomi.data.download

import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import java.util.TreeMap

/**
 * Finds downloaded files that no chapter can reach any more, for the manual scan screen.
 *
 * Reachability is the same rule the automatic cleanup uses: a file or folder under a manga's download
 * directory is kept when its on-disk name is one of the names
 * [DownloadProvider.getValidChapterDirNames] produces for *any* chapter of that manga, so a
 * replacement chapter with the same name keeps its file.
 *
 * Nothing here deletes on its own: [delete] only removes what the caller selected.
 */
@Inject
@SingleIn(AppScope::class)
class OrphanedDownloadScanner(
    private val provider: DownloadProvider,
    private val storageManager: StorageManager,
    private val sourceManager: SourceManager,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val downloadCache: DownloadCache,
) {

    enum class Kind {
        /** A single chapter's file or folder. */
        CHAPTER,

        /** A whole manga folder whose name maps to no manga of its source. */
        MANGA,

        /** A whole source folder whose name maps to no installed source. */
        SOURCE,
    }

    /**
     * @param id the stable identity of the entry, which is its [relativePath]; UI selection uses it.
     * @param relativePath its path relative to the downloads root, with `/` separators.
     * @param sizeBytes the total size of the files under it.
     */
    data class OrphanedDownload(
        val id: String,
        val relativePath: String,
        val sizeBytes: Long,
        val kind: Kind,
        val file: UniFile,
    )

    private class SourceFolder(val dir: UniFile, val name: String, val mangaDirs: List<UniFile>)

    /**
     * Walks the downloads tree and returns everything no chapter can reach.
     *
     * An empty downloads directory yields an empty list, but a downloads directory that cannot be
     * read at all — a revoked storage access grant, a removed SD card — throws instead: reporting it
     * as "nothing orphaned" would hide every file behind it.
     *
     * [onProgress] is called with the number of manga folders (or unmatched source folders) handled
     * so far, so the caller can show a determinate bar; `total` is 0 when there is nothing to walk.
     */
    suspend fun scan(onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> }): List<OrphanedDownload> =
        withContext(Dispatchers.IO) {
            val root = downloadsRoot()

            // Every installed source the download tree could belong to, keyed by its folder name. More
            // than one source can produce the same name, and their chapters may share the folder.
            // Folder identity ignores case, like the volume the files live on: a case-insensitive
            // volume folds two such folders into one, so an exact-name lookup would miss the source
            // and its chapters would be served as orphans.
            val sourcesByDirName = TreeMap<String, MutableList<Source>>(String.CASE_INSENSITIVE_ORDER)
                .apply {
                    sourceManager.getAll().forEach { source ->
                        getOrPut(provider.getSourceDirName(source)) { mutableListOf() } += source
                    }
                }

            // A root that cannot be listed is the same failure as a missing root: `orEmpty()` here
            // would report a full download tree as empty.
            val sourceFolders = (root.listFiles() ?: throw downloadsUnavailable())
                .filter { it.isDirectory && it.name != null }
                .map { dir ->
                    val name = dir.name!!
                    val mangaDirs = dir.listFiles().orEmpty()
                        .filter {
                            it.isDirectory && it.name?.endsWith(Downloader.TMP_DIR_SUFFIX) == false
                        }
                    SourceFolder(dir, name, mangaDirs)
                }

            // Every walked entry counts exactly once, so `scanned` can actually reach `total`: an
            // unmatched source folder is one step (its manga folders are never walked), and each
            // manga folder of a matched source is one step — including one whose name cannot be read.
            val total = sourceFolders.sumOf { folder ->
                if (folder.name !in sourcesByDirName) 1 else folder.mangaDirs.size
            }
            var scanned = 0
            onProgress(scanned, total)

            val orphans = mutableListOf<OrphanedDownload>()
            for (folder in sourceFolders) {
                ensureActive()
                if (folder.name !in sourcesByDirName) {
                    // Never guessed at, and never removed automatically: a source that is merely
                    // uninstalled right now still owns these files.
                    orphans += folder.dir.toOrphan(folder.name, Kind.SOURCE)
                    scanned++
                    onProgress(scanned, total)
                    continue
                }

                val mangasByDirName = mangasByDirName(sourcesByDirName.getValue(folder.name))
                for (mangaDir in folder.mangaDirs) {
                    ensureActive()
                    val mangaDirName = mangaDir.name
                    if (mangaDirName != null) {
                        val relativePath = "${folder.name}/$mangaDirName"
                        val mangas = mangasByDirName[mangaDirName]
                        if (mangas.isNullOrEmpty()) {
                            orphans += mangaDir.toOrphan(relativePath, Kind.MANGA)
                        } else {
                            val reachable = reachableFileNames(mangas)
                            // An entry with a temporary name is a download in progress, never a
                            // candidate. One whose name cannot be read is left out of the pairs and
                            // therefore kept, like the pure filter does.
                            val entries = mangaDir.listFiles().orEmpty()
                                .mapNotNull { entry ->
                                    entry.name
                                        ?.takeUnless { it.endsWith(Downloader.TMP_DIR_SUFFIX) }
                                        ?.let { entry to it }
                                }
                            val orphanedEntries = OrphanedDownloads.orphaned(entries, { it.second }, reachable)
                            orphanedEntries.forEach { (entry, name) ->
                                orphans += entry.toOrphan("$relativePath/$name", Kind.CHAPTER)
                            }
                        }
                    }
                    scanned++
                    onProgress(scanned, total)
                }
            }
            orphans
        }

    /**
     * Deletes [items] and prunes the manga and source folders that are now empty.
     *
     * Every entry was produced by [scan], so the only guard needed is that it still sits under the
     * downloads root. The download cache is invalidated once at the end: a targeted removal would also
     * evict a surviving chapter's entry.
     *
     * A cancelled call has already deleted some entries, so the invalidation is not cancellable: it
     * runs in a `finally` even when the caller was cancelled, or the cache would keep serving entries
     * whose files are gone.
     *
     * @return how many entries could be deleted.
     * @throws IllegalStateException when the downloads directory is unavailable, since nothing can be
     * deleted then and reporting `0` would look like the caller's selection was empty.
     */
    suspend fun delete(items: List<OrphanedDownload>): Int = withContext(Dispatchers.IO) {
        val root = downloadsRoot()
        val rootUri = root.uri.toString()

        var deleted = 0
        try {
            items.forEach { item ->
                ensureActive()
                if (item.file.uri.toString() == rootUri || !item.file.isUnder(rootUri)) {
                    logcat(LogPriority.ERROR) { "Refusing to delete ${item.file.uri} outside the downloads root" }
                    return@forEach
                }
                val parent = item.file.parentFile
                if (item.file.delete()) {
                    deleted++
                    pruneEmptyDirectories(parent, rootUri)
                }
            }
        } finally {
            withContext(NonCancellable) { downloadCache.invalidateCache() }
        }
        deleted
    }

    /**
     * Whether this file is the [rootUri] directory or lives under it.
     *
     * Walked by identity (`parentFile` up to the root), never by string prefix: on a storage access
     * framework tree the children are `<root>/document/…%2F…`, so a `startsWith` test would refuse
     * every folder the user picked.
     */
    private fun UniFile.isUnder(rootUri: String): Boolean {
        var current: UniFile? = this
        while (current != null) {
            if (current.uri.toString() == rootUri) return true
            current = current.parentFile
        }
        return false
    }

    private fun pruneEmptyDirectories(start: UniFile?, rootUri: String) {
        var dir = start
        while (dir != null) {
            // Stop at the downloads root: it is never pruned, and nothing above it is ours.
            if (dir.uri.toString() == rootUri) return
            if (!dir.isUnder(rootUri)) return
            if (!dir.isDirectory || !dir.listFiles().orEmpty().isEmpty()) return
            dir.delete()
            dir = dir.parentFile
        }
    }

    /**
     * The manga of [sources], keyed by the folder name each one's downloads live under.
     *
     * The key is compared ignoring case for the same reason the source names are: the folder is one
     * on a case-insensitive volume, so every manga that resolves to it has to be found.
     */
    private suspend fun mangasByDirName(sources: List<Source>): Map<String, List<Manga>> {
        val byDirName = TreeMap<String, MutableList<Manga>>(String.CASE_INSENSITIVE_ORDER)
        sources.forEach { source ->
            mangaRepository.getMangaBySourceId(source.id).forEach { manga ->
                byDirName.getOrPut(provider.getMangaDirName(manga.ogTitle)) { mutableListOf() } += manga
            }
        }
        return byDirName
    }

    private suspend fun reachableFileNames(mangas: List<Manga>): Set<String> {
        val chapters = mangas.flatMap { manga -> chapterRepository.getChapterByMangaId(manga.id) }
        return OrphanedDownloads.reachableFileNames(chapters, provider::getValidChapterDirNames)
    }

    /**
     * The downloads root, or a failure when it cannot be read.
     *
     * The ViewModel maps this to its scan/delete error state; silently reporting "nothing here"
     * would present a revoked grant or a removed SD card as a clean download tree.
     */
    private fun downloadsRoot(): UniFile {
        return storageManager.getDownloadsDirectory() ?: throw downloadsUnavailable()
    }

    private fun downloadsUnavailable() = IllegalStateException("Downloads directory unavailable")

    private suspend fun UniFile.toOrphan(relativePath: String, kind: Kind): OrphanedDownload {
        return OrphanedDownload(
            id = relativePath,
            relativePath = relativePath,
            sizeBytes = measure(),
            kind = kind,
            file = this,
        )
    }

    /**
     * The total size of this entry, walking a directory's subtree.
     *
     * Cancellable at every directory: a large subtree is walked file by file, and leaving the screen
     * must stop it instead of freezing the progress bar until the walk finishes.
     */
    private suspend fun UniFile.measure(): Long {
        currentCoroutineContext().ensureActive()
        if (!isDirectory) return length()
        var sizeBytes = 0L
        listFiles().orEmpty().forEach { child ->
            sizeBytes += child.measure()
        }
        return sizeBytes
    }
}
