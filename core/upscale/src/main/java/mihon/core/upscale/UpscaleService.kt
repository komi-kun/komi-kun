// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The app-scoped upscaling facade: cache lookup, request queue, focus, cancellation.
//
// Not an Android `Service` — just an object the DI graph owns (phase 4 decides the lifecycle). It
// schedules everything on the engine's single inference thread, so the queue, the ORT session and the
// page decode all live on one thread and nothing here needs a lock around the engine itself.
//
// **The reader's contract.** `cached(...)` answers synchronously, on whatever thread asks, so a page
// that is already upscaled can be served without suspending. `request(...)` never blocks: it enqueues
// `pageBytes` (a *lazy* supplier, so a page that is no longer wanted is never read from disk),
// dedupes against queued/in-flight work for the same cache key, and drops silently while upscaling is
// off or the model is not installed.
//
// `focus(chapterId, pageIndex)` is what keeps the reader responsive: the visible page and the next
// three are served first, and anything queued for another chapter is dropped. A request that arrives
// for another chapter *after* a focus is still accepted — it is just served last — so a reader that
// prefetches the next chapter during the gap keeps working.
//
// `requestAuto(...)` is the reader's entry point: it takes the *routing* decision as a lambda and runs
// it after decoding, because a page's height and colour are only known then. `request(...)` names the
// model up front and stays as the explicit/benchmark/test path.
//
// **Reporting the end of a page.** Every request takes an `onDone` that is handed an [UpscaleOutcome],
// so the reader can remember a page it can never upscale (and stop asking for it on every page turn)
// without having to guess from the cache — and, just as important, can tell a *cancelled* page from a
// failed one, because a cancellation says nothing about the page itself.
//
// **Cancellation.** A run captures its cancel token *before* it reads the page's bytes: `cancelAll`
// swaps the token, so a token read after a long decode would be the fresh one and the page would run
// (and be written) long after the reader moved on.
// KUN <--
package mihon.core.upscale

import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import mihon.core.upscale.cache.UpscaleCache
import mihon.core.upscale.engine.DecodedPage
import mihon.core.upscale.engine.PageDecoder
import mihon.core.upscale.engine.UpscaleCancelledException
import mihon.core.upscale.engine.UpscaleEngine
import mihon.core.upscale.engine.UpscaleResult
import mihon.core.upscale.model.InstalledModels
import mihon.core.upscale.model.UpscaleModel
import tachiyomi.core.common.preference.UpscalePreferences
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Identity of one page, as far as the cache is concerned.
 *
 * [sourceLength] and [sourceLastModified] make a re-downloaded chapter miss instead of serving an
 * upscale of the previous bytes.
 */
data class PageKey(
    val sourceId: Long,
    val mangaId: Long,
    val chapterId: Long,
    val pageIndex: Int,
    val sourceLength: Long,
    val sourceLastModified: Long,
)

/**
 * How one queued page ended.
 *
 * The caller needs this to tell three different things apart: a page that now has a cache entry, a
 * page that can never be upscaled under the current selection (remember it and stop asking), and a
 * page that was merely cancelled or dropped (say nothing about it — the page itself is fine).
 */
enum class UpscaleOutcome {
    /** The page has a cache entry (written now, or already there). */
    CACHED,

    /** Nothing ran: upscaling is off. */
    DISABLED,

    /** The model the page needed is not installed (any more). */
    NO_MODEL,

    /** The page's bytes could not be read or decoded. */
    UNDECODABLE,

    /** No model applies to this page: the router said leave it at its own resolution. */
    NO_ROUTE,

    /** The page is over the output cap, or the inference/encode failed. */
    FAILED,

    /** A cancel arrived before the page was written; `cancelAll` swapped the token. */
    CANCELLED,
}

/** Quality the cached page is encoded at (see [JpegPageEncoder]). */
private const val JPEG_QUALITY = 95

/** Encodes a finished page into its cache entry. */
fun interface PageEncoder {
    fun encode(result: UpscaleResult, target: File)
}

/**
 * The app's encoder: JPEG at quality 95. Lossless PNG would be roughly four times the bytes for line
 * art, and the cached page is only ever an intermediate (the reader decodes it like a source page).
 */
val JpegPageEncoder: PageEncoder = PageEncoder { result, target ->
    val bitmap = result.bitmap ?: error("the engine produced no bitmap")
    FileOutputStream(target).use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
            "could not encode the upscaled page"
        }
    }
}

/**
 * The reader-facing upscaling service (one instance, app-scoped).
 */
class UpscaleService(
    private val cache: UpscaleCache,
    private val models: InstalledModels,
    private val engine: UpscaleEngine,
    private val preferences: UpscalePreferences,
    /** Decodes the compressed page; overridable so the queue is testable without a decoder. */
    private val pageDecoder: (ByteArray) -> DecodedPage? = PageDecoder::decode,
    /** Tile size in input pixels, before any memory-pressure fallback. */
    private val tileSize: () -> Int = { DEFAULT_TILE_SIZE },
    /** Encodes a finished page into its cache entry (`JpegPageEncoder` in the app). */
    private val encoder: PageEncoder = JpegPageEncoder,
) : AutoCloseable {

    private class Pending(
        val key: String,
        val pageKey: PageKey,
        /** The model to run; `null` when [route] decides it from the decoded page. */
        val model: UpscaleModel?,
        /** The routing decision of `requestAuto`; `null` for an explicit-model [request]. */
        val route: ((DecodedPage) -> UpscaleModel?)?,
        val scale: Int,
        val signature: String,
        val pageBytes: () -> ByteArray?,
        /** Every caller waiting for this page, so a coalesced request is not left waiting forever. */
        val onDone: MutableList<(UpscaleOutcome) -> Unit>,
    )

    private val lock = Any()
    private val pending = ArrayList<Pending>()
    private val known = HashSet<String>()

    /** The chapter the reader is showing, and its page; `null` until [focus] is called once. */
    private var focusChapter: Long? = null
    private var focusPage: Int = 0

    /** Flipped by [cancelAll] to abort the running page; replaced for the next run. */
    @Volatile
    private var cancelToken = AtomicBoolean(false)

    private var draining = false
    private val closed = AtomicBoolean(false)

    /** The cached upscale of this page, or `null`. Synchronous by design (see the file header). */
    fun cached(pageKey: PageKey, model: UpscaleModel, scale: Int, signature: String): File? =
        cachedFor(pageKey, model.id, scale, signature)

    /** [cached] by model id, for a caller that knows the id but not the catalog object. */
    fun cachedFor(pageKey: PageKey, modelId: String, scale: Int, signature: String): File? =
        cache.get(keyFor(pageKey, modelId, scale, signature))

    /**
     * Queues [pageKey] for upscaling.
     *
     * Dropped when upscaling is off or [model] is not installed; coalesced when the same page is
     * already queued or running; [onDone] is invoked on the inference thread once the page is done
     * (whether an entry was written or not), and immediately when it is already cached.
     */
    fun request(
        pageKey: PageKey,
        pageBytes: () -> ByteArray?,
        model: UpscaleModel,
        scale: Int,
        signature: String,
        onDone: ((UpscaleOutcome) -> Unit)? = null,
    ) {
        if (closed.get() || !preferences.isEnabled()) return
        if (models.fileFor(model) == null) return
        val key = keyFor(pageKey, model.id, scale, signature)
        if (cache.get(key) != null) {
            onDone?.invoke(UpscaleOutcome.CACHED)
            return
        }
        val item = Pending(key, pageKey, model, null, scale, signature, pageBytes, mutableListOf())
        onDone?.let { item.onDone += it }
        enqueue(item)
    }

    /**
     * Queues [pageKey] for upscaling with the model [route] picks from the **decoded** page.
     *
     * The reader cannot know a page's height or whether it is colour before decoding it, so the
     * routing rule (`GrayscaleDetector` + `UpscaleRouter.route`) runs on the engine thread, on the one
     * decode this request already pays for. Nothing here trusts a colour page to a manga-family model:
     * [route] is the router, and `null` from it leaves the page at its own resolution.
     *
     * Unlike [request] the cache key is only known after routing, so the reader's own lookup
     * (`UpscaleCache.get` behind the reader session) is what normally avoids re-queueing a page; a
     * page whose entry appeared meanwhile is skipped instead of re-upscaled.
     *
     * Dropped while upscaling is off, and coalesced by page identity (not by model — which model to
     * run is exactly what is still open) while queued or running. Whether *any* model is installed is
     * deliberately not asked here: `InstalledModels.installedIds()` re-reads the install marker and
     * parses the manifest, and this runs on the reader's page-turn path. The caller's pre-decode gate
     * (`UpscaleRuntimePolicy.isActive` over its own memoised set) is what stops the work, and
     * [route] itself is what decides whether a model applies at all.
     */
    fun requestAuto(
        pageKey: PageKey,
        pageBytes: () -> ByteArray?,
        scale: Int,
        signature: String,
        route: (DecodedPage) -> UpscaleModel?,
        onDone: ((UpscaleOutcome) -> Unit)? = null,
    ) {
        if (closed.get() || !preferences.isEnabled()) return
        // The routing identity: the same page and query, whatever model it resolves to.
        val key = keyFor(pageKey, ROUTING_KEY_MODEL_ID, scale, signature)
        val item = Pending(key, pageKey, null, route, scale, signature, pageBytes, mutableListOf())
        onDone?.let { item.onDone += it }
        enqueue(item)
    }

    /** Adds [item] to the queue, coalescing onto a run already queued for its key; starts draining. */
    private fun enqueue(item: Pending) {
        val schedule = synchronized(lock) {
            val existing = pending.firstOrNull { it.key == item.key }
            if (existing != null) {
                // Coalesced: wait on the run that is already queued instead of starting a second one.
                existing.onDone += item.onDone
                return
            }
            if (!known.add(item.key)) return
            pending += item
            if (draining) {
                false
            } else {
                draining = true
                true
            }
        }
        if (schedule) {
            runCatching { engine.submit { drain() } }.onFailure {
                synchronized(lock) {
                    pending.clear()
                    known.clear()
                    draining = false
                }
            }
        }
    }

    /**
     * Re-prioritises the queue around the visible page: it, then the next three, come first, and work
     * queued for another chapter is dropped.
     */
    fun focus(chapterId: Long, pageIndex: Int) {
        synchronized(lock) {
            focusChapter = chapterId
            focusPage = pageIndex
            val dropped = pending.filter { it.pageKey.chapterId != chapterId }
            if (dropped.isNotEmpty()) {
                pending.removeAll(dropped.toSet())
                dropped.forEach { known.remove(it.key) }
            }
        }
    }

    /** Drops queued work and aborts the page in flight; nothing from it reaches the cache. */
    fun cancelAll() {
        cancelToken.set(true)
        cancelToken = AtomicBoolean(false)
        synchronized(lock) {
            pending.clear()
            known.clear()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancelAll()
        engine.close()
    }

    // ------------------------------------------------------------------ queue

    private fun drain() {
        while (true) {
            val item = synchronized(lock) {
                if (closed.get()) {
                    pending.clear()
                    known.clear()
                    draining = false
                    return
                }
                val next = nextItem()
                if (next == null) {
                    draining = false
                    return
                }
                next
            }
            val outcome = try {
                runItem(item)
            } catch (_: UpscaleCancelledException) {
                // Expected: `cancelAll` aborted this page.
                UpscaleOutcome.CANCELLED
            } catch (error: Throwable) {
                logcat { "UpscaleService: ${item.pageKey} failed: ${error.message}" }
                UpscaleOutcome.FAILED
            } finally {
                synchronized(lock) {
                    pending.remove(item)
                    known.remove(item.key)
                }
            }
            synchronized(lock) { item.onDone.toList() }.forEach { it(outcome) }
        }
    }

    /** The highest-priority queued page: the focused page, then +1..+3, then everything else. */
    private fun nextItem(): Pending? {
        if (pending.isEmpty()) return null
        val chapter = focusChapter ?: return pending.first()
        val page = focusPage
        return pending.minByOrNull { item ->
            val distance = item.pageKey.pageIndex - page
            when {
                item.pageKey.chapterId != chapter -> FOCUS_FAR
                distance in 0..PREFETCH_PAGES -> distance
                else -> PREFETCH_PAGES + 1
            }
        }
    }

    /** Runs one queued page and reports how it ended, for the caller's negative memo. */
    private fun runItem(item: Pending): UpscaleOutcome {
        if (!preferences.isEnabled()) return UpscaleOutcome.DISABLED
        item.model?.let { model -> if (models.fileFor(model) == null) return UpscaleOutcome.NO_MODEL }
        // Captured *before* the bytes are read: `cancelAll` replaces the token, so a token read after
        // the (long) read/decode/route would be the fresh one and this page would run to the end.
        val token = cancelToken
        if (token.get()) return UpscaleOutcome.CANCELLED
        val bytes = item.pageBytes() ?: return UpscaleOutcome.UNDECODABLE
        val page = pageDecoder(bytes) ?: return UpscaleOutcome.UNDECODABLE
        if (token.get()) return UpscaleOutcome.CANCELLED
        // An auto request routes on the one decode it already paid for; `null` keeps the page as it is.
        val model = item.model ?: item.route?.invoke(page) ?: return UpscaleOutcome.NO_ROUTE
        if (token.get()) return UpscaleOutcome.CANCELLED
        val file = models.fileFor(model) ?: return UpscaleOutcome.NO_MODEL
        val key = keyFor(item.pageKey, model.id, item.scale, item.signature)
        if (cache.get(key) != null) return UpscaleOutcome.CACHED
        if (token.get()) return UpscaleOutcome.CANCELLED
        val result = engine.upscale(
            page = page,
            model = model,
            tileSize = tileSize(),
            maxOutputPixels = preferences.maxOutputMegapixels.get() * 1_000_000L,
            isCancelled = { token.get() },
        )
        try {
            // A cancel that landed after the last tile must not write the page either.
            if (token.get()) return UpscaleOutcome.CANCELLED
            runBlocking {
                cache.put(key) { target -> encoder.encode(result, target) }
            }
        } finally {
            // The page is on disk now: the bitmap's native memory is what a 32 MP page refuses to
            // give back until GC, so it is released here rather than left to the collector.
            result.bitmap?.recycle()
        }
        logcat { "UpscaleService: cached ${item.pageKey} (${file.name})" }
        return UpscaleOutcome.CACHED
    }

    private fun keyFor(
        pageKey: PageKey,
        modelId: String,
        scale: Int,
        signature: String,
    ): String = cache.key(
        sourceId = pageKey.sourceId,
        mangaId = pageKey.mangaId,
        chapterId = pageKey.chapterId,
        pageIndex = pageKey.pageIndex,
        sourceLength = pageKey.sourceLength,
        sourceLastModified = pageKey.sourceLastModified,
        modelId = modelId,
        scale = scale,
        signature = signature,
    )

    companion object {
        /**
         * Default tile size in input pixels; the engine halves it under memory pressure.
         *
         * Smaller tiles are strictly worse, because every tile pays the model's per-tile overhead and
         * the page is a fixed number of pixels. One 1200x1600 page (2400x3200 out), the
         * session-reusing run, measured on the OnePlus Pad 2 in phase 3b:
         *
         * | tile | MangaJaNai 2x 1600p (WebGPU) | Ill. 2x V3 detail FDAT (CPU) | Ill. 2x V3 detail SPAN (WebGPU) |
         * |------|------------------------------|------------------------------|---------------------------------|
         * | 512  | **56 s** (12 tiles)          | **103 s** (12 tiles)         | **14 s** (12 tiles)             |
         * | 384  | 69 s (20 tiles)              | 126-130 s (20 tiles)         | 17 s (20 tiles)                 |
         * | 256  | 82 s (35 tiles)              | 163-172 s (35 tiles)         | 21 s (35 tiles)                 |
         *
         * (The matrix deadline cut MangaJaNai's 256 px row; that one cell is from the same benchmark's
         * earlier pass.) Every model behaves the same way, and 512 is the largest size the benchmark
         * ran — a boundary of the matrix, not a peak, so a larger tile would probably be faster still.
         * It is simply unmeasured, and a 512 px tile already holds a ~2 GB native session here.
         */
        const val DEFAULT_TILE_SIZE: Int = 512

        /** How many pages after the visible one are served before the rest of the queue. */
        const val PREFETCH_PAGES: Int = 3

        /**
         * The model-id slot of an auto request's *routing* key. Only ever used to dedupe two requests
         * for the same page that have not been routed yet; the cache key is built after routing.
         */
        private const val ROUTING_KEY_MODEL_ID = "auto"

        private const val FOCUS_FAR = PREFETCH_PAGES + 2
    }
}
