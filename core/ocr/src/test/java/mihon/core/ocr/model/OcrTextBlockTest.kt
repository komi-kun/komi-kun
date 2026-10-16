// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Unit tests for the unified viewer block: reading order, display text, lookup extraction
// (contract §5, 03 §3.6). Ported from Chimahon (GPL-3.0-or-later) @ f85c063.
// KUN <--
package mihon.core.ocr.model

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import mihon.core.ocr.OcrLineGeometry
import mihon.core.ocr.toBlockData
import mihon.core.ocr.toTextBlock
import org.junit.jupiter.api.Test

class OcrTextBlockTest {

    private fun geometry(
        xmin: Float,
        ymin: Float,
        xmax: Float,
        ymax: Float,
    ) = OcrLineGeometry(xmin = xmin, ymin = ymin, xmax = xmax, ymax = ymax)

    @Test
    fun `fullText concatenates lines without separator`() {
        val block = OcrTextBlock(0f, 0f, 1f, 1f, lines = listOf("あ", "い", "う"))

        block.fullText shouldBe "あいう"
    }

    @Test
    fun `ordered indices follow top-to-bottom reading order for horizontal text`() {
        val block = OcrTextBlock(
            xmin = 0f,
            ymin = 0f,
            xmax = 1f,
            ymax = 1f,
            lines = listOf("second", "first"),
            vertical = false,
            lineGeometries = listOf(
                geometry(0.5f, 0.5f, 0.9f, 0.6f),
                geometry(0.1f, 0.1f, 0.5f, 0.2f),
            ),
        )

        block.orderedLineIndices() shouldBe listOf(1, 0)
        block.orderedFullText shouldBe "firstsecond"
    }

    @Test
    fun `ordered indices follow right-to-left reading order for vertical text`() {
        val block = OcrTextBlock(
            xmin = 0f,
            ymin = 0f,
            xmax = 1f,
            ymax = 1f,
            lines = listOf("left", "right"),
            vertical = true,
            lineGeometries = listOf(
                geometry(0.1f, 0.1f, 0.2f, 0.6f),
                geometry(0.8f, 0.1f, 0.9f, 0.6f),
            ),
        )

        block.orderedLineIndices() shouldBe listOf(1, 0)
        block.orderedFullText shouldBe "rightleft"
    }

    @Test
    fun `missing geometry falls back to detection order`() {
        val block = OcrTextBlock(0f, 0f, 1f, 1f, lines = listOf("b", "a"), vertical = false)

        block.orderedLineIndices() shouldBe listOf(0, 1)
    }

    @Test
    fun `display text trims duplicated line overlaps`() {
        val block = OcrTextBlock(
            0f,
            0f,
            1f,
            1f,
            lines = listOf("hello world", "world again"),
            vertical = false,
        )

        block.displayText shouldBe "hello world again"
    }

    @Test
    fun `display text keeps short overlaps untouched`() {
        val block = OcrTextBlock(0f, 0f, 1f, 1f, lines = listOf("ab", "abc"), vertical = false)

        block.displayText shouldBe "ab abc"
    }

    @Test
    fun `vertical display text is concatenated without separator`() {
        val block = OcrTextBlock(0f, 0f, 1f, 1f, lines = listOf("あ", "い"), vertical = true)

        block.displayText shouldBe "あい"
    }

    @Test
    fun `line boundaries of an offset`() {
        val block = OcrTextBlock(0f, 0f, 1f, 1f, lines = listOf("ab", "cd"))

        block.lineBoundariesFor(1) shouldBe (0 to 2)
        block.lineBoundariesFor(2) shouldBe (2 to 4)
        block.lineBoundariesFor(3) shouldBe (2 to 4)
    }

    @Test
    fun `toOrderedOffset remaps into reading order`() {
        val block = OcrTextBlock(
            xmin = 0f,
            ymin = 0f,
            xmax = 1f,
            ymax = 1f,
            lines = listOf("ab", "cd"),
            vertical = false,
            lineGeometries = listOf(
                geometry(0.1f, 0.5f, 0.5f, 0.6f),
                geometry(0.1f, 0.1f, 0.5f, 0.2f),
            ),
        )

        // Reading order is [1, 0]; raw offset 0 (line 0) maps after line 1's two characters.
        block.toOrderedOffset(0) shouldBe 2
    }

    @Test
    fun `extract lookup string as a character run`() {
        val block = OcrTextBlock(0f, 0f, 1f, 1f, lines = listOf("これは本です。"))

        block.extractLookupString(global = 0, wholeWord = false) shouldBe "これは本です"
    }

    @Test
    fun `extract lookup string as a whole word`() {
        val block = OcrTextBlock(0f, 0f, 1f, 1f, lines = listOf("hello world"))

        block.extractLookupString(global = 2, wholeWord = true) shouldBe "hello"
    }

    @Test
    fun `extract whole word does not cross a line boundary`() {
        val block = OcrTextBlock(0f, 0f, 1f, 1f, lines = listOf("abcd", "efghij"))

        block.extractLookupString(global = 5, wholeWord = true) shouldBe "efghij"
    }

    @Test
    fun `uniform char offset maps horizontal cells`() {
        val block = OcrTextBlock(0f, 0f, 1f, 1f, lines = listOf("abcd"))

        uniformCharOffset(block, localX = 60f, localY = 25f, screenW = 100f, screenH = 50f) shouldBe 2
    }

    @Test
    fun `uniform char offset maps vertical columns right-to-left`() {
        val block = OcrTextBlock(0f, 0f, 1f, 1f, lines = listOf("ab", "cd"), vertical = true)

        uniformCharOffset(block, localX = 25f, localY = 25f, screenW = 100f, screenH = 100f) shouldBe 2
    }

    @Test
    fun `block round-trips through its cache DTO`() {
        val block = OcrTextBlock(
            xmin = 0.1f,
            ymin = 0.2f,
            xmax = 0.3f,
            ymax = 0.4f,
            lines = listOf("あ", "い"),
            vertical = true,
            lineGeometries = listOf(geometry(0.1f, 0.2f, 0.3f, 0.3f)),
            language = "ja",
        )

        val dto = block.toBlockData()
        dto.toTextBlock() shouldBe block
    }

    @Test
    fun `block is serializable`() {
        val block = OcrTextBlock(0.1f, 0.2f, 0.3f, 0.4f, lines = listOf("あ"), language = "ja")

        val encoded = Json.encodeToString(OcrTextBlock.serializer(), block)
        val decoded = Json.decodeFromString(OcrTextBlock.serializer(), encoded)

        decoded shouldBe block
    }
}
