// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.ui.reader.ocr

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
    // KUN <--
}
