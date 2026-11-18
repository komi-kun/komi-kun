// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// **Source-level guard on the webtoon OCR adapter's *inputs* — not a behavioural test.**
//
// `WebtoonOcrProjectionTest` proves the arithmetic; what no JVM test in this module can prove is that
// the adapter feeds it the right live numbers, because that needs real `View`s under a real zoom
// transform. So this reads the call site instead, the way `ReaderOcrOverlayWiringTest` guards
// `ReaderActivity`'s overlay composition and `OcrPolicySourceWiringTest` guards the other OCR sites.
//
// Three specific regressions are pinned, all of which leave the pure math passing:
//  - the extent drawn from the strip's *layout* size with no zoom factor (the reported bug: boxes of
//    constant size that drift up/left as the zoom grows),
//  - `strip.scaleX` used as that factor — the trap in this reader, because the zoom is a layer
//    transform on the recycler (`WebtoonRecyclerView.setScaleRate`), so the strip's own scale is
//    always 1 and the bug would come back silently,
//  - a hit-test that subtracts the offset but not the scale (taps drift left/up), and a transform
//    token that ignores the zoom (no redraw during a pinch).
//
// Residual gap: source text proves what the adapter reads in the file, not that the running views
// return those values - a device gesture is still the only end-to-end proof.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File

/**
 * Comments are stripped so a needle can only be satisfied (or broken) by code, and so a comment that
 * mentions the old shape cannot keep a test green.
 */
private fun viewportSource(): String = File(VIEWPORT_PATH)
    .readText()
    .replace(Regex("""/\*[\s\S]*?\*/"""), "")
    .replace(Regex("""(?m)//[^\n]*$"""), "")

private const val VIEWPORT_PATH =
    "src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonOcrViewport.kt"

private fun requireNeedle(source: String, needle: String) {
    if (!source.contains(needle)) fail("missing in $VIEWPORT_PATH: $needle")
}

/** The substring of [source] from [from] up to its balanced [close] character. */
private fun block(source: String, from: String, open: Char = '(', close: Char = ')'): String {
    val start = source.indexOf(from)
    if (start < 0) fail("missing in $VIEWPORT_PATH: $from")
    var depth = 0
    var i = source.indexOf(open, start)
    if (i < 0) fail("missing `$open` after $from in $VIEWPORT_PATH")
    val fromIndex = i
    while (i < source.length) {
        when (source[i]) {
            open -> depth++
            close -> {
                depth--
                if (depth == 0) return source.substring(fromIndex, i + 1)
            }
        }
        i++
    }
    fail("unbalanced `$open` after $from in $VIEWPORT_PATH")
}

class WebtoonOcrViewportScaleWiringTest {

    @Test
    fun `the drawn block is scaled by the ancestor zoom, not by the strip's own layout scale`() {
        val source = viewportSource()

        // The strip path projects through the shared arithmetic, with the live layout size...
        val projection = block(source, "projectImageNormalizedToScreen(")
        requireNeedle(projection, "sheetWidth = strip.width.toFloat()")
        requireNeedle(projection, "sheetHeight = strip.height.toFloat()")
        // ...and with the accumulated ancestor zoom as its factor.
        requireNeedle(projection, "scaleX = scale.x")
        requireNeedle(projection, "scaleY = scale.y")
        requireNeedle(source, "val scale = ancestorScaleOf(strip)")

        // The trap: the strip's own scaleX is always 1 here, so reading it would restore the bug
        // while every pure-math test above still passes.
        assertFalse(
            source.contains("strip.scaleX") || source.contains("strip.scaleY"),
            "the webtoon zoom lives on the recycler; the strip's own scale is always 1 " +
                "(use ancestorScaleOf(strip))",
        )
    }

    @Test
    fun `the hit-test undoes the ancestor zoom before asking the recycler for a child`() {
        val source = viewportSource()

        // `findChildViewUnder` wants the recycler's own, unzoomed coordinates: the offset and the
        // scale both have to come off first.
        val mapping = block(source, "overlayToViewLocal(")
        requireNeedle(mapping, "originX = recyclerOffset.x")
        requireNeedle(mapping, "scaleX = recyclerScale.x")
        requireNeedle(mapping, "scaleY = recyclerScale.y")
        requireNeedle(source, "findChildViewUnder(recyclerLocal.x, recyclerLocal.y)")

        // The pre-fix shape, kept out: offset only, so the tap drifts left/up as the zoom grows.
        assertFalse(
            source.contains("findChildViewUnder(x - recyclerOffset.x"),
            "the hit-test must undo the zoom as well as the offset",
        )
        assertFalse(
            source.contains("(x - off.x) / strip.width"),
            "the strip hit-test must divide by the zoomed extent, not the layout size",
        )
    }

    @Test
    fun `the transform token includes the zoom and the pan so a pinch redraws`() {
        val source = viewportSource()

        val version = block(source, "override fun transformVersion()", open = '{', close = '}')
        requireNeedle(version, "recycler.scaleX.toRawBits()")
        requireNeedle(version, "recycler.scaleY.toRawBits()")
        requireNeedle(version, "recycler.x.toRawBits()")
        requireNeedle(version, "recycler.y.toRawBits()")

        // The scroll offset alone is not the geometry: a pinch changes neither it nor the holders.
        assertTrue(
            version.contains("computeVerticalScrollOffset"),
            "the existing scroll term must stay in the token",
        )
    }
}
