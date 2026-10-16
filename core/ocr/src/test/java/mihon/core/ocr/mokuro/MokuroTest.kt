// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Unit tests for `.mokuro` parse/resolve/convert (contract §5, 03 §3.6).
// Ported from Chimahon (GPL-3.0-or-later) @ f85c063.
// KUN <--
package mihon.core.ocr.mokuro

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MokuroTest {

    private val validJson = """
        {
          "version": "1.0",
          "title": "My Title",
          "title_uuid": "title-1",
          "volume": "1",
          "volume_uuid": "vol-1",
          "extra_ignored": true,
          "pages": [
            {
              "img_path": "My Title/1/001.jpg",
              "img_width": 1000,
              "img_height": 2000,
              "blocks": [
                {
                  "box": [100, 200, 300, 400],
                  "vertical": true,
                  "lines": ["あ", "い"],
                  "lines_coords": [[[100, 200], [300, 200], [300, 400], [100, 400]]]
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses a volume and ignores unknown keys`() {
        val volume = parseMokuro(validJson)

        volume.shouldNotBeNull()
        volume.title shouldBe "My Title"
        volume.titleUuid shouldBe "title-1"
        volume.pages shouldHaveSize 1
        volume.pages.first().imgWidth shouldBe 1000
    }

    @Test
    fun `returns null for malformed json`() {
        parseMokuro("{ not json").shouldBeNull()
    }

    @Test
    fun `returns null when a required field is missing`() {
        parseMokuro("""{"version":"1.0","title":"t","pages":[]}""").shouldBeNull()
    }

    @Test
    fun `resolves a page by path suffix`() {
        val volume = parseMokuro(validJson)!!
        val files = listOf(ImageFileInfo(name = "001.jpg", relativePath = "x/001.jpg", basename = "001"))

        val page = resolveMokuroPage(volume, files, 0)

        page?.imgPath shouldBe "My Title/1/001.jpg"
    }

    @Test
    fun `resolves a page by basename when the path differs`() {
        val volume = parseMokuro(validJson)!!
        val files = listOf(ImageFileInfo(name = "other.png", relativePath = "x/other.png", basename = "001"))

        val page = resolveMokuroPage(volume, files, 0)

        page?.imgPath shouldBe "My Title/1/001.jpg"
    }

    @Test
    fun `falls back to positional resolution`() {
        val volume = parseMokuro(validJson)!!
        val files = listOf(ImageFileInfo(name = "zzz.jpg", relativePath = "x/zzz.jpg", basename = "zzz"))

        val page = resolveMokuroPage(volume, files, 0)

        page?.imgPath shouldBe "My Title/1/001.jpg"
    }

    @Test
    fun `returns null when the page index is out of range`() {
        val volume = parseMokuro(validJson)!!

        resolveMokuroPage(volume, emptyList(), 3).shouldBeNull()
    }

    @Test
    fun `converts blocks to normalized text blocks`() {
        val page = parseMokuro(validJson)!!.pages.first()

        val blocks = convertMokuroBlocks(page)

        blocks shouldHaveSize 1
        val block = blocks.first()
        block.xmin shouldBe 0.1f
        block.ymin shouldBe 0.1f
        block.xmax shouldBe 0.3f
        block.ymax shouldBe 0.2f
        block.vertical shouldBe true
        block.lines shouldBe listOf("あ", "い")
        block.lineGeometries?.single()?.xmin shouldBe 0.1f
        block.lineGeometries?.single()?.ymax shouldBe 0.2f
    }

    @Test
    fun `drops degenerate blocks`() {
        val page = MokuroPage(
            imgPath = "x.jpg",
            imgWidth = 1000,
            imgHeight = 2000,
            blocks = listOf(
                MokuroBlock(box = listOf(300f, 200f, 100f, 400f), vertical = false, lines = listOf("a")),
                MokuroBlock(box = listOf(1f, 2f, 3f), vertical = false, lines = listOf("b")),
            ),
        )

        convertMokuroBlocks(page) shouldHaveSize 0
    }

    @Test
    fun `drops blocks without any non-blank line`() {
        val page = MokuroPage(
            imgPath = "x.jpg",
            imgWidth = 1000,
            imgHeight = 2000,
            blocks = listOf(
                MokuroBlock(box = listOf(100f, 200f, 300f, 400f), vertical = false, lines = listOf(" ", "")),
            ),
        )

        convertMokuroBlocks(page) shouldHaveSize 0
    }

    @Test
    fun `empty page dimensions yield no blocks`() {
        val page = MokuroPage(
            imgPath = "x.jpg",
            imgWidth = 0,
            imgHeight = 0,
            blocks = listOf(
                MokuroBlock(box = listOf(100f, 200f, 300f, 400f), vertical = false, lines = listOf("a")),
            ),
        )

        convertMokuroBlocks(page) shouldHaveSize 0
    }

    @Test
    fun `facade forwards to the top-level functions`() {
        val volume = Mokuro.parseMokuro(validJson)!!
        val files = listOf(ImageFileInfo(name = "001.jpg", relativePath = "x/001.jpg", basename = "001"))

        val page = Mokuro.resolveMokuroPage(volume, files, 0)!!

        Mokuro.convertMokuroBlocks(page) shouldHaveSize 1
    }
}
