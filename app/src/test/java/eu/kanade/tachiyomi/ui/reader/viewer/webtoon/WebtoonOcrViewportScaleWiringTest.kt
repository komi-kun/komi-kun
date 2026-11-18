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
// A fourth defect was a *contract split* rather than arithmetic: `WebtoonViewer` converted its taps
// with `Activity.toOverlayPoint`, which adds the origin and no scale, i.e. `origin + local` — a third
// space. Every arithmetic test above stayed green while the tap path resolved off-block, which is why
// the behavioural `a tap on a drawn block resolves to that block at every zoom` exists, and why the
// guard here additionally checks the caller. The fit's scroll delta (overlay pixels fed to a
// layout-pixel `scrollBy`) gets the same treatment.
//
// Residual gap: source text proves what the adapter reads in the file, not that the running views
// return those values - a device gesture is still the only end-to-end proof.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File

/**
 * Comments are stripped so a needle can only be satisfied (or broken) by code, and so a comment that
 * mentions the old shape cannot keep a test green.
 */
private fun String.withoutComments(): String = replace(Regex("""/\*[\s\S]*?\*/"""), "")
    .replace(Regex("""(?m)//[^\n]*$"""), "")

private fun sourceOf(path: String): String = File(path).readText().withoutComments()

private fun viewportSource(): String = sourceOf(VIEWPORT_PATH)

private fun viewerSource(): String = sourceOf(VIEWER_PATH)

private const val VIEWPORT_PATH =
    "src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonOcrViewport.kt"

private const val VIEWER_PATH =
    "src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonViewer.kt"

private fun requireNeedle(source: String, needle: String) {
    if (!source.contains(needle)) fail("missing in $VIEWPORT_PATH: $needle")
}

/** How many times [needle] occurs in [source]. */
private fun occurrences(source: String, needle: String): Int {
    var count = 0
    var at = source.indexOf(needle)
    while (at >= 0) {
        count++
        at = source.indexOf(needle, at + needle.length)
    }
    return count
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

        // The origin is as load-bearing as the zoom: the strip's own position in the overlay, not the
        // recycler's, or every page after the first is drawn shifted by the difference.
        requireNeedle(projection, "originX = off.x")
        requireNeedle(projection, "originY = off.y")
        requireNeedle(source, "val off = OcrOverlayCoords.offsetOf(strip, overlayView)")
        assertFalse(
            projection.contains("recyclerOffset"),
            "the drawn block's origin is the strip's, not the recycler's",
        )

        // The trap: the strip's own scaleX is always 1 here, so reading it would restore the bug
        // while every pure-math test above still passes.
        assertFalse(
            source.contains("strip.scaleX") || source.contains("strip.scaleY"),
            "the webtoon zoom lives on the recycler; the strip's own scale is always 1 " +
                "(use ancestorScaleOf(strip))",
        )
    }

    @Test
    fun `the escape hatch's SSIV branch is scaled and offset like the strip branch`() {
        val source = viewportSource()
        val branch = block(source, "ocrSubsamplingImageView()?.let { ssiv ->", open = '{', close = '}')

        // `alwaysDecodeLongStripWithSSIV` draws the strip through SSIV, whose `sourceToViewCoord`
        // corners are layout coordinates exactly like the strip's - so the same origin and ancestor
        // zoom have to be applied, through the shared arithmetic (`overlayRectOfViewRect`, covered
        // arithmetically by `WebtoonOcrViewportBodyTest`). Deleting either factor from this branch
        // used to restore the bug for that configuration with every strip test green.
        requireNeedle(branch, "overlayRectOfViewRect(")
        requireNeedle(branch, "originX = off.x")
        requireNeedle(branch, "originY = off.y")
        requireNeedle(branch, "scaleX = scale.x")
        requireNeedle(branch, "scaleY = scale.y")
        requireNeedle(source, "val scale = ancestorScaleOf(ssiv)")
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
        // The token itself is composed by the pure `webtoonOcrTransformToken`, which
        // `WebtoonOcrProjectionTest` pins term by term; what this guard owns is that the *live* view
        // state reaches it, from the recycler the zoom lives on.
        requireNeedle(version, "webtoonOcrTransformToken(")
        requireNeedle(version, "scrollOffset = recycler.computeVerticalScrollOffset()")
        requireNeedle(version, "holderIds = holders.map { it.hashCode() }")
        requireNeedle(version, "holderTransformVersions = holders.map { it.ocrFrame().ocrTransformVersion() }")
        requireNeedle(version, "scaleX = recycler.scaleX")
        requireNeedle(version, "scaleY = recycler.scaleY")
        requireNeedle(version, "translationX = recycler.x")
        requireNeedle(version, "translationY = recycler.y")

        // The trap: the zoom lives on the recycler, so reading the strip's own scale would put a
        // constant 1 in the token and a pinch would never redraw.
        assertFalse(version.contains("strip.scaleX") || version.contains("strip.scaleY"))
    }

    @Test
    fun `the viewer converts taps through the viewport, not through the unscaled overlay conversion`() {
        // `event.x`/`event.y` are the recycler's own, unzoomed coordinates. `Activity.toOverlayPoint`
        // adds the origin and nothing else, so handing a tap through it put the caller in a third
        // space - `origin + local` - that the viewport's inverse then over-divided by the zoom.
        // `WebtoonOcrViewportScaleWiringTest` cannot see that split from this file, which is why the
        // behavioural `a tap on a drawn block resolves to that block at every zoom` exists as well.
        val viewer = viewerSource()
        assertFalse(
            viewer.contains("toOverlayPoint(recycler"),
            "the webtoon tap path must convert through the viewport's own origin + zoom",
        )
        assertEquals(
            2,
            occurrences(viewer, "ocrViewportRef.overlayPointOfRecyclerLocal(event.x, event.y)"),
            "both the single tap and the long press must convert their point" +
                " (found in WebtoonViewer.kt)",
        )
    }

    @Test
    fun `the fit scrolls by layout pixels, not by the overlay delta`() {
        val source = viewportSource()

        // `scrollBy`/`computeVerticalScrollOffset` count layout pixels, which the zoom leaves alone;
        // scrolling by the overlay delta overshoots by the zoom factor.
        val fit = block(source, "override fun fitBlockAbove(", open = '{', close = '}')
        requireNeedle(fit, "overlayDistanceToScroll(")
        requireNeedle(fit, "scrollScale = ancestorScaleOf(recycler).y")
        assertFalse(
            fit.contains("(rect.centerY() - target.targetCenterY).roundToInt()"),
            "the fit must divide its overlay delta by the zoom before scrolling",
        )
    }
}
