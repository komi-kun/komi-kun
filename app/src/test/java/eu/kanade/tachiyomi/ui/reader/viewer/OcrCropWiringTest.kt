// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// **Source-level guard on the border-crop plumbing — not a behavioural test.**
//
// `OcrCropTransformTest` proves the arithmetic; what no JVM test in this module can prove is that the
// crop rect the arithmetic uses is the one the bitmap was cut with, because that needs real views
// (SSIV reports its cropped size only after it is ready). So this reads the call sites instead, the
// way `WebtoonOcrViewportScaleWiringTest` guards the webtoon adapter's inputs.
//
// Each regression pinned here leaves every arithmetic test green:
//  - a holder that never wraps its transform in the crop (`ocrTransform` stays `None`/`Merge`/`Split`
//    and blocks are placed on the uncropped page - the defect this change fixes),
//  - the crop derived *after* `onImageLoaded` (nothing bumps the transform version, so the overlay
//    keeps projecting the uncropped mapping until something else moves),
//  - the webtoon strip decoder's own window dropped (`StripsImage.cropWindow` no longer published,
//    which would silently fall back to the uncropped mapping for the default webtoon path),
//  - a derived rect used without the check against SSIV's `sWidth`/`sHeight`, so a recipe that
//    drifts from the pinned SSIV shifts every block instead of falling back.
//
// Residual gap: source text proves what the call sites pass, not that the running views return those
// values - a device with crop borders on is still the only end-to-end proof.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.viewer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File

/** Comments are stripped so a needle can only be satisfied (or broken) by code. */
private fun String.withoutComments(): String = replace(Regex("""/\*[\s\S]*?\*/"""), "")
    .replace(Regex("""(?m)//[^\n]*$"""), "")

private const val READER_MODULE = "src/main/java/eu/kanade/tachiyomi/ui/reader"
private const val PAGER_HOLDER = "$READER_MODULE/viewer/pager/PagerPageHolder.kt"
private const val WEBTOON_HOLDER = "$READER_MODULE/viewer/webtoon/WebtoonPageHolder.kt"
private const val WEBTOON_VIEWPORT = "$READER_MODULE/viewer/webtoon/WebtoonOcrViewport.kt"
private const val PAGE_IMAGE = "$READER_MODULE/viewer/ReaderPageImageView.kt"
private const val COIL_DECODER = "src/main/java/eu/kanade/tachiyomi/data/coil/ImageDecoder.kt"

private fun sourceOf(path: String): String {
    val file = File(path)
    if (!file.isFile) fail("missing source file: $path")
    return file.readText().withoutComments()
}

private fun String.requireNeedle(needle: String, path: String) {
    if (!contains(needle)) fail("missing in $path: $needle")
}

/** The body of [name], from its declaration to the end of its last `}` (braces counted). */
private fun bodyOf(source: String, name: String, path: String): String {
    val start = source.indexOf(name)
    if (start < 0) fail("missing in $path: $name")
    var depth = 0
    var i = source.indexOf('{', start)
    if (i < 0) fail("missing `{` after $name in $path")
    val open = i
    while (i < source.length) {
        when (source[i]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return source.substring(open, i + 1)
            }
        }
        i++
    }
    fail("unbalanced braces after $name in $path")
}

class OcrCropWiringTest {

    @Test
    fun `the paged holder derives the crop off the main thread and applies it once the image loads`() {
        val source = sourceOf(PAGER_HOLDER)

        // The rect is derived from the sheet SSIV is given, from the same setting SSIV is given, and
        // it is derived inside the background block (it decodes the sheet).
        source.requireNeedle(
            "ocrScanSheetCrop(source, isAnimated, viewer.paged.page.cropBorders)",
            PAGER_HOLDER,
        )
        val setImage = bodyOf(source, "private suspend fun setImage()", PAGER_HOLDER)
        val background = setImage.indexOf("withContext(Dispatchers.Default)")
        val scan = setImage.indexOf("ocrScanSheetCrop(")
        assertTrue(scan > background, "the crop scan must run inside the background decode block")

        // The window is resolved by `super` (which needs SSIV's reported size), then applied.
        val loaded = bodyOf(source, "override fun onImageLoaded()", PAGER_HOLDER)
        assertTrue(
            loaded.indexOf("super.onImageLoaded()") < loaded.indexOf("croppedBy(ocrCropWindow())"),
            "the crop must be applied after super resolved the window",
        )
    }

    @Test
    fun `the webtoon holder derives the crop and applies the window its decoder published`() {
        val source = sourceOf(WEBTOON_HOLDER)

        source.requireNeedle("frame.ocrScanSheetCrop(", WEBTOON_HOLDER)
        // Same expression the reader passes as `Config.cropBorders`: both long-strip modes crop on
        // their own preference, so a scan gated on one of them alone misses half the pages.
        source.requireNeedle("viewer.longStrip.cropBorders && viewer.isContinuous", WEBTOON_HOLDER)
        source.requireNeedle("viewer.longStrip.continuousCropBorders && !viewer.isContinuous", WEBTOON_HOLDER)

        val decoded = bodyOf(source, "private fun onImageDecoded()", WEBTOON_HOLDER)
        decoded.requireNeedle("croppedBy(frame.ocrCropWindow())", WEBTOON_HOLDER)
    }

    @Test
    fun `the page view resolves the derived crop only when SSIV reports the cropped size`() {
        val source = sourceOf(PAGE_IMAGE)

        val resolve = bodyOf(source, "private fun resolveOcrCropWindow()", PAGE_IMAGE)
        // The decision lives in `OcrSheetCrop.resolvedAgainst`, which
        // `OcrCropTransformTest.a mismatched crop resolves to no window...` pins behaviourally; here
        // only the live values it is fed are pinned, because SSIV is not readable off-device.
        resolve.requireNeedle("pending.resolvedAgainst(ssiv.sWidth, ssiv.sHeight)", PAGE_IMAGE)
        resolve.requireNeedle("ocrCropWindow = pending.resolvedAgainst(", PAGE_IMAGE)

        // ...and it must run on load, before the version bump that makes the overlay re-project.
        val loaded = bodyOf(source, "open fun onImageLoaded()", PAGE_IMAGE)
        assertTrue(
            loaded.indexOf("resolveOcrCropWindow()") < loaded.indexOf("bumpOcrTransformVersion()"),
            "the window must be resolved before the overlay is told the mapping moved",
        )

        // SSIV's rect is not readable, so it is derived - and only for the paths that crop with SSIV.
        val scan = bodyOf(source, "fun ocrScanSheetCrop(", PAGE_IMAGE)
        scan.requireNeedle("ssivCropScan(sheet)", PAGE_IMAGE)
        scan.requireNeedle("isWebtoon && !alwaysDecodeLongStripWithSSIV", PAGE_IMAGE)
        scan.requireNeedle("dictionaryOcrPreferences.isEnabled()", PAGE_IMAGE)
    }

    @Test
    fun `the paged holder asks SSIV to crop and derives a crop from the same setting`() {
        val source = sourceOf(PAGER_HOLDER)
        val setImage = bodyOf(source, "private suspend fun setImage()", PAGER_HOLDER)

        // Two call sites in one method, one setting, and nothing compares them: if the scan were
        // gated on another expression than the `Config` SSIV is given, the overlay would derive a
        // crop for a sheet SSIV never cropped - and every behavioural test would still pass. (The
        // margin helpers read the same preference for their own reasons, hence two explicit needles
        // rather than a count over the file.)
        setImage.requireNeedle("cropBorders = viewer.paged.page.cropBorders,", PAGER_HOLDER)
        setImage.requireNeedle(
            "ocrScanSheetCrop(source, isAnimated, viewer.paged.page.cropBorders)",
            PAGER_HOLDER,
        )
    }

    @Test
    fun `the webtoon strip decoder publishes the window it cropped with`() {
        val decoder = sourceOf(COIL_DECODER)

        // The exact rect, cut by the same numbers as the bitmaps: no derivation and no size check.
        decoder.requireNeedle("val cropWindow = OcrCropWindow.ofPixels(", COIL_DECODER)
        decoder.requireNeedle("StripsImage(strips, cropWindow)", COIL_DECODER)

        val view = sourceOf(PAGE_IMAGE)
        view.requireNeedle("(result as ImageDecoder.StripsImage).cropWindow", PAGE_IMAGE)
    }

    @Test
    fun `the crop window is cleared when a holder is recycled`() {
        val view = sourceOf(PAGE_IMAGE)
        val recycle = bodyOf(view, "fun recycle()", PAGE_IMAGE)

        // A recycled view shows another page: a stale window would place its blocks on the previous
        // page's crop.
        recycle.requireNeedle("ocrCropWindow = null", PAGE_IMAGE)
        recycle.requireNeedle("ocrPendingCrop = null", PAGE_IMAGE)
    }

    @Test
    fun `the webtoon token carries each holder's transform version`() {
        val source = sourceOf(WEBTOON_VIEWPORT)
        val version = bodyOf(source, "override fun transformVersion()", WEBTOON_VIEWPORT)

        // The webtoon crop is applied when the page's own decode finishes
        // (`WebtoonPageHolder.onImageDecoded`), while blocks can resolve before it: without this term
        // the token does not move and the overlay keeps drawing them at the pre-crop positions until
        // a scroll. `WebtoonOcrProjectionTest` pins the composition; this pins that the live value is
        // read at all.
        version.requireNeedle("holderTransformVersions = holders.map { it.ocrFrame().ocrTransformVersion() }", WEBTOON_VIEWPORT)
        version.requireNeedle("webtoonOcrTransformToken(", WEBTOON_VIEWPORT)
    }
}
