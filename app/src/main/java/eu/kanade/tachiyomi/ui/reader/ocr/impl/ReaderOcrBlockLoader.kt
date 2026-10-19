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
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * only this GPL-package helper and `ReaderOcrHostImpl` do.
 */
class ReaderOcrBlockLoader(
    private val ocrManager: OcrManager,
    private val engine: OcrEngine,
    private val getManga: GetManga,
    private val downloadProvider: DownloadProvider,
    private val sourceManager: SourceManager,
    private val dictionaryPreferences: DictionaryPreferences,
    @AppCoroutineScope private val scope: CoroutineScope,
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

    private val inFlight = ConcurrentHashMap.newKeySet<OcrPageKey>()

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

    init {
        // Keep the reader's in-memory cache fresh while the background queue OCRs the same chapter,
        // so blocks appear live without leaving/re-entering it (fix: `invalidate()` had no caller).
        observeOcrQueue()
    }

    /** Starts loading the blocks of [pages] that are not cached yet. Main-thread callers only. */
    fun ensureLoaded(pages: List<ReaderPage>) {
        val now = System.nanoTime()
        pages.forEach { page ->
            val key = page.ocrKey()
            if (key.chapterId <= 0L || _blocks.value.containsKey(key)) return@forEach
            retryAfter[key]?.let { if (now < it) return@forEach }
            if (!inFlight.add(key)) return@forEach
            scope.launch(Dispatchers.IO) {
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
                inFlight.remove(key)
                when (outcome) {
                    // The load did not return within [loadTimeoutMs]: assume a hang and treat it
                    // exactly like "not resolvable yet" — retry later, never cache an empty result.
                    null -> {
                        logLoadTimeout(page)
                        retryAfter[key] = System.nanoTime() + RETRY_INTERVAL_NANOS
                    }
                    else -> {
                        val loaded = outcome.blocks
                        if (loaded == null) {
                            retryAfter[key] = System.nanoTime() + RETRY_INTERVAL_NANOS
                        } else {
                            // A completed resolution (even an empty one) is cached; null retries later.
                            timeoutLogged.remove(key)
                            retryAfter.remove(key)
                            _blocks.update { it + (key to loaded) }
                        }
                    }
                }
            }
        }
    }

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
     */
    fun redoOcr(page: ReaderPage, chapter: Boolean = false, onComplete: (() -> Unit)? = null) {
        scope.launch(Dispatchers.IO) {
            val pagesToRedo = if (chapter) {
                page.chapter.pages.orEmpty()
            } else {
                listOf(page)
            }
            if (pagesToRedo.isEmpty()) return@launch

            val ch = page.chapter.chapter
            val manga = getManga.await(ch.mangaId) ?: return@launch
            val source = ocrManager.sourceFor(ch.mangaId)
            val cacheKey = ocrManager.cacheKey(
                sourceId = manga.source,
                mangaId = ch.mangaId,
                chapterId = ch.id,
            )
            val language = resolveLanguage(ch.mangaId, manga.source)

            for (p in pagesToRedo) {
                val key = p.ocrKey()
                _blocks.update { it - key }
                inFlight.remove(key)
                retryAfter.remove(key)
                timeoutLogged.remove(key)

                val bytes = pageBytes(p) ?: continue
                try {
                    val blocks = ocrManager.forceRecognizeAndCache(
                        key = cacheKey,
                        pageIndex = p.index,
                        bytes = bytes,
                        language = language,
                        source = source,
                    )
                    _blocks.update { it + (key to blocks) }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logcat(LogPriority.WARN, e) { "ReaderOcr: redo OCR failed for page ${p.index}" }
                }
            }
            onComplete?.invoke()
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
