// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Unit tests for the runtime `.mokuro` path (fix B1): sidecar selection + page parsing.
// KUN <--
package mihon.core.ocr.cache

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import mihon.core.ocr.mokuro.ImageFileInfo
import org.junit.jupiter.api.Test

class MokuroChapterLoaderTest {

    private val volumeJson = """
        {
          "version": "1.0",
          "title": "T",
          "title_uuid": "t",
          "volume": "1",
          "volume_uuid": "v",
          "pages": [
            {
              "img_path": "T/1/001.jpg",
              "img_width": 1000,
              "img_height": 2000,
              "blocks": [
                { "box": [100, 200, 300, 400], "vertical": true, "lines": ["あ"], "lines_coords": [] }
              ]
            },
            {
              "img_path": "T/1/002.jpg",
              "img_width": 1000,
              "img_height": 2000,
              "blocks": [
                { "box": [10, 20, 30, 40], "vertical": false, "lines": ["b"] }
              ]
            }
          ]
        }
    """.trimIndent()

    private val images = listOf(
        ImageFileInfo(name = "001.jpg", relativePath = "001.jpg", basename = "001"),
        ImageFileInfo(name = "002.jpg", relativePath = "002.jpg", basename = "002"),
    )

    @Test
    fun `parses and resolves the requested page`() {
        val blocks = MokuroChapterLoader.blocksForPage(volumeJson, images, 1)

        blocks.shouldNotBeNull()
        blocks shouldHaveSize 1
        blocks.first().vertical shouldBe false
        blocks.first().lines shouldBe listOf("b")
    }

    @Test
    fun `returns null for malformed content`() {
        MokuroChapterLoader.blocksForPage("{ not json", images, 0).shouldBeNull()
    }

    @Test
    fun `returns null when the page cannot be resolved`() {
        MokuroChapterLoader.blocksForPage(volumeJson, emptyList(), 0).shouldBeNull()
        MokuroChapterLoader.blocksForPage(volumeJson, images, 9).shouldBeNull()
    }

    @Test
    fun `prefers the exact chapter sidecar name`() {
        val present = listOf("other.mokuro", "Chapter 1.mokuro", "Chapter 1.cbz.mokuro")

        MokuroChapterLoader.selectSidecar("Chapter 1.cbz", present) shouldBe "Chapter 1.cbz.mokuro"
    }

    @Test
    fun `falls back to the archive stem`() {
        val present = listOf("other.mokuro", "Chapter 1.mokuro")

        MokuroChapterLoader.selectSidecar("Chapter 1.cbz", present) shouldBe "Chapter 1.mokuro"
    }

    @Test
    fun `falls back to any sidecar deterministically`() {
        val present = listOf("zzz.mokuro", "aaa.mokuro", "cover.jpg")

        MokuroChapterLoader.selectSidecar("Chapter 1", present) shouldBe "aaa.mokuro"
    }

    @Test
    fun `matches sidecar names case-insensitively`() {
        val present = listOf("CHAPTER 1.CBZ.MOKURO")

        MokuroChapterLoader.selectSidecar("chapter 1.cbz", present) shouldBe "CHAPTER 1.CBZ.MOKURO"
    }

    @Test
    fun `returns null when no sidecar is present`() {
        MokuroChapterLoader.selectSidecar("Chapter 1", emptyList()).shouldBeNull()
        MokuroChapterLoader.selectSidecar("Chapter 1", listOf("cover.jpg")).shouldBeNull()
    }
}
