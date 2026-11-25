package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.util.AtomicFile
import androidx.core.net.toUri
import androidx.core.util.writeBytes
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Cache where we dump the downloads directory from the filesystem. This class is needed because
 * directory checking is expensive and it slows down the app. The cache is invalidated by the time
 * defined in [renewInterval] as we don't have any control over the filesystem and the user can
 * delete the folders at any time without the app noticing.
 */
@Inject
@SingleIn(AppScope::class)
class DownloadCache(
    @AppCoroutineScope private val scope: CoroutineScope,
    private val context: Context,
    private val provider: DownloadProvider,
    private val sourceManager: SourceManager,
    private val extensionManager: ExtensionManager,
    private val storageManager: StorageManager,
    // KMK -->
    private val downloadPreferences: DownloadPreferences,
    // KMK <--
) {

    private val _changes: Channel<Unit> = Channel(Channel.UNLIMITED)
    val changes = _changes.receiveAsFlow()
        .onStart { emit(Unit) }
        .shareIn(scope, SharingStarted.Lazily, 1)

    /**
     * The interval after which this cache should be invalidated. 1 hour shouldn't cause major
     * issues, as the cache is only used for UI feedback.
     */
    private val renewInterval // = 1.hours.inWholeMilliseconds
        // KMK -->
        get() = downloadPreferences.downloadCacheRenewInterval.get()
            .hours.inWholeMilliseconds
    // KMK <--

    /**
     * The last time the cache was refreshed.
     */
    private var lastRenew = 0L
    private var renewalJob: Job? = null
    private val initJob: Job

    private val _isInitializing = MutableStateFlow(false)
    val isInitializing = _isInitializing
        .debounce(1.seconds) // Don't notify if it finishes quickly enough
        .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    private val diskCacheFile: AtomicFile
        get() = AtomicFile(File(context.cacheDir, "dl_index_cache_v3"))

    private val rootDownloadsDirMutex = Mutex()

    @Volatile
    private var rootDownloadsDir = RootDirectory(null)

    // KUN -->
    /**
     * Set where the index is really filled in — the disk-cache load in `init` and the walk in
     * [renewCache] — and never cleared: an index that has been built once stays usable (stale at
     * worst) for the rest of the process, while the [RootDirectory] stub the class starts with is
     * exactly the "no index" state this reports. It is not derived from the index being empty, so a
     * genuinely empty library counts as built.
     *
     * Deliberately not `lastRenew != 0L`: that stamp is also written by [renewCache]'s completion
     * handler for a renewal that *failed*, which leaves the empty stub in place — reporting that as
     * "built" is the false positive a caller trusting an empty index must not get.
     */
    @Volatile
    private var indexBuilt = false

    /**
     * Whether something in this process has built the downloads index, i.e. whether the lookups that
     * never renew ([getMangaWithDownloads], [getDownloadedChapterIdsNoRenew]) see real data rather
     * than the empty stub. A pure read: it never touches the filesystem and never starts a build.
     */
    fun isIndexBuilt(): Boolean = indexBuilt
    // KUN <--

    init {
        // Attempt to read cache file
        initJob = scope.launch(Dispatchers.IO) {
            rootDownloadsDirMutex.withLock {
                try {
                    if (diskCacheFile.baseFile.exists()) {
                        val diskCache = diskCacheFile.openRead().use {
                            ProtoBuf.decodeFromByteArray<RootDirectory>(it.readBytes())
                        }
                        // KMK -->
                        // The disk cache stores a folder shared by several source ids once per id, so link
                        // those ids to one copy again, as renewCache does
                        val sourceDirsByUri = diskCache.sourceDirs.values
                            .mapNotNull { sourceDir -> sourceDir.dir?.uri?.let { it to sourceDir } }
                            .toMap()
                        diskCache.sourceDirs = diskCache.sourceDirs.mapValues { (_, sourceDir) ->
                            sourceDir.dir?.uri?.let { sourceDirsByUri[it] } ?: sourceDir
                        }
                        // KMK <--
                        rootDownloadsDir = diskCache
                        lastRenew = System.currentTimeMillis()
                        // KUN -->
                        // Loaded from disk, so the lookups above have real data from here on.
                        indexBuilt = true
                        // KUN <--
                    }
                } catch (e: Throwable) {
                    logcat(LogPriority.ERROR, e) { "Failed to initialize from disk cache" }
                    diskCacheFile.delete()
                }
            }
        }

        storageManager.changes
            .onEach { invalidateCache() }
            .launchIn(scope)
    }

    /**
     * Returns the ids of the downloaded chapters among the given chapters of one manga.
     *
     * @param chapters the chapters to query, all of the same manga.
     * @param mangaTitle the title of the manga to query.
     * @param sourceId the id of the source of the chapters.
     */
    fun getDownloadedChapterIds(chapters: List<Chapter>, mangaTitle: String, sourceId: Long): Set<Long> {
        renewCache()

        val mangaDir = rootDownloadsDir.sourceDirs[sourceId]?.mangaDirs?.get(provider.getMangaDirName(mangaTitle))
        if (mangaDir == null || mangaDir.chapterDirs.isEmpty()) return emptySet()

        return chapters
            .filter { chapter ->
                provider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url)
                    .any { it in mangaDir.chapterDirs }
            }
            .mapTo(HashSet()) { it.id }
    }

    /**
     * Returns true if the chapter is downloaded.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query
     * @param chapterUrl the url of the chapter to query
     * @param mangaTitle the title of the manga to query.
     * @param sourceId the id of the source of the chapter.
     */
    fun isChapterDownloaded(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        mangaTitle: String,
        sourceId: Long,
    ): Boolean {
        renewCache()

        val sourceDir = rootDownloadsDir.sourceDirs[sourceId]
        if (sourceDir != null) {
            val mangaDir = sourceDir.mangaDirs[provider.getMangaDirName(mangaTitle)]
            if (mangaDir != null) {
                return provider.getValidChapterDirNames(
                    chapterName,
                    chapterScanlator,
                    chapterUrl,
                ).any { it in mangaDir.chapterDirs }
            }
        }
        return false
    }

    /**
     * Returns the amount of downloaded chapters.
     */
    fun getTotalDownloadCount(): Int {
        renewCache()

        // The disk index stores a folder shared by several ids once per id
        return rootDownloadsDir.sourceDirs.values.distinctBy { it.dir?.uri }.sumOf { sourceDir ->
            sourceDir.mangaDirs.values.sumOf { mangaDir ->
                mangaDir.chapterDirs.size
            }
        }
    }

    /**
     * Returns the amount of downloaded chapters for a manga.
     * This method is quick, but might count other junk files
     * It's still maybe useful while developing the clean-up features
     *
     * @param manga the manga to check.
     */
    fun getDownloadCount(manga: Manga): Int {
        renewCache()

        val sourceDir = rootDownloadsDir.sourceDirs[manga.source]
        if (sourceDir != null) {
            val mangaDir = sourceDir.mangaDirs[
                provider.getMangaDirName(/* SY --> */ manga.ogTitle /* SY <-- */),
            ]
            if (mangaDir != null) {
                return mangaDir.chapterDirs.size
            }
        }
        return 0
    }

    /**
     * Returns the amount of downloaded chapters for each of the given manga, keyed by manga id.
     *
     * @param manga the manga to check.
     */
    fun getDownloadCounts(manga: List<Manga>): Map<Long, Int> {
        renewCache()

        val sourceDirs = rootDownloadsDir.sourceDirs
        return manga.associate {
            val mangaDirs = sourceDirs[it.source]?.mangaDirs
            // Building the directory name is the costly part, so it's skipped for sources without downloads
            val mangaDir = if (mangaDirs.isNullOrEmpty()) {
                null
            } else {
                mangaDirs[provider.getMangaDirName(/* KMK --> */ it.ogTitle /* KMK <-- */)]
            }
            it.id to (mangaDir?.chapterDirs?.size ?: 0)
        }
    }

    // KUN -->
    /**
     * The [mangas] that currently have at least one chapter entry in the already-built index.
     *
     * Unlike the lookups above this never starts a cache renewal: a periodic background sweep must not
     * turn itself into a filesystem walk, and it runs on the index as it is. A stale index is safe for
     * that caller — it can only hide a manga that has downloads, and the delete path re-derives the
     * directory names of the specific chapters it deletes, so a stale entry retains a file and never
     * deletes one.
     *
     * @param mangas the mangas to check.
     */
    fun getMangaWithDownloads(mangas: List<Manga>): List<Manga> {
        val sourceDirs = rootDownloadsDir.sourceDirs
        return mangas.filter { manga ->
            val mangaDirs = sourceDirs[manga.source]?.mangaDirs
            // Building the directory name is the costly part, so it's skipped for sources without downloads
            if (mangaDirs.isNullOrEmpty()) {
                false
            } else {
                mangaDirs[provider.getMangaDirName(/* SY --> */ manga.ogTitle /* SY <-- */)]
                    ?.chapterDirs?.isNotEmpty() == true
            }
        }
    }

    /**
     * The ids of the downloaded chapters among [chapters] of [manga], read from the index as it stands.
     *
     * Same lookup as [getDownloadedChapterIds], but this one never calls `renewCache()` — it exists for
     * the periodic sweep of filtered downloads, which runs hourly and would otherwise rebuild the whole
     * downloads tree on almost every run (`renewInterval` defaults to one hour).
     *
     * Running on a stale index is safe for that caller: a stale or not-yet-built index can only
     * *under-report* downloads, which can only skip queueing a chapter or drop a queue row — both of
     * which merely postpone a deletion — and the delete path re-derives the directory name of the
     * specific chapter it deletes, so nothing here can cause a file to be deleted. If nothing in this
     * process has built the index yet, the sweep simply finds nothing and the next pass (or any screen
     * that renews it) picks the work up.
     *
     * @param chapters the chapters of [manga] to look for.
     * @param manga the manga of the chapters.
     */
    fun getDownloadedChapterIdsNoRenew(chapters: List<Chapter>, manga: Manga): List<Long> {
        val chapterDirs = rootDownloadsDir.sourceDirs[manga.source]
            ?.mangaDirs?.get(provider.getMangaDirName(/* SY --> */ manga.ogTitle /* SY <-- */))
            ?.chapterDirs
        if (chapterDirs.isNullOrEmpty()) return emptyList()

        return chapters
            .filter { chapter ->
                provider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url)
                    .any { it in chapterDirs }
            }
            .map { it.id }
    }
    // KUN <--

    /**
     * Adds a chapter that has just been download to this cache.
     *
     * @param chapterDirName the downloaded chapter's directory name.
     * @param mangaUniFile the directory of the manga.
     * @param manga the manga of the chapter.
     */
    suspend fun addChapter(chapterDirName: String, mangaUniFile: UniFile, manga: Manga) {
        rootDownloadsDirMutex.withLock {
            // Retrieve the cached source directory or cache a new one
            var sourceDir = rootDownloadsDir.sourceDirs[manga.source]
            if (sourceDir == null) {
                val source = sourceManager.get(manga.source) ?: return
                val sourceUniFile = provider.findSourceDir(source) ?: return
                sourceDir = SourceDirectory(sourceUniFile)
                rootDownloadsDir.sourceDirs += manga.source to sourceDir
            }

            // Retrieve the cached manga directory or cache a new one
            val mangaDirName = provider.getMangaDirName(/* SY --> */ manga.ogTitle /* SY <-- */)
            var mangaDir = sourceDir.mangaDirs[mangaDirName]
            if (mangaDir == null) {
                mangaDir = MangaDirectory(mangaUniFile)
                sourceDir.mangaDirs += mangaDirName to mangaDir
            }

            // Save the chapter directory
            mangaDir.chapterDirs += chapterDirName
        }

        notifyChanges()
    }

    /**
     * Removes a chapter that has been deleted from this cache.
     *
     * @param chapter the chapter to remove.
     * @param manga the manga of the chapter.
     */
    suspend fun removeChapter(chapter: Chapter, manga: Manga) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val mangaDir = sourceDir.mangaDirs[
                provider.getMangaDirName(
                    // SY -->
                    manga.ogTitle,
                    // SY <--
                ),
            ] ?: return
            mangaDir.chapterDirs -=
                provider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url).toSet()
        }

        notifyChanges()
    }

    // SY -->
    suspend fun removeFolders(folders: List<String>, manga: Manga) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val mangaDir = sourceDir.mangaDirs[provider.getMangaDirName(manga.ogTitle)] ?: return
            folders.forEach { chapter ->
                if (chapter in mangaDir.chapterDirs) {
                    mangaDir.chapterDirs -= chapter
                }
            }
        }
    }

    // SY <--

    /**
     * Removes a list of chapters that have been deleted from this cache.
     *
     * @param chapters the list of chapter to remove.
     * @param manga the manga of the chapter.
     */
    suspend fun removeChapters(chapters: List<Chapter>, manga: Manga) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val mangaDir = sourceDir.mangaDirs[
                provider.getMangaDirName(
                    // SY -->
                    manga.ogTitle,
                    // SY <--
                ),
            ] ?: return
            mangaDir.chapterDirs -= chapters
                .flatMap { provider.getValidChapterDirNames(it.name, it.scanlator, it.url) }
                .toSet()
        }

        notifyChanges()
    }

    /**
     * Removes a manga that has been deleted from this cache.
     *
     * @param manga the manga to remove.
     */
    suspend fun removeManga(manga: Manga) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val mangaDirName = provider.getMangaDirName(/* SY --> */ manga.ogTitle /* SY <-- */)
            if (sourceDir.mangaDirs.containsKey(mangaDirName)) {
                sourceDir.mangaDirs -= mangaDirName
            }
        }

        notifyChanges()
    }

    /**
     * Renames a manga in this cache.
     *
     * @param manga the manga being renamed.
     * @param mangaUniFile the manga's new directory.
     * @param newTitle the manga's new title.
     */
    suspend fun renameManga(manga: Manga, mangaUniFile: UniFile, newTitle: String) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val oldMangaDirName = provider.getMangaDirName(/* KMK --> */ manga.ogTitle /* KMK <-- */)
            var oldChapterDirs: Set<String>? = null
            // Save the old name's cached chapter dirs
            if (sourceDir.mangaDirs.containsKey(oldMangaDirName)) {
                oldChapterDirs = sourceDir.mangaDirs[oldMangaDirName]?.chapterDirs
                sourceDir.mangaDirs -= oldMangaDirName
            }

            // Retrieve/create the cached manga directory for new name
            val newMangaDirName = provider.getMangaDirName(newTitle)
            var mangaDir = sourceDir.mangaDirs[newMangaDirName]
            if (mangaDir == null) {
                mangaDir = MangaDirectory(mangaUniFile)
                sourceDir.mangaDirs += newMangaDirName to mangaDir
            }

            // Add the old chapters to new name's cache
            if (!oldChapterDirs.isNullOrEmpty()) {
                mangaDir.chapterDirs += oldChapterDirs
            }
        }

        notifyChanges()
    }

    suspend fun removeSource(source: Source) {
        rootDownloadsDirMutex.withLock {
            rootDownloadsDir.sourceDirs -= source.id
        }

        notifyChanges()
    }

    fun invalidateCache() {
        lastRenew = 0L
        renewalJob?.cancel()
        renewCache(
            // KMK -->
            renewInterval = 0L,
            // KMK <--
            deleteDiskCache = true,
        )
    }

    // KMK -->
    fun cleanInvalidDownloads() {
        lastRenew = 0L
        renewalJob?.cancel()
        renewCache(
            // KMK -->
            renewInterval = 0L,
            // KMK <--
            deleteDiskCache = true,
        )
    }
    // KMK <--

    /**
     * Renews the downloads cache.
     */
    private fun renewCache(
        // KMK -->
        renewInterval: Long = this.renewInterval,
        // KMK <--
        deleteDiskCache: Boolean = false,
    ) {
        // Avoid renewing cache if in the process nor too often
        if (lastRenew + renewInterval >= System.currentTimeMillis() ||
            // KMK -->
            renewInterval < 0L ||
            // KMK <--
            renewalJob?.isActive == true
        ) {
            return
        }

        renewalJob = scope.launch(Dispatchers.IO) {
            // The disk index is older than this scan and would replace it if read afterwards
            initJob.join()
            if (deleteDiskCache) diskCacheFile.delete()

            if (lastRenew == 0L) {
                _isInitializing.emit(true)
            }

            // Try to wait until extensions and sources have loaded
            // SY -->
            var sources = emptyList<Source>()
            withTimeoutOrNull(30.seconds) {
                sources = getSources()
            }
            // SY <--

            // A stored stub can share its name with an installed source, so a folder can belong to several ids
            val sourceIdsByDirName = sources.groupBy({ provider.getSourceDirName(it).lowercase() }, { it.id })

            rootDownloadsDirMutex.withLock {
                val updatedRootDir = RootDirectory(storageManager.getDownloadsDirectory())

                updatedRootDir.sourceDirs = updatedRootDir.dir?.listFiles().orEmpty()
                    .filter { it.isDirectory && !it.name.isNullOrBlank() }
                    .flatMap { dir ->
                        val sourceDir = SourceDirectory(dir)
                        sourceIdsByDirName[dir.name!!.lowercase()].orEmpty().map { it to sourceDir }
                    }
                    .toMap()

                updatedRootDir.sourceDirs.values.distinct().map { sourceDir ->
                    async {
                        sourceDir.mangaDirs = sourceDir.dir?.listFiles().orEmpty()
                            .filter { it.isDirectory && !it.name.isNullOrBlank() }
                            .associate { it.name!! to MangaDirectory(it) }

                        sourceDir.mangaDirs.values.forEach { mangaDir ->
                            val chapterDirs = mangaDir.dir?.listFiles().orEmpty()
                                .mapNotNull {
                                    when {
                                        // Ignore incomplete downloads
                                        it.name?.endsWith(Downloader.TMP_DIR_SUFFIX) == true -> null
                                        // Folder of images
                                        it.isDirectory -> it.name
                                        // CBZ files
                                        it.isFile && it.extension == "cbz" -> it.nameWithoutExtension
                                        // Anything else is irrelevant
                                        else -> null
                                    }
                                }
                                .toSet()

                            mangaDir.chapterDirs = chapterDirs
                        }
                    }
                }
                    .awaitAll()

                rootDownloadsDir = updatedRootDir
                // KUN -->
                // The walk completed, so the index is built even if the tree turned out to be empty.
                indexBuilt = true
                // KUN <--
            }

            _isInitializing.emit(false)
        }.also {
            it.invokeOnCompletion(onCancelling = true) { exception ->
                if (exception != null && exception !is CancellationException) {
                    logcat(LogPriority.ERROR, exception) { "DownloadCache: failed to create cache" }
                }
                // A cancelled renewal (e.g. from invalidateCache) must not hold off the one replacing it
                if (exception !is CancellationException) lastRenew = System.currentTimeMillis()
                notifyChanges()
            }
        }

        // Mainly to notify the indexing notifier UI
        notifyChanges()
    }

    private suspend fun getSources(): List<Source> {
        // SY -->
        return sourceManager.getVisibleOnlineSources() + sourceManager.getStubSources()
        // SY <--
    }

    private fun notifyChanges() {
        _changes.trySend(Unit)
        updateDiskCache()
    }

    private var updateDiskCacheJob: Job? = null
    private fun updateDiskCache() {
        updateDiskCacheJob?.cancel()
        updateDiskCacheJob = scope.launch(Dispatchers.IO) {
            delay(1.seconds)
            val bytes = rootDownloadsDirMutex.withLock {
                ProtoBuf.encodeToByteArray(rootDownloadsDir)
            }
            ensureActive()
            try {
                diskCacheFile.writeBytes(bytes)
            } catch (e: Throwable) {
                logcat(
                    priority = LogPriority.ERROR,
                    throwable = e,
                    message = { "Failed to write disk cache file" },
                )
            }
        }
    }
}

/**
 * Class to store the files under the root downloads directory.
 */
@Serializable
private class RootDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    @Volatile
    var sourceDirs: Map<Long, SourceDirectory> = mapOf(),
)

/**
 * Class to store the files under a source directory.
 */
@Serializable
private class SourceDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    @Volatile
    var mangaDirs: Map<String, MangaDirectory> = mapOf(),
)

/**
 * Class to store the files under a manga directory.
 */
@Serializable
private class MangaDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    // Replaced rather than changed in place, so the lock-free lookups always read a consistent set
    @Volatile
    var chapterDirs: Set<String> = emptySet(),
)

private object UniFileAsStringSerializer : KSerializer<UniFile?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("UniFile", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UniFile?) {
        return if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeString(value.uri.toString())
        }
    }

    override fun deserialize(decoder: Decoder): UniFile? {
        return if (decoder.decodeNotNullMark()) {
            UniFile.fromUri(Injekt.get<Context>(), decoder.decodeString().toUri())
        } else {
            decoder.decodeNull()
        }
    }
}
