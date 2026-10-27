// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Pure geometry rules for the line merger's "are these two boxes one text run?" decisions.
// Extracted from `OwOCRMerger` so the merge criterion can be documented and unit-tested
// independently of paragraph bookkeeping and reading order.
// KUN <--
package mihon.core.ocr.merge

import mihon.core.ocr.NormalizedBBox
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Geometry predicates that decide whether two OCR boxes belong to the same text run.
 *
 * Two boxes are adjacent in one of two ways, and the orientation of the text decides which
 * axis is which:
 *
 * | | horizontal text | vertical (tategaki) text |
 * |---|---|---|
 * | run axis (characters advance along a line) | x | y |
 * | advance axis (successive lines) | y | x |
 *
 * * [nextLine] — the boxes are successive lines of one paragraph (adjacent on the advance
 *   axis). They need a comparable thickness (line height / column width) and a meaningful
 *   overlap on the run axis, so two blocks that merely sit next to each other on the same
 *   row (or beside each other in different columns) are rejected.
 * * [sameLineFragment] — the boxes are two fragments of one line that the detector split
 *   (adjacent on the run axis). They need to be aligned on the advance axis and to have a
 *   small *positive* gap; boxes that abut or overlap are not fragments of one line.
 *
 * The key invariant for the reported bug: **abutting boxes never join as line fragments**.
 * A detector box is expanded by the unclip ratio, so two unrelated text patches whose boxes
 * happen to share an edge look "close" even though the text does not continue. Requiring a
 * positive (but bounded) whitespace gap keeps those patches apart while a detector-split
 * line, which leaves a small gap at the split, still rejoins.
 */
internal object OcrMergeGeometry {

    // KUN -->
    /**
     * Smaller/larger thickness needed to consider two boxes the same kind of text.
     *
     * 0.556 was tried on the labelled pages: it recovered 12 of the 25 within-unit splits
     * (25 -> 13) and looked text-neutral there, but a page-level diagnosis found it glues
     * neighbouring bubbles together — a two-glyph column joined the bottom line of the bubble above
     * through [sameLineFragment] at a thickness ratio of 0.6245 with a 48 px gutter, and the two
     * bubbles were reported as one paragraph. [sameLineFragment] shares this ratio, so the cross-bubble
     * join is what 0.65 prevents; [MAX_ADVANCE_OVERLAP] keeps its own relaxation.
     */
    const val MIN_THICKNESS_RATIO = 0.65
    // KUN <--

    /** Widest whitespace gap (as a fraction of thickness) that can join two line fragments. */
    private const val MAX_FRAGMENT_GAP_RATIO = 0.35

    // KUN -->
    /** Maximum whitespace gap along advance axis that still counts as successive lines of one paragraph. */
    private const val MAX_LINE_GAP_RATIO = 0.50

    /** Maximum alignment drift (as a fraction of thickness) between start or center edges of successive lines. */
    private const val MAX_ALIGNMENT_DRIFT = 0.55

    /**
     * Overlap on the advance axis above which the boxes are on the same row/column rather
     * than one following the other. Same-row boxes must go through [sameLineFragment].
     * Relaxed from 0.35 to 0.45 to account for detector unclip expansion (1.40), and then to
     * 0.55 on the labelled pages: the expansion makes successive lines of one paragraph overlap by
     * up to ~0.5 of the shorter box, and rejecting those was the other half of the within-unit
     * splits (25 -> 13 in total for both constants), text-neutral on dialogue and train.
     */
    private const val MAX_ADVANCE_OVERLAP = 0.55

    /** Overlap on the run axis needed for two boxes to count as successive lines. Relaxed from 0.5 to 0.4 for ragged lines. */
    private const val MIN_RUN_OVERLAP = 0.4
    // KUN <--

    /** Overlap on the advance axis needed for two boxes to count as one line. */
    private const val MIN_FRAGMENT_ADVANCE_OVERLAP = 0.8

    /** Boxes that already cover almost the same run span are duplicates, not fragments. */
    private const val SAME_SPAN = 0.8

    private const val EPS = 1e-6

    /** Extent of the box perpendicular to the run axis: line height, or column width. */
    fun thickness(box: NormalizedBBox, vertical: Boolean): Double =
        if (vertical) box.width else box.height

    /** Fraction of the smaller box that overlaps on the run axis. */
    fun runOverlap(a: NormalizedBBox, b: NormalizedBBox, vertical: Boolean): Double =
        if (vertical) verticalOverlap(a, b) else horizontalOverlap(a, b)

    /** Fraction of the smaller box that overlaps on the advance axis. */
    fun advanceOverlap(a: NormalizedBBox, b: NormalizedBBox, vertical: Boolean): Double =
        if (vertical) horizontalOverlap(a, b) else verticalOverlap(a, b)

    /** Whitespace between the boxes along the run axis (0 when they touch or overlap). */
    fun runGap(a: NormalizedBBox, b: NormalizedBBox, vertical: Boolean): Double =
        if (vertical) verticalDistance(a, b) else horizontalDistance(a, b)

    /** Whitespace between the boxes along the advance axis (0 when they touch or overlap). */
    fun advanceGap(a: NormalizedBBox, b: NormalizedBBox, vertical: Boolean): Double =
        if (vertical) horizontalDistance(a, b) else verticalDistance(a, b)

    /**
     * Two fragments of a single line that the detector split.
     *
     * Requires strong alignment on the advance axis, a comparable thickness, and a *strictly
     * positive* gap bounded by [MAX_FRAGMENT_GAP_RATIO] of the thickness. Abutting boxes (gap
     * zero) are rejected: that is the "two patches joined at a border" case.
     */
    fun sameLineFragment(a: NormalizedBBox, b: NormalizedBBox, vertical: Boolean): Boolean {
        if (!sameThickness(a, b, vertical, MIN_THICKNESS_RATIO)) return false
        if (advanceOverlap(a, b, vertical) < MIN_FRAGMENT_ADVANCE_OVERLAP) return false
        if (runOverlap(a, b, vertical) >= SAME_SPAN) return false

        val gap = runGap(a, b, vertical)
        if (gap <= EPS) return false
        return gap <= MAX_FRAGMENT_GAP_RATIO * minOf(thickness(a, vertical), thickness(b, vertical))
    }

    /**
     * Successive lines of one paragraph: stacked along the advance axis with a meaningful
     * overlap along the run axis (or a matching start/end edge for ragged text).
     *
     * Touching lines are allowed here — manga leading is often zero after the detector's
     * unclip expansion — but only when their run spans overlap, which is what tells a real
     * paragraph line apart from an unrelated box that merely abuts at a corner.
     *
     * [allowCenteredRuns] controls whether a mere run overlap is enough (center-aligned text)
     * or the boxes must share a start/end edge (the `supportCenterAlignedText` preference).
     */
    fun nextLine(
        a: NormalizedBBox,
        b: NormalizedBBox,
        vertical: Boolean,
        allowCenteredRuns: Boolean = true,
    ): Boolean {
        if (!sameThickness(a, b, vertical, MIN_THICKNESS_RATIO)) return false
        if (advanceOverlap(a, b, vertical) > MAX_ADVANCE_OVERLAP) return false

        val maxGap = MAX_LINE_GAP_RATIO * minOf(thickness(a, vertical), thickness(b, vertical))
        if (advanceGap(a, b, vertical) > maxGap) return false

        // KUN -->
        if (runOverlap(a, b, vertical) < MIN_RUN_OVERLAP) return false
        if (startAligned(a, b, vertical)) return true
        if (allowCenteredRuns && centerAligned(a, b, vertical)) return true
        return false
        // KUN <--
    }

    private fun sameThickness(
        a: NormalizedBBox,
        b: NormalizedBBox,
        vertical: Boolean,
        minRatio: Double,
    ): Boolean {
        val ta = thickness(a, vertical)
        val tb = thickness(b, vertical)
        val larger = max(ta, tb)
        if (larger <= 0.0) return false
        return minOf(ta, tb) / larger >= minRatio
    }

    // KUN -->
    private fun startAligned(a: NormalizedBBox, b: NormalizedBBox, vertical: Boolean): Boolean {
        val aStart = if (vertical) a.top else a.left
        val bStart = if (vertical) b.top else b.left
        val minT = minOf(thickness(a, vertical), thickness(b, vertical))
        return abs(aStart - bStart) <= MAX_ALIGNMENT_DRIFT * minT
    }

    private fun centerAligned(a: NormalizedBBox, b: NormalizedBBox, vertical: Boolean): Boolean {
        val aCenter = if (vertical) a.centerY else a.centerX
        val bCenter = if (vertical) b.centerY else b.centerX
        val minT = minOf(thickness(a, vertical), thickness(b, vertical))
        return abs(aCenter - bCenter) <= MAX_ALIGNMENT_DRIFT * minT
    }
    // KUN <--
}

// KUN -->
// Shared box-relative geometry, in normalized page coordinates. Kept top-level because both
// `OwOCRMerger` and `OcrMergeGeometry` use them.
// KUN <--

internal fun horizontalOverlap(a: NormalizedBBox, b: NormalizedBBox): Double {
    val overlapLeft = maxOf(a.left, b.left)
    val overlapRight = minOf(a.right, b.right)
    if (overlapRight <= overlapLeft) return 0.0
    val overlapWidth = overlapRight - overlapLeft
    val smallerWidth = minOf(a.width, b.width)
    return if (smallerWidth > 0) overlapWidth / smallerWidth else 0.0
}

internal fun verticalOverlap(a: NormalizedBBox, b: NormalizedBBox): Double {
    val overlapTop = maxOf(a.top, b.top)
    val overlapBottom = minOf(a.bottom, b.bottom)
    if (overlapBottom <= overlapTop) return 0.0
    val overlapHeight = overlapBottom - overlapTop
    val smallerHeight = minOf(a.height, b.height)
    return if (smallerHeight > 0) overlapHeight / smallerHeight else 0.0
}

internal fun horizontalDistance(a: NormalizedBBox, b: NormalizedBBox): Double = when {
    a.right < b.left -> b.left - a.right
    b.right < a.left -> a.left - b.right
    else -> 0.0
}

internal fun verticalDistance(a: NormalizedBBox, b: NormalizedBBox): Double = when {
    a.bottom < b.top -> b.top - a.bottom
    b.bottom < a.top -> a.top - b.bottom
    else -> 0.0
}
