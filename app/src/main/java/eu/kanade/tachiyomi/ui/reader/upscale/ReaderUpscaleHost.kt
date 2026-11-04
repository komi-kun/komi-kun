// SPDX-License-Identifier: Apache-2.0
// KUN -->
// Apache-2.0 reader→upscaling seam (contract §1.3: upstream-contributable, GPL-free). It exposes no
// GPL type: the engine, cache, models, router and preferences stay behind the host, and the reader
// only ever hands a `ReaderPage` in and gets page bytes (or nothing) back.
//
// The host is the app-wide half — it holds nothing that belongs to a reader — and exists so a reader
// can obtain its own [ReaderUpscaleSession]. One reader activity owns exactly one session: created in
// `onCreate`, `reset()` when the same activity opens another chapter, and `close(...)` in `onDestroy`.
//
// **The three decode paths.** Each viewer asks [ReaderUpscaleSession.open] for the page bytes to
// decode, and [ReaderUpscaleSession.request] / [ReaderUpscaleSession.focus] to get the page queued.
// Both are cheap enough for the decode path: `open` is one synchronous cache lookup, `request` only
// enqueues.
//
// This file is a **registered** GPL-free seam file (`scripts/verify-gpl-free.sh`): keep the Apache
// header, never import a GPL package, and never name a GPL type — "disabled" is the answer for every
// caller while the feature is off.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.upscale

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import java.io.InputStream

/**
 * Gives a reader its own [ReaderUpscaleSession], or an always-inert one while upscaling is off.
 *
 * The implementation is GPL and bound by DI (`@ContributesBinding`); the reader never sees it.
 */
interface ReaderUpscaleHost {
    /** True while reader upscaling may run (the user opted in *and* at least one model is installed). */
    fun isEnabled(): Boolean

    /**
     * Creates the session for **one** reader instance. The caller owns it and must [close][ReaderUpscaleSession.close]
     * it when the reader goes away. Sessions are never shared between readers.
     */
    fun newSession(): ReaderUpscaleSession
}

/**
 * Handle on one reader's upscaling: what the viewer asks for while decoding a page, and the queue
 * hints that keep the pages being read ahead of the rest.
 *
 * Everything app-wide (engine, cache, models, queue) lives behind the host and the service it wraps;
 * nothing held here outlives the reader that created it.
 */
interface ReaderUpscaleSession {
    /**
     * The cached upscaled page as a stream, or `null` to use the original. **Must stay cheap and
     * synchronous**: it runs on the page-decode path, which cannot wait for a coroutine.
     *
     * It also answers `null` for a page whose bytes are not loaded yet (the loader has not filled in
     * its length/mtime): such a page has no cache identity, so no entry can belong to it.
     */
    fun open(page: ReaderPage): InputStream?

    /**
     * Enqueue a background upscale for this page (a no-op while disabled or with no model installed).
     *
     * Ask **only after [open] returned `null`**: a page that already has an upscale is decoded from
     * its entry, and queueing it again would decode and upscale it a second time on every page turn.
     */
    fun request(page: ReaderPage)

    /** Re-prioritise the queue around the visible page (visible + next 3). */
    fun focus(page: ReaderPage)

    /**
     * Releases what this session holds for the chapter that is going away, keeping the handle alive
     * for the chapter that replaces it: queued and in-flight work for the old chapter is dropped.
     *
     * Safe to call repeatedly; changes no preference and no cache entry.
     */
    fun reset()

    /**
     * Closes this reader's session, exactly once; later calls do nothing.
     *
     * [recreating] is true when the reader activity is re-created for a configuration change.
     * `false` also stops the work this reader queued; a recreation keeps it running (the results are
     * app-scoped cache entries, and the recreated reader reads them back through [open]).
     */
    fun close(recreating: Boolean)
}
