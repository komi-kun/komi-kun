// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Ported from Chimahon (https://github.com/Chimahon/Chimahon, GPL-3.0-or-later),
// `app/src/main/java/eu/kanade/tachiyomi/data/ocr/OcrManager.kt` (jointly with
// `OcrEngineSelector.kt`) @ f85c063.
// Adapted for komi-kun (contract §1.1, 03 §5): the module cannot see `:domain`/`:data`, so this
// manager is domain-agnostic. Callers (the app/reader layer) resolve a chapter into a
// [ChapterOcrInput] — a cache key, the language, the [ReaderOcrSource] policy and a
// [ChapterImageProvider] over the page bytes — and the manager runs OCR off the byte stream (never a
// view bitmap), merges the lines and persists the result. Lens/cloud engines are dropped: the only
// engine is the injected [OcrEngine] (Paddle).
// KUN <--
package mihon.core.ocr.cache

import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import mihon.core.ocr.OcrBitmapDecoder
import mihon.core.ocr.OcrEngine
import mihon.core.ocr.OcrLanguage
import mihon.core.ocr.OcrPreferences
import mihon.core.ocr.merge.OcrLineMerger
import mihon.core.ocr.model.OcrTextBlock
import tachiyomi.core.common.ocr.ReaderOcrSource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Orchestrates OCR: picks the source policy (mokuro / engine / cache), runs the engine off page
 * bytes, merges the lines and persists the result, and drives the persisted queue.
 */
class OcrManager(
    private val engine: OcrEngine,
    private val merger: OcrLineMerger,
    private val cacheManager: OcrCacheManager,
    private val store: OcrStore,
    private val preferences: OcrPreferences,
    private val scope: CoroutineScope,
) {
    private val _queueState = MutableStateFlow<List<OcrQueueItem>>(emptyList())
    val queueState: StateFlow<List<OcrQueueItem>> = _queueState.asStateFlow()

    private val stateMutex = Mutex()
    private val runningJobs = ConcurrentHashMap<Long, Job>()

    val isRunning: Boolean get() = _queueState.value.any { it.status.isActionable() }

    fun isChapterQueued(chapterId: Long): Boolean = store.get(chapterId) != null

    fun isChapterRunning(chapterId: Long): Boolean = _queueState.value.any {
        it.chapterId == chapterId && it.status == OcrQueueStatus.PROCESSING
    }

    /** Enqueues [chapterIds] of [mangaId] for OCR (`waitForDownload` defers until downloaded). */
    fun queueChapters(mangaId: Long, chapterIds: List<Long>, waitForDownload: Boolean = true) {
        if (chapterIds.isEmpty()) return
        store.enqueue(
            chapterIds.map { chapterId ->
                OcrEnqueueRequest(mangaId = mangaId, chapterId = chapterId, waitForDownload = waitForDownload)
            },
        )
        scope.launch { refreshQueueState() }
    }

    /** Promotes a downloaded WAITING_DOWNLOAD task to PENDING. */
    fun markChapterReadyForOcr(chapterId: Long): Boolean {
        val current = store.get(chapterId) ?: return false
        if (current.status != OcrQueueStatus.WAITING_DOWNLOAD) return false
        store.save(
            current.copy(
                waitForDownload = false,
                status = OcrQueueStatus.PENDING,
                currentPage = 0,
                totalPages = 0,
            ),
        )
        scope.launch { refreshQueueState() }
        return true
    }

    /** Marks a task whose download failed. */
    fun markChapterDownloadFailed(chapterId: Long): Boolean {
        val current = store.get(chapterId) ?: return false
        if (current.status != OcrQueueStatus.WAITING_DOWNLOAD) return false
        store.save(current.copy(waitForDownload = false, status = OcrQueueStatus.ERROR, currentPage = 0, totalPages = 0))
        scope.launch { refreshQueueState() }
        return true
    }

    fun retryChapter(chapterId: Long): Boolean {
        val task = store.get(chapterId) ?: return false
        if (task.status != OcrQueueStatus.ERROR) return false
        store.save(task.copy(status = OcrQueueStatus.PENDING))
        scope.launch { refreshQueueState() }
        return true
    }

    fun retryAllErrors() {
        store.getAll().filter { it.status == OcrQueueStatus.ERROR }.forEach { task ->
            store.save(task.copy(status = OcrQueueStatus.PENDING))
        }
        scope.launch { refreshQueueState() }
    }

    suspend fun cancelChapter(chapterId: Long) {
        store.remove(chapterId)
        runningJobs[chapterId]?.cancel()
        stateMutex.withLock { _queueState.update { current -> current.filterNot { it.chapterId == chapterId } } }
    }

    fun clearPendingChapters(chapterIds: Collection<Long>) {
        if (chapterIds.isEmpty()) return
        store.removeAll(chapterIds)
        scope.launch { refreshQueueState() }
    }

    /** Deletes a chapter's persisted OCR results and clears its queue entry. */
    suspend fun deleteOcrForChapter(key: OcrCacheKey) {
        cacheManager.deleteChapter(key)
        store.remove(key.chapterId)
        refreshQueueState()
    }

    suspend fun deleteOcrForManga(sourceId: Long, mangaId: Long) {
        cacheManager.deleteManga(sourceId, mangaId)
    }

    suspend fun getStorageSize(): String = cacheManager.readableSize()

    suspend fun clearCache() = cacheManager.clear()

    // ---------------------------------------------------------------- source policy / cache

    /**
     * Loads cached page blocks for [key] when the [source] policy persists results. MOKURO does not
     * (its sidecar *is* the cache — 03 §4), so it returns null here and the app supplies the mokuro
     * blocks instead.
     */
    suspend fun cachedBlocks(
        key: OcrCacheKey,
        pageIndex: Int,
        source: ReaderOcrSource,
    ): List<OcrTextBlock>? =
        if (source.persistsOcrResults) cacheManager.load(key, pageIndex, source.persistentCacheVariant) else null

    /** The effective OCR source policy for a manga. */
    fun sourceFor(mangaId: Long): ReaderOcrSource = preferences.resolveSource(mangaId)

    // ---------------------------------------------------------------- running OCR

    /** Runs the engine over one page's bytes and merges the lines into viewer blocks. */
    suspend fun recognizePage(bytes: ByteArray, language: OcrLanguage): List<OcrTextBlock> {
        val lines = engine.recognize(bytes, language)
        if (lines.isEmpty()) return emptyList()
        val (width, height) = imageDimensions(bytes)
        return merger.merge(lines, width, height)
    }

    /**
     * Runs OCR for every page of [input], persisting each page (when the policy caches results) and
     * keeping the queue progress updated. Returns the terminal [OcrTaskResult].
     */
    suspend fun processChapter(
        input: ChapterOcrInput,
        stopRequested: () -> Boolean = { false },
    ): OcrTaskResult = try {
        runChapter(input, stopRequested)
    } finally {
        // The manager consumes the provider: whoever built it hands over ownership here.
        input.provider.close()
    }

    private suspend fun runChapter(
        input: ChapterOcrInput,
        stopRequested: () -> Boolean,
    ): OcrTaskResult {
        val chapterId = input.key.chapterId
        val variant = input.source.persistentCacheVariant
        val provider = input.provider

        val pageCount = provider.pageCount
        if (pageCount <= 0) {
            updateTask(chapterId) { it.copy(status = OcrQueueStatus.ERROR) }
            return OcrTaskResult.ERROR
        }

        updateTask(chapterId) { it.copy(status = OcrQueueStatus.PROCESSING, totalPages = pageCount) }

        var failedPages = 0
        for (pageIndex in 0 until pageCount) {
            if (stopRequested()) {
                updateTask(chapterId) { it.copy(status = OcrQueueStatus.PENDING) }
                return OcrTaskResult.STOPPED
            }
            if (store.get(chapterId) == null) return OcrTaskResult.CANCELLED

            if (input.source.persistsOcrResults && cacheManager.load(input.key, pageIndex, variant) != null) {
                progress(chapterId, pageIndex, pageCount)
                continue
            }

            val bytes = try {
                provider.pageBytes(pageIndex)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "OcrManager: failed to read page $pageIndex" }
                null
            }
            if (bytes == null) {
                failedPages++
                progress(chapterId, pageIndex, pageCount)
                continue
            }

            try {
                val blocks = retryWithBackoff(times = 3) { recognizePage(bytes, input.language) }
                if (input.source.persistsOcrResults) {
                    cacheManager.save(input.key, pageIndex, blocks, input.language.bcp47, variant)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "OcrManager: OCR failed for page $pageIndex" }
                failedPages++
            }
            progress(chapterId, pageIndex, pageCount)
        }

        if (failedPages > 0 && !stopRequested() && store.get(chapterId) != null) {
            retryFailedPages(input, pageCount) { failedPages-- }
        }

        val cachedPages = countCachedPages(input, pageCount)
        return if (cachedPages >= pageCount) {
            updateTask(chapterId) { it.copy(status = OcrQueueStatus.COMPLETED, currentPage = pageCount) }
            OcrTaskResult.SUCCESS
        } else {
            updateTask(chapterId) { it.copy(status = OcrQueueStatus.ERROR) }
            OcrTaskResult.ERROR
        }
    }

    /**
     * Drives the persisted queue until it is empty or [stopRequested]. [inputFor] resolves a queued
     * task into a runnable [ChapterOcrInput] (app-side: locate the downloaded chapter and open its
     * image provider); it returns null when the chapter cannot run and the task is failed.
     */
    suspend fun runPendingQueue(
        inputFor: suspend (OcrTask) -> ChapterOcrInput?,
        stopRequested: () -> Boolean = { false },
    ) = supervisorScope {
        while (!stopRequested()) {
            val parallelLimit = preferences.parallelOcrLimit.get().coerceAtLeast(1)
            val next = store.getAll()
                .filter { it.status.isRunnable() }
                .firstOrNull { !runningJobs.containsKey(it.chapterId) }

            if (next == null) {
                if (runningJobs.isEmpty()) break
                delay(QUEUE_POLL_MS)
                continue
            }
            if (runningJobs.size >= parallelLimit) {
                delay(QUEUE_POLL_MS)
                continue
            }

            val input = inputFor(next)
            if (input == null) {
                logcat(LogPriority.WARN) { "OcrManager: cannot resolve chapter ${next.chapterId}" }
                updateTask(next.chapterId) { it.copy(status = OcrQueueStatus.ERROR) }
                continue
            }

            val job = launch(Dispatchers.IO) {
                try {
                    when (processChapter(input, stopRequested)) {
                        OcrTaskResult.SUCCESS,
                        OcrTaskResult.CANCELLED,
                        -> store.remove(next.chapterId)
                        OcrTaskResult.ERROR -> Unit
                        OcrTaskResult.STOPPED -> updateTask(next.chapterId) { it.copy(status = OcrQueueStatus.PENDING) }
                    }
                } catch (e: CancellationException) {
                    if (store.get(next.chapterId) != null) {
                        updateTask(next.chapterId) { it.copy(status = OcrQueueStatus.PENDING) }
                    }
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "OcrManager: task ${next.chapterId} failed" }
                    updateTask(next.chapterId) { it.copy(status = OcrQueueStatus.ERROR) }
                } finally {
                    runningJobs.remove(next.chapterId)
                    refreshQueueState()
                }
            }
            runningJobs[next.chapterId] = job
        }
        runningJobs.values.forEach { it.cancel() }
        runningJobs.clear()
    }

    // ---------------------------------------------------------------- internals

    private suspend fun retryFailedPages(input: ChapterOcrInput, pageCount: Int, onRecovered: () -> Unit) {
        val variant = input.source.persistentCacheVariant
        for (pageIndex in 0 until pageCount) {
            if (input.source.persistsOcrResults && cacheManager.load(input.key, pageIndex, variant) != null) continue
            val bytes = try {
                input.provider.pageBytes(pageIndex)
            } catch (_: Exception) {
                null
            } ?: continue
            try {
                val blocks = retryWithBackoff(times = 3) { recognizePage(bytes, input.language) }
                if (input.source.persistsOcrResults) {
                    cacheManager.save(input.key, pageIndex, blocks, input.language.bcp47, variant)
                }
                onRecovered()
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "OcrManager: retry failed for page $pageIndex" }
            }
        }
    }

    private suspend fun countCachedPages(input: ChapterOcrInput, pageCount: Int): Int {
        if (!input.source.persistsOcrResults) return pageCount
        var cached = 0
        for (pageIndex in 0 until pageCount) {
            if (cacheManager.load(input.key, pageIndex, input.source.persistentCacheVariant) != null) cached++
        }
        return cached
    }

    private suspend fun progress(chapterId: Long, pageIndex: Int, pageCount: Int) {
        updateTask(chapterId) { it.copy(currentPage = pageIndex + 1, totalPages = pageCount) }
    }

    private suspend fun updateTask(chapterId: Long, transform: (OcrTask) -> OcrTask) {
        val updated = store.update(chapterId, transform) ?: return
        stateMutex.withLock {
            _queueState.update { current ->
                current.map { item ->
                    if (item.chapterId != chapterId) {
                        item
                    } else {
                        item.copy(
                            currentPage = updated.currentPage,
                            totalPages = updated.totalPages,
                            progress = if (updated.totalPages > 0) {
                                updated.currentPage.toFloat() / updated.totalPages
                            } else {
                                0f
                            },
                            status = updated.status,
                        )
                    }
                }
            }
        }
    }

    private suspend fun refreshQueueState() {
        stateMutex.withLock {
            _queueState.value = store.getAll().map { task ->
                OcrQueueItem(
                    mangaId = task.mangaId,
                    chapterId = task.chapterId,
                    progress = if (task.totalPages > 0) task.currentPage.toFloat() / task.totalPages else 0f,
                    currentPage = task.currentPage,
                    totalPages = task.totalPages,
                    status = task.status,
                )
            }
        }
    }

    /** Retries [block] with exponential backoff; rethrows [CancellationException] immediately. */
    private suspend fun <T> retryWithBackoff(
        times: Int = 3,
        initialDelayMs: Long = 1000,
        maxDelayMs: Long = 10000,
        factor: Double = 2.0,
        block: suspend () -> T,
    ): T {
        var currentDelay = initialDelayMs
        repeat(times - 1) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "OcrManager: retry in ${currentDelay}ms (${e.message})" }
            }
            delay(currentDelay)
            currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelayMs)
        }
        return block()
    }

    private fun imageDimensions(bytes: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth > 0 && options.outHeight > 0) return options.outWidth to options.outHeight

        // Fallback for formats BitmapFactory cannot measure: decode once for the dimensions only.
        return try {
            val bitmap = OcrBitmapDecoder.decode(bytes)
            val dimensions = bitmap.width to bitmap.height
            bitmap.recycle()
            dimensions
        } catch (_: Throwable) {
            DEFAULT_IMAGE_DIMENSION to DEFAULT_IMAGE_DIMENSION
        }
    }

    private companion object {
        private const val QUEUE_POLL_MS = 300L
        private const val DEFAULT_IMAGE_DIMENSION = 1000
    }
}

/** One chapter to OCR: its cache key, language, source policy and page-byte provider. */
data class ChapterOcrInput(
    val key: OcrCacheKey,
    val language: OcrLanguage,
    val source: ReaderOcrSource,
    val provider: ChapterImageProvider,
)

/** A live queue entry for the OCR UI. */
data class OcrQueueItem(
    val mangaId: Long,
    val chapterId: Long,
    val progress: Float,
    val currentPage: Int,
    val totalPages: Int,
    val status: OcrQueueStatus = OcrQueueStatus.PENDING,
)

enum class OcrTaskResult {
    SUCCESS,
    ERROR,
    CANCELLED,
    STOPPED,
}

/** Reads the page bytes of a chapter; the manager never touches a rendered bitmap. */
interface ChapterImageProvider : AutoCloseable {
    val pageCount: Int
    fun pageBytes(pageIndex: Int): ByteArray?
    override fun close() {}
}

/** Pages read from a directory of image files. */
class DirectoryImageProvider(
    private val chapterDir: File,
) : ChapterImageProvider {
    private val imageFiles: List<File> = chapterDir.listFiles()
        ?.filter { it.isFile && ImageUtil.isImage(it.name) { it.inputStream() } }
        ?.sortedWith { a, b -> naturalCompare(a.name, b.name) }
        .orEmpty()

    override val pageCount: Int = imageFiles.size

    override fun pageBytes(pageIndex: Int): ByteArray? =
        imageFiles.getOrNull(pageIndex)?.let { runCatching { it.readBytes() }.getOrNull() }
}

/** Pages read from a CBZ (plain zip) chapter. */
class ZipImageProvider(
    private val archiveFile: File,
) : ChapterImageProvider {
    private val zip = ZipFile(archiveFile)
    private val entryNames: List<String> = zip.entries().asSequence()
        .filter { !it.isDirectory && ImageUtil.isImage(it.name) { zip.getInputStream(it) } }
        .map { it.name }
        .sortedWith { a, b -> naturalCompare(a, b) }
        .toList()

    override val pageCount: Int = entryNames.size

    override fun pageBytes(pageIndex: Int): ByteArray? {
        val name = entryNames.getOrNull(pageIndex) ?: return null
        return runCatching { zip.getInputStream(zip.getEntry(name)).use { it.readBytes() } }.getOrNull()
    }

    override fun close() {
        zip.close()
    }
}

/**
 * Case-insensitive natural order ("page2" < "page10"), so page providers sort files/entries the way
 * a reader expects without pulling in `:core:common`'s natural-comparator helper.
 */
private fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var ni = i
            var nj = j
            while (ni < a.length && a[ni].isDigit()) ni++
            while (nj < b.length && b[nj].isDigit()) nj++
            val numA = a.substring(i, ni).trimStart('0').ifEmpty { "0" }
            val numB = b.substring(j, nj).trimStart('0').ifEmpty { "0" }
            val byLength = numA.length.compareTo(numB.length)
            val byValue = numA.compareTo(numB)
            if (byLength != 0) return byLength
            if (byValue != 0) return byValue
            i = ni
            j = nj
        } else {
            val byChar = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (byChar != 0) return byChar
            i++
            j++
        }
    }
    return (a.length - i).compareTo(b.length - j)
}
