// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.anki

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.ceil

/**
 * T1 gate for the `{screenshot}` crop decision (plan 05 §B4): the mode decides *whether* a card
 * image exists, the tapped OCR block decides *where*, and the preset aspect must never clip the
 * block or leave the page. The Android decode/crop/encode half lives in the app and is device-only.
 */
class AnkiScreenshotCropTest {

    private val page = 1000

    // 0.4..0.6 of a 1000×1000 page: a 200×200 px block, i.e. the "bubble" being mined.
    private val block = NormalizedCropRect(0.4f, 0.4f, 0.6f, 0.6f)

    @Test
    fun `NONE mode produces no image`() {
        AnkiScreenshotCrop.rectFor(AnkiScreenshotMode.NONE, block, page, page) shouldBe null
    }

    @Test
    fun `FULL mode uses the whole page`() {
        AnkiScreenshotCrop.rectFor(AnkiScreenshotMode.FULL, block, page, page) shouldBe
            NormalizedCropRect(0f, 0f, 1f, 1f)
    }

    @Test
    fun `ANIMATED_SCENE degrades to the whole page`() {
        AnkiScreenshotCrop.rectFor(AnkiScreenshotMode.ANIMATED_SCENE, block, page, page) shouldBe
            NormalizedCropRect(0f, 0f, 1f, 1f)
    }

    @Test
    fun `CROP without a preset frames the block plus the margin`() {
        // 200 px block + 8 % per side = 232 px, centred on the block's centre (500, 500).
        AnkiScreenshotCrop.rectFor(AnkiScreenshotMode.CROP, block, page, page) shouldMatch
            Crop(0.384f, 0.384f, 0.616f, 0.616f)
    }

    @Test
    fun `CROP expands the short side to the preset aspect`() {
        // 232 px square grown to 4:3 → 309.333 px wide at the same height: 345.333..654.667.
        AnkiScreenshotCrop.rectFor(
            mode = AnkiScreenshotMode.CROP,
            block = block,
            pageWidth = page,
            pageHeight = page,
            aspect = AnkiCropAspect(4, 3),
        ) shouldMatch Crop(0.345333f, 0.384f, 0.654667f, 0.616f)
    }

    @Test
    fun `CROP slides back inside the page instead of clipping the block`() {
        // A bubble flush against the right edge: 4:3 would end at 1016.667 px, so the crop moves left
        // to 866.667..1000 rather than cutting the block at the page edge.
        val edgeBlock = NormalizedCropRect(0.9f, 0.4f, 1f, 0.5f)
        AnkiScreenshotCrop.rectFor(
            mode = AnkiScreenshotMode.CROP,
            block = edgeBlock,
            pageWidth = page,
            pageHeight = page,
            aspect = AnkiCropAspect(4, 3),
            margin = 0f,
        ) shouldMatch Crop(0.866667f, 0.4f, 1f, 0.5f)
    }

    @Test
    fun `CROP shrinks a page-filling preset to the page and keeps the aspect`() {
        // 1:1 on a 1000×200 strip page: the crop cannot be 1000 px tall, so both sides shrink to 200.
        AnkiScreenshotCrop.rectFor(
            mode = AnkiScreenshotMode.CROP,
            block = NormalizedCropRect(0f, 0f, 1f, 1f),
            pageWidth = 1000,
            pageHeight = 200,
            aspect = AnkiCropAspect(1, 1),
            margin = 0f,
        ) shouldMatch Crop(0.4f, 0f, 0.6f, 1f)
    }

    @Test
    fun `reversed block edges are ordered before cropping`() {
        val reversed = NormalizedCropRect(0.6f, 0.6f, 0.4f, 0.4f)
        AnkiScreenshotCrop.rectFor(AnkiScreenshotMode.CROP, reversed, page, page) shouldMatch
            Crop(0.384f, 0.384f, 0.616f, 0.616f)
    }

    @Test
    fun `out-of-range block coordinates are clamped into the page`() {
        val overflowing = NormalizedCropRect(0.9f, 0.9f, 1.4f, 1.4f)
        // Clamped to 0.9..1.0 of the page: a 100 px square at (900, 900) plus the 8 % margin, slid
        // back to end exactly on the page edge.
        AnkiScreenshotCrop.rectFor(AnkiScreenshotMode.CROP, overflowing, page, page) shouldMatch
            Crop(0.884f, 0.884f, 1f, 1f)
    }

    @Test
    fun `a block with no area yields no image`() {
        val dot = NormalizedCropRect(0.5f, 0.5f, 0.5f, 0.5f)
        AnkiScreenshotCrop.rectFor(AnkiScreenshotMode.CROP, dot, page, page) shouldBe null
    }

    @Test
    fun `an unmeasured page yields no image`() {
        AnkiScreenshotCrop.rectFor(AnkiScreenshotMode.CROP, block, 0, page) shouldBe null
        AnkiScreenshotCrop.rectFor(AnkiScreenshotMode.CROP, block, page, 0) shouldBe null
    }

    private data class Crop(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** Asserts all four edges; written as one `Crop(...)` so each case reads as a single value. */
    private infix fun NormalizedCropRect?.shouldMatch(expected: Crop): NormalizedCropRect? {
        val rect = this ?: error("expected a crop rect, got null")
        rect.left.shouldBeClose(expected.left)
        rect.top.shouldBeClose(expected.top)
        rect.right.shouldBeClose(expected.right)
        rect.bottom.shouldBeClose(expected.bottom)
        return rect
    }

    private fun Float.shouldBeClose(expected: Float) {
        if (abs(this - expected) > TOLERANCE) {
            throw AssertionError("expected $expected (± $TOLERANCE) but was $this")
        }
    }

    // ---------------------------------------------------------------- pixel rect

    @Test
    fun `pixel rect floors the normalized edges on a measured page`() {
        // (0.384..0.616) of 1000 px is exactly 384..616; 333.5 / 666.5 floor down.
        AnkiScreenshotCrop.pixelRectFor(NormalizedCropRect(0.384f, 0.384f, 0.616f, 0.616f), 1000, 1000) shouldBe
            PixelCropRect(384, 384, 616, 616)
        AnkiScreenshotCrop.pixelRectFor(NormalizedCropRect(0.3335f, 0f, 0.6665f, 1f), 1000, 1000) shouldBe
            PixelCropRect(333, 0, 666, 1000)
    }

    @Test
    fun `a crop that rounds away keeps one pixel instead of collapsing`() {
        // The same normalized point on both edges: the decoder rejects an empty region, so the crop
        // must stay 1×1 rather than become 0×0.
        AnkiScreenshotCrop.pixelRectFor(NormalizedCropRect(0.5f, 0.5f, 0.5f, 0.5f), 10, 10) shouldBe
            PixelCropRect(5, 5, 6, 6)
    }

    @Test
    fun `pixel rect is clamped inside the page for out-of-range input`() {
        AnkiScreenshotCrop.pixelRectFor(NormalizedCropRect(0f, 0f, 1f, 1f), 999, 777) shouldBe
            PixelCropRect(0, 0, 999, 777)
        AnkiScreenshotCrop.pixelRectFor(NormalizedCropRect(-0.5f, -0.5f, 1.5f, 1.5f), 100, 100) shouldBe
            PixelCropRect(0, 0, 100, 100)
        AnkiScreenshotCrop.pixelRectFor(NormalizedCropRect(0.6f, 0.6f, 0.4f, 0.4f), 100, 100) shouldBe
            PixelCropRect(40, 40, 60, 60)
    }

    @Test
    fun `every pixel rect satisfies the decoder's region contract`() {
        val rects = listOf(
            NormalizedCropRect(0.4f, 0.4f, 0.6f, 0.6f),
            NormalizedCropRect(0f, 0f, 1f, 1f),
            NormalizedCropRect(0.9999f, 0.9999f, 1f, 1f),
            NormalizedCropRect(0.5f, 0.5f, 0.5f, 0.5f),
            NormalizedCropRect(-1f, -1f, 2f, 2f),
            NormalizedCropRect(0.1f, 0.9f, 0.1f, 1f),
        )
        for (rect in rects) {
            for ((w, h) in listOf(1 to 1, 7 to 3, 1080 to 1920, 5136 to 7288)) {
                val pixel = AnkiScreenshotCrop.pixelRectFor(rect, w, h)
                    ?: throw AssertionError("$rect on ${w}x$h produced no pixel rect")
                // imagedecoder's `checkRegion` requires this exact invariant, else it throws.
                val inside = pixel.left >= 0 && pixel.top >= 0 && pixel.right <= w && pixel.bottom <= h
                val nonEmpty = pixel.right > pixel.left && pixel.bottom > pixel.top
                (inside && nonEmpty) shouldBe true
            }
        }
    }

    @Test
    fun `an unmeasured page has no pixel rect`() {
        AnkiScreenshotCrop.pixelRectFor(NormalizedCropRect(0f, 0f, 1f, 1f), 0, 100) shouldBe null
        AnkiScreenshotCrop.pixelRectFor(NormalizedCropRect(0f, 0f, 1f, 1f), 100, 0) shouldBe null
    }

    // ---------------------------------------------------------------- sample size

    @Test
    fun `sample size is 1 until the crop exceeds the cap`() {
        AnkiScreenshotCrop.sampleSizeFor(PixelCropRect(0, 0, 1024, 800), 1024) shouldBe 1
        AnkiScreenshotCrop.sampleSizeFor(PixelCropRect(0, 0, 1, 1), 1024) shouldBe 1
        AnkiScreenshotCrop.sampleSizeFor(PixelCropRect(0, 0, 1025, 800), 1024) shouldBe 2
        AnkiScreenshotCrop.sampleSizeFor(PixelCropRect(0, 0, 2048, 2048), 1024) shouldBe 2
    }

    @Test
    fun `sample size rounds up so the decoded side never exceeds the cap`() {
        // 2049 px: floor would give 2 → ceil(2049/2) = 1025 px, one over the cap; ceil gives 3.
        AnkiScreenshotCrop.sampleSizeFor(PixelCropRect(0, 0, 2049, 10), 1024) shouldBe 3
        AnkiScreenshotCrop.sampleSizeFor(PixelCropRect(0, 0, 7288, 300), 1024) shouldBe 8

        for (longest in listOf(1, 1024, 1025, 2049, 4096, 7288)) {
            val sample = AnkiScreenshotCrop.sampleSizeFor(PixelCropRect(0, 0, longest, 1), 1024)
            val decoded = ceil(longest.toDouble() / sample).toInt()
            (decoded <= 1024) shouldBe true
        }
    }

    @Test
    fun `sample size never drops below one even for a one pixel cap`() {
        AnkiScreenshotCrop.sampleSizeFor(PixelCropRect(0, 0, 100, 50), 1) shouldBe 100
        AnkiScreenshotCrop.sampleSizeFor(PixelCropRect(0, 0, 100, 50), 1_000_000) shouldBe 1
    }

    private companion object {
        const val TOLERANCE = 1e-4f
    }
}
// KUN <--
