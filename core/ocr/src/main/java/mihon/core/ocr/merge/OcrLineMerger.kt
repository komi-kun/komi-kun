// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The seam that isolates the (GPL-derived, high-risk) line merge/reading-order algorithm from the
// rest of the pipeline (contract §2.2, 03 §3.3). Kept deliberately tiny so it can be unit-tested
// against fixtures and swapped without touching callers.
// KUN <--
package mihon.core.ocr.merge

import mihon.core.ocr.EngineLine
import mihon.core.ocr.model.OcrTextBlock

/**
 * Merges raw engine lines into viewer-facing [OcrTextBlock]s: paragraph grouping, furigana
 * filtering, close-paragraph merging and reading order.
 */
interface OcrLineMerger {
    fun merge(lines: List<EngineLine>, imageW: Int, imageH: Int): List<OcrTextBlock>
}
