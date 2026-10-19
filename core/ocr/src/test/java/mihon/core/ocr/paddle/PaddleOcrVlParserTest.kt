// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.ocr.paddle

import mihon.core.ocr.OcrLanguage
import mihon.core.ocr.WritingDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaddleOcrVlParserTest {

    @Test
    fun `empty and blank outputs return empty list`() {
        assertEquals(emptyList<Any>(), PaddleOcrVlParser.parse(""))
        assertEquals(emptyList<Any>(), PaddleOcrVlParser.parse("   \n\t  "))
    }

    @Test
    fun `parses 1000-scaled bracketed coordinates`() {
        val output = """
            [100, 200, 500, 300] 素晴らしい！
            [550, 200, 900, 300] 剣聖と呼ばれた
        """.trimIndent()

        val lines = PaddleOcrVlParser.parse(output, OcrLanguage.JAPANESE)
        assertEquals(2, lines.size)

        val first = lines[0]
        assertEquals("素晴らしい！", first.text)
        assertEquals(0.2, first.bbox.left, 0.001)
        assertEquals(0.1, first.bbox.top, 0.001)
        assertEquals(0.3, first.bbox.right, 0.001)
        assertEquals(0.5, first.bbox.bottom, 0.001)
        assertEquals(WritingDirection.TTB, first.writingDirection)
        assertTrue(first.hasJpText)

        val second = lines[1]
        assertEquals("剣聖と呼ばれた", second.text)
        assertEquals(0.2, second.bbox.left, 0.001)
        assertEquals(0.55, second.bbox.top, 0.001)
        assertEquals(0.3, second.bbox.right, 0.001)
        assertEquals(0.9, second.bbox.bottom, 0.001)
        assertTrue(second.hasKanji)
    }

    @Test
    fun `parses normalized float coordinates`() {
        val output = "[0.05, 0.10, 0.15, 0.90] 水平テキスト"
        val lines = PaddleOcrVlParser.parse(output, OcrLanguage.JAPANESE)

        assertEquals(1, lines.size)
        val line = lines.single()
        assertEquals("水平テキスト", line.text)
        assertEquals(0.10, line.bbox.left, 0.001)
        assertEquals(0.05, line.bbox.top, 0.001)
        assertEquals(0.90, line.bbox.right, 0.001)
        assertEquals(0.15, line.bbox.bottom, 0.001)
        assertEquals(WritingDirection.LTR, line.writingDirection)
    }

    @Test
    fun `parses xml box tags`() {
        val output = "<box>[200, 300, 600, 400]</box> テストタグ"
        val lines = PaddleOcrVlParser.parse(output, OcrLanguage.JAPANESE)

        assertEquals(1, lines.size)
        assertEquals("テストタグ", lines.single().text)
        assertEquals(0.3, lines.single().bbox.left, 0.001)
        assertEquals(0.2, lines.single().bbox.top, 0.001)
        assertEquals(0.4, lines.single().bbox.right, 0.001)
        assertEquals(0.6, lines.single().bbox.bottom, 0.001)
    }

    @Test
    fun `parses 4-point polygon coordinates`() {
        val output = "[[200, 100], [300, 100], [300, 500], [200, 500]]: ポリゴンテキスト"
        val lines = PaddleOcrVlParser.parse(output, OcrLanguage.JAPANESE)

        assertEquals(1, lines.size)
        val line = lines.single()
        assertEquals("ポリゴンテキスト", line.text)
        assertEquals(0.2, line.bbox.left, 0.001)
        assertEquals(0.1, line.bbox.top, 0.001)
        assertEquals(0.3, line.bbox.right, 0.001)
        assertEquals(0.5, line.bbox.bottom, 0.001)
    }

    @Test
    fun `parses json response array`() {
        val jsonOutput = """
            [
              {"bbox": [150, 100, 250, 600], "text": "第1行"},
              {"bbox": [300, 100, 400, 700], "text": "第2行"}
            ]
        """.trimIndent()

        val lines = PaddleOcrVlParser.parse(jsonOutput, OcrLanguage.JAPANESE)
        assertEquals(2, lines.size)
        assertEquals("第1行", lines[0].text)
        assertEquals("第2行", lines[1].text)
    }

    @Test
    fun `falls back gracefully for plain text without coordinates`() {
        val output = """
            タイトル
            本文の一行目
            本文の二行目
        """.trimIndent()

        val lines = PaddleOcrVlParser.parse(output, OcrLanguage.JAPANESE)
        assertEquals(3, lines.size)
        assertEquals("タイトル", lines[0].text)
        assertEquals("本文の一行目", lines[1].text)
        assertEquals("本文の二行目", lines[2].text)
    }
}
// KUN <--
