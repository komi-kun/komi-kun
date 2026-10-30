// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.ui.reader.ocr

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import eu.kanade.tachiyomi.ui.reader.RedoOcrResult
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

/**
 * Apache-2.0 reader→OCR seam (contract §1.2/§2.3). GPL-free: exposes **no** `OcrTextBlock`; hit-test
 * and lookup-text extraction happen inside the GPL `ReaderOcrHostImpl`. Reader files obtain the impl
 * only through this interface via DI.
 */
interface ReaderOcrHost {
    @Composable fun Overlay(viewport: OcrViewport?, modifier: Modifier)

    /** true iff the point hit an OCR block and a lookup was started; else false (→ page dialog). */
    fun onPageLongPress(page: ReaderPage, extraPage: ReaderPage?, overlayPoint: Offset): Boolean

    // KUN -->
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
     * Releases everything the host holds for the reader that is going away: the dictionary popup
     * anchor (including the screenshot provider it captured for the open lookup), the recorded
     * viewport and the active block, and closes the popup. Also stops any OCR page work still in
     * flight — it reads the chapter's page bytes, which the reader unmaps as it tears down. The
     * implementation is app-graph scoped, so without this the last reader's page stays retained
     * after the reader exits.
     *
     * Safe to call repeatedly; does not change any preference or cache.
     */
    fun detach()
    // KUN <--
}
