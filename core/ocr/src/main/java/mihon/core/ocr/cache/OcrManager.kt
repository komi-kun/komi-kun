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
import ca.mpreg.imagedecoder.ImageDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import mihon.core.ocr.TILE_TRIGGER_DIMENSION
import mihon.core.ocr.merge.OcrLineMerger
import mihon.core.ocr.model.OcrTextBlock
import mihon.core.ocr.processImageWithChunks
import mihon.core.ocr.samplingDimensions
import tachiyomi.core.common.ocr.ReaderOcrSource
import tachiyomi.core.common.preference.DictionaryOcrPreferences
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
    // KUN -->
    /** Explicit dictionary/OCR opt-in: every OCR entry point below is inert while it is off. */
    private val dictionaryOcr: DictionaryOcrPreferences,
    // KUN <--
    private val scope: CoroutineScope,
) {
    private val _queueState = MutableStateFlow<List<OcrQueueItem>>(emptyList())
    val queueState: StateFlow<List<OcrQueueItem>> = _queueState.asStateFlow()

    private val stateMutex = Mutex()
    private val runningJobs = ConcurrentHashMap<Long, Job>()

    // KUN -->
    /**
     * True while the user has not opted into the dictionary/OCR features. Every entry point that
     * would enqueue, recognise, run the queue, read the cache or write OCR results is a no-op in
     * that state, so no engine work, no download and no OCR cache write happens before the user
     * explicitly enables it.
     *
     * Queue **metadata** bookkeeping ([markChapterReadyForOcr], [markChapterDownloadFailed]) is
     * deliberately *not* gated: a chapter enqueued while the feature was on and downloaded while it
     * is off must still leave `WAITING_DOWNLOAD` (and a failed download must still be recorded), or
     * it would be stranded forever. Both only update the persisted queue entry — the queue runner
     * refuses to start while [ocrDisabled], so a promoted task simply waits.
     */
    private fun ocrDisabled(): Boolean = !dictionaryOcr.isEnabled()
    // KUN <--

    val isRunning: Boolean get() = _queueState.value.any { it.status.isActionable() }

    fun isChapterRunning(chapterId: Long): Boolean = _queueState.value.any {
        it.chapterId == chapterId && it.status == OcrQueueStatus.PROCESSING
    }

    /** Enqueues [chapterIds] of [mangaId] for OCR (`waitForDownload` defers until downloaded). */
    fun queueChapters(mangaId: Long, chapterIds: List<Long>, waitForDownload: Boolean = true) {
        // KUN -->
        if (ocrDisabled()) return
        // KUN <--
        if (chapterIds.isEmpty()) return
        store.enqueue(
            chapterIds.map { chapterId ->
                OcrEnqueueRequest(mangaId = mangaId, chapterId = chapterId, waitForDownload = waitForDownload)
            },
        )
        scope.launch { refreshQueueState() }
    }

    /**
     * KUN -->
     * Promotes a task whose chapter has finished downloading to PENDING.
     *
     * Promoted from `WAITING_DOWNLOAD`, and also from `ERROR` while [OcrTask.waitForDownload] is
     * still set — a download that failed (or was retried by the user and then succeeded) leaves the
     * task `ERROR` but still waiting for the chapter, so the next successful download must revive it
     * instead of leaving it failed forever.
     *
     * Not opt-in gated: it only moves queue metadata, so a chapter that was queued while the
     * feature was on and finished downloading while it is off is not stranded as
     * `WAITING_DOWNLOAD`. Nothing runs it — [runPendingQueue] is inert while the feature is off.
     */
    fun markChapterReadyForOcr(chapterId: Long): Boolean {
        val current = store.get(chapterId) ?: return false
        val wasWaiting = current.status == OcrQueueStatus.WAITING_DOWNLOAD ||
            (current.status == OcrQueueStatus.ERROR && current.waitForDownload)
        if (!wasWaiting) return false
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

    /**
     * KUN -->
     * Marks a task whose download failed.
     *
     * Like [markChapterReadyForOcr] this is queue metadata only, so it stays available while the
     * feature is off — otherwise an enqueued task whose download failed would keep showing
     * `WAITING_DOWNLOAD` forever instead of becoming `ERROR`.
     *
     * [OcrTask.waitForDownload] is **kept**: the task is still waiting for its chapter, so a later
     * successful download (the downloader's own retry, or the user re-downloading) promotes it via
     * [markChapterReadyForOcr]. Clearing it here made that promotion impossible and stranded the
     * chapter at `ERROR` even after the download eventually worked.
     */
    fun markChapterDownloadFailed(chapterId: Long): Boolean {
        val current = store.get(chapterId) ?: return false
        if (current.status != OcrQueueStatus.WAITING_DOWNLOAD) return false
        store.save(current.copy(waitForDownload = true, status = OcrQueueStatus.ERROR, currentPage = 0, totalPages = 0))
        scope.launch { refreshQueueState() }
        return true
    }

    /**
     * KUN -->
     * Re-arms the tasks a failed queue run left `ERROR`, so the retried run has work to do.
     *
     * `OcrWorker` calls this before returning `Result.retry()`: `runPendingQueue` only picks up
     * `PENDING`/`PROCESSING`, so a retry over an `ERROR`-only queue would drain nothing and the
     * chapters the failed attempt gave up on would stay failed. Returns how many tasks were
     * requeued.
     *
     * A task whose **download** failed stays `ERROR` (`waitForDownload` is still set): it is not an
     * OCR failure, and its retry is the download's, which promotes it through
     * [markChapterReadyForOcr] when the files finally land.
     */
    fun requeueFailedTasks(): Int {
        if (ocrDisabled()) return 0
        val failed = store.getAll().filter { it.status == OcrQueueStatus.ERROR && !it.waitForDownload }
        if (failed.isEmpty()) return 0
        failed.forEach { task ->
            store.save(task.copy(status = OcrQueueStatus.PENDING, currentPage = 0, totalPages = 0))
        }
        scope.launch { refreshQueueState() }
        return failed.size
    }

    fun clearPendingChapters(chapterIds: Collection<Long>) {
        if (chapterIds.isEmpty()) return
        store.removeAll(chapterIds)
        scope.launch { refreshQueueState() }
    }

    /**
     * Deletes a chapter's persisted OCR results and clears its queue entry.
     *
     * **The file is the user's disk, the store is our bookkeeping.** The cache file is always
     * deleted, but while the feature is off the store write (a synchronous
     * `SharedPreferences.commit()`, i.e. an fsync) and the queue re-read are skipped: the caller —
     * bulk chapter deletion — would otherwise pay both for *every* chapter on the default path.
     * Skipping the file delete instead would leak it: turning the feature off does not purge
     * `filesDir/ocr_cache` (the preference is all that is written) and the only UI that clears the
     * cache is hidden while the feature is off, so results recognised before a disable could never
     * be reclaimed. The stale store entry is harmless — the queue runner is gated on the same switch
     * and ignores it.
     */
    suspend fun deleteOcrForChapter(key: OcrCacheKey) {
        // KUN -->
        cacheManager.deleteChapter(key)
        if (ocrDisabled()) return
        // KUN <--
        store.remove(key.chapterId)
        refreshQueueState()
    }

    /**
     * Deletes the persisted OCR results of [keys] in one pass: one file delete per chapter, one store
     * write for all of them and **one** queue refresh.
     *
     * The per-chapter method above is the wrong unit for bulk deletion: deleting a 1000-chapter manga
     * through it means 1000 `commit()`s (each an fsync) and 1000 full queue scans.
     *
     * Split like [deleteOcrForChapter] for the same reason: the files are reclaimed either way, only
     * the store write and the refresh are skipped while the feature is off.
     */
    suspend fun deleteOcrForChapters(keys: List<OcrCacheKey>) {
        if (keys.isEmpty()) return
        // KUN -->
        keys.forEach { cacheManager.deleteChapter(it) }
        if (ocrDisabled()) return
        // KUN <--
        store.removeAll(keys.map { it.chapterId })
        refreshQueueState()
    }

    /**
     * Deletes every persisted OCR result of a manga — unconditionally, like the file deletes above:
     * with no store entry to maintain there is nothing to skip, and a library removal must still
     * reclaim the cache a chapter recognised before the feature was turned off.
     */
    suspend fun deleteOcrForManga(sourceId: Long, mangaId: Long) {
        // KUN -->
        cacheManager.deleteManga(sourceId, mangaId)
        // KUN <--
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
    ): List<OcrTextBlock>? {
        // KUN -->
        if (ocrDisabled()) return null
        // KUN <--
        return if (source.persistsOcrResults) cacheManager.load(key, pageIndex, cacheVariant(source)) else null
    }

    /**
     * The persisted OCR cache key of one chapter. Built here (rather than inline at each call site)
     * so the reader's block loader and the download/queue worker cannot drift onto different keys
     * (fix B1/B4) — a chapter pre-OCRed by the worker then shows instantly in the reader.
     */
    fun cacheKey(sourceId: Long, mangaId: Long, chapterId: Long): OcrCacheKey =
        OcrCacheKey(sourceId = sourceId, mangaId = mangaId, chapterId = chapterId)

    /** The effective OCR source policy for a manga. */
    fun sourceFor(mangaId: Long): ReaderOcrSource = preferences.resolveSource(mangaId)

    // ---------------------------------------------------------------- running OCR

    // KUN -->
    /** Runs the engine over one page's bytes and merges the lines into viewer blocks. */
    suspend fun recognizePage(bytes: ByteArray, language: OcrLanguage): List<OcrTextBlock> {
        // KUN -->
        if (ocrDisabled()) return emptyList()
        // KUN <--
        val (width, height) = imageDimensions(bytes)
        if (width > TILE_TRIGGER_DIMENSION || height > TILE_TRIGGER_DIMENSION) {
            val bitmap = try {
                // KUN -->
                // Shrink while decoding: the tiler would otherwise be fed a page that first had to
                // exist as a full native frame plus a full ARGB_8888 bitmap (~430 MB at 6000x9000).
                OcrBitmapDecoder.decode(bytes, OcrBitmapDecoder.sampleSizeFor(width, height, bytes))
                // KUN <--
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to decode image for chunked OCR" }
                null
            }
            if (bitmap != null) {
                try {
                    return processImageWithChunks(
                        bitmap = bitmap,
                        language = language,
                        // KUN --> The advanced-tuning detector resolution, resolved per page.
                        detResolution = preferences.effectiveTuning().detResolution,
                        // KUN <--
                    ) { chunk ->
                        // KUN -->
                        // The chunk carries the limit resolved from its own long side; never recompute
                        // it here from a bitmap that may itself have been resized.
                        engine.recognize(chunk.bitmap, language, chunk.detectorLimitSide)
                        // KUN <--
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }
        val lines = engine.recognize(bytes, language)
        if (lines.isEmpty()) return emptyList()
        return merger.merge(lines, width, height)
    }
    // KUN <--

    /**
     * Recognizes one page on demand and persists it under [key] when the [source] policy caches
     * results (the reader's live path, fix B1). The page index, key and cache variant match the
     * queue worker's, so an on-demand result and a pre-OCRed one converge on the same cache entry.
     *
     * Empty results are **not** persisted: they usually mean the engine/native lib is not installed
     * yet, and caching them would hide a later successful run once the models are downloaded.
     */
    suspend fun recognizeAndCache(
        key: OcrCacheKey,
        pageIndex: Int,
        bytes: ByteArray,
        language: OcrLanguage,
        source: ReaderOcrSource,
    ): List<OcrTextBlock> {
        // KUN -->
        if (ocrDisabled()) return emptyList()
        // KUN <--
        // KUN -->
        // Pinned *before* recognition, exactly like `runChapter`: the variant names the model bytes
        // the engine holds, so reading it afterwards would file a page produced by the old weights
        // under an identity a download that landed mid-inference just created.
        val variant = cacheVariant(source)
        // KUN <--
        val blocks = recognizePage(bytes, language)
        if (source.persistsOcrResults && blocks.isNotEmpty()) {
            cacheManager.save(key, pageIndex, blocks, language.bcp47, variant)
        }
        return blocks
    }

    /**
     * Force-recognizes one page and persists it under [key], overwriting any existing cache entry
     * and ensuring it takes precedence over a .mokuro sidecar in the archive.
     */
    suspend fun forceRecognizeAndCache(
        key: OcrCacheKey,
        pageIndex: Int,
        bytes: ByteArray,
        language: OcrLanguage,
        source: ReaderOcrSource,
    ): List<OcrTextBlock> {
        // KUN -->
        if (ocrDisabled()) return emptyList()
        // KUN <--
        // KUN -->
        // Pinned before recognition for the same reason as [recognizeAndCache].
        val variant = cacheVariant(source)
        // KUN <--
        val blocks = recognizePage(bytes, language)
        cacheManager.save(key, pageIndex, blocks, language.bcp47, variant)
        return blocks
    }

    /**
     * Runs OCR for every page of [input], persisting each page (when the policy caches results) and
     * keeping the queue progress updated. Returns the terminal [OcrTaskResult].
     */
    suspend fun processChapter(
        input: ChapterOcrInput,
        stopRequested: () -> Boolean = { false },
    ): OcrTaskResult = try {
        // KUN -->
        // Inert until the user opts in: no engine work, no cache writes, and the (persisted) queue
        // entry is left untouched — [OcrTaskResult.STOPPED] means "not run", not "failed".
        if (ocrDisabled()) {
            OcrTaskResult.STOPPED
        } else {
            runChapter(input, stopRequested)
        }
        // KUN <--
    } finally {
        // The manager consumes the provider: whoever built it hands over ownership here.
        input.provider.close()
    }

    private suspend fun runChapter(
        input: ChapterOcrInput,
        stopRequested: () -> Boolean,
    ): OcrTaskResult {
        val chapterId = input.key.chapterId
        val variant = cacheVariant(input.source)
        val provider = input.provider

        val pageCount = provider.pageCount
        if (pageCount <= 0) {
            // KUN -->
            // A chapter with no readable page is failed, and this is the only place that knows it:
            // the provider swallows the reason when it opens/enumerates the archive, and `ERROR` is
            // not runnable, so nothing retries and no surface shows it. Log the stage that cannot be
            // distinguished here (open/enumerate failure, or an archive without image entries).
            logcat(LogPriority.WARN) {
                "OcrManager: chapter $chapterId has no readable pages " +
                    "(the chapter file could not be opened/enumerated, or it holds no images); failing it"
            }
            // KUN <--
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
            // KUN -->
            // The user can switch the feature off in the middle of a chapter. Abort before the page
            // is even read, leaving the task `PENDING` (runnable) instead of running the engine for
            // a feature that is now off.
            if (ocrDisabled()) return abortForOptOut(chapterId)
            // KUN <--
            if (store.get(chapterId) == null) return OcrTaskResult.CANCELLED

            if (input.source.persistsOcrResults && cacheManager.load(input.key, pageIndex, variant) != null) {
                progress(chapterId, pageIndex, pageCount)
                continue
            }

            // KUN -->
            // Nothing can be recognised while the engine cannot run (no models/`.so`, or init
            // failed), and `recognizePage` then answers an empty list — which must not be cached,
            // counted as done or turned into `COMPLETED`. Abort before the page is even read, leaving
            // the task `PENDING` like an opt-out so it runs again once the models are installed. An
            // *available* engine that returns no blocks for a blank page still caches and completes
            // as before. Pages already cached above are still counted, so a chapter that is fully
            // cached can complete with the engine down.
            if (!engine.isAvailable()) return abortForUnavailableEngine(chapterId)
            // KUN <--

            val bytes = try {
                provider.pageBytes(pageIndex)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "OcrManager: failed to read page $pageIndex" }
                null
            }
            // KUN -->
            // Empty bytes are a page that could not be read, never a page without text: persisting the
            // empty result `recognizePage` then answers would cache "nothing to show" for a page that
            // was never recognised, and `countCachedPages` would count it as done — which is how a
            // chapter whose provider could not read its archive was marked `COMPLETED` after a single
            // bogus page and dropped from the queue. A page whose bytes cannot be read fails the page
            // instead, so the chapter stays incomplete (and retryable) until it can really be read.
            if (bytes == null || bytes.isEmpty()) {
                failedPages++
                progress(chapterId, pageIndex, pageCount)
                continue
            }
            // KUN <--

            try {
                val blocks = retryWithBackoff(times = 3) { recognizePage(bytes, input.language) }
                // KUN -->
                // Re-checked *after* recognition: [recognizePage] returns an empty list once the
                // feature is off, and persisting that would poison the page's cache entry. Abort
                // here instead of saving an empty `OcrPageData` and counting the page as done.
                if (ocrDisabled()) return abortForOptOut(chapterId)
                // KUN <--
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

        // KUN -->
        // The switch may have been turned off after the last page: do not start the retry pass, mark
        // the chapter `COMPLETED`/`ERROR`, or let the runner drop the task.
        if (ocrDisabled()) return abortForOptOut(chapterId)
        // KUN <--
        if (failedPages > 0 && !stopRequested() && store.get(chapterId) != null) {
            retryFailedPages(input, pageCount) { failedPages-- }
            // KUN -->
            if (ocrDisabled()) return abortForOptOut(chapterId)
            // KUN <--
        }

        val cachedPages = countCachedPages(input, pageCount)
        return if (cachedPages >= pageCount) {
            updateTask(chapterId) { it.copy(status = OcrQueueStatus.COMPLETED, currentPage = pageCount) }
            OcrTaskResult.SUCCESS
        } else {
            // KUN -->
            // The one log line a chapter that ends up failed produces: the counts say whether pages
            // could not be read at all or the engine could not recognise them. Nothing retries an
            // `ERROR` task on its own, so this is the only record of why the chapter stopped.
            logcat(LogPriority.WARN) {
                "OcrManager: chapter $chapterId failed after the run " +
                    "($cachedPages/$pageCount pages cached, $failedPages page(s) unreadable or unrecognised)"
            }
            // KUN <--
            updateTask(chapterId) { it.copy(status = OcrQueueStatus.ERROR) }
            OcrTaskResult.ERROR
        }
    }

    /**
     * Drives the persisted queue until it is empty, [stopRequested] or the user turns the feature
     * off. [inputFor] resolves a queued task into a runnable [ChapterOcrInput] (app-side: locate the
     * downloaded chapter and open its image provider); it returns null when the chapter cannot run
     * and the task is failed.
     */
    suspend fun runPendingQueue(
        inputFor: suspend (OcrTask) -> ChapterOcrInput?,
        stopRequested: () -> Boolean = { false },
    ) = supervisorScope {
        // KUN -->
        // Nothing is dequeued or processed while the feature is off (no store reads, no jobs).
        if (ocrDisabled()) return@supervisorScope
        // KUN <--
        while (!stopRequested()) {
            // KUN -->
            // The user can turn the feature off in the middle of a run. Re-checked every iteration:
            // stop dequeuing, stop resolving chapters ([inputFor]) and stop writing queue metadata,
            // then cancel what is still in flight below. Interrupted tasks are left `PENDING`, so a
            // later re-enable resumes them instead of this loop hot-spinning on tasks that
            // [processChapter] now refuses to run.
            if (ocrDisabled()) {
                logcat(LogPriority.INFO) { "OcrManager: OCR disabled; stopping the queue run" }
                break
            }
            // KUN <--
            // KUN -->
            // An unavailable engine (no models/`.so`, or a failed init) leaves a chapter `PENDING`
            // instead of running it, and the runner would pick that same chapter straight back up —
            // a hot loop of store writes that OCRs nothing. No task can make progress, so stop the
            // run here; the tasks stay runnable and drain on the next run once the models are there.
            if (!engine.isAvailable()) {
                logcat(LogPriority.WARN) { "OcrManager: OCR engine unavailable; stopping the queue run" }
                break
            }
            // KUN <--
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

            // KUN -->
            // Last check before the expensive part: locating the chapter and opening its page
            // provider (a CBZ/zip open) must not happen after the feature was switched off.
            if (ocrDisabled()) break
            // KUN <--
            // KUN -->
            // Resolving the chapter is the one call here that can throw (it opens the download, the
            // archive and its page provider). Contained like the `null` branch below: the task is
            // marked `ERROR` and the rest of the queue keeps running, instead of the exception
            // leaving this `supervisorScope` — which left the task `PENDING` for the next launch while
            // `OcrWorker` reported `Result.success()` and nothing retried.
            val input = runCatching { inputFor(next) }.getOrElse { e ->
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "OcrManager: failed to resolve chapter ${next.chapterId}" }
                null
            }
            // KUN <--
            if (input == null) {
                logcat(LogPriority.WARN) { "OcrManager: cannot resolve chapter ${next.chapterId}" }
                updateTask(next.chapterId) { it.copy(status = OcrQueueStatus.ERROR) }
                continue
            }

            // KUN -->
            // LAZY start so the job is registered in [runningJobs] *before* it can run (and remove
            // itself). Launched eagerly, a task that finishes before the `runningJobs[...] = job`
            // below left its own finished Job in the map: `firstOrNull { !runningJobs.containsKey(..) }`
            // then skipped that chapter forever, the loop below re-read the store every
            // [QUEUE_POLL_MS] for the life of the process (it only exits on an empty map), and
            // `isRunning` never went false — so `OcrWorker`'s `ocrJob.isRunning.first { !it }` never
            // returned and its foreground notification stayed up. Same pattern as
            // `ReaderOcrBlockLoader.ensureLoaded`.
            // The resolved input owns a native resource (an mmap-backed archive reader), and
            // [processChapter]'s `finally` is the only thing that releases it. A job whose body never
            // runs therefore leaks it — and that is reachable, not theoretical: [inputFor] suspends
            // (database reads, the archive open), so the run's scope can be cancelled after it
            // returned the input but before this launch, and `launch` then answers an already-cancelled
            // child whose body — and whose `finally` — never executes. Ownership is handed over
            // explicitly instead: the body claims it right before [processChapter] (which then owns
            // the close), and anything else releases it through the completion handler once.
            val consumed = AtomicBoolean(false)
            val job = launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                val self = coroutineContext[Job]
                try {
                    // Claimed: from here on `processChapter`'s `finally` closes the provider.
                    consumed.set(true)
                    when (processChapter(input, stopRequested)) {
                        // KUN -->
                        // Only a run that persisted **every** page of the chapter may drop its task:
                        // `SUCCESS` is reached exactly when `countCachedPages` covers the provider's
                        // page count, and the next run then skips the pages that are already cached
                        // and recognises the rest.
                        //
                        // `CANCELLED` is re-armed **defensively**, not because it is what saves an
                        // interrupted chapter: its only producer answers it when the store entry is
                        // already gone, so `updateTask` finds nothing and writes nothing here. The
                        // "never drop an interrupted run" guarantee is carried by the paths that
                        // actually occur — `STOPPED` (stop request, opt-out, unavailable engine) is
                        // persisted as `PENDING`, the cancellation handler below does the same, and a
                        // task killed mid-run stays `PROCESSING` in the store, which [isRunnable]
                        // picks up again.
                        OcrTaskResult.SUCCESS -> store.remove(next.chapterId)
                        OcrTaskResult.CANCELLED -> updateTask(next.chapterId) {
                            it.copy(status = OcrQueueStatus.PENDING)
                        }
                        // KUN <--
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
                    // KUN -->
                    // Compare-and-remove with this job's own handle, so a finishing (or cancelled) job
                    // can never drop a registration a newer run made for the same chapter.
                    if (self != null) {
                        runningJobs.remove(next.chapterId, self)
                    } else {
                        runningJobs.remove(next.chapterId)
                    }
                    // KUN <--
                    refreshQueueState()
                }
            }
            runningJobs[next.chapterId] = job
            // KUN --> Registered: it may run (and release its own registration) from here on.
            // The body never claimed the input (cancelled parent, cancelled before dispatch), so
            // nothing else will release what resolving it opened. Fires inline when the job is
            // already complete, which is exactly the cancelled-before-start case.
            job.invokeOnCompletion {
                if (!consumed.get()) runCatching { input.provider.close() }
            }
            job.start()
            // KUN <--
        }
        runningJobs.values.forEach { it.cancel() }
        runningJobs.clear()
    }

    // ---------------------------------------------------------------- internals

    // KUN -->
    /**
     * Aborts [chapterId] because the user switched the dictionary/OCR feature off mid-run. The task
     * stays in the store as `PENDING` (runnable again once the feature is back on) and is neither
     * cached further, counted as failed, marked `COMPLETED` nor removed.
     */
    private suspend fun abortForOptOut(chapterId: Long): OcrTaskResult {
        logcat(LogPriority.INFO) { "OcrManager: OCR disabled; aborting chapter $chapterId" }
        updateTask(chapterId) { it.copy(status = OcrQueueStatus.PENDING) }
        return OcrTaskResult.STOPPED
    }

    /**
     * Aborts [chapterId] because the OCR engine cannot run (models or the native library are missing,
     * or init failed). The task stays `PENDING`, exactly like an opt-out and for the same reason: the
     * engine produced nothing, so nothing is cached, the chapter is not counted as done and it runs
     * again once the models are installed. Its empty result is **not** "this page has no text".
     */
    private suspend fun abortForUnavailableEngine(chapterId: Long): OcrTaskResult {
        logcat(LogPriority.WARN) { "OcrManager: OCR engine unavailable; leaving chapter $chapterId pending" }
        updateTask(chapterId) { it.copy(status = OcrQueueStatus.PENDING) }
        return OcrTaskResult.STOPPED
    }
    // KUN <--

    private suspend fun retryFailedPages(input: ChapterOcrInput, pageCount: Int, onRecovered: () -> Unit) {
        val variant = cacheVariant(input.source)
        for (pageIndex in 0 until pageCount) {
            // KUN -->
            // Abort the retry pass as soon as the feature is switched off (before reading the page,
            // and again before persisting the result), so no page is saved for a disabled feature.
            if (ocrDisabled()) return
            // KUN <--
            if (input.source.persistsOcrResults && cacheManager.load(input.key, pageIndex, variant) != null) continue
            // KUN -->
            // An unavailable engine answers an empty list, which must not be cached as this page's
            // text: stop the retry pass and leave the chapter failed (it is not counted as completed).
            if (!engine.isAvailable()) return
            // KUN <--
            val bytes = try {
                input.provider.pageBytes(pageIndex)
            } catch (_: Exception) {
                null
                // KUN --> Empty bytes are an unreadable page, exactly as in `runChapter`.
            }?.takeIf { it.isNotEmpty() } ?: continue
            // KUN <--
            try {
                val blocks = retryWithBackoff(times = 3) { recognizePage(bytes, input.language) }
                // KUN -->
                if (ocrDisabled()) return
                // KUN <--
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
            if (cacheManager.load(input.key, pageIndex, cacheVariant(input.source)) != null) cached++
        }
        return cached
    }

    /**
     * KUN -->
     * The cache variant for [source], keyed by engine generation, the non-default thresholds and the
     * **identity of the model bytes the engine would load now**.
     *
     * The generation component is why a page OCR'd by an older engine or model is not served from
     * cache after the engine changes: OCR caches survive app upgrades, so without it a pre-v6
     * (Paddle-Lite / PP-OCRv5) result keeps masking the current engine's output. The threshold
     * component makes changed sliders re-run instead of serving the old thresholds' result.
     *
     * The engine identity is what makes the selection-only signature safe: every release publishes
     * identically named model files, so selecting another release and OCR-ing before its download
     * completes would store pages under the new tag while the installed (old) weights produced them
     * — permanently, since the tag then matches. A download or a replaced file changes the identity,
     * so the pages are re-read; a selection change alone cannot claim weights that are not installed.
     */
    private fun cacheVariant(source: ReaderOcrSource): String? {
        val base = source.persistentCacheVariant ?: return null
        val signature = preferences.cacheSignature()
        val modelIdentity = engine.modelBytesIdentity()
        return buildString {
            append(base).append('-').append(ENGINE_CACHE_GENERATION)
            if (signature.isNotEmpty()) append('-').append(signature)
            if (!modelIdentity.isNullOrEmpty()) append('-').append(modelIdentity)
        }
    }
    // KUN <--

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
        // KUN -->
        // The decode applies the EXIF orientation, so the sampling decision must be taken on the
        // decoder's own (oriented) header, not on BitmapFactory's stored-orientation bounds:
        // a stored 12000x2000 landscape strip with EXIF rotate 90 decodes as 2000x12000, and the
        // bounds-first order hid it from the tall-strip bypass of `sampleSizeFor`, which then halved
        // its text. Bounds stay as the fallback for formats the decoder cannot open.
        val header = try {
            ImageDecoder.open(ByteArrayInputStream(bytes)).use { it.width to it.height }
        } catch (_: Throwable) {
            null
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val stored = if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            null
        }
        return samplingDimensions(header, stored, DEFAULT_IMAGE_DIMENSION)
        // KUN <--
    }

    private companion object {
        /**
         * KUN --> Bump when the OCR engine, model or merge/filter pipeline changes so persisted
         * page results from the previous pipeline are not reused (they survive app upgrades).
         * `v8` = PP-OCRv6 small with 0.35 dialogue floor, 1-char SFX filtering, cross-bubble separation.
         * `v10` = dialogue-first detector (SFX detector alone does not delete dialogue lines or glue
         * bubbles any more) + the release tag in [OcrPreferences.cacheSignature], so `v9` results read
         * by the SFX detector are never served as dialogue.
         */
        private const val ENGINE_CACHE_GENERATION = "v10"
        // KUN <--

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
