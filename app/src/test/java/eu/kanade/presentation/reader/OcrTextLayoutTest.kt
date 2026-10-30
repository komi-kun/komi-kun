// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package eu.kanade.presentation.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Geometry of the OCR overlay's tategaki (vertical Japanese) layout: upright glyphs stacked
 * top-to-bottom in right-to-left columns, sized from the block's printed ink extents, floored for
 * legibility — plus the vertical glyph/orientation policy and source-level guards on the Compose
 * wiring in `OcrOverlay.kt`.
 *
 * What these tests cannot judge (eyes on the device): how the laid-out size reads against the
 * printed text underneath, whether the plate gives enough contrast over artwork, and whether the few
 * line-like marks that still turn look right. They pin the arithmetic and the policy, not the
 * aesthetics.
 */
class OcrTextLayoutTest {

    /** Ink-to-advance ratio measured for the system CJK font on a device (printed glyphs: ~0.84). */
    private val systemFontInkRatio = 0.89f

    private fun metric(text: String, width: Float = 20f, height: Float = width) = GlyphMetric(text, width, height)

    private fun columns(vararg columnTexts: List<String>, size: Float = 20f): List<List<GlyphMetric>> =
        columnTexts.map { column -> column.map { metric(it, size) } }

    private fun layout(
        columns: List<List<GlyphMetric>>,
        rectLeft: Float = 0f,
        rectTop: Float = 0f,
        rectRight: Float = 100f,
        rectBottom: Float = 100f,
        minScale: Float = 0.1f,
        glyphScale: Float = 1f,
    ) = layoutVerticalText(
        columns = columns,
        rectLeft = rectLeft,
        rectTop = rectTop,
        rectRight = rectRight,
        rectBottom = rectBottom,
        minScale = minScale,
        glyphScale = glyphScale,
    )

    @Test
    fun `a column is stacked one printed cell per glyph, cell derived from the box height`() {
        val placed = layout(columns(listOf("あ", "い", "う")), rectBottom = 300f)

        assertEquals(listOf("あ", "い", "う"), placed.map { it.text })
        // The printed cell is 300 / (3 glyphs + 0.7 cells of detector padding) = 81.08 -- not 300/3,
        // which is what filling the box did and made every overlaid glyph too big.
        assertEquals(81.08f, placed[1].top - placed[0].top, 0.05f)
        assertEquals(81.08f, placed[2].top - placed[1].top, 0.05f)
        // The glyph's advance is drawn at 95% of that cell, so its *ink* (~0.89 of the advance) lands
        // on the printed glyph's ~0.84 em ink.
        assertEquals(81.08f * GLYPH_INK_FIT / 20f, placed[0].scale, 0.001f)
        // Upright, not rotated sideways.
        assertTrue(placed.none { it.rotated })
        // All glyphs share the column centre.
        val centers = placed.map { it.left + 20f * it.scale / 2f }
        assertEquals(centers[0], centers[1], 0.01f)
        assertEquals(centers[1], centers[2], 0.01f)
    }

    @Test
    fun `a glyph is drawn inside its cell so its ink matches the printed ink`() {
        val placed = layout(columns(listOf("あ"))).single()

        // cell = min(100 / 1.7, 100 * 1.15) = 58.8; the glyph advance is 0.95 * 58.8 = 55.9.
        assertEquals(55.9f, 20f * placed.scale, 0.1f)
    }

    @Test
    fun `a narrow block caps the cell at the column pitch so columns cannot collide`() {
        val placed = layout(columns(listOf("あ")), rectRight = 40f, rectBottom = 400f).single()

        // The height alone would allow 400 / 1.7 = 235, but one column is 40 wide: 40 * 1.15 = 46.
        assertEquals(46f, 20f * (placed.scale / GLYPH_INK_FIT), 0.1f)
    }

    @Test
    fun `the calibrated run matches a real block's printed ink extents`() {
        // A block measured on a real page: 4 columns (longest 8 glyphs) in a 111 x 187 detector box.
        // The printed lettering there is a 21.4px cell whose glyph ink is 18px wide, and the columns
        // sit 26.5px apart.
        val placed = layout(
            columns(List(8) { "あ" }, List(7) { "い" }, List(7) { "う" }, List(5) { "え" }),
            rectRight = 111f,
            rectBottom = 187f,
        )

        // Drawn glyph advance: 0.95 * (187 / 8.7) = 20.4, so its ink (~0.89 of the advance) is 18.1
        // against the printed glyph's measured 18px ink.
        assertEquals(18.1f, systemFontInkRatio * 20f * placed.first().scale, 0.4f)
        // Column pitch: 111 / 4 = 27.75 (printed: 26.5).
        val rightmost = placed.first { it.text == "あ" }
        val leftmost = placed.first { it.text == "え" }
        assertEquals(27.75f, (rightmost.left - leftmost.left) / 3f, 0.2f)
        // Nothing is pushed out of the box by the calibration.
        assertTrue(placed.all { it.top >= 0f && it.top < 187f })
    }

    @Test
    fun `a short run stays at the top of an over-large box, not in the middle`() {
        val placed = layout(columns(listOf("あ")), rectBottom = 600f).single()

        // The cell is capped by the 100px column (115); the inset is capped at half a cell, so the
        // run sits in the first cell instead of floating to the middle of the 600px box.
        assertTrue(placed.top < 115f, "run must stay in the first cell, was ${placed.top}")
        assertEquals(60.4f, placed.top, 0.1f)
    }

    @Test
    fun `columns are ordered right to left and share the block width`() {
        val placed = layout(columns(listOf("あ"), listOf("い")), rectBottom = 200f)

        val first = placed.first { it.text == "あ" }
        val second = placed.first { it.text == "い" }
        assertTrue(first.left > second.left, "the first column must be the rightmost")
        // Column centres are one column pitch (rectWidth / columns) apart, each centred in its slot.
        assertEquals(75f, first.left + 20f * first.scale / 2f, 0.001f)
        assertEquals(25f, second.left + 20f * second.scale / 2f, 0.001f)
    }

    @Test
    fun `every column is top-anchored even when shorter than the longest`() {
        val placed = layout(
            columns(listOf("あ", "い", "う"), listOf("か")),
            rectTop = 10f,
            rectRight = 200f,
            rectBottom = 610f,
        )

        // The longest column (3) defines the cell; the 1-glyph column must still start at the same
        // top edge as the rest of the run.
        val first = placed.first { it.text == "あ" }
        val short = placed.first { it.text == "か" }
        assertEquals(first.top, short.top, 0.001f)
        assertTrue(short.top < placed.first { it.text == "い" }.top)
        assertTrue(first.top < 10f + 115f, "the run must not float down the box")
    }

    @Test
    fun `the reference cell is the glyph advance, so a taller line box does not shrink the glyph`() {
        // Advance 20 (its em), line-box height 30 (font leading): the cell must still come from the
        // box height and the glyph count, and the glyph must be drawn at GLYPH_INK_FIT of it.
        val placed = layout(listOf(listOf(GlyphMetric("あ", width = 20f, height = 30f))), rectBottom = 100f)

        // Had the 30px line box been the reference, the glyph would be drawn at 58.8 * 0.95 / 30.
        assertEquals(55.9f, 20f * placed.single().scale, 0.1f)
        assertEquals(22.1f, placed.single().left, 0.1f)
    }

    @Test
    fun `the legibility floor wins and overflows the box for the clip to trim`() {
        val placed = layout(columns(listOf("あ")), rectRight = 5f, rectBottom = 200f, minScale = 0.45f)

        // The fitted glyph would be 5.75 * 0.95 / 20 = 0.27; the floor lifts it to 0.45, so the glyph
        // is wider than the box.
        assertEquals(0.45f, placed.single().scale, 0.001f)
        assertTrue(placed.single().left < 0f, "floored glyph overflows and relies on the caller's clip")
    }

    @Test
    fun `the legibility floor overflows a short box downward from the top`() {
        val placed = layout(columns(List(10) { "あ" }), rectBottom = 20f, minScale = 0.45f)

        val cell = 20f * (placed.first().scale / GLYPH_INK_FIT)
        // Top-anchored: the first cell sits at the box top and the overflow runs below it.
        assertTrue(placed.first().top < cell, "the run starts at the top of the box")
        assertTrue(placed.last().top + cell > 20f, "the column overflows below the box")
    }

    @Test
    fun `the overlay text size preference scales the glyph, keeping the printed column pitch`() {
        val base = layout(columns(listOf("あ", "い")), rectBottom = 200f)
        val bigger = layout(columns(listOf("あ", "い")), rectBottom = 200f, glyphScale = 1.5f)

        // The cell (the printed pitch) is unchanged; only the drawn glyph grows.
        assertEquals(base[1].top - base[0].top, bigger[1].top - bigger[0].top, 0.01f)
        assertEquals(1.5f, bigger[0].scale / base[0].scale, 0.001f)
    }

    @Test
    fun `punctuation occupies its own cell`() {
        val placed = layout(columns(listOf("あ", "。", "い")), rectBottom = 300f)

        assertEquals(listOf("あ", "。", "い"), placed.map { it.text })
        assertEquals(placed[0].left, placed[1].left, 0.001f)
        assertEquals(placed[1].left, placed[2].left, 0.001f)
        assertEquals(81.08f, placed[1].top - placed[0].top, 0.05f)
    }

    @Test
    fun `empty columns are ignored`() {
        val placed = layout(columns(listOf("あ"), emptyList()), rectBottom = 200f)

        assertEquals(1, placed.size)
        // The lone non-empty column is centred in the block (empty columns contribute no width).
        assertEquals(50f, placed.single().left + 20f * placed.single().scale / 2f, 0.001f)
    }

    @Test
    fun `latin and digits are set upright, one cell each, centred in their column`() {
        // The OCR engine drops the full-width/half-width distinction, so a printed ＲＰＧ comes back
        // as ASCII RPG. Both are drawn upright, one glyph per printed cell — never turned on their
        // side (the old policy rotated every printable ASCII character).
        val ascii = layout(columns(listOf("R", "P", "G")), rectRight = 100f, rectBottom = 300f)
        val fullWidth = layout(columns(listOf("Ｒ", "Ｐ", "Ｇ", "１")), rectRight = 100f, rectBottom = 400f)

        assertEquals(listOf("R", "P", "G"), ascii.map { it.text })
        assertEquals(listOf("Ｒ", "Ｐ", "Ｇ", "１"), fullWidth.map { it.text })
        assertTrue(ascii.none { it.rotated }, "ASCII latin must stay upright")
        assertTrue(fullWidth.none { it.rotated }, "full-width latin and digits must stay upright")
        // One printed cell per glyph: 300 / (3 + 0.7) = 81.08, 400 / (4 + 0.7) = 85.11.
        assertEquals(81.08f, ascii[1].top - ascii[0].top, 0.05f)
        assertEquals(85.11f, fullWidth[1].top - fullWidth[0].top, 0.05f)
        assertEquals(85.11f, fullWidth[3].top - fullWidth[2].top, 0.05f)
        // Each glyph is centred in its column slot (rectRight / 2 = 50), not pushed to an edge.
        ascii.forEach { assertEquals(50f, it.left + 20f * it.scale / 2f, 0.001f) }
        fullWidth.forEach { assertEquals(50f, it.left + 20f * it.scale / 2f, 0.001f) }
    }

    @Test
    fun `a column of mixed-width glyphs advances one cell each and centres by own width`() {
        // Realistic, non-square metrics: a CJK glyph is 20x20, a Latin capital has a narrow advance
        // in a tall line box, and a full-width glyph is square again.
        val placed = layout(
            listOf(
                listOf(
                    GlyphMetric("漢", width = 20f, height = 20f),
                    GlyphMetric("R", width = 11f, height = 23f),
                    GlyphMetric("Ｒ", width = 20f, height = 20f),
                    GlyphMetric("あ", width = 20f, height = 20f),
                ),
            ),
            rectRight = 100f,
            rectBottom = 400f,
        )

        assertEquals(listOf("漢", "R", "Ｒ", "あ"), placed.map { it.text })
        assertTrue(placed.none { it.rotated })
        // One printed cell per glyph whatever its own advance: 400 / (4 + 0.7) = 85.11.
        val cell = 400f / (4 + VERTICAL_BOX_PAD_CELLS)
        val scale = placed[0].scale
        // Every glyph is centred vertically in its own cell using its own height, so the 23px-tall
        // Latin line box sits half of (23 - 20) * scale higher than its neighbours: the step into it
        // is that much shorter and the step out of it that much longer. A block-uniform centring (the
        // reference cell's height for every glyph) would make all three steps exactly `cell`.
        assertEquals(cell, placed[3].top - placed[2].top, 0.01f)
        assertEquals(cell - 1.5f * scale, placed[1].top - placed[0].top, 0.01f)
        assertEquals(cell + 1.5f * scale, placed[2].top - placed[1].top, 0.01f)
        // A single uniform scale for the whole block: advances differ, the scale does not.
        assertEquals(1, placed.map { it.scale }.distinct().size)
        // Every glyph is centred on the column's centre line using its *own* advance. Centring by a
        // block-uniform width (the reference cell) would push R by (20 - 11) * scale / 2 off centre.
        placed.forEach { glyph ->
            val ownWidth = if (glyph.text == "R") 11f else 20f
            assertEquals(50f, glyph.left + ownWidth * glyph.scale / 2f, 0.001f, glyph.text)
        }
    }

    @Test
    fun `an ellipsis keeps its own cell upright`() {
        val placed = layout(columns(listOf("あ", "…", "い")), rectBottom = 300f)

        assertEquals(listOf("あ", "…", "い"), placed.map { it.text })
        // Upright and in its own full cell: the tategaki shape comes from `vert` or the FE fallback
        // (pinned by the mapping tests), never from turning the horizontal glyph on its side.
        assertFalse(placed[1].rotated, "… must not be rotated")
        assertEquals(81.08f, placed[1].top - placed[0].top, 0.05f)
        assertEquals(placed[0].left, placed[1].left, 0.001f)
    }

    @Test
    fun `only line-like marks rotate in a vertical run`() {
        // Deliberately no longer rotating printable ASCII: letters, digits and symbols are upright.
        assertFalse(isRotatedInVertical("A"))
        assertFalse(isRotatedInVertical("RPG"))
        assertFalse(isRotatedInVertical("1"))
        assertFalse(isRotatedInVertical("１２３"))
        assertFalse(isRotatedInVertical("ＲＰＧ"))
        // The font's `vert` feature (or the FE fallback) supplies these tategaki forms, so rotating
        // the horizontal glyph would draw the wrong shape off-centre.
        assertFalse(isRotatedInVertical("…"))
        assertFalse(isRotatedInVertical("、"))
        assertFalse(isRotatedInVertical("。"))
        assertFalse(isRotatedInVertical("「"))
        assertFalse(isRotatedInVertical("」"))
        assertFalse(isRotatedInVertical("・"))
        assertFalse(isRotatedInVertical("あ"))
        assertFalse(isRotatedInVertical("漢"))
        assertFalse(isRotatedInVertical(""))
        // Feature-only marks: `vert` is their only vertical form, so they are drawn by rotation.
        assertTrue(isRotatedInVertical("ー"), "ー has no FE form; it must rotate")
        assertTrue(isRotatedInVertical("〜"), "〜 has no FE form; it must rotate")
        assertTrue(isRotatedInVertical("～"), "～ has no FE form; it must rotate")
        assertTrue(isRotatedInVertical("｜"), "｜ has no FE form; it must rotate")
        assertTrue(isRotatedInVertical("゠"), "゠ has no FE form; it must rotate")
        // And the stroke family, which `vert` does not substitute at all.
        assertTrue(isRotatedInVertical("-"), "the ASCII hyphen is a vertical stroke in tategaki")
        assertTrue(isRotatedInVertical("‐"))
        assertTrue(isRotatedInVertical("—"))
        assertTrue(isRotatedInVertical("－"))
        assertTrue(isRotatedInVertical("〰"))
        assertTrue(isRotatedInVertical("_"))
        assertTrue(isRotatedInVertical("="))
        assertTrue(isRotatedInVertical("~"))
        assertTrue(isRotatedInVertical("|"))
    }

    @Test
    fun `a feature-only mark is rotated in its own cell`() {
        val placed = layout(columns(listOf("あ", "ー", "い")), rectBottom = 300f)

        assertEquals(listOf("あ", "ー", "い"), placed.map { it.text })
        // ー keeps a full cell and turns: its printed tategaki shape is the horizontal glyph rotated,
        // and no Unicode vertical presentation form exists for it.
        assertTrue(placed[1].rotated, "ー must be drawn rotated")
        assertEquals(81.08f, placed[1].top - placed[0].top, 0.05f)
        assertEquals(placed[0].left, placed[1].left, 0.001f)
        // The FE fallback must not invent a mapping for any of them...
        listOf("ー", "〜", "～", "｜", "゠").forEach { mark ->
            assertEquals(mark, verticalGlyphForm(mark), "$mark must not be remapped")
            assertTrue(isRotatedInVertical(mark), "$mark must rotate")
            assertTrue(mark.codePointAt(0) in FEATURE_ONLY_VERTICAL_MARKS, "$mark must be feature-only")
        }
        // ...and the set must hold exactly them, not their neighbours. `．` is the near miss: its
        // `vert` form is a translation, not a quarter turn, so it stays upright.
        assertFalse(0x2026 in FEATURE_ONLY_VERTICAL_MARKS, "… has an FE form")
        assertFalse('-'.code in FEATURE_ONLY_VERTICAL_MARKS, "the ASCII stroke has no `vert` form at all")
        assertFalse(0xFF0E in FEATURE_ONLY_VERTICAL_MARKS, "． vert form is a translation, not a turn")
        assertFalse(0x3042 in FEATURE_ONLY_VERTICAL_MARKS, "kana are upright")
        assertFalse(isRotatedInVertical("．"), "． must stay upright")
    }

    /**
     * Noto Sans CJK JP 2.004's GSUB `vert` substitutions that have a real Unicode code point, written
     * out independently of the production table: if a target in `VERTICAL_GLYPH_FORMS` is typo'd or a
     * row goes missing, the table comparison below fails rather than agreeing with itself.
     */
    private val verifiedVerticalForms = mapOf(
        0x3001 to '\uFE11', // 、 ︑
        0x3002 to '\uFE12', // 。 ︒
        0x3008 to '\uFE3F', // 〈 ︿
        0x3009 to '\uFE40', // 〉 ﹀
        0x300A to '\uFE3D', // 《 ︽
        0x300B to '\uFE3E', // 》 ︾
        0x300C to '\uFE41', // 「 ﹁
        0x300D to '\uFE42', // 」 ﹂
        0x300E to '\uFE43', // 『 ﹃
        0x300F to '\uFE44', // 』 ﹄
        0x3010 to '\uFE3B', // 【 ︻
        0x3011 to '\uFE3C', // 】 ︼
        0x3014 to '\uFE39', // 〔 ︹
        0x3015 to '\uFE3A', // 〕 ︺
        0x3016 to '\uFE17', // 〖 ︗
        0x3017 to '\uFE18', // 〗 ︘
        0x2015 to '\uFE31', // ― ︱
        0x2025 to '\uFE30', // ‥ ︰
        0x2026 to '\uFE19', // … ︙
        0xFF01 to '\uFE15', // ！ ︕
        0xFF08 to '\uFE35', // （ ︵
        0xFF09 to '\uFE36', // ） ︶
        0xFF0C to '\uFE10', // ， ︐
        0xFF1A to '\uFE13', // ： ︓
        0xFF1B to '\uFE14', // ； ︔
        0xFF1F to '\uFE16', // ？ ︖
        0xFF3B to '\uFE47', // ［ ﹇
        0xFF3D to '\uFE48', // ］ ﹈
        0xFF3F to '\uFE33', // ＿ ︳
        0xFF5B to '\uFE37', // ｛ ︷
        0xFF5D to '\uFE38', // ｝ ︸
    )

    @Test
    fun `the vertical form table is exactly the font's verified vert pairs`() {
        assertEquals(verifiedVerticalForms, VERTICAL_GLYPH_FORMS)
        assertEquals(31, VERTICAL_GLYPH_FORMS.size)
    }

    @Test
    fun `every row of the table maps through verticalGlyphForm and every target is idempotent`() {
        VERTICAL_GLYPH_FORMS.forEach { (codePoint, vertical) ->
            val source = String(Character.toChars(codePoint))
            val label = "U+%04X".format(codePoint)
            assertEquals(vertical.toString(), verticalGlyphForm(source), label)
            // A target is never a key, so the fallback cannot run twice over the same glyph.
            assertFalse(VERTICAL_GLYPH_FORMS.containsKey(vertical.code), "$label target must not be a key")
            assertEquals(vertical.toString(), verticalGlyphForm(vertical.toString()), "$label must be idempotent")
            // Nothing outside the FE ranges is a target (a Latin letter would mean a bad row).
            assertTrue(vertical.code in 0xFE10..0xFE4F, "$label target U+%04X is not an FE vertical form".format(vertical.code))
        }
    }

    @Test
    fun `characters outside the table pass through unchanged`() {
        // Kana, kanji, ASCII and full-width Latin/digits, the feature-only marks, punctuation the
        // font has no `vert` pair for, and a surrogate pair (emoji) — none of them is remapped.
        listOf(
            "あ", "漢", "R", "Ｒ", "RPG", "1", "１",
            "ー", "〜", "～", "｜", "゠", "．", "・",
            "\uD83D\uDE00",
        ).forEach { glyph ->
            assertFalse(VERTICAL_GLYPH_FORMS.containsKey(glyph.codePointAt(0)), "$glyph must not be a key")
            assertEquals(glyph, verticalGlyphForm(glyph), "unmapped glyph must pass through")
        }
        assertEquals("", verticalGlyphForm(""))
        // A printed full-width Latin run recognised as ASCII is left as recognised, per glyph.
        assertEquals("RPG", splitGlyphs("RPG").joinToString("") { verticalGlyphForm(it) })
    }

    @Test
    fun `a trailing combining mark stays with the mapped base glyph`() {
        // Only the base code point is mapped; the mark it modifies is appended unchanged.
        assertEquals("\uFE11\u0301", verticalGlyphForm("、\u0301"))
        assertEquals("か\u3099", verticalGlyphForm("か\u3099"))
    }

    @Test
    fun `a clearly horizontal single-line block stays horizontal`() {
        assertTrue(isClearlyHorizontal(vertical = true, lineCount = 1, rectWidth = 300f, rectHeight = 50f))
        assertFalse(isClearlyHorizontal(vertical = true, lineCount = 1, rectWidth = 50f, rectHeight = 300f))
        // Several columns legitimately make a vertical block wider than tall.
        assertFalse(isClearlyHorizontal(vertical = true, lineCount = 3, rectWidth = 300f, rectHeight = 50f))
        // Already horizontal blocks are untouched.
        assertFalse(isClearlyHorizontal(vertical = false, lineCount = 1, rectWidth = 300f, rectHeight = 50f))
    }

    @Test
    fun `the horizontal fit is unchanged and floors`() {
        // Shrink to fit the smaller axis (this is the pre-existing behaviour).
        assertEquals(0.5f, horizontalTextScale(100f, 100f, 200f, 50f, 0.1f), 0.0001f)
        assertEquals(0.5f, horizontalTextScale(100f, 100f, 50f, 200f, 0.1f), 0.0001f)
        // Never below the floor.
        assertEquals(0.45f, horizontalTextScale(5f, 5f, 200f, 200f, 0.45f), 0.0001f)
        // Degenerate boxes fall back to the floor, matching the old early-return guard.
        assertEquals(0.3f, horizontalTextScale(0f, 0f, 200f, 200f, 0.3f), 0.0001f)
    }

    @Test
    fun `splitGlyphs keeps surrogate pairs and combining marks whole`() {
        assertEquals(listOf("漢", "字"), splitGlyphs("漢字"))
        assertEquals(listOf("😀", "字"), splitGlyphs("😀字"))
        // U+3099 combining voiced sound mark stays attached to its base.
        assertEquals(listOf("か\u3099"), splitGlyphs("か\u3099"))
        assertEquals(emptyList<String>(), splitGlyphs(""))
    }

    @Test
    fun `whitespace keeps its cell without breaking the stacking order`() {
        val placed = layout(columns(listOf("あ", " ", "い")), rectBottom = 300f)

        assertEquals(listOf("あ", " ", "い"), placed.map { it.text })
        assertTrue(placed[0].top < placed[1].top && placed[1].top < placed[2].top)
    }

    // --- Source-level guards on the Compose wiring -------------------------------------------------
    //
    // `OcrOverlay.kt` needs a `TextMeasurer`, a `DrawScope` and a `TextStyle` applied to a real
    // Android `Paint`, so the `vert` wiring cannot be exercised from a JVM test. These guards read
    // the call site instead, the way `DictionaryOcrSetupLayoutTest` and `OcrPolicySourceWiringTest`
    // do: comments are stripped first (the KUN blocks *document* the rules, and a guard that matched
    // its own documentation would be unfixable), then whitespace-tolerant regexes — never an exact
    // multi-line string, so re-indenting or wrapping (Spotless's business) cannot fail them.
    // **Residual gap: they prove the feature string, the per-glyph style choice and the argument flow
    // are present in the source, not that the device's font actually applies `vert`** — only the
    // rendered overlay shows that.

    private fun overlaySource(): String =
        File("src/main/java/eu/kanade/presentation/reader/OcrOverlay.kt").readText().withoutComments()

    private fun sourceContains(source: String, pattern: String, message: String) {
        assertTrue(Regex(pattern, RegexOption.DOT_MATCHES_ALL).containsMatchIn(source), message)
    }

    @Test
    fun `the vertical style is the base style plus vert, wired only into the vertical path`() {
        val source = overlaySource()

        sourceContains(
            source,
            """baseTextStyle\s*\.\s*copy\(\s*fontFeatureSettings\s*=\s*"vert"\s*\)""",
            "the vertical style must be baseTextStyle.copy(fontFeatureSettings = \"vert\")",
        )
        // Exactly one `vert` in the whole file: if the horizontal style also got it, horizontal
        // punctuation would stand on its end.
        assertEquals(
            1,
            Regex("\"vert\"").findAll(source).count(),
            "\"vert\" must be set exactly once (vertical path only)",
        )
        assertFalse(
            Regex("\"vrt2\"").containsMatchIn(source),
            "use \"vert\", not \"vrt2\": vrt2 misses ！：；？",
        )
        // Both styles reach the tategaki path (the plain one is what the feature-only marks need)...
        sourceContains(
            source,
            """getOrMeasureVertical\(\s*block\s*,\s*textMeasurer\s*,\s*verticalStyle\s*,\s*baseStyle\s*\)""",
            "the vertical measurement must receive both the vert and the plain style",
        )
        sourceContains(
            source,
            """drawTategakiText\(\s*cache\s*,\s*block\s*,\s*rect\s*,\s*textMeasurer\s*,\s*verticalStyle\s*,\s*baseStyle""",
            "the vertical draw must receive both styles",
        )
        // ...and the horizontal path keeps the plain style.
        sourceContains(
            source,
            """getOrMeasureHorizontal\(\s*block\s*,\s*textMeasurer\s*,\s*baseStyle\s*\)""",
            "the horizontal measurement must keep the plain style",
        )
        sourceContains(
            source,
            """drawHorizontalOcrText\(\s*cache\s*,\s*block\s*,\s*rect\s*,\s*textMeasurer\s*,\s*baseStyle""",
            "the horizontal draw must keep the plain style",
        )
    }

    @Test
    fun `the vertical path measures each glyph's tategaki form, not the raw character`() {
        val source = overlaySource()

        // The FE fallback is computed per glyph...
        sourceContains(source, """val\s+form\s*=\s*verticalGlyphForm\(\s*glyph\s*\)""", "verticalGlyphForm(glyph) must feed the measurement")
        // ...and *that* is what gets measured: the raw character must not reach the measurer.
        assertFalse(
            Regex("""AnnotatedString\(\s*glyph\s*\)""").containsMatchIn(source),
            "the raw glyph must not be measured; its tategaki form must be",
        )
        sourceContains(
            source,
            """AnnotatedString\(\s*form\s*\)\s*,\s*style\s*=\s*style\b""",
            "the chosen style must be passed to textMeasurer.measure",
        )
        // The metric still carries the recognised character, so hit-testing/offsets stay aligned.
        sourceContains(source, """GlyphMetric\(\s*it\.text\s*,""", "GlyphMetric must keep the recognised character")
    }

    @Test
    fun `every rotated glyph is measured with the plain style under its own cache key`() {
        val source = overlaySource()

        // The style choice follows the *rotation* decision, not the feature-only subset: a rotated
        // glyph must never be shaped by `vert`, or a font that substituted one of the stroke marks
        // would hand back a substituted shape that then gets turned sideways.
        sourceContains(
            source,
            """val\s+plainStyle\s*=\s*isRotatedInVertical\(\s*glyph\s*\)""",
            "the per-glyph style choice must consult isRotatedInVertical",
        )
        sourceContains(
            source,
            """val\s+style\s*=\s*if\s*\(\s*plainStyle\s*\)\s*baseStyle\s+else\s+verticalStyle""",
            "rotated glyphs must be measured with the plain style, everything else with vert",
        )
        // The cache key must distinguish the two styles, or a `vert`-shaped ー could be served to a
        // plain-style measurement (and vice versa).
        sourceContains(
            source,
            """VerticalGlyphKey\(\s*form\s*,\s*plainStyle\s*\)""",
            "the glyph cache key must include the style kind",
        )
    }

    @Test
    fun `a rotated glyph pivots about its pre-scale local centre`() {
        // Compose expands `rotate(degrees, pivot)` as translate(p); rotate; translate(-p) on the
        // current canvas, and canvas ops pre-concatenate (M' = M · op), so the pivot is a point in
        // the glyph's *untransformed* layout coordinates and must be its own local centre. A
        // "scaled" pivot would be scaled a second time and fling the glyph out of its cell.
        val source = overlaySource()

        sourceContains(
            source,
            """rotate\(\s*90f\s*,\s*pivot\s*=\s*Offset\(\s*baseW\s*/\s*2f\s*,\s*baseH\s*/\s*2f\s*\)\s*\)""",
            "the rotation pivot must be the glyph's pre-scale local centre",
        )
        assertFalse(
            Regex("""baseW\s*\*\s*glyph\.scale""").containsMatchIn(source),
            "the pivot must not be pre-scaled: canvas ops already apply `scale`",
        )
        // ...and the rotation itself must stay conditional: rotating unconditionally would stand the
        // whole run on its side, which every layout test alone would still pass.
        sourceContains(
            source,
            """if\s*\(\s*glyph\.rotated\s*\)\s*\{\s*rotate\(\s*90f""",
            "only a glyph the layout marked rotated may be turned",
        )
    }
}

/**
 * Drops comments before asserting, matching `DictionaryOcrSetupLayoutTest`: the KUN blocks document
 * the rules they guard, and a guard that matched its own documentation could never be satisfied.
 */
private fun String.withoutComments(): String = replace(Regex("""/\*[\s\S]*?\*/"""), "")
    .replace(Regex("""//[^\n]*"""), "")
// KUN <--
