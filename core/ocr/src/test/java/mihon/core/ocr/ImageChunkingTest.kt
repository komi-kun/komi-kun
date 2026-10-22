// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.ocr

import android.graphics.Bitmap
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.mockk.mockk
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
            text = "剣聖と呼ばれた",
            bbox = NormalizedBBox(left = 0.4501, top = 0.2010, right = 0.4705, bottom = 0.3502),
            writingDirection = WritingDirection.TTB,
            language = OcrLanguage.JAPANESE,
        )
        // Jittered duplicate from overlapping tile
        val lineTile2 = EngineLine(
            text = "剣聖と呼ばれた",
            bbox = NormalizedBBox(left = 0.4503, top = 0.2012, right = 0.4707, bottom = 0.3504),
            writingDirection = WritingDirection.TTB,
            language = OcrLanguage.JAPANESE,
        )
        // Truncated version from tile edge
        val lineTruncated = EngineLine(
            text = "剣聖と",
            bbox = NormalizedBBox(left = 0.4502, top = 0.2011, right = 0.4706, bottom = 0.2800),
            writingDirection = WritingDirection.TTB,
            language = OcrLanguage.JAPANESE,
        )
        // Distinct second column in the same bubble
        val lineNextCol = EngineLine(
            text = "ライオネル様に",
            bbox = NormalizedBBox(left = 0.4200, top = 0.2010, right = 0.4400, bottom = 0.3502),
            writingDirection = WritingDirection.TTB,
            language = OcrLanguage.JAPANESE,
        )

        val deduped = dedupeEngineLines(listOf(lineTile1, lineTile2, lineTruncated, lineNextCol))
        deduped shouldHaveSize 2
        deduped.map { it.text } shouldBe listOf("剣聖と呼ばれた", "ライオネル様に")
    }

    @Test
    fun `dedupeChunkBoundaryResults strips duplicate consecutive lines inside block`() {
        val blockWithDups = OcrTextBlock(
            lines = listOf("剣聖と呼ばれた", "剣聖と呼ばれた", "ライオネル様に", "ライオネル様に"),
            xmin = 0.40f,
            ymin = 0.20f,
            xmax = 0.50f,
            ymax = 0.35f,
            vertical = true,
        )

        val deduped = dedupeChunkBoundaryResults(listOf(blockWithDups))
        deduped shouldHaveSize 1
        deduped.first().lines shouldBe listOf("剣聖と呼ばれた", "ライオネル様に")
    }
}
// KUN <--
