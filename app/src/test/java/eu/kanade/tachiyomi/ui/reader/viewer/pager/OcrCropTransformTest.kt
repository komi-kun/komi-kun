// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The OCR overlay's border-crop mapping, pinned as arithmetic instead of through a device.
//
// The defect these tests pin: with `cropBorders` on, the viewer displays a *cropped* sheet, but OCR
// runs on the raw page and its block coordinates are normalized to the **uncropped** image. The
// overlay projected them as if the page were shown whole, so every box was placed with a constant
// offset and a constant scale ratio - independent of zoom, which is what separates this from the
// zoom bug the rest of the branch fixes.
//
// `OcrDisplayTransform.Crop` existed for this but was never constructed; the mapping it needs is an
// offset *and* a per-axis scale (a crop may trim different margins left/right than top/bottom), and
// it composes with the split/merge layouts the holders already use, because both SSIV and the strip
// decoder crop whatever sheet they are handed - so crop is the outermost step.
//
// What no JVM test here can prove: that the rect fed to `Crop` is the one the bitmap was cut with.
// SSIV keeps its rect private, so `ReaderPageImageView` derives it with SSIV's own routine and only
// trusts it when SSIV reports the cropped size it produced; `OcrCropWiringTest` guards that call
// site, and the recipe tests at the bottom pin the derivation against a drifted port.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.ocr.OcrCropWindow
import eu.kanade.tachiyomi.ui.reader.ocr.OcrDisplayTransform
import eu.kanade.tachiyomi.ui.reader.ocr.impl.OcrSheetCrop
import eu.kanade.tachiyomi.ui.reader.ocr.impl.ssivCropRect
import eu.kanade.tachiyomi.ui.reader.ocr.impl.ssivCropSampleSize
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OcrCropTransformTest {

    private val page = ReaderPage(index = 0, url = "page")
    private val partner = ReaderPage(index = 1, url = "partner")

    /** A block big enough to be measurable, well inside the page. */
    private val block = floatArrayOf(0.2f, 0.3f, 0.5f, 0.6f)

    private fun OcrDisplayTransform.map(
        rect: FloatArray = block,
        ownerW: Int = 0,
        ownerH: Int = 0,
    ): FloatArray = mapForwardRect(
        left = rect[0],
        top = rect[1],
        right = rect[2],
        bottom = rect[3],
        page = page,
        owner = page,
        partner = partner,
        ownerW = ownerW,
        ownerH = ownerH,
    )

    private fun assertMaps(expected: FloatArray, actual: FloatArray, delta: Float = 0f) {
        assertArrayEquals(expected, actual, delta, "expected ${expected.toList()} but was ${actual.toList()}")
    }

    // ------------------------------------------------------------ the default: crop off

    @Test
    fun `a page shown whole maps exactly as the uncropped transform does`() {
        val none = OcrDisplayTransform.None
        val uncropped = none.croppedBy(null)
        // The whole page is not a crop: `croppedBy` must leave the transform alone, not wrap it in an
        // identity window (which would still divide, and any rounding there would move a box).
        assertEquals(none, uncropped)
        assertEquals(none, none.croppedBy(OcrCropWindow(0f, 0f, 1f, 1f)))

        // Delta 0f on purpose: the crop-off path has to be *identical*, not merely close.
        assertMaps(block, uncropped.map())
        assertMaps(floatArrayOf(0f, 0f, 1f, 1f), uncropped.map(floatArrayOf(0f, 0f, 1f, 1f)))
    }

    // ------------------------------------------------------------ offset

    @Test
    fun `the page's corners land on the crop window's corners`() {
        val window = OcrCropWindow(left = 0.1f, top = 0.2f, right = 0.9f, bottom = 0.8f)
        val cropped = OcrDisplayTransform.None.croppedBy(window)

        // A block at the *page's* top-left is the crop's top-left: the margins are gone.
        assertMaps(
            floatArrayOf(0f, 0f, 0f, 0f),
            cropped.map(floatArrayOf(0.1f, 0.2f, 0.1f, 0.2f)),
        )
        // ...and one at the page's bottom-right is the crop's bottom-right.
        assertMaps(
            floatArrayOf(1f, 1f, 1f, 1f),
            cropped.map(floatArrayOf(0.9f, 0.8f, 0.9f, 0.8f)),
        )
    }

    @Test
    fun `a block inside the page is offset into crop space and scaled by the crop`() {
        val cropped = OcrDisplayTransform.None
            .croppedBy(OcrCropWindow(left = 0.1f, top = 0.2f, right = 0.9f, bottom = 0.8f))

        // x: (0.2 - 0.1) / 0.8 and (0.5 - 0.1) / 0.8; y: (0.3 - 0.2) / 0.6 and (0.6 - 0.2) / 0.6.
        // Treating the page as whole (the defect) would have given the block back unchanged.
        assertMaps(floatArrayOf(0.125f, 0.16666667f, 0.5f, 0.6666667f), cropped.map(), delta = 1e-6f)
    }

    // ------------------------------------------------------------ per-axis scale

    @Test
    fun `a non-square crop maps each axis with its own factor`() {
        // 10% trimmed on both sides horizontally, 25% on both sides vertically: the axes scale by
        // 0.8 and 0.5, so one shared factor would put the block wrong on one of them.
        val cropped = OcrDisplayTransform.None
            .croppedBy(OcrCropWindow(left = 0.1f, top = 0.25f, right = 0.9f, bottom = 0.75f))

        val mapped = cropped.map(floatArrayOf(0.2f, 0.25f, 0.6f, 0.75f))

        assertMaps(
            floatArrayOf(
                (0.2f - 0.1f) / 0.8f,
                (0.25f - 0.25f) / 0.5f,
                (0.6f - 0.1f) / 0.8f,
                (0.75f - 0.25f) / 0.5f,
            ),
            mapped,
            delta = 1e-6f,
        )
        // Spelled out: x is divided by 0.8, y by 0.5 - neither borrows the other's factor.
        assertEquals(0.625f, mapped[2], 1e-6f)
        assertEquals(1f, mapped[3], 1e-6f)
    }

    // ------------------------------------------------------------ composition with split / merge

    @Test
    fun `the crop applies outside a split sheet, not to the page`() {
        // A split page shows one half at 2x: the block's sheet-space rect is doubled and the crop then
        // applies to that displayed half, because the half is what gets border-cropped.
        val split = OcrDisplayTransform.Split(keepLeft = true)
        val half = floatArrayOf(0.1f, 0.2f, 0.5f, 0.6f)

        // Without a crop: ((0.1 - 0) * 2, 0.2, (0.5 - 0) * 2, 0.6).
        assertMaps(floatArrayOf(0.2f, 0.2f, 1f, 0.6f), split.map(half))

        // With one, over a window that only makes sense on the split sheet: x is untouched (width 1),
        // y is (0.2 - 0.1) / 0.5 and (0.6 - 0.1) / 0.5.
        val cropped = split.croppedBy(OcrCropWindow(left = 0f, top = 0.1f, right = 1f, bottom = 0.6f))
        assertMaps(floatArrayOf(0.2f, 0.2f, 1f, 1f), cropped.map(half), delta = 1e-6f)
    }

    @Test
    fun `the crop applies outside a merged sheet`() {
        // Two 400x600 pages, no centre margin, LTR: the owner fills the merged sheet's left half.
        val merge = OcrDisplayTransform.Merge(
            partner = partner,
            partnerW = 400,
            partnerH = 600,
            isLTR = true,
            centerMarginPx = 0,
        )
        val ownerRect = floatArrayOf(0.2f, 0.2f, 0.8f, 0.8f)

        // Without a crop the block sits in the merged sheet's left half.
        assertMaps(floatArrayOf(0.1f, 0.2f, 0.4f, 0.8f), merge.map(ownerRect, ownerW = 400, ownerH = 600))

        // With one, the crop applies to the *merged* sheet: (0.1, 0.2, 0.4, 0.8) through
        // (0.05, 0.1, 0.55, 0.9) - a window only reachable if the sheet, not the page, is cropped.
        val cropped = merge.croppedBy(OcrCropWindow(left = 0.05f, top = 0.1f, right = 0.55f, bottom = 0.9f))
        assertMaps(
            floatArrayOf(0.1f, 0.125f, 0.7f, 0.875f),
            cropped.map(ownerRect, ownerW = 400, ownerH = 600),
            delta = 1e-6f,
        )
    }

    @Test
    fun `a crop window cannot be applied twice`() {
        val window = OcrCropWindow(left = 0.1f, top = 0.2f, right = 0.9f, bottom = 0.8f)
        val once = OcrDisplayTransform.None.croppedBy(window)

        // A second window would map crop space through crop space, i.e. place every block twice.
        assertEquals(once, once.croppedBy(OcrCropWindow(left = 0f, top = 0f, right = 0.5f, bottom = 0.5f)))
    }

    // ------------------------------------------------------------ inverse

    @Test
    fun `the window's inverse undoes its mapping`() {
        val window = OcrCropWindow(left = 0.1f, top = 0.35f, right = 0.9f, bottom = 0.65f)

        for (x in listOf(0.1f, 0.2f, 0.5f, 0.9f)) {
            assertEquals(x, window.unmapX(window.mapX(x)), 1e-6f, "x = $x")
        }
        for (y in listOf(0.35f, 0.4f, 0.5f, 0.65f)) {
            assertEquals(y, window.unmapY(window.mapY(y)), 1e-6f, "y = $y")
        }
        // The displayed sheet's corners are the window's corners in page space.
        assertEquals(0.35f, window.unmapY(0f), 1e-6f)
        assertEquals(0.65f, window.unmapY(1f), 1e-6f)
    }

    // ------------------------------------------------------------ degenerate windows

    @Test
    fun `a window with no area is not a crop`() {
        val collapsed = OcrCropWindow(left = 0.5f, top = 0.5f, right = 0.5f, bottom = 0.5f)

        assertFalse(collapsed.isUsable)
        // It must not wrap (and then divide by zero); the uncropped mapping stays.
        assertEquals(OcrDisplayTransform.None, OcrDisplayTransform.None.croppedBy(collapsed))
        assertMaps(block, OcrDisplayTransform.None.croppedBy(collapsed).map())
    }

    // ------------------------------------------------------------ the rect's own arithmetic

    @Test
    fun `a pixel rect becomes fractions of the sheet, and one that trims nothing is not a crop`() {
        assertEquals(
            OcrCropWindow(left = 0.1f, top = 0.2f, right = 0.6f, bottom = 0.8f),
            OcrCropWindow.ofPixels(left = 100, top = 200, width = 500, height = 600, sheetWidth = 1000, sheetHeight = 1000),
        )
        // Nothing trimmed: no window at all, so the uncropped mapping stays byte-identical.
        assertNull(OcrCropWindow.ofPixels(left = 0, top = 0, width = 1000, height = 1000, sheetWidth = 1000, sheetHeight = 1000))
        // Degenerate rects are refused rather than dividing by zero.
        assertNull(OcrCropWindow.ofPixels(left = 10, top = 10, width = 0, height = 500, sheetWidth = 1000, sheetHeight = 1000))
        assertNull(OcrCropWindow.ofPixels(left = 10, top = 10, width = 100, height = 100, sheetWidth = 0, sheetHeight = 100))
    }

    @Test
    fun `the scan decodes at SSIV's 4 megapixel bound`() {
        // Max(1, ceil(sqrt(w * h / 4_000_000))) - the size SSIV's decoder scans at.
        assertEquals(1, ssivCropSampleSize(1000, 1000))
        assertEquals(1, ssivCropSampleSize(2000, 2000)) // exactly 4 MP
        assertEquals(2, ssivCropSampleSize(3000, 3000)) // 9 MP
        assertEquals(2, ssivCropSampleSize(4000, 3000)) // 12 MP
        assertEquals(3, ssivCropSampleSize(4000, 6000)) // 24 MP
    }

    @Test
    fun `at full resolution the crop is the rect the routines found`() {
        assertArrayEquals(
            intArrayOf(10, 20, 100, 200),
            ssivCropRect(
                found = intArrayOf(10, 20, 100, 200),
                frameWidth = 1000,
                frameHeight = 1000,
                sheetWidth = 1000,
                sheetHeight = 1000,
                sampleSize = 1,
            ),
        )
    }

    @Test
    fun `a sampled scan widens the rect by a pixel and snaps it to the sample grid`() {
        // SSIV scans at 1/2 and then widens the found rect by one sampled pixel on each side, so the
        // crop covers the borders it found plus the detail lost to sampling: (10-1)*2 = 18,
        // (10+50+1)*2 = 122, (20-1)*2 = 38, (20+60+1)*2 = 162.
        assertArrayEquals(
            intArrayOf(18, 38, 104, 124),
            ssivCropRect(
                found = intArrayOf(10, 20, 50, 60),
                frameWidth = 100,
                frameHeight = 100,
                sheetWidth = 200,
                sheetHeight = 200,
                sampleSize = 2,
            ),
        )
    }

    @Test
    fun `a scanned rect is clamped to the sheet`() {
        // The pad would run past the sheet's edge at 1/2; the rect stops at the sheet, not past it.
        assertArrayEquals(
            intArrayOf(0, 0, 200, 100),
            ssivCropRect(
                found = intArrayOf(0, 0, 99, 49),
                frameWidth = 100,
                frameHeight = 50,
                sheetWidth = 200,
                sheetHeight = 100,
                sampleSize = 2,
            ),
        )
    }

    @Test
    fun `a rect the view could not have used is refused`() {
        val usable = intArrayOf(10, 20, 50, 60)
        // Runs outside the sampled frame.
        assertNull(ssivCropRect(usable, frameWidth = 50, frameHeight = 100, sheetWidth = 100, sheetHeight = 200, sampleSize = 1))
        assertNull(ssivCropRect(usable, frameWidth = 100, frameHeight = 60, sheetWidth = 200, sheetHeight = 120, sampleSize = 1))
        // Degenerate or negative.
        assertNull(ssivCropRect(intArrayOf(10, 20, 0, 60), 100, 100, 100, 100, 1))
        assertNull(ssivCropRect(intArrayOf(-1, 20, 50, 60), 100, 100, 100, 100, 1))
        // No result at all from the native routine.
        assertNull(ssivCropRect(null, 100, 100, 100, 100, 1))
        assertNull(ssivCropRect(intArrayOf(1, 2), 100, 100, 100, 100, 1))
    }

    @Test
    fun `the derived crop is only trusted when it is the size SSIV reports`() {
        val crop = OcrSheetCrop(left = 100, top = 200, width = 500, height = 600, sheetWidth = 1000, sheetHeight = 1000)

        assertTrue(crop.matches(sWidth = 500, sHeight = 600))
        // A drifted recipe produces different dims, and then nothing is applied at all.
        assertFalse(crop.matches(sWidth = 1000, sHeight = 1000))
        assertFalse(crop.matches(sWidth = 502, sHeight = 600))
        assertEquals(
            OcrCropWindow(left = 0.1f, top = 0.2f, right = 0.6f, bottom = 0.8f),
            crop.window(),
        )
    }
}
