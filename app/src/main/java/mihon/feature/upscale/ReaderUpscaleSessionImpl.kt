// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// One reader's upscaling handle: page identity, the synchronous cache lookup the decode path uses,
// and the queue hints that keep the pages being read ahead of everything else.
//
// **The cache-hit rule.** `open(page)` runs before the page is decoded, so neither its height nor its
// colour is known yet. It therefore looks up the entries the *mode* allows (see
// `UpscaleRuntimePolicy.eligibleModels`) and returns the first one that exists. That is sound for
// AUTO because `UpscalePreferences.cacheSignature()` carries the mode: a MangaJaNai entry under an
// AUTO signature can only have been written for a page the router judged grayscale, and a mode change
// invalidates every entry from the previous mode. The routing decision itself stays with
// `UpscaleRouter.route`, applied to the decoded page by `request(page)`.
//
// **Bytes are never held.** `request` captures a lambda that reopens `page.stream()` when the engine
// thread gets to the page, so a page that is no longer wanted is never read — and the caller's own
// buffer is never shared with the engine.
//
// **The page identity is only usable once the loader has filled it in.** `ReaderPage.sourceLength` /
// `sourceLastModified` stay `-1` until the loader has the bytes (`HttpPageLoader` sets them when the
// image lands in the chapter cache), so a page requested before that would be upscaled later but
// written under the `-1` key — an entry `open` can never match, which also evicts usable ones. Such a
// page is skipped by `request`/`focus` and refused by `open`; the next page turn sees it loaded.
//
// **Nothing expensive runs per call.** `InstalledModels.installedIds()` reads the install marker,
// parses the release manifest and stats every model, and this is the page-turn path, so the installed
// set is resolved once per `reset()` (and per [INSTALLED_VALIDITY_MILLIS] window, so a model downloaded
// while reading is picked up) and reused by `open`, `request` and the router.
//
// **A failed page is not retried on every turn.** The request's outcome is recorded in
// [UpscaleNegativeMemo] for "no route / model missing / undecodable / failed"; cancellations and a
// switched-off feature are not a verdict about the page and are never memoised.
// KUN <--
package mihon.feature.upscale

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.upscale.ReaderUpscaleSession
import eu.kanade.tachiyomi.ui.reader.upscale.UpscaleModelRef
import eu.kanade.tachiyomi.ui.reader.upscale.UpscaleRuntimePolicy
import mihon.core.upscale.PageKey
import mihon.core.upscale.UpscaleOutcome
import mihon.core.upscale.UpscaleService
import mihon.core.upscale.cache.UpscaleCache
import mihon.core.upscale.engine.DecodedPage
import mihon.core.upscale.model.InstalledModels
import mihon.core.upscale.model.UpscaleModel
import mihon.core.upscale.model.UpscaleModelCatalog
import mihon.core.upscale.model.isGrayscale
import mihon.core.upscale.model.route
import tachiyomi.core.common.preference.UpscalePreferences
import tachiyomi.core.common.util.system.logcat
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The reader session over [UpscaleService] — see the file header for the cache-hit rule.
 *
 * All dependencies are app-scoped; the only state this object owns is the chapter/page it last saw,
 * the models it has resolved, the pages it has given up on, and whether it is closed.
 */
internal class ReaderUpscaleSessionImpl(
    private val service: UpscaleService,
    private val cache: UpscaleCache,
    private val models: InstalledModels,
    private val preferences: UpscalePreferences,
    /** Clock behind [INSTALLED_VALIDITY_MILLIS]; overridable so a test needs no sleep. */
    private val now: () -> Long = System::currentTimeMillis,
) : ReaderUpscaleSession {

    private val closed = AtomicBoolean(false)

    /** The chapter/page this reader last focused, for [reset]. */
    @Volatile
    private var currentChapterId: Long = -1L

    @Volatile
    private var currentPageIndex: Int = -1

    /** One resolution of [InstalledModels.installedIds]: the ids, and the catalog refs they name. */
    private class Installed(val ids: Set<String>, val refs: List<UpscaleModelRef>)

    /**
     * The installed models, resolved once per session: `installedIds()` is an install-marker read
     * plus a manifest parse plus one stat per entry, and every one of `open`/`request`/routing needs
     * it. A model installed while this chapter is open is picked up at the next chapter ([reset]) or
     * once the validity window has elapsed.
     */
    @Volatile
    private var installed: Installed? = null

    @Volatile
    private var installedAt: Long = 0L

    /** Pages this reader has learnt it cannot upscale under the current selection; see [remember]. */
    internal val negative = UpscaleNegativeMemo()

    override fun open(page: ReaderPage): InputStream? {
        if (closed.get() || !preferences.isEnabled()) return null
        // A page the loader has not filled in yet has no cache identity: any entry under `-1` was
        // written for bytes that are not these, so it is never served.
        if (page.sourceLength < 0L) return null
        val pageKey = pageKey(page) ?: return null
        val scale = preferences.scale.get()
        val signature = preferences.cacheSignature()
        for (model in eligibleModels(scale)) {
            service.cachedFor(pageKey, model.id, scale, signature)?.let { file ->
                return runCatching { file.inputStream() }.getOrNull()
            }
        }
        return null
    }

    override fun request(page: ReaderPage) {
        if (closed.get() || !isActive()) return
        // See the file header: a page without its bytes yet would key an entry it can never match.
        if (page.sourceLength < 0L) return
        val pageKey = pageKey(page) ?: return
        val scale = preferences.scale.get()
        val signature = preferences.cacheSignature()
        // A page already known to be impossible under this selection is not decoded again.
        if (negative.contains(pageKey, signature)) return
        service.requestAuto(
            pageKey = pageKey,
            pageBytes = pageBytes(page),
            scale = scale,
            signature = signature,
            route = { decoded -> routeFor(decoded, scale) },
            onDone = { outcome -> remember(pageKey, signature, outcome) },
        )
    }

    override fun focus(page: ReaderPage) {
        if (closed.get() || !preferences.isEnabled()) return
        val pageKey = pageKey(page) ?: return
        currentChapterId = pageKey.chapterId
        currentPageIndex = page.index
        service.focus(pageKey.chapterId, page.index)
        // The visible page and the next three are what the reader is about to show. A page the
        // loader has not filled in yet is skipped by `request`, and picked up by the next focus once
        // its bytes (and therefore its cache identity) are known.
        val pages = runCatching { page.chapter.pages.orEmpty() }.getOrDefault(emptyList())
        for (offset in 0..UpscaleService.PREFETCH_PAGES) {
            pages.getOrNull(page.index + offset)?.let(::request)
        }
    }

    override fun reset() {
        if (closed.get()) return
        logcat { "ReaderUpscaleSession: dropping chapter $currentChapterId (page $currentPageIndex)" }
        currentChapterId = -1L
        currentPageIndex = -1
        installed = null
        negative.clear()
        service.cancelAll()
    }

    override fun close(recreating: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        // A recreated reader reads its results back out of the app-scoped cache, so its in-flight
        // work keeps running. Leaving the reader is what stops it.
        if (!recreating) service.cancelAll()
    }

    // ------------------------------------------------------------------ internals

    /** Whether upscaling may run now: opted in *and* at least one model installed. */
    private fun isActive(): Boolean = UpscaleRuntimePolicy.isActive(
        enabled = preferences.isEnabled(),
        installedIds = installed().ids,
    )

    /**
     * The models whose cached entry may serve this page, before it is decoded (height unknown).
     */
    private fun eligibleModels(scale: Int): List<UpscaleModelRef> = UpscaleRuntimePolicy.eligibleModels(
        pageHeight = null,
        mode = preferences.mode.get(),
        scale = scale,
        installed = installed().refs,
        illustrationModelId = preferences.illustrationModel.get(),
    )

    /**
     * The installed models, read at most once per [INSTALLED_VALIDITY_MILLIS] window.
     *
     * The refs come from the same `installedIds()` answer as the gate, so "which models may run" and
     * "which entries may be served" can never disagree — a leftover file from another release is not
     * installed for either (see `ModelDownloader.installedIds`).
     */
    private fun installed(): Installed {
        val at = now()
        installed?.let { cached -> if (at - installedAt < INSTALLED_VALIDITY_MILLIS) return cached }
        val ids = models.installedIds()
        val refs = UpscaleModelCatalog.models
            .filter { it.id in ids }
            .map { UpscaleModelRef(id = it.id, scale = it.scale, bucketHeight = it.bucketHeight) }
        return Installed(ids, refs).also {
            installed = it
            installedAt = at
        }
    }

    /**
     * The model for a decoded page: the page's own grayscale verdict, then the routing rule. A colour
     * page therefore never lands on a MangaJaNai model unless the user asked for exactly that.
     */
    private fun routeFor(page: DecodedPage, scale: Int): UpscaleModel? = route(
        isGrayscale = isGrayscale(page.argb, page.width, page.height),
        pageHeight = page.height,
        mode = preferences.mode.get(),
        scale = scale,
        installedIds = installed().ids,
        illustrationModelId = preferences.illustrationModel.get(),
    )

    /**
     * Remembers how a queued page ended, so the next page turn does not repeat work that cannot
     * succeed. Only a verdict about the page is kept: a cancellation or a switched-off feature says
     * nothing about the page, and a page that did produce an entry is forgotten instead.
     */
    private fun remember(pageKey: PageKey, signature: String, outcome: UpscaleOutcome) {
        when (outcome) {
            UpscaleOutcome.CACHED -> negative.forget(pageKey, signature)
            UpscaleOutcome.NO_ROUTE,
            UpscaleOutcome.NO_MODEL,
            UpscaleOutcome.UNDECODABLE,
            UpscaleOutcome.FAILED,
            -> negative.record(pageKey, signature)

            // Not a property of the page: the queue was cancelled, or the feature was turned off.
            UpscaleOutcome.DISABLED, UpscaleOutcome.CANCELLED -> Unit
        }
    }

    /**
     * A lazy supplier of the page bytes: it reopens [ReaderPage.stream] on the engine thread instead
     * of capturing whatever buffer the caller happened to hold, and it never touches the stream when
     * the page was dropped from the queue before it ran.
     */
    private fun pageBytes(page: ReaderPage): () -> ByteArray? = {
        runCatching { page.stream?.invoke()?.use { it.readBytes() } }.getOrNull()
    }

    /**
     * The cache identity of [page], or `null` for a page that has no chapter yet (an insert page or
     * a chapter transition), which has nothing to key on.
     */
    private fun pageKey(page: ReaderPage): PageKey? = try {
        val chapter = page.chapter.chapter
        PageKey(
            // `Chapter` carries no source id; the manga id already identifies the series it came from.
            sourceId = NO_SOURCE_ID,
            mangaId = chapter.mangaId,
            chapterId = chapter.id,
            pageIndex = page.index,
            sourceLength = page.sourceLength,
            sourceLastModified = page.sourceLastModified,
        )
    } catch (_: UninitializedPropertyAccessException) {
        null
    }

    companion object {
        /** `PageKey.sourceId` is unused by the cache identity (see [pageKey]). */
        private const val NO_SOURCE_ID: Long = -1L

        /**
         * How long one `installedIds()` answer stays usable.
         *
         * Long enough that a page turn costs no file I/O, short enough that a model downloaded while
         * a chapter is open is picked up without leaving the reader. `reset()` drops it at once.
         */
        const val INSTALLED_VALIDITY_MILLIS: Long = 30_000L
    }
}
