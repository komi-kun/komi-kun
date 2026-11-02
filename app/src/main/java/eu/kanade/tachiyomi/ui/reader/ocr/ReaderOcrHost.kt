// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.ui.reader.ocr

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import eu.kanade.tachiyomi.ui.reader.RedoOcrResult
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

/**
 * Apache-2.0 reader→OCR seam (contract §1.2/§2.3). GPL-free: exposes **no** `OcrTextBlock`; hit-test
 * and lookup-text extraction happen inside the GPL implementation. Reader files obtain the impl
 * only through this interface via DI.
 *
 * The host is the app-wide half: it holds nothing that belongs to a reader and exists so a reader
 * can obtain its own [ReaderOcrSession]. One reader activity owns exactly one session, created in
 * `onCreate` and closed in `onDestroy`.
 */
interface ReaderOcrHost {
    // KUN -->
    /**
     * Creates the OCR session for **one reader instance**. The caller owns it: it must
     * [ReaderOcrSession.close] it when the reader goes away, on every destroy — including a
     * configuration change, where [ReaderOcrSession.close] hands the open lookup to the session the
     * recreated reader creates.
     *
     * A session created with nothing to inherit (the normal case: a reader opened after the previous
     * one was closed) starts empty. It is never shared between readers.
     */
    fun newSession(): ReaderOcrSession
    // KUN <--
}

// KUN -->
/**
 * Apache-2.0 handle on one reader's OCR overlay: the viewport the overlay is drawn through, the
 * block currently looked up, and the popup anchor that follows it — all of it owned by this object
 * and released by [close], so no reader state can outlive the reader that created it.
 *
 * Everything app-wide (engine, OCR cache, the download queue, the dictionary popup) lives behind the
 * host/services, not here.
 */
interface ReaderOcrSession {
    @Composable fun Overlay(viewport: OcrViewport?, modifier: Modifier)

    /** true iff the point hit an OCR block and a lookup was started; else false (→ page dialog). */
    fun onPageLongPress(page: ReaderPage, extraPage: ReaderPage?, overlayPoint: Offset): Boolean

    /**
     * true iff a **single** tap at [overlayPoint] hit an OCR block and a lookup was started; else
     * false (→ the viewer's normal single-tap navigation). Same hit-test + lookup path as
     * [onPageLongPress]; used while the OCR overlay is enabled so scanning needs one tap, not a
     * long press.
     */
    fun onPageSingleTap(page: ReaderPage, extraPage: ReaderPage?, overlayPoint: Offset): Boolean

    /**
     * true iff [overlayPoint] currently lands on an OCR text block. Side-effect free, so the
     * outside-tap dismissal can tell "the user is aiming at another word" (leave the popup for the
     * single-tap lookup to move) from "the user tapped the page" (dismiss).
     */
    fun hitsBlock(overlayPoint: Offset): Boolean

    /**
     * Force re-runs OCR for [page] (or the entire chapter if [chapter] is true),
     * updating the cache and overwriting any .mokuro sidecar in the archive.
     *
     * [onComplete] is invoked exactly once — also when nothing ran at all — with how the re-run
     * ended, so the caller never reports success for a run that changed nothing.
     */
    fun redoOcr(
        page: ReaderPage,
        chapter: Boolean = false,
        onComplete: ((RedoOcrResult) -> Unit)? = null,
    )

    /**
     * Releases what this session holds for the **chapter** that is going away, keeping the handle
     * alive for the chapter that replaces it: the dictionary popup anchor (including the screenshot
     * provider it captured for the open lookup), the recorded viewport and the active block. Also
     * stops any OCR page work still in flight — it reads the chapter's page bytes, which the reader
     * unmaps when it switches chapters.
     *
     * The reader calls this when the same activity opens another chapter (`onNewIntent`), not when it
     * is destroyed: that is [close]. Safe to call repeatedly; does not change any preference or
     * cache.
     */
    fun reset()

    /**
     * Closes this reader's session, exactly once; later calls do nothing.
     *
     * [recreating] is true when the reader activity is being re-created for a configuration change.
     * The session is closed either way — the handle never survives its reader — but only a
     * recreation hands the open lookup (its block and the popup anchor it is drawn at) to the
     * session the recreated reader creates, and keeps the shared page work running, exactly as the
     * reader did before the rotation. A real destroy releases everything: the popup is closed, the
     * anchor, its captured page and the recorded viewport are dropped, and in-flight page work is
     * stopped.
     */
    fun close(recreating: Boolean = false)
}
// KUN <--
