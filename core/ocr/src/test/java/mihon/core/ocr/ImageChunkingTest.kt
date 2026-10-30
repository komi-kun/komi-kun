// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.ocr

import android.graphics.Bitmap
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import mihon.core.ocr.model.OcrTextBlock
import org.junit.jupiter.api.Test

class ImageChunkingTest {

    @Test
    fun `computeIntervals returns single interval for small dimension`() {
        val intervals = computeIntervals(totalSize = 1080, maxTileSize = 2400, overlapPx = 180)
        intervals shouldBe listOf(0 to 1080)
    }

    @Test
    fun `computeIntervals tiles 4500px into 2 overlapping intervals`() {
        val intervals = computeIntervals(totalSize = 4500, maxTileSize = 2400, overlapPx = 180)
        intervals shouldHaveSize 2
        intervals[0] shouldBe (0 to 2400)
        intervals[1] shouldBe (2100 to 2400)
        // Verify overlap is at least 180px
        val overlap = (intervals[0].first + intervals[0].second) - intervals[1].first
        overlap shouldBe 300
    }

    @Test
    fun `computeIntervals tiles 6400px into 3 overlapping intervals`() {
        val intervals = computeIntervals(totalSize = 6400, maxTileSize = 2400, overlapPx = 180)
        intervals shouldHaveSize 3
        intervals[0] shouldBe (0 to 2400)
        intervals[1] shouldBe (2220 to 2400)
        intervals[2] shouldBe (4000 to 2400)
        // Verify full coverage
        (intervals.last().first + intervals.last().second) shouldBe 6400
    }

    @Test
    fun `normalizeFromChunk correctly projects chunk coordinates to full page`() {
        val mockBitmap = mockk<Bitmap>(relaxed = true)
        val chunk = ImageChunk(
            bitmap = mockBitmap,
            width = 2400,
            height = 2400,
            globalX = 2100,
            globalY = 4000,
            fullWidth = 4500,
            fullHeight = 6400,
        )

        val localLine = EngineLine(
            text = "テスト",
            bbox = NormalizedBBox(left = 0.1, top = 0.2, right = 0.5, bottom = 0.6),
            writingDirection = WritingDirection.LTR,
            language = OcrLanguage.JAPANESE,
        )

        val normalized = localLine.normalizeFromChunk(chunk)

        // Pixel coordinates in chunk:
        // left: 0.1 * 2400 + 2100 = 2340 -> 2340 / 4500 = 0.52
        // right: 0.5 * 2400 + 2100 = 3300 -> 3300 / 4500 = 0.7333333333
        // top: 0.2 * 2400 + 4000 = 4480 -> 4480 / 6400 = 0.70
        // bottom: 0.6 * 2400 + 4000 = 5440 -> 5440 / 6400 = 0.85
        normalized.bbox.left shouldBe (0.52 plusOrMinus 1e-6)
        normalized.bbox.right shouldBe (0.7333333 plusOrMinus 1e-6)
        normalized.bbox.top shouldBe (0.70 plusOrMinus 1e-6)
        normalized.bbox.bottom shouldBe (0.85 plusOrMinus 1e-6)
    }

    @Test
    fun `dedupeChunkBoundaryResults drops duplicates at tile seams and keeps longer text`() {
        val block1 = OcrTextBlock(
            lines = listOf("こんにちは"),
            xmin = 0.10f,
            ymin = 0.10f,
            xmax = 0.20f,
            ymax = 0.15f,
            vertical = false,
        )
        // Duplicate on overlapping tile with slightly less text
        val block2 = OcrTextBlock(
            lines = listOf("こんにち"),
            xmin = 0.102f,
            ymin = 0.101f,
            xmax = 0.198f,
            ymax = 0.151f,
            vertical = false,
        )
        // Distinct non-overlapping block
        val block3 = OcrTextBlock(
            lines = listOf("さようなら"),
            xmin = 0.50f,
            ymin = 0.50f,
            xmax = 0.60f,
            ymax = 0.55f,
            vertical = false,
        )

        val deduped = dedupeChunkBoundaryResults(listOf(block1, block2, block3))
        deduped shouldHaveSize 2
        deduped.map { it.lines.first() } shouldBe listOf("こんにちは", "さようなら")
    }

    @Test
    fun `dedupeEngineLines drops duplicate lines from overlapping tiles and keeps full text`() {
        val lineTile1 = EngineLine(
            text = "サンプルでした",
            bbox = NormalizedBBox(left = 0.4501, top = 0.2010, right = 0.4705, bottom = 0.3502),
            writingDirection = WritingDirection.TTB,
            language = OcrLanguage.JAPANESE,
        )
        // Jittered duplicate from overlapping tile
        val lineTile2 = EngineLine(
            text = "サンプルでした",
            bbox = NormalizedBBox(left = 0.4503, top = 0.2012, right = 0.4707, bottom = 0.3504),
            writingDirection = WritingDirection.TTB,
            language = OcrLanguage.JAPANESE,
        )
        // Truncated version from tile edge
        val lineTruncated = EngineLine(
            text = "サンプ",
            bbox = NormalizedBBox(left = 0.4502, top = 0.2011, right = 0.4706, bottom = 0.2800),
            writingDirection = WritingDirection.TTB,
            language = OcrLanguage.JAPANESE,
        )
        // Distinct second column in the same bubble
        val lineNextCol = EngineLine(
            text = "サンプルの文章",
            bbox = NormalizedBBox(left = 0.4200, top = 0.2010, right = 0.4400, bottom = 0.3502),
            writingDirection = WritingDirection.TTB,
            language = OcrLanguage.JAPANESE,
        )

        val deduped = dedupeEngineLines(listOf(lineTile1, lineTile2, lineTruncated, lineNextCol))
        deduped shouldHaveSize 2
        deduped.map { it.text } shouldBe listOf("サンプルでした", "サンプルの文章")
    }

    @Test
    fun `dedupeChunkBoundaryResults strips duplicate consecutive lines inside block`() {
        val blockWithDups = OcrTextBlock(
            lines = listOf("サンプルでした", "サンプルでした", "サンプルの文章", "サンプルの文章"),
            xmin = 0.40f,
            ymin = 0.20f,
            xmax = 0.50f,
            ymax = 0.35f,
            vertical = true,
        )

        val deduped = dedupeChunkBoundaryResults(listOf(blockWithDups))
        deduped shouldHaveSize 1
        deduped.first().lines shouldBe listOf("サンプルでした", "サンプルの文章")
    }

    @Test
    fun `detectorFrame replicates the native 960 px - 32 px quantisation`() {
        // Pinned against libpaddle_ocr.so's DB preprocess: per side
        // max(lround(side * (float)(960 / longestSide) / 32) * 32, 32), half away from zero.
        detectorFrame(1140, 1200) shouldBe DetectorFrame(width = 928, height = 960)
        detectorFrame(4560, 4800) shouldBe DetectorFrame(width = 928, height = 960)
        detectorFrame(1404, 1428) shouldBe DetectorFrame(width = 928, height = 960)
        detectorFrame(1176, 882) shouldBe DetectorFrame(width = 960, height = 736)
        detectorFrame(1654, 1170) shouldBe DetectorFrame(width = 960, height = 672)
        // Under the limit the native still rounds each side to a multiple of 32.
        detectorFrame(869, 843) shouldBe DetectorFrame(width = 864, height = 832)
        detectorFrame(700, 700) shouldBe DetectorFrame(width = 704, height = 704)
    }

    @Test
    fun `budget clamp keeps the detector frame of the page it shrinks`() {
        // Pages whose naive budget math (int() truncation of both sides) lands the shorter side in a
        // different 32-px bucket, plus pages where it already agrees and pages that are themselves
        // over budget. The frame must never change: that is the whole contract.
        val pages = listOf(
            5616 to 5712, // 1404x1428 at 4x: naive clamp gives 960x960, page frame is 928x960
            4704 to 3528, // 1176x882 at 4x: naive clamp gives 960x704, page frame is 960x736
            7840 to 6404, // 1960x1601 at 4x: naive clamp gives 960x768, page frame is 960x800
            6452 to 3980, // 1613x995 at 4x: naive clamp gives 960x576, page frame is 960x608
            3180 to 2491, // naive clamp gives 960x736, page frame is 960x768
            4560 to 4800, // 1140x1200 at 4x: naive clamp already agrees
            6616 to 4680, // 1654x1170 at 4x: naive clamp already agrees
            3476 to 3372, // 869x843 at 4x: page itself is under the limit
            9920 to 14032, // 2480x3508 at 4x, a native page already over the 4 MP budget
        )

        for ((width, height) in pages) {
            val (newW, newH) = budgetedResizeDims(width, height, MAX_OCR_PIXELS)
            withClue("$width x $height -> $newW x $newH") {
                detectorFrame(newW, newH) shouldBe detectorFrame(width, height)
                (newW.toLong() * newH) shouldBeLessThanOrEqual MAX_OCR_PIXELS.toLong()
                newW shouldBeLessThanOrEqual maxOf(width, height)
                newH shouldBeLessThanOrEqual maxOf(width, height)
            }
        }
    }

    @Test
    fun `an upscaled page reaches the detector with the frame of the original page`() {
        // The defect P0 measured: the same page, once at native size and once at 4x, read from two
        // different detector geometries because the 4 MP clamp truncates both sides.
        val originalPages = listOf(
            1140 to 1200, // P0's headline class: 896x960 vs 928x960 in a banker's-rounding replica
            1404 to 1428,
            1176 to 882,
            1960 to 1601,
            1613 to 995,
            1654 to 1170, // ordinary page: clamp already agrees
            1200 to 1600,
            1382 to 2000,
            943 to 1400,
        )

        for ((width, height) in originalPages) {
            val (newW, newH) = budgetedResizeDims(width * 4, height * 4, MAX_OCR_PIXELS)
            withClue("${width}x$height at 4x -> $newW x $newH") {
                detectorFrame(newW, newH) shouldBe detectorFrame(width, height)
            }
        }
    }

    @Test
    fun `budget clamp leaves pages whose frame already agrees untouched`() {
        // Exact values from the pre-fix implementation: no ordinary page changes size.
        budgetedResizeDims(4560, 4800, MAX_OCR_PIXELS) shouldBe (1949 to 2051)
        budgetedResizeDims(6616, 4680, MAX_OCR_PIXELS) shouldBe (2377 to 1681)
        budgetedResizeDims(1600, 1200, MAX_OCR_PIXELS) shouldBe (1600 to 1200) // under budget: no-op
        // And the repaired classes move only as far as the frame requires.
        budgetedResizeDims(5616, 5712, MAX_OCR_PIXELS) shouldBe (1982 to 2016)
        budgetedResizeDims(4704, 3528, MAX_OCR_PIXELS) shouldBe (2284 to 1731)
    }

    @Test
    fun `detectorFrame honours a bigger requested limit and keeps the 32 px alignment`() {
        // The same chunk read at the three fixed limits: the longest side lands exactly on the limit
        // and the other side keeps its aspect ratio snapped to a 32 px bucket (round, half away from
        // zero) — the arithmetic `libpaddle_ocr.so` uses once it is handed a bigger `max_side_len`.
        detectorFrame(2385, 1689, DETECTOR_LIMIT_SIDE) shouldBe DetectorFrame(width = 960, height = 672)
        detectorFrame(2385, 1689, 1280) shouldBe DetectorFrame(width = 1280, height = 896)
        detectorFrame(2385, 1689, 1600) shouldBe DetectorFrame(width = 1600, height = 1120)

        // Every side is a multiple of 32 at every limit, and a chunk over the limit is reduced to it.
        for (limit in listOf(960, 1280, 1600)) {
            for (chunk in listOf(2385 to 1689, 3200 to 2000, 1920 to 2400, 4000 to 3000)) {
                val frame = detectorFrame(chunk.first, chunk.second, limit)
                withClue("${chunk.first}x${chunk.second} at $limit -> $frame") {
                    (frame.width % DETECTOR_SIDE_ALIGN) shouldBe 0
                    (frame.height % DETECTOR_SIDE_ALIGN) shouldBe 0
                    maxOf(frame.width, frame.height) shouldBe limit
                }
            }
        }

        // A chunk already inside the limit is only snapped to 32 px, whatever the limit is.
        detectorFrame(1300, 1300, 1600) shouldBe DetectorFrame(width = 1312, height = 1312)
    }

    @Test
    fun `the budget clamp keeps the frame at a 1600 limit too`() {
        // The clamp's contract does not depend on the limit: it must reproduce whatever frame the
        // detector produces at the requested limit, not only the default 960 one.
        val pages = listOf(
            5616 to 5712, // 1404x1428 at 4x
            4704 to 3528, // 1176x882 at 4x
            9532 to 6756, // a ~2380 px page at 4x, far above the 4 MP budget
            6616 to 4680, // 1654x1170 at 4x
        )

        for ((width, height) in pages) {
            val (newW, newH) = budgetedResizeDims(width, height, MAX_OCR_PIXELS, 1600)
            withClue("$width x $height -> $newW x $newH") {
                detectorFrame(newW, newH, 1600) shouldBe detectorFrame(width, height, 1600)
                (newW.toLong() * newH) shouldBeLessThanOrEqual MAX_OCR_PIXELS.toLong()
            }
        }
    }

    // KUN -->
    // ---------------------------------------------------------------------------------------
    // Detector resolution is carried on the chunk: the limit is resolved once from the page's own
    // long side and applied to every chunk, so 960/1280/1600 must all keep the frame contract and
    // the limit must reach the engine rather than falling back to the shim's 960.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the budget clamp keeps the 32 px frame and the 4 MP budget at every detector limit`() {
        val pages = listOf(
            5616 to 5712, // 1404x1428 at 4x
            4704 to 3528, // 1176x882 at 4x
            9532 to 6756, // a ~2380 px page at 4x, far above the 4 MP budget
            6616 to 4680, // 1654x1170 at 4x
            7840 to 6404, // 1960x1601 at 4x
            3476 to 3372, // 869x843 at 4x: below every limit, only snapped to 32 px
            9920 to 14032, // 2480x3508 at 4x, a native page already over the budget
        )

        for (limit in listOf(DETECTOR_LIMIT_SIDE, 1280, 1600)) {
            for ((width, height) in pages) {
                val (newW, newH) = budgetedResizeDims(width, height, MAX_OCR_PIXELS, limit)
                val frame = detectorFrame(newW, newH, limit)
                withClue("${width}x$height at $limit -> $newW x $newH ($frame)") {
                    // Same frame as the page the resize stood in for: the whole point of the clamp.
                    frame shouldBe detectorFrame(width, height, limit)
                    (frame.width % DETECTOR_SIDE_ALIGN) shouldBe 0
                    (frame.height % DETECTOR_SIDE_ALIGN) shouldBe 0
                    (newW.toLong() * newH) shouldBeLessThanOrEqual MAX_OCR_PIXELS.toLong()
                    // A page over the limit lands exactly on it, at every limit.
                    maxOf(frame.width, frame.height) shouldBe limit
                }
            }
        }
    }

    @Test
    fun `prepareForOcr resolves the limit once from the page and applies it to every chunk`() {
        mockkStatic(Bitmap::class)
        try {
            every {
                Bitmap.createScaledBitmap(any<Bitmap>(), any<Int>(), any<Int>(), any<Boolean>())
            } answers { bitmapOf(secondArg(), arg(2)) }
            every {
                Bitmap.createBitmap(any<Bitmap>(), any<Int>(), any<Int>(), any<Int>(), any<Int>())
            } answers { bitmapOf(arg(3), arg(4)) }

            // A 1910x2150 page (4.1 MP) is shrunk to the 4 MP budget and left untiled. Its own long
            // side (2150) is the 1600 rung, and the shrunk 1885x2121 chunk keeps it: re-resolving
            // from the chunk would not move this one, but the frame contract is the page's, so the
            // page is the only place the limit may come from (see the near-square 2134x1894 case in
            // `the AUTO pre-shrink keeps the page's detector frame and does not drop a rung`).
            val shrunk = prepareForOcr(bitmapOf(1910, 2150), OcrDetResolution.AUTO).single()
            OcrDetResolution.AUTO.limitFor(2150) shouldBe 1600
            (shrunk.width to shrunk.height) shouldBe (1885 to 2121)
            shrunk.detectorLimitSide shouldBe 1600

            // A tiled page: every 2400 px tile carries the page's limit, and the fixed modes reach it
            // unattended (the old clamp pinned every chunk to 960).
            val tiledAuto = prepareForOcr(bitmapOf(2600, 1500), OcrDetResolution.AUTO)
            tiledAuto.map { it.width to it.height } shouldBe listOf(2400 to 1500, 2400 to 1500)
            tiledAuto.map { it.detectorLimitSide } shouldBe listOf(1600, 1600)

            prepareForOcr(bitmapOf(2600, 1500), OcrDetResolution.HIGH)
                .map { it.detectorLimitSide } shouldBe listOf(1280, 1280)
            prepareForOcr(bitmapOf(2600, 1500), OcrDetResolution.DEFAULT)
                .map { it.detectorLimitSide } shouldBe listOf(960, 960)
        } finally {
            unmockkStatic(Bitmap::class)
        }
    }

    @Test
    fun `the AUTO pre-shrink keeps the page's detector frame and does not drop a rung`() {
        mockkStatic(Bitmap::class)
        try {
            every {
                Bitmap.createScaledBitmap(any<Bitmap>(), any<Int>(), any<Int>(), any<Boolean>())
            } answers { bitmapOf(secondArg(), arg(2)) }
            every {
                Bitmap.createBitmap(any<Bitmap>(), any<Int>(), any<Int>(), any<Int>(), any<Int>())
            } answers { bitmapOf(arg(3), arg(4)) }

            // A 2134x1894 page is ~4.04 MP and near square, so the 4 MP pre-shrink lands on
            // 2122x1883 — across a ladder rung: the page's long side is the 1600 rung, the shrunk
            // chunk's is the 1280 one (2134: 1600, 2122: 1280). Resolving the limit per chunk made
            // the detector geometry depend on whether the budget ran, so this page read at
            // 1600x1408 unbudgeted but 1280x1120 after the shrink.
            val native = 2134 to 1894
            val upscaled = (native.first * 4) to (native.second * 4)

            for (detResolution in listOf(OcrDetResolution.AUTO, OcrDetResolution.VERY_HIGH)) {
                val frames = listOf(native, upscaled).map { (width, height) ->
                    val pageLimit = detResolution.limitFor(maxOf(width, height))
                    val chunk = prepareForOcr(bitmapOf(width, height), detResolution).single()
                    val frame = detectorFrame(chunk.width, chunk.height, chunk.detectorLimitSide)
                    withClue("${width}x$height $detResolution -> ${chunk.width}x${chunk.height} @ ${chunk.detectorLimitSide} ($frame)") {
                        // The chunk carries the page's rung, not the one its own shrunk size implies…
                        chunk.detectorLimitSide shouldBe pageLimit
                        // …so its frame is the frame the page has when no budget runs at all.
                        frame shouldBe detectorFrame(width, height, pageLimit)
                    }
                    frame
                }
                withClue("$detResolution native vs 4x") {
                    frames[0] shouldBe frames[1]
                }
            }

            // Exact geometry of both views: the 1x page and its 4x upscale read at the 1600 rung.
            val autoNative = prepareForOcr(bitmapOf(native.first, native.second), OcrDetResolution.AUTO).single()
            detectorFrame(autoNative.width, autoNative.height, autoNative.detectorLimitSide) shouldBe
                DetectorFrame(width = 1600, height = 1408)
            // And the rung the shrunk chunk would have resolved on its own — the pre-fix frame.
            OcrDetResolution.AUTO.limitFor(2122) shouldBe 1280
            detectorFrame(2122, 1883, 1280) shouldBe DetectorFrame(width = 1280, height = 1120)
        } finally {
            unmockkStatic(Bitmap::class)
        }
    }

    /** A bitmap whose only real behaviour is its size; [width]/[height] drive every chunker. */
    private fun bitmapOf(width: Int, height: Int): Bitmap = mockk(relaxed = true) {
        every { this@mockk.width } returns width
        every { this@mockk.height } returns height
    }
    // KUN <--
}
// KUN <--
