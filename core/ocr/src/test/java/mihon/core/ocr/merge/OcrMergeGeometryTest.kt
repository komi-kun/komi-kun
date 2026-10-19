// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Unit tests for the pure box-merge geometry: abutting vs. gapped boxes, same-line fragments,
// successive paragraph lines, mixed sizes and orientation-dependent axes (contract §5).
// KUN <--
package mihon.core.ocr.merge

import io.kotest.matchers.shouldBe
import mihon.core.ocr.NormalizedBBox
import org.junit.jupiter.api.Test

class OcrMergeGeometryTest {

    private fun box(l: Double, t: Double, r: Double, b: Double) = NormalizedBBox(l, t, r, b)

    // ------------------------------------------------------------------ same-line fragments

    @Test
    fun `abutting boxes are not fragments of one line`() {
        val a = box(0.10, 0.10, 0.30, 0.20)
        val b = box(0.30, 0.10, 0.50, 0.20)

        OcrMergeGeometry.sameLineFragment(a, b, vertical = false) shouldBe false
    }

    @Test
    fun `overlapping boxes are not fragments of one line`() {
        val a = box(0.10, 0.10, 0.32, 0.20)
        val b = box(0.30, 0.10, 0.50, 0.20)

        OcrMergeGeometry.sameLineFragment(a, b, vertical = false) shouldBe false
    }

    @Test
    fun `a small gap keeps a detector-split line as one fragment pair`() {
        val a = box(0.10, 0.10, 0.30, 0.20)
        val b = box(0.305, 0.10, 0.50, 0.20)

        OcrMergeGeometry.sameLineFragment(a, b, vertical = false) shouldBe true
    }

    @Test
    fun `a wide gap is not a fragment pair`() {
        val a = box(0.10, 0.10, 0.30, 0.20)
        val b = box(0.36, 0.10, 0.56, 0.20)

        OcrMergeGeometry.sameLineFragment(a, b, vertical = false) shouldBe false
    }

    @Test
    fun `fragments on different baselines are not one line`() {
        val a = box(0.10, 0.10, 0.30, 0.20)
        val b = box(0.305, 0.14, 0.50, 0.24)

        OcrMergeGeometry.sameLineFragment(a, b, vertical = false) shouldBe false
    }

    @Test
    fun `vertical column fragments join along the column`() {
        val upper = box(0.80, 0.10, 0.90, 0.40)
        val lower = box(0.80, 0.405, 0.90, 0.70)

        OcrMergeGeometry.sameLineFragment(upper, lower, vertical = true) shouldBe true
    }

    // ------------------------------------------------------------------ successive lines

    @Test
    fun `stacked lines with a small gap are successive lines`() {
        val a = box(0.10, 0.10, 0.40, 0.20)
        val b = box(0.10, 0.21, 0.40, 0.31)

        OcrMergeGeometry.nextLine(a, b, vertical = false) shouldBe true
    }

    @Test
    fun `touching stacked lines are successive lines when their runs overlap`() {
        val a = box(0.10, 0.10, 0.40, 0.20)
        val b = box(0.10, 0.20, 0.40, 0.30)

        OcrMergeGeometry.nextLine(a, b, vertical = false) shouldBe true
    }

    @Test
    fun `side by side boxes are not successive lines`() {
        val a = box(0.10, 0.10, 0.30, 0.20)
        val b = box(0.30, 0.10, 0.50, 0.20)

        OcrMergeGeometry.nextLine(a, b, vertical = false) shouldBe false
    }

    @Test
    fun `boxes that only touch at a corner are not successive lines`() {
        val a = box(0.10, 0.10, 0.30, 0.20)
        val b = box(0.30, 0.20, 0.50, 0.30)

        OcrMergeGeometry.nextLine(a, b, vertical = false) shouldBe false
    }

    @Test
    fun `a huge leading gap is not a successive line`() {
        val a = box(0.10, 0.10, 0.40, 0.20)
        val b = box(0.10, 0.40, 0.40, 0.50)

        OcrMergeGeometry.nextLine(a, b, vertical = false) shouldBe false
    }

    @Test
    fun `mixed line sizes are not successive lines`() {
        val body = box(0.10, 0.10, 0.40, 0.20)
        val title = box(0.10, 0.21, 0.40, 0.41)

        OcrMergeGeometry.nextLine(body, title, vertical = false) shouldBe false
    }

    @Test
    fun `vertical columns side by side are successive lines`() {
        val right = box(0.80, 0.10, 0.90, 0.60)
        val left = box(0.70, 0.10, 0.80, 0.60)

        OcrMergeGeometry.nextLine(right, left, vertical = true) shouldBe true
    }

    @Test
    fun `columns with no vertical run overlap are not successive lines`() {
        val right = box(0.80, 0.10, 0.90, 0.40)
        val left = box(0.70, 0.50, 0.80, 0.80)

        OcrMergeGeometry.nextLine(right, left, vertical = true) shouldBe false
    }

    @Test
    fun `center-aligned overlap can be rejected by the caller preference`() {
        val wide = box(0.10, 0.10, 0.50, 0.20)
        val narrow = box(0.22, 0.21, 0.32, 0.31)

        OcrMergeGeometry.nextLine(wide, narrow, vertical = false, allowCenteredRuns = true) shouldBe true
        OcrMergeGeometry.nextLine(wide, narrow, vertical = false, allowCenteredRuns = false) shouldBe false
    }

    // KUN -->
    @Test
    fun `relaxed advance overlap accepts adjacent lines with moderate overlap`() {
        val a = box(0.10, 0.10, 0.40, 0.20)
        val b = box(0.10, 0.16, 0.40, 0.26)

        OcrMergeGeometry.nextLine(a, b, vertical = false) shouldBe true
    }

    @Test
    fun `relaxed run overlap accepts ragged dialogue lines`() {
        val a = box(0.10, 0.10, 0.40, 0.20)
        val b = box(0.12, 0.21, 0.35, 0.31)

        OcrMergeGeometry.nextLine(a, b, vertical = false) shouldBe true
    }

    @Test
    fun `same line fragments with slight vertical offset still form a fragment pair`() {
        val left = box(0.10, 0.100, 0.30, 0.200)
        val right = box(0.305, 0.099, 0.50, 0.199)

        OcrMergeGeometry.sameLineFragment(left, right, vertical = false) shouldBe true
    }

    @Test
    fun `staggered vertical columns do not pass nextLine even with run overlap`() {
        // Shuffled / cross-bubble columns like Page 1:
        // "サンプルでした" (top=0.075, h=0.222, w=0.038) vs "テストの文章" (top=0.125, h=0.207, w=0.038)
        val col1 = box(0.612, 0.075, 0.650, 0.297)
        val col2 = box(0.539, 0.125, 0.577, 0.332)

        // Delta top = 0.050 (1.3 * thickness), Delta centerY = 0.0425 (1.1 * thickness)
        OcrMergeGeometry.nextLine(col1, col2, vertical = true, allowCenteredRuns = true) shouldBe false

        // Top-aligned column ("です！") passes
        val topAligned = box(0.580, 0.076, 0.618, 0.200)
        OcrMergeGeometry.nextLine(col1, topAligned, vertical = true, allowCenteredRuns = true) shouldBe true

        // Genuinely center-aligned column passes
        val centerAligned = box(0.580, 0.090, 0.618, 0.282) // centerY = 0.186 matching col1
        OcrMergeGeometry.nextLine(col1, centerAligned, vertical = true, allowCenteredRuns = true) shouldBe true
    }

    @Test
    fun `tightened line gap ratio rejects column jumps`() {
        // Gap = 0.035 > 0.50 * 0.038
        val col1 = box(0.612, 0.075, 0.650, 0.297)
        val colFar = box(0.539, 0.075, 0.577, 0.297)

        OcrMergeGeometry.nextLine(col1, colFar, vertical = true) shouldBe false
    }
    // KUN <--
}
