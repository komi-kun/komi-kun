// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// App-side block source for the reader OCR overlay/dictionary path.
//
// `:core:ocr` cannot depend on `:domain`/`:app` (contract §1.1), so the manager is domain-agnostic
// and callers resolve a chapter into its cache key. This loader does that resolution for the reader
// (page -> chapter -> manga/source -> `OcrCacheKey`) and keeps the resulting blocks in memory so the
// overlay and the long-press hit-test share one cache.
//
// The full source policy is implemented here (fix B1):
//   1. `.mokuro` sidecar when the source uses mokuro (AUTOMATIC/MOKURO) — sibling file, downloaded
//      chapter dir or CBZ sidecar, parsed through `MokuroChapterLoader`;
//   2. the persisted OCR cache (the pre-OCRed path) when the source persists results;
//   3. engine OCR over the page byte stream, persisted under the same cache key the queue worker
//      uses, so an on-demand page and a pre-OCRed chapter converge on one cache entry (fix B4).
// KUN <--
package eu.kanade.tachiyomi.ui.reader.ocr.impl

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.reader.RedoOcrResult
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import mihon.core.ocr.OcrEngine
import mihon.core.ocr.OcrLanguage
import mihon.core.ocr.cache.MokuroChapterLoader
import mihon.core.ocr.cache.OcrCacheKey
import mihon.core.ocr.cache.OcrManager
import mihon.core.ocr.model.OcrTextBlock
import mihon.core.ocr.mokuro.ImageFileInfo
import mihon.core.ocr.paddle.PaddleOcrEngine
import tachiyomi.core.common.ocr.ReaderOcrSource
import tachiyomi.core.common.preference.DictionaryOcrPreferences
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Identifies one page's OCR blocks: its chapter id plus the page index within the chapter. */
data class OcrPageKey(val chapterId: Long, val pageIndex: Int)

/** The [OcrPageKey] of this reader page. */
fun ReaderPage.ocrKey(): OcrPageKey = OcrPageKey(chapter.chapter.id, index)

/**
 * Resolves and caches the OCR blocks of the pages the reader is showing.
 *
 * Blocks come from the `.mokuro` sidecar and/or the persisted OCR cache ([OcrManager.cachedBlocks]);
 * when neither yields anything the engine runs over the page byte stream and the result is persisted
 * under the worker's cache key. The reader never touches the GPL `OcrTextBlock` model directly —
 * only this GPL-package helper and `ReaderOcrSessionImpl` do.
 */
class ReaderOcrBlockLoader(
    private val ocrManager: OcrManager,
    private val engine: OcrEngine,
    private val getManga: GetManga,
    private val downloadProvider: DownloadProvider,
    private val sourceManager: SourceManager,
    private val dictionaryPreferences: DictionaryPreferences,
    // KUN -->
    /**
     * App-lifetime scope, used **only** by [observeOcrQueue]: page work that reads a chapter's
     * archive must not outlive the reader (see [work]).
     */
    @AppCoroutineScope private val scope: CoroutineScope,
    /**
     * The combined dictionary/OCR opt-in. Checked at the work entry points ([ensureLoaded],
     * [redoOcr]) so a disabled feature never reads a `.mokuro` sidecar, never reads the persisted
     * OCR cache and never runs the engine — "off" is inert, not just invisible.
     */
    private val dictionaryOcrPreferences: DictionaryOcrPreferences,
    // KUN <--
    /**
     * Watchdog for one page's [load] (F2). Generous on purpose: the first OCR run of a session
     * includes native engine init and model load (seconds on device), so the limit is in the tens
     * of seconds — but a genuine hang never completes, and must be surfaced rather than left
     * invisible. Overridable so tests can shrink it.
     */
    private val loadTimeoutMs: Long = DEFAULT_LOAD_TIMEOUT_MS,
) {
    private val _blocks = MutableStateFlow<Map<OcrPageKey, List<OcrTextBlock>>>(emptyMap())
    val blocks: StateFlow<Map<OcrPageKey, List<OcrTextBlock>>> = _blocks.asStateFlow()

    // KUN -->
    /**
     * Pages whose load (or forced re-run) owns the page right now, each mapped to the **token of the
     * work that claimed it**. The token is what makes the release ownership-checked: [detach] (and
     * [invalidate]) drop every claim when a session ends, and the job of the *old* session then
     * finishes afterwards. An unconditional `remove` would delete the claim the new session (or the
     * next drive) just registered, so the same page could be read and OCRed twice.
     */
    private val inFlight = ConcurrentHashMap<OcrPageKey, Any>()
    // KUN <--

    /** Earliest retry time (ns) for a page that could not be resolved yet; bounded retry rate. */
    private val retryAfter = ConcurrentHashMap<OcrPageKey, Long>()

    /**
     * Pages whose watchdog already logged a timeout, so a genuine hang logs **once per hang
     * episode**, not once per frame or per retry. Cleared when the page finally resolves or the
     * in-memory cache is [invalidate]d.
     */
    private val timeoutLogged = ConcurrentHashMap.newKeySet<OcrPageKey>()

    /**
     * Earliest retry time (ns) for the local OCR engine once an `init()` failed (e.g. an `x86_64`
     * emulator or a device without the downloaded models). Bounds the engine init/log spam that
     * would otherwise happen once per page, while still recovering if the models arrive later.
     */
    private val engineRetryAfter = AtomicLong(0L)

    // KUN -->
    /**
     * The reader session's **page work** scope. Never [scope]: page work reads the chapter's page
     * bytes, and those streams are backed by the reader's archive, which `ArchivePageLoader.recycle`
     * `munmap`s (it no longer exists once a chapter is switched away or the reader is destroyed).
     * A load launched on the app-lifetime [scope] therefore outlives the archive it reads and used to
     * fault inside libarchive (`SIGSEGV`/`SEGV_MAPERR` in `archive_read_open1`, which no `catch` can
     * handle — the mapped range is simply gone). This scope is cancelled and replaced by
     * [endSession] — when the reader goes away, and when the reader opens another chapter — so the
     * next session still has live work.
     */
    @Volatile
    private var work: CoroutineScope = newWorkScope()

    /**
     * The chapter the current **on-demand** session belongs to, or [NO_CHAPTER] when no session is
     * running. Exactly one on-demand session exists at a time: the reader is one session per chapter,
     * so a drive for a different chapter ends the previous one (see [beginSessionFor]).
     *
     * Only written from the session entry points, which are main-thread ([ensureLoaded], [redoOcr])
     * and from [detach].
     */
    @Volatile
    private var sessionChapterId: Long = NO_CHAPTER

    /**
     * The in-flight job of each page, so a forced re-run ([redoOcr]) can cancel the load it
     * supersedes. Cancellation is cooperative: it is what keeps a read that has not started yet from
     * happening at all; `ArchiveReadGuard` is what makes an already-started read safe.
     */
    private val jobs = ConcurrentHashMap<OcrPageKey, Job>()

    private fun newWorkScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Stops every page/redo job the reader started — called from the session that owns the reader
     * (`ReaderOcrSessionImpl.release`), i.e. when the reader activity is really going away — and
     * drops what they accumulated: a cancelled load cannot complete, so its retry/in-flight
     * bookkeeping is meaningless, and the blocks are re-read from the persisted OCR cache by the next
     * reader session.
     *
     * The loader stays usable ([ensureLoaded] afterwards runs on a fresh scope) because it is an
     * app-graph singleton shared by every reader session.
     */
    fun detach() = endSession()

    /**
     * Ends the current on-demand session: cancels its page/redo jobs (they read the chapter archive,
     * which the reader recycles as soon as it leaves that chapter) and drops what they accumulated.
     * The loader stays usable, because the next drive gets a fresh scope.
     *
     * Only [work] is cancelled — never the injected app-lifetime [scope], which also carries
     * [observeOcrQueue]. The background queue (the download-triggered path) is not part of the
     * on-demand session and must keep running and keep updating an open reader.
     */
    private fun endSession() {
        val previous = work
        work = newWorkScope()
        sessionChapterId = NO_CHAPTER
        previous.cancel()
        jobs.clear()
        inFlight.clear()
        retryAfter.clear()
        timeoutLogged.clear()
        _blocks.update { emptyMap() }
    }

    /**
     * Makes the chapter of [pages] the one on-demand session, ending the previous one when those
     * pages belong to a **different** chapter.
     *
     * This is the last-wins rule: the reader switches chapter inside the same activity (next chapter,
     * chapter dialog, a re-entry through `onNewIntent`), and nothing used to stop the chapter being
     * left from finishing its OCR — its page jobs kept reading the archive the reader had already
     * recycled, and raced the new chapter's session over the engine and the cache.
     *
     * A drive for the session's own chapter — the per-frame re-drive of the visible pages and their
     * neighbours, a page turn inside the chapter, opening the same chapter again — is not a new
     * session: cancelling there would cancel the very loads the drive is about to need.
     */
    private fun beginSessionFor(pages: List<ReaderPage>) {
        val chapterIds = pages.mapNotNullTo(LinkedHashSet()) { page ->
            page.chapter.chapter.id.takeIf { it > 0L }
        }
        // A chapter that is still on screen keeps the session alive even beside another chapter's
        // pages: the reader has not left it yet.
        if (chapterIds.isEmpty() || sessionChapterId in chapterIds) return
        endSession()
        sessionChapterId = chapterIds.first()
    }
    // KUN <--

    init {
        // Keep the reader's in-memory cache fresh while the background queue OCRs the same chapter,
        // so blocks appear live without leaving/re-entering it (fix: `invalidate()` had no caller).
        observeOcrQueue()
    }

    /** Starts loading the blocks of [pages] that are not cached yet. Main-thread callers only. */
    fun ensureLoaded(pages: List<ReaderPage>) {
        // KUN --> gate at the work entry: no sidecar/cache/engine reads while the feature is off.
        if (!dictionaryOcrPreferences.isEnabled()) return
        // KUN <--
        // KUN --> last-wins: pages from a chapter other than the session's start a new session.
        beginSessionFor(pages)
        // KUN <--
        val now = System.nanoTime()
        pages.forEach { page ->
            val key = page.ocrKey()
            if (key.chapterId <= 0L || _blocks.value.containsKey(key)) return@forEach
            retryAfter[key]?.let { if (now < it) return@forEach }
            // KUN -->
            // Claim the page with a token of **this** job: the release in [runPageLoad] is
            // ownership-checked, so a job that outlives its session cannot clear a later claim.
            val claim = Any()
            if (inFlight.putIfAbsent(key, claim) != null) return@forEach
            // LAZY start so the job is registered before it can run (and remove itself) — page work
            // always runs on the session scope, never on the app-lifetime one.
            val job = work.launch(start = CoroutineStart.LAZY) {
                val self = coroutineContext[Job]
                try {
                    runPageLoad(page, key, claim)
                } finally {
                    // Compare-and-remove: a cancelled job reaches this late, after its successor (a
                    // forced re-run, or a re-drive once the page was released) registered itself
                    // under the same key. An unconditional `remove` would delete *that*
                    // registration, and the successor could then never be cancelled.
                    if (self != null) jobs.remove(key, self)
                }
            }
            jobs[key] = job
            job.start()
            // KUN <--
        }
    }

    // KUN -->
    /** One page's [load] with the watchdog and the outcome bookkeeping (see [ensureLoaded]). */
    private suspend fun runPageLoad(page: ReaderPage, key: OcrPageKey, claim: Any) {
        // Watchdog (F2): a future hang can never again be invisible. `withTimeoutOrNull`
        // cannot distinguish a timeout from a block that returned null, so the result is
        // wrapped in a non-null [LoadOutcome].
        val outcome = runCatching {
            withTimeoutOrNull(loadTimeoutMs) { LoadOutcome(load(page)) }
        }.getOrElse { e ->
            // F1: never treat cancellation as a failure — rethrow it so the job is cancelled
            // (same pattern as `runEngineOcr`). Other throwables are never swallowed:
            // a throwing load() looked exactly like "OCR does nothing" (no logs, no cache,
            // endless retry). See `ort-v6-migration.md` §6/§9.
            if (e is CancellationException) throw e
            logcat(LogPriority.WARN, e) { "ReaderOcr: failed to resolve OCR blocks for page ${page.index}" }
            null
        }
        // Compare-and-remove with this job's own token: [detach] clears every claim when a reader
        // session ends, and this job may finish only afterwards — an unconditional `remove` would
        // delete the claim the next session registered for the same page and let it load twice.
        inFlight.remove(key, claim)
        when (outcome) {
            // The load did not return within [loadTimeoutMs]: assume a hang and treat it
            // exactly like "not resolvable yet" — retry later, never cache an empty result.
            null -> {
                logLoadTimeout(page)
                retryAfter[key] = System.nanoTime() + RETRY_INTERVAL_NANOS
            }
            else -> {
                val loaded = outcome.blocks
                // The switch can be turned off while this load is suspended: `load()` then
                // gets no blocks from the manager (an opt-out is inert, not "no text here").
                // Caching that empty list would mark the page as resolved-empty, so a later
                // re-enable in the same reader session would never OCR it again. Drop it as
                // unresolved instead — no backoff, because [ensureLoaded] is gated on the
                // switch, so nothing re-drives this page until the feature is back on.
                val droppedByOptOut = loaded != null && loaded.isEmpty() &&
                    !dictionaryOcrPreferences.isEnabled()
                when {
                    loaded == null -> retryAfter[key] = System.nanoTime() + RETRY_INTERVAL_NANOS
                    droppedByOptOut -> retryAfter.remove(key)
                    else -> {
                        // A completed resolution (even an empty one) is cached; null retries later.
                        timeoutLogged.remove(key)
                        retryAfter.remove(key)
                        _blocks.update { it + (key to loaded) }
                    }
                }
            }
        }
    }
    // KUN <--

    /** The blocks already loaded for [page] (empty until [ensureLoaded] finishes). */
    fun blocksFor(page: ReaderPage): List<OcrTextBlock> = _blocks.value[page.ocrKey()].orEmpty()

    /** Drops the in-memory cache; the next [ensureLoaded] re-reads the persisted OCR cache. */
    fun invalidate() {
        _blocks.update { emptyMap() }
        inFlight.clear()
        retryAfter.clear()
        timeoutLogged.clear()
    }

    /**
     * Refreshes the in-memory cache when the background OCR queue advances or a chapter leaves it,
     * so an already-open page picks up blocks the worker just persisted (fix: `invalidate()` had no
     * caller). Dropped only when a chapter whose pages are actually cached changed, to avoid needless
     * reload churn from unrelated queue activity.
     */
    private fun observeOcrQueue() {
        scope.launch {
            var previous = emptyMap<Long, Int>()
            ocrManager.queueState.collect { items ->
                val current = items.associate { it.chapterId to it.currentPage }
                val changed = buildSet {
                    current.forEach { (chapterId, page) ->
                        val before = previous[chapterId]
                        if (before != null && before != page) add(chapterId)
                    }
                    // A chapter that left the queue finished, failed or was cancelled: re-read it.
                    addAll(previous.keys - current.keys)
                }
                val cachedChapters = _blocks.value.keys.map { it.chapterId }
                if (changed.any { it in cachedChapters }) invalidate()
                previous = current
            }
        }
    }

    /**
     * Logs one page's watchdog timeout **once per hang episode**. A genuine hang (a stalled native
     * call or a deadlocked suspension point) must be visible; the reader re-drives [ensureLoaded]
     * while the page is shown, so this is deduplicated per page (not per frame/retry) via
     * [timeoutLogged], and cleared once the page finally resolves or the cache is [invalidate]d.
     */
    private fun logLoadTimeout(page: ReaderPage) {
        if (timeoutLogged.add(page.ocrKey())) {
            logcat(LogPriority.WARN) {
                "ReaderOcr: OCR for page ${page.index} did not complete within ${loadTimeoutMs}ms; retrying later"
            }
        }
    }

    /**
     * Resolves one page through the source policy (03 §4, fix B1). Returns null only when the page
     * cannot be resolved *yet* (manga missing, page bytes unavailable); a completed resolution with
     * nothing to show returns an empty list so the loader stops retrying it.
     */
    private suspend fun load(page: ReaderPage): List<OcrTextBlock>? {
        val chapter = page.chapter.chapter
        val manga = getManga.await(chapter.mangaId) ?: return null
        val source = ocrManager.sourceFor(chapter.mangaId)
        val key = ocrManager.cacheKey(
            sourceId = manga.source,
            mangaId = chapter.mangaId,
            chapterId = chapter.id,
        )

        // 1. Cached engine OCR results take top priority. This allows a forced OCR run
        // to overwrite/supersede a .mokuro sidecar in the archive.
        if (source.persistsOcrResults) {
            ocrManager.cachedBlocks(key, page.index, source)?.let { return it }
        }

        // 2. Fall back to .mokuro sidecar if the source policy allows it.
        if (source.usesMokuro) {
            loadMokuroBlocks(page, manga)?.let { return it }
            // MOKURO-only: the sidecar *is* the cache and there is no engine fallback.
            if (!source.persistsOcrResults) return emptyList()
        }

        // 3. Fall back to running engine OCR on demand.
        return runEngineOcr(page, key, source, chapter.mangaId, manga.source)
    }

    /**
     * Force re-runs OCR for [page] (or all pages in the chapter if [chapter] is true),
     * overwriting the persisted cache and overriding any .mokuro sidecar in the archive.
     *
     * [onComplete] is called **exactly once**, with how the re-run ended — including when nothing ran
     * at all. A re-run in which every page failed (models missing, engine load failure, no page
     * bytes) is a [RedoOcrResult.FAILED], never a success, and the "nothing to redo" early returns
     * report themselves instead of leaving the reader's "Re-running OCR…" state as the last word.
     */
    fun redoOcr(
        page: ReaderPage,
        chapter: Boolean = false,
        onComplete: ((RedoOcrResult) -> Unit)? = null,
    ) {
        // KUN --> gated work entry: a forced re-run must not bypass the disabled feature.
        if (!dictionaryOcrPreferences.isEnabled()) {
            // Still "exactly once": the caller has already been told a re-run started, so this
            // settles that state instead of leaving "Re-running OCR…" as the last word.
            onComplete?.invoke(RedoOcrResult.NOTHING_TO_REDO)
            return
        }
        // KUN <--
        // KUN --> A forced re-run is on-demand work too: it starts (or joins) the chapter's session,
        // so a re-run of a chapter the reader has left is cancelled like any other on-demand load.
        beginSessionFor(listOf(page))
        // KUN <--
        work.launch {
            val pagesToRedo = if (chapter) {
                page.chapter.pages.orEmpty()
            } else {
                listOf(page)
            }
            if (pagesToRedo.isEmpty()) {
                onComplete?.invoke(RedoOcrResult.NOTHING_TO_REDO)
                return@launch
            }

            val ch = page.chapter.chapter
            val manga = getManga.await(ch.mangaId)
            if (manga == null) {
                // Without the manga row there is no source and no cache key: not one page can run,
                // so the caller must not be told the OCR was updated.
                onComplete?.invoke(RedoOcrResult.NOTHING_TO_REDO)
                return@launch
            }
            val source = ocrManager.sourceFor(ch.mangaId)
            val cacheKey = ocrManager.cacheKey(
                sourceId = manga.source,
                mangaId = ch.mangaId,
                chapterId = ch.id,
            )
            val language = resolveLanguage(ch.mangaId, manga.source)

            var redone = 0
            for (p in pagesToRedo) {
                val key = p.ocrKey()
                // KUN -->
                // A forced re-run supersedes whatever load is running for this page. Claim the page
                // for the **whole** redo body: the reader re-drives `ensureLoaded` every frame with
                // the visible pages and their neighbours, so releasing the slot here would let a
                // normal load start beside the redo a frame later — the page then read and inferred
                // twice, and the loser can overwrite `_blocks[key]`. Then cancel the load that was
                // already registered so the two never read the same page at once. The redo claims
                // the page with its own token, so the cancelled load's late release cannot drop it.
                val claim = Any()
                inFlight[key] = claim
                jobs.remove(key)?.cancel()
                // KUN <--
                _blocks.update { it - key }
                retryAfter.remove(key)
                timeoutLogged.remove(key)

                var pageRedone = false
                try {
                    // A page with no readable bytes is a page this re-run gave up on — counted as a
                    // failure, so an all-unreadable run reports failure instead of success.
                    val bytes = pageBytes(p)
                    if (bytes == null) {
                        logcat(LogPriority.WARN) { "ReaderOcr: redo OCR has no page bytes for page ${p.index}" }
                    } else {
                        try {
                            val blocks = ocrManager.forceRecognizeAndCache(
                                key = cacheKey,
                                pageIndex = p.index,
                                bytes = bytes,
                                language = language,
                                source = source,
                            )
                            _blocks.update { it + (key to blocks) }
                            pageRedone = true
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            logcat(LogPriority.WARN, e) { "ReaderOcr: redo OCR failed for page ${p.index}" }
                        }
                    }
                } finally {
                    // KUN --> the page is only released once the re-run is over or gave up, so a
                    // per-frame `ensureLoaded` resumes it as an ordinary load from then on — unless
                    // a newer claim (a superseding redo or a new session) replaced this one.
                    inFlight.remove(key, claim)
                    // KUN <--
                }
                if (pageRedone) redone++
            }
            onComplete?.invoke(if (redone > 0) RedoOcrResult.UPDATED else RedoOcrResult.FAILED)
        }
    }

    // ---------------------------------------------------------------- mokuro

    /** Loads the page's blocks from a `.mokuro` sidecar, or null when none applies. */
    private suspend fun loadMokuroBlocks(page: ReaderPage, manga: Manga): List<OcrTextBlock>? {
        val chapter = page.chapter.chapter
        val source = sourceManager.get(manga.source) ?: return null
        val chapterFile = downloadProvider.findChapterDir(
            chapterName = chapter.name,
            chapterScanlator = chapter.scanlator,
            chapterUrl = chapter.url,
            mangaTitle = manga.ogTitle,
            source = source,
        ) ?: return null

        val sidecar = findMokuroSidecar(chapterFile) ?: return null
        val content = runCatching {
            sidecar.openInputStream().use { it.readBytes().decodeToString() }
        }.getOrElse { e ->
            logcat(LogPriority.WARN, e) { "ReaderOcr: failed to read mokuro sidecar ${sidecar.name}" }
            return null
        }

        val imageFiles = page.chapter.pages.orEmpty().mapNotNull { it.toImageFileInfo() }
        return MokuroChapterLoader.blocksForPage(content, imageFiles, page.index)
    }

    /**
     * Finds a chapter's `.mokuro` sidecar: inside a directory chapter, or beside a directory/CBZ
     * chapter (sibling file). Returned as a `UniFile` so it also works on storage-access-framework
     * locations where no plain path exists.
     */
    private fun findMokuroSidecar(chapterFile: UniFile): UniFile? {
        val parent = chapterFile.parentFile
        val presentNames = buildSet {
            if (chapterFile.isDirectory) {
                chapterFile.listFiles().orEmpty().mapNotNullTo(this) { it.name }
            }
            parent?.listFiles().orEmpty().mapNotNullTo(this) { it.name }
        }

        val chosen = MokuroChapterLoader.selectSidecar(chapterFile.name.orEmpty(), presentNames) ?: return null
        val inside = if (chapterFile.isDirectory) chapterFile.findFile(chosen) else null
        return inside ?: parent?.findFile(chosen)
    }

    private fun ReaderPage.toImageFileInfo(): ImageFileInfo? {
        val name = (imageUrl ?: url).substringAfterLast('/').substringBefore('?')
        if (name.isBlank()) return null
        return ImageFileInfo(
            name = name,
            relativePath = name,
            basename = name.substringBeforeLast('.'),
        )
    }

    // ---------------------------------------------------------------- engine

    /** Runs the engine over the page byte stream and persists the result (fix B1/B4). */
    private suspend fun runEngineOcr(
        page: ReaderPage,
        key: OcrCacheKey,
        source: ReaderOcrSource,
        mangaId: Long,
        sourceId: Long,
    ): List<OcrTextBlock>? {
        // An unavailable engine (x86_64 emulator, models not downloaded) is tolerated: skip it until
        // its cooldown elapses instead of re-initializing (and logging) once per page. Returning null
        // leaves the page unresolved, so [ensureLoaded] retries it later rather than caching "empty".
        if (!isEngineAvailable()) return null

        val bytes = pageBytes(page) ?: return null
        val language = resolveLanguage(mangaId, sourceId)
        val result = runCatching {
            ocrManager.recognizeAndCache(key, page.index, bytes, language, source)
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            logcat(LogPriority.WARN, e) { "ReaderOcr: engine OCR failed for page ${page.index}" }
            emptyList()
        }

        val current = engine
        if (current is PaddleOcrEngine && !current.isInitialized()) {
            // The attempt did not bring the engine up; back off before trying again.
            engineRetryAfter.set(System.nanoTime() + ENGINE_RETRY_COOLDOWN_NANOS)
            logcat(LogPriority.INFO) { "ReaderOcr: local OCR engine unavailable, backing off" }
            // KUN --> An attempt that the engine never came up for produced no text, which is "not
            // resolvable yet", not "this page has no text": returning the (empty) result would cache
            // it as a resolved page, and `ensureLoaded` skips pages already in `_blocks`, so the
            // page would stay blank until the reader is reopened even after the models arrive.
            return null
            // KUN <--
        }
        return result
    }

    /** False while the Paddle engine is known unavailable and its retry cooldown has not elapsed. */
    private fun isEngineAvailable(): Boolean {
        val current = engine
        return current !is PaddleOcrEngine ||
            current.isInitialized() ||
            System.nanoTime() >= engineRetryAfter.get()
    }

    private fun pageBytes(page: ReaderPage): ByteArray? {
        val stream = page.stream ?: return null
        return runCatching { stream().use { it.readBytes() } }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun resolveLanguage(mangaId: Long, sourceId: Long): OcrLanguage {
        val profile = dictionaryPreferences.profileResolver.resolve(mangaId = mangaId, sourceId = sourceId)
        return OcrLanguage.entries.firstOrNull { it.bcp47.equals(profile.languageCode, ignoreCase = true) }
            ?: DEFAULT_LANGUAGE
    }

    private companion object {
        private val DEFAULT_LANGUAGE = OcrLanguage.JAPANESE

        /** No on-demand session has been started (chapter ids are row ids, so always positive). */
        private const val NO_CHAPTER = 0L

        /** Minimum gap between retries of a page that is not resolvable yet (bounds DB/disk churn). */
        private const val RETRY_INTERVAL_NANOS = 1_000_000_000L

        /** How long to stop retrying a local OCR engine that failed to initialize. */
        private const val ENGINE_RETRY_COOLDOWN_NANOS = 60_000_000_000L

        /**
         * How long one page's [load] may run before the reader assumes a hang (F2). Deliberately
         * generous (tens of seconds): the first OCR run of a session pays for native engine init and
         * model load, which is seconds on device, while a genuine hang never completes and must be
         * surfaced rather than silently starve the page. See [logLoadTimeout].
         */
        private const val DEFAULT_LOAD_TIMEOUT_MS = 60_000L
    }
}

/**
 * Non-null carrier for [ReaderOcrBlockLoader.load]'s result so `withTimeoutOrNull` can tell a timed
 * out load (outer `null`) from a load that legitimately returned null — "not resolvable yet" (inner
 * `null`). With a nullable `T`, `withTimeoutOrNull` returns `null` for both cases.
 */
private class LoadOutcome(val blocks: List<OcrTextBlock>?)
