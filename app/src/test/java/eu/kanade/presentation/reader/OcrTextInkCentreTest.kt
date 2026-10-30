// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package eu.kanade.presentation.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.min

/**
 * Ink centring of the four marks a font's tategaki form does not centre by itself — `！` `？` `!` `?` —
 * in the OCR overlay's vertical layout ([layoutVerticalText] + [VERTICAL_INK_CENTRE_EM]).
 *
 * The bug these pin: the layout centres a glyph's **advance** box (and its line box) in the cell, but
 * `vert` moves the mark of `！` (`U+FF01` → `︕` `U+FE15`) to 0.724 em of a 1 em advance, so the mark
 * is drawn **0.224 em to the right of its cell centre** (`？`/`︖`: 0.221 em). These tests assert the
 * drawn *ink* centre lands on the cell centre instead, on both axes, with the numbers stated.
 *
 * Everything here is synthetic at the reference size (`BASE_TEXT_SP` = 20sp → a 20px em), the way
 * `OcrOverlay.getOrMeasureVertical` measures: `width`/`height` are the glyph's advance and line box and
 * the ink centre comes from the production table via [withTategakiInkCentre]. What a JVM test cannot
 * see: that the device font really is the font the table was measured from (Noto Sans CJK JP 2.004 /
 * Roboto), and that Android does not rescale the CJK fallback away from 1 em per font size.
 */
class OcrTextInkCentreTest {

    /** The reference em: every glyph is measured at 20sp, i.e. 20px here. */
    private val emPx = 20f

    /** A glyph measured the way the overlay measures it, ink centre included. */
    private fun mark(text: String, width: Float, height: Float, baseline: Float): GlyphMetric =
        GlyphMetric(text, width, height).withTategakiInkCentre(verticalGlyphForm(text), baseline, emPx)

    /** A CJK glyph: 1 em square advance, a 1 em typo line box, baseline at 0.88 em (Noto CJK). */
    private fun cjk(text: String): GlyphMetric = mark(text, emPx, emPx, 0.88f * emPx)

    /** A Latin mark: its own narrow advance, a padded line box, baseline at Roboto's 0.9277 em. */
    private fun latin(text: String, advanceEm: Float, lineBoxPx: Float): GlyphMetric =
        mark(text, advanceEm * emPx, lineBoxPx, 0.9277f * emPx)

    /** Cell pitch, uniform scale and top inset exactly as [layoutVerticalText] documents them. */
    private data class Geometry(
        val cell: Float,
        val scale: Float,
        val topInset: Float,
        val columnCenterX: Float,
    ) {
        fun cellCentreY(index: Int) = topInset + cell * index + cell / 2f
    }

    private fun geometry(
        columns: List<List<GlyphMetric>>,
        rectRight: Float,
        rectBottom: Float,
        glyphScale: Float = 1f,
        minScale: Float = 0.1f,
    ): Geometry {
        val longest = columns.maxOf { it.size }
        val refCell = columns.maxOf { column -> column.maxOf { it.width } }.takeIf { it > 0f }
            ?: columns.maxOf { column -> column.maxOf { it.height } }
        var cell = min(
            rectBottom / (longest + VERTICAL_BOX_PAD_CELLS),
            (rectRight / columns.size) * COLUMN_PITCH_HEAD_ROOM,
        )
        var scale = cell * GLYPH_INK_FIT * glyphScale / refCell
        if (scale < minScale) {
            // The legibility floor, exactly as layoutVerticalText applies it: the glyph is drawn at
            // the floor and the cell follows it (else the stack collapses into itself).
            scale = minScale
            cell = refCell * scale / (GLYPH_INK_FIT * glyphScale)
        }
        val inkExtent = cell * (longest - 1) + refCell * scale
        val topInset = ((rectBottom - inkExtent) / 2f).coerceIn(0f, cell * MAX_TOP_INSET_CELLS)
        return Geometry(cell, scale, topInset, rectRight - rectRight / columns.size / 2f)
    }

    private fun layout(
        columns: List<List<GlyphMetric>>,
        rectRight: Float = 100f,
        rectBottom: Float = 100f,
        glyphScale: Float = 1f,
        minScale: Float = 0.1f,
    ): List<PlacedGlyph> = layoutVerticalText(
        columns,
        0f,
        0f,
        rectRight,
        rectBottom,
        minScale = minScale,
        glyphScale = glyphScale,
    )

    /** The drawn ink centre, from the placement and the metric's ink, against the cell centre. */
    private fun assertInkOnCellCentre(
        placed: PlacedGlyph,
        metric: GlyphMetric,
        columnCenterX: Float,
        cellCentreY: Float,
        label: String,
    ) {
        val inkX = checkNotNull(metric.inkCentreX) { "$label must carry an ink centre" }
        val inkY = checkNotNull(metric.inkCentreY) { "$label must carry an ink centre" }
        assertEquals(columnCenterX, placed.left + inkX * placed.scale, 0.01f, "$label drawn ink centre x")
        assertEquals(cellCentreY, placed.top + inkY * placed.scale, 0.01f, "$label drawn ink centre y")
    }

    @Test
    fun `the vertical exclamation and question forms sit 0_224 and 0_221 em right of the advance centre`() {
        val exclamation = checkNotNull(verticalInkCentre(verticalGlyphForm("！")))
        val question = checkNotNull(verticalInkCentre(verticalGlyphForm("？")))

        // ！ vert→︕ U+FE15: ink x 660..788 of a 1000-unit em → 0.724 em, against an advance centre of
        // 0.5 em (advance 1000). y 69..822 → 0.4455 em above the baseline.
        assertEquals(0.724f, exclamation.xEm, 0.0005f)
        // The mark sits 0.224 em right of the advance centre, so the correction is dx = -0.224 em.
        assertEquals(0.224f, exclamation.xEm - 0.5f, 0.001f, "dx = -0.224 em")
        assertEquals(0.4455f, exclamation.yEm, 0.0005f)
        // ？ vert→︖ U+FE16: ink x 479..963 → 0.721 em, y 69..835 → 0.452 em.
        assertEquals(0.721f, question.xEm, 0.0005f)
        assertEquals(0.221f, question.xEm - 0.5f, 0.001f, "dx = -0.221 em")
        assertEquals(0.452f, question.yEm, 0.0005f)

        // The ASCII pair is keyed by itself (no vert substitution); both rows are table constants
        // tabulated from Roboto 3.016, not measured on the device (see VERTICAL_INK_CENTRE_EM).
        val asciiBang = checkNotNull(verticalInkCentre("!"))
        val asciiQuestion = checkNotNull(verticalInkCentre("?"))
        assertEquals(0.132f, asciiBang.xEm, 0.0005f)
        assertEquals(0.3525f, asciiBang.yEm, 0.0005f)
        assertEquals(0.235f, asciiQuestion.xEm, 0.0005f)
        assertEquals(0.3574f, asciiQuestion.yEm, 0.0005f)
        // An FE form is looked up as itself, so the table is reachable however the glyph was written.
        assertEquals(exclamation, verticalInkCentre("\uFE15"))

        // Exactly these four: 、。「」・, kana and kanji must keep the advance/line-box centring, and a
        // row for any of them would shift a glyph that is already printed centred.
        assertEquals(setOf(0xFE15, 0xFE16, 0x0021, 0x003F), VERTICAL_INK_CENTRE_EM.keys)
        assertNull(verticalInkCentre("。"), "。 is centred by its vertical form; it must not shift")
        assertNull(verticalInkCentre("、"))
        assertNull(verticalInkCentre("「"))
        assertNull(verticalInkCentre("・"))
        assertNull(verticalInkCentre("あ"))
        assertNull(verticalInkCentre("…"))
        assertNull(verticalInkCentre(""), "an empty metric has no ink")
    }

    @Test
    fun `a lone full-width exclamation mark is shifted 0_224 em left so its ink sits on the cell centre`() {
        val glyph = mark("！", emPx, emPx, 0.88f * emPx)
        val placed = layout(listOf(listOf(glyph))).single()

        // cell = 100 / (1 + 0.7) = 58.82 → scale = 58.82 * 0.95 / 20 = 2.794.
        // Advance box 55.88 wide: box centring would put left at 50 - 27.94 = 22.06, but the ink
        // centre is 4.48 ref px right of the advance centre, so the fix moves the box
        // 4.48 * 2.794 = 12.52 px left (dx = -0.224 em * 20 * 2.794).
        assertEquals(9.54f, placed.left, 0.01f)
        // Vertically the ink centre is 8.69 px below the box top against a 10 px box centre, so the
        // box moves 1.31 * 2.794 = 3.66 px down: 22.06 + 1.47 + 3.66.
        assertEquals(27.19f, placed.top, 0.01f)
        assertFalse(placed.rotated, "！ is drawn upright")

        // The invariant the numbers above exist for: the drawn ink is on the cell centre.
        val geometry = geometry(listOf(listOf(glyph)), 100f, 100f)
        val inkX = checkNotNull(glyph.inkCentreX)
        val inkY = checkNotNull(glyph.inkCentreY)
        assertEquals(14.48f, inkX, 0.001f) // 0.724 em
        assertEquals(8.69f, inkY, 0.001f) // 17.6 - 0.4455 * 20
        assertEquals(50f, placed.left + inkX * placed.scale, 0.01f)
        assertEquals(geometry.topInset + geometry.cell / 2f, placed.top + inkY * placed.scale, 0.01f)
        // ...and the box the cell actually sees moved by exactly the 0.224 em correction.
        assertEquals(-0.224f * emPx * geometry.scale, placed.left - (50f - emPx * geometry.scale / 2f), 0.01f)
    }

    @Test
    fun `a run scaled up by glyphScale 1_25 keeps each mark's ink on the cell centre`() {
        // `glyphScale` is the user's overlay text size (`OcrOverlay` passes `textScale`) and it is
        // already part of `scale`: the ink offset must be multiplied by that same `scale` exactly
        // **once**. An offset applied on top of an already-scaled one — `-(ink - w/2) * scale * scale`
        // — would double it (！ would land ~15.6 px further left, at -16.22 instead of -0.57) and
        // swing the mark out of its cell at any scale != 1.
        //
        // The arithmetic, for both marks, is the lone-column case of the first test times `scale`:
        // cell = 100 / 1.7 = 58.8235 → scale = 58.8235 * 0.95 * 1.25 / 20 = 3.49265, so the box is
        // 69.8529 wide (half-width 34.9265) and topInset = (100 - 20 * 3.49265) / 2 = 15.0735.
        // The ink sits 0.224 / 0.221 em right of the advance centre, so dx = -15.647 / -15.4375, and
        // 1.31 / 1.44 px below the box centre in reference px, so dy = +4.575 / +5.029.
        val expected = listOf(
            Triple("！", 0.724f, -0.574f to 14.134f),
            Triple("？", 0.721f, -0.362f to 14.588f),
        )
        for ((text, inkXEm, leftAndTop) in expected) {
            val (expectedLeft, expectedTop) = leftAndTop
            val glyph = mark(text, emPx, emPx, 0.88f * emPx)
            val columns = listOf(listOf(glyph))
            val placed = layout(columns, glyphScale = 1.25f).single()
            val geometry = geometry(columns, 100f, 100f, glyphScale = 1.25f)

            assertEquals(3.4926f, placed.scale, 0.0005f, "$text scale carries glyphScale once")
            assertEquals(expectedLeft, placed.left, 0.01f, "$text left at glyphScale 1.25")
            assertEquals(expectedTop, placed.top, 0.01f, "$text top at glyphScale 1.25")
            assertInkOnCellCentre(placed, glyph, geometry.columnCenterX, geometry.cellCentreY(0), text)
            // The pin for "once, not twice": the drawn box moved from its advance-centred position
            // by exactly one `scale`-times-em correction, with `scale` read back from the placement.
            assertEquals(
                -(inkXEm - 0.5f) * emPx * placed.scale,
                placed.left - (50f - emPx * placed.scale / 2f),
                0.01f,
                "$text single-scaled dx",
            )
        }
    }

    @Test
    fun `the legibility floor still centres the ink after it recomputes the cell`() {
        // A block too small for the computed cell, at the production floor (`MIN_TEXT_SP / BASE_TEXT_SP`
        // = 9 / 20 = 0.45): cell = 30 / (3 + 0.7) = 8.1081 → scale = 8.1081 * 0.95 / 20 = 0.3851,
        // below the floor, so `scale` becomes 0.45 and the cell follows the glyph —
        // 20 * 0.45 / 0.95 = 9.4737. The ink must be centred against that *recomputed* cell, not the
        // fitted one the floor threw away.
        val kanji = cjk("漢")
        val kana = cjk("あ")
        val bang = mark("！", emPx, emPx, 0.88f * emPx)
        val columns = listOf(listOf(kanji, kana, bang))
        val placed = layout(columns, rectRight = 40f, rectBottom = 30f, minScale = 0.45f)
        val geometry = geometry(columns, 40f, 30f, minScale = 0.45f)

        // The floor, not the fit, decided the scale; and the cell is the floor's own recomputation
        // (9.4737), which the pitch between two identically-shaped kana/kanji proves directly.
        assertEquals(0.45f, placed[2].scale, 0.0001f, "the floor wins over the fitted 0.3851")
        assertEquals(9.4737f, placed[1].top - placed[0].top, 0.001f, "cell follows the floored glyph")
        // Ink extent = 2 * 9.4737 + 20 * 0.45 = 27.9474 → topInset = (30 - 27.9474) / 2 = 1.0263.
        // Boxes are 9 px square on a 9.4737 cell; the mark's ink dx is -4.48 * 0.45 = -2.016 and its
        // ink dy 1.31 * 0.45 = 0.5895.
        assertEquals(15.5f, placed[0].left, 0.01f, "the kanji keeps box centring")
        assertEquals(1.263f, placed[0].top, 0.01f)
        assertEquals(10.737f, placed[1].top, 0.01f)
        assertEquals(13.484f, placed[2].left, 0.01f)
        assertEquals(20.800f, placed[2].top, 0.01f)
        assertInkOnCellCentre(placed[2], bang, geometry.columnCenterX, geometry.cellCentreY(2), "！")
    }

    @Test
    fun `a full-width exclamation mark beside a kanji keeps its ink on the cell centre`() {
        val kanji = cjk("漢")
        val glyph = mark("！", emPx, emPx, 0.88f * emPx)
        val placed = layout(listOf(listOf(kanji, glyph)), rectBottom = 300f)

        // cell = 300 / 2.7 = 111.11 → scale = 5.278; column centre x = 50, cell 1 centre y = 208.33.
        // Box centring would draw ！ at left = -2.78 / top = 155.56; the ink correction moves it to
        // left = -26.42 / top = 162.47.
        assertEquals(-2.78f, placed[0].left, 0.01f, "the kanji is untouched by the ink correction")
        assertEquals(44.44f, placed[0].top, 0.01f)
        assertEquals(-26.42f, placed[1].left, 0.01f)
        assertEquals(162.47f, placed[1].top, 0.01f)
        assertInkOnCellCentre(placed[1], glyph, 50f, 208.33f, "！")
    }

    @Test
    fun `! ? ！ ？ are ink centred in a lone column and beside a kanji`() {
        val marks = listOf(
            mark("！", emPx, emPx, 0.88f * emPx),
            mark("？", emPx, emPx, 0.88f * emPx),
            latin("!", advanceEm = 0.2578f, lineBoxPx = 26f),
            latin("?", advanceEm = 0.4727f, lineBoxPx = 26f),
        )
        for (glyph in marks) {
            // A lone column: the mark's own advance is the reference cell.
            val lone = layout(listOf(listOf(glyph)))
            val loneGeometry = geometry(listOf(listOf(glyph)), 100f, 100f)
            assertInkOnCellCentre(
                lone.single(),
                glyph,
                loneGeometry.columnCenterX,
                loneGeometry.cellCentreY(0),
                "${glyph.text} alone",
            )

            // Beside a kanji: the block's reference cell is the 1 em CJK advance instead.
            val mixedColumns = listOf(listOf(cjk("漢"), glyph))
            val mixed = layout(mixedColumns, rectBottom = 300f)
            val mixedGeometry = geometry(mixedColumns, 100f, 300f)
            assertInkOnCellCentre(
                mixed[1],
                glyph,
                mixedGeometry.columnCenterX,
                mixedGeometry.cellCentreY(1),
                "${glyph.text} beside 漢",
            )
        }
    }

    @Test
    fun `a Latin mark lands on the cell centre whatever line box the device reports`() {
        // The ASCII pair is advance-centred horizontally, so only the line box can move its ink: the
        // tight Roboto box (23px) leaves it ~0.2px high, a padded one (26px) ~1.5px low. Both must end
        // on the cell centre — the correction is measured, never assumed from the box.
        val tight = latin("!", advanceEm = 0.2578f, lineBoxPx = 23f)
        val padded = latin("!", advanceEm = 0.2578f, lineBoxPx = 26f)
        assertEquals(
            checkNotNull(tight.inkCentreY),
            checkNotNull(padded.inkCentreY),
            0.0001f,
            "the ink is a property of the font, not the box",
        )

        for (glyph in listOf(tight, padded)) {
            val columns = listOf(listOf(cjk("漢"), glyph))
            val placed = layout(columns, rectBottom = 300f)[1]
            val geometry = geometry(columns, 100f, 300f)
            assertInkOnCellCentre(
                placed,
                glyph,
                geometry.columnCenterX,
                geometry.cellCentreY(1),
                "! at ${glyph.height}px",
            )
        }
    }

    @Test
    fun `a run of ! and ? in one column is centred mark by mark`() {
        // `!?` and `？！` are corrected per code point (splitGlyphs), never as a ligature.
        assertEquals(listOf("!", "?"), splitGlyphs("!?"))
        assertEquals(listOf("？", "！"), splitGlyphs("？！"))

        val bang = mark("！", emPx, emPx, 0.88f * emPx)
        val question = mark("？", emPx, emPx, 0.88f * emPx)
        val columns = listOf(listOf(bang, question))
        val placed = layout(columns, rectBottom = 200f)
        val geometry = geometry(columns, 100f, 200f)

        assertEquals(listOf("！", "？"), placed.map { it.text })
        assertInkOnCellCentre(placed[0], bang, geometry.columnCenterX, geometry.cellCentreY(0), "！")
        assertInkOnCellCentre(placed[1], question, geometry.columnCenterX, geometry.cellCentreY(1), "？")
        // Each correction is its own: the two marks' ink centres are exactly one printed cell apart.
        val ink0 = placed[0].top + checkNotNull(bang.inkCentreY) * placed[0].scale
        val ink1 = placed[1].top + checkNotNull(question.inkCentreY) * placed[1].scale
        assertEquals(geometry.cell, ink1 - ink0, 0.01f, "the two marks sit one printed cell apart")
    }

    @Test
    fun `every other glyph keeps the advance and line-box centring it always had`() {
        // The pre-fix placement, unchanged: a lone あ in a 100px box is 50 - 20 * 2.794 / 2 across and
        // topInset + (cell - 55.88) / 2 down — the same numbers as before the ink rule existed.
        val lone = layout(listOf(listOf(cjk("あ")))).single()
        assertEquals(22.06f, lone.left, 0.01f, "あ")
        assertEquals(23.53f, lone.top, 0.01f, "あ")

        val placed = layout(listOf(listOf(cjk("あ"), cjk("。"), cjk("ー"))))

        assertEquals(listOf("あ", "。", "ー"), placed.map { it.text })
        // All three share the column centre and one cell of pitch: no per-glyph ink correction.
        placed.forEach { assertEquals(placed[0].left, it.left, 0.001f) }
        assertEquals(27.03f, placed[1].top - placed[0].top, 0.01f)
        assertTrue(placed[2].rotated, "ー still rotates")
        // None of them carries an ink centre, so the correction is exactly zero for all of them.
        listOf(cjk("あ"), cjk("。"), cjk("ー")).forEach { assertNull(it.inkCentreX) }
    }

    @Test
    fun `the ink correction is skipped when the em or the baseline is unusable`() {
        val plain = GlyphMetric("！", emPx, emPx)
        // An em-sized or unresolved font size reads back as 0 (see OcrOverlay.referenceEmPx), and a
        // layout with no measurable baseline gives NaN: both must leave the metric alone rather than
        // hand the layout a NaN offset.
        assertSame(plain, plain.withTategakiInkCentre("︕", 17.6f, 0f))
        assertSame(plain, plain.withTategakiInkCentre("︕", 17.6f, Float.NaN))
        assertSame(plain, plain.withTategakiInkCentre("︕", Float.NaN, emPx))
        // A glyph outside the table is returned as-is, ink or no ink.
        assertSame(plain, plain.withTategakiInkCentre("あ", 17.6f, emPx))
        // ...and a usable one is decorated.
        val inked = plain.withTategakiInkCentre("︕", 17.6f, emPx)
        assertEquals(0.724f * emPx, checkNotNull(inked.inkCentreX), 0.0005f)
        assertEquals(17.6f - 0.4455f * emPx, checkNotNull(inked.inkCentreY), 0.0005f)
    }

    @Test
    fun `the horizontal path is unaffected`() {
        // The horizontal path draws one joined layout centred on the rect (OcrOverlay.kt) and never
        // builds a GlyphMetric, so this file's ink fields cannot reach it: its fit is unchanged.
        assertEquals(0.5f, horizontalTextScale(100f, 100f, 200f, 50f, 0.1f), 0.0001f)
        assertEquals(0.45f, horizontalTextScale(5f, 5f, 200f, 200f, 0.45f), 0.0001f)
        assertNotNull(horizontalTextScale(100f, 100f, 10f, 10f, 0.1f))
    }
}
// KUN <--
