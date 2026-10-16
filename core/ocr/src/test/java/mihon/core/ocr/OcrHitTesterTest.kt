// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Unit tests for the scale-aware hit tester (contract §5, 03 §3.6).
// Ported from Chimahon (GPL-3.0-or-later) @ f85c063.
// KUN <--
package mihon.core.ocr

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OcrHitTesterTest {

    private val fullBox = OcrNormalizedBounds(0f, 0f, 1f, 1f)

    @Test
    fun `contains an interior point`() {
        OcrHitTester.contains(fullBox, 0.5f, 0.5f) shouldBe true
    }

    @Test
    fun `rejects a point outside the box`() {
        OcrHitTester.contains(fullBox, 1.5f, 0.5f) shouldBe false
        OcrHitTester.contains(fullBox, 0.5f, -0.1f) shouldBe false
    }

    @Test
    fun `scale expands the hit area around the center`() {
        val box = OcrNormalizedBounds(0.4f, 0.4f, 0.6f, 0.6f)

        OcrHitTester.contains(box, 0.7f, 0.5f) shouldBe false
        OcrHitTester.contains(box, 0.7f, 0.5f, scaleX = 2f) shouldBe true
    }

    @Test
    fun `padding expands the hit area`() {
        val box = OcrNormalizedBounds(0.4f, 0.4f, 0.6f, 0.6f)

        OcrHitTester.contains(box, 0.7f, 0.5f) shouldBe false
        OcrHitTester.contains(box, 0.7f, 0.5f, paddingX = 0.2f) shouldBe true
    }

    @Test
    fun `findBlock picks the closest center`() {
        val blocks = mapOf(
            "left" to OcrNormalizedBounds(0.0f, 0.0f, 0.2f, 0.2f),
            "right" to OcrNormalizedBounds(0.8f, 0.8f, 1.0f, 1.0f),
        )

        val hit = OcrHitTester.findBlock(blocks.keys, 0.9f, 0.9f) { blocks.getValue(it) }

        hit shouldBe "right"
    }

    @Test
    fun `findBlock returns null when nothing contains the point`() {
        val blocks = mapOf("left" to OcrNormalizedBounds(0.0f, 0.0f, 0.2f, 0.2f))

        OcrHitTester.findBlock(blocks.keys, 0.9f, 0.9f) { blocks.getValue(it) }.shouldBeNull()
    }

    @Test
    fun `hitCharacter maps horizontal position to a character offset`() {
        val first = OcrHitTester.hitCharacter("abcd", 0f, 0f, 4f, 1f, 0.5f, 0.5f, 0f, vertical = false)
        val third = OcrHitTester.hitCharacter("abcd", 0f, 0f, 4f, 1f, 2.5f, 0.5f, 0f, vertical = false)

        first?.offset shouldBe 0
        third?.offset shouldBe 2
    }

    @Test
    fun `hitCharacter maps vertical position to a character offset`() {
        val hit = OcrHitTester.hitCharacter("abcd", 0f, 0f, 1f, 4f, 0.5f, 2.5f, 0f, vertical = true)

        hit?.offset shouldBe 2
    }

    @Test
    fun `hitCharacter returns null for a point outside the line`() {
        OcrHitTester.hitCharacter("abcd", 0f, 0f, 4f, 1f, 4.5f, 0.5f, 0f, vertical = false).shouldBeNull()
    }

    @Test
    fun `hitCharacter returns null for empty text`() {
        OcrHitTester.hitCharacter("", 0f, 0f, 4f, 1f, 1f, 0.5f, 0f, vertical = false).shouldBeNull()
    }

    @Test
    fun `hitCharacter accounts for rotation`() {
        // A 90-degree box: the point below the center maps to the last character.
        val hit = OcrHitTester.hitCharacter("abcd", 0f, 0f, 4f, 1f, 2f, 2.5f, 90f, vertical = false)

        hit?.offset shouldBe 3
    }

    @Test
    fun `hitLines selects the containing line and its character`() {
        val lines = listOf(
            OcrCharacterLine("top", "abcd", 0, 0f, 0f, 4f, 1f, 0f, vertical = false),
            OcrCharacterLine("bottom", "efgh", 0, 0f, 1f, 4f, 2f, 0f, vertical = false),
        )

        val hit = OcrHitTester.hitLines(lines, 2.5f, 1.5f)

        hit?.value shouldBe "bottom"
        hit?.lineOffset shouldBe 2
        hit?.textOffset shouldBe 2
    }

    @Test
    fun `isLineVertical heuristic`() {
        OcrHitTester.isLineVertical(true, 0f, 0f, 10f, 10f) shouldBe true
        OcrHitTester.isLineVertical(false, 0f, 0f, 10f, 5f) shouldBe false
        OcrHitTester.isLineVertical(false, 0f, 0f, 10f, 20f) shouldBe true
        OcrHitTester.isLineVertical(false, 0f, 0f, 0f, 20f) shouldBe false
    }
}
