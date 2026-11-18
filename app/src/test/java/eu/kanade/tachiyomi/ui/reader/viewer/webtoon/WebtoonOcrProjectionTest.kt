// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The webtoon OCR projection, pinned as arithmetic instead of through a device.
//
// The defect these tests pin is the pinch-zoom mismatch in the webtoon reader: the zoom is a layer
// transform on the recycler, so the strip's layout size never changes, and the overlay drew the
// blocks at their *unzoomed* extent (they stayed the same size and drifted up/left as the zoom
// grew) while the tap mapping divided by nothing at all, so draw and hit-test disagreed.
//
// The second half is the *touch* side: Android delivers a MotionEvent in the touched view's local
// space, so a tap has to be converted into overlay pixels with the same origin and zoom the boxes are
// drawn with. `a tap on a drawn block resolves to that block at every zoom` walks that path through
// these pure helpers - drawn box, finger, caller-side conversion, viewport inverse - so it pins that
// the conversions compose and disagree nowhere.
//
// It feeds the helpers directly, so it cannot see *which* values the caller passes; that half is the
// source guard's job (`WebtoonOcrViewportScaleWiringTest`), and the live zoom the tap path reads off
// the views is `WebtoonOcrViewportBodyTest`'s.
//
// What no JVM test can prove: that the adapter reads the right live numbers off the views. The
// arithmetic is exact here; `WebtoonOcrViewportScaleWiringTest` guards the call site for that part.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

class WebtoonOcrProjectionTest {

    private val sheetWidth = 1000f
    private val sheetHeight = 1000f

    /** Normalized coordinates are `0..1`, so 1e-5 is far below a device pixel. */
    private val epsilon = 1e-5f

    @Test
    fun `at scale 1 with no origin the projection is the plain layout formula`() {
        val block = OcrRect(0.25f, 0.5f, 0.75f, 1f)

        val screen = projectImageNormalizedToScreen(
            block = block,
            sheetWidth = sheetWidth,
            sheetHeight = sheetHeight,
            originX = 0f,
            originY = 0f,
            scaleX = 1f,
            scaleY = 1f,
        )

        // The pre-fix formula, term for term: `off + display * stripSize`. Delta 0f on purpose - the
        // identity case must not be perturbed by the multipliers now in the expression.
        assertEquals(0f + block.left * sheetWidth, screen.left, 0f)
        assertEquals(0f + block.top * sheetHeight, screen.top, 0f)
        assertEquals(0f + block.right * sheetWidth, screen.right, 0f)
        assertEquals(0f + block.bottom * sheetHeight, screen.bottom, 0f)
    }

    @Test
    fun `at scale 1 the origin is added to the layout extents unchanged`() {
        val screen = projectImageNormalizedToScreen(
            block = OcrRect(0.25f, 0.5f, 0.75f, 1f),
            sheetWidth = 400f,
            sheetHeight = 1000f,
            originX = 30f,
            originY = 44f,
            scaleX = 1f,
            scaleY = 1f,
        )

        assertEquals(130f, screen.left, epsilon)
        assertEquals(544f, screen.top, epsilon)
        assertEquals(330f, screen.right, epsilon)
        assertEquals(1044f, screen.bottom, epsilon)
    }

    @Test
    fun `at scale 2 both the offset from the origin and the extent double`() {
        // 0.25 of a 1000px sheet is 250px of layout offset, and the block is 150px wide/tall there.
        val block = OcrRect(0.25f, 0.25f, 0.4f, 0.4f)

        val screen = projectImageNormalizedToScreen(
            block = block,
            sheetWidth = sheetWidth,
            sheetHeight = sheetHeight,
            originX = 0f,
            originY = 0f,
            scaleX = 2f,
            scaleY = 2f,
        )

        // The block stays at twice its unzoomed distance from the origin (500px, not 250px)...
        assertEquals(500f, screen.left, epsilon)
        assertEquals(500f, screen.top, epsilon)
        // ...and its extent doubles with it: 150px of layout size becomes 300px on screen. This is
        // the reported bug - the boxes used to keep the 150px.
        assertEquals(300f, screen.right - screen.left, epsilon)
        assertEquals(300f, screen.bottom - screen.top, epsilon)
    }

    @Test
    fun `a non-square zoom is applied to each axis independently`() {
        val block = OcrRect(0.25f, 0.25f, 0.5f, 0.5f)

        val screen = projectImageNormalizedToScreen(
            block = block,
            sheetWidth = sheetWidth,
            sheetHeight = sheetHeight,
            originX = 10f,
            originY = 20f,
            scaleX = 2f,
            scaleY = 3f,
        )

        // x doubled, y tripled; neither axis borrows the other's factor.
        assertEquals(10f + 250f * 2f, screen.left, epsilon)
        assertEquals(20f + 250f * 3f, screen.top, epsilon)
        assertEquals(250f * 2f, screen.right - screen.left, epsilon)
        assertEquals(250f * 3f, screen.bottom - screen.top, epsilon)
    }

    @Test
    fun `the inverse mapping round-trips the block at every zoom and origin`() {
        val block = OcrRect(0.2f, 0.3f, 0.8f, 0.9f)
        for (scale in listOf(1f, 1.5f, 2f, 3f)) {
            for (origin in listOf(0f to 0f, 37.5f to -12.25f, -140f to 260f)) {
                val screen = projectImageNormalizedToScreen(
                    block = block,
                    sheetWidth = sheetWidth,
                    sheetHeight = sheetHeight,
                    originX = origin.first,
                    originY = origin.second,
                    scaleX = scale,
                    scaleY = scale,
                )

                val topLeft = projectScreenToImageNormalized(
                    x = screen.left,
                    y = screen.top,
                    sheetWidth = sheetWidth,
                    sheetHeight = sheetHeight,
                    originX = origin.first,
                    originY = origin.second,
                    scaleX = scale,
                    scaleY = scale,
                ) ?: fail("top-left of the drawn box fell outside the sheet at scale $scale, origin $origin")

                val bottomRight = projectScreenToImageNormalized(
                    x = screen.right,
                    y = screen.bottom,
                    sheetWidth = sheetWidth,
                    sheetHeight = sheetHeight,
                    originX = origin.first,
                    originY = origin.second,
                    scaleX = scale,
                    scaleY = scale,
                ) ?: fail("bottom-right of the drawn box fell outside the sheet at scale $scale, origin $origin")

                // A tap on a drawn box must land on that box: same corners, same place.
                assertEquals(block.left, topLeft.x, epsilon, "left at scale $scale, origin $origin")
                assertEquals(block.top, topLeft.y, epsilon, "top at scale $scale, origin $origin")
                assertEquals(block.right, bottomRight.x, epsilon, "right at scale $scale, origin $origin")
                assertEquals(block.bottom, bottomRight.y, epsilon, "bottom at scale $scale, origin $origin")
            }
        }
    }

    @Test
    fun `the inverse mapping round-trips a non-square zoom`() {
        val screen = projectImageNormalizedToScreen(
            block = OcrRect(0.2f, 0.3f, 0.8f, 0.9f),
            sheetWidth = sheetWidth,
            sheetHeight = sheetHeight,
            originX = 24f,
            originY = -8f,
            scaleX = 2f,
            scaleY = 3f,
        )

        val back = projectScreenToImageNormalized(
            x = screen.right,
            y = screen.bottom,
            sheetWidth = sheetWidth,
            sheetHeight = sheetHeight,
            originX = 24f,
            originY = -8f,
            scaleX = 2f,
            scaleY = 3f,
        ) ?: fail("the drawn corner fell outside the sheet")

        // Each axis divides by its own factor - using one factor for both would land at 0.8 / 0.6.
        assertEquals(0.8f, back.x, epsilon)
        assertEquals(0.9f, back.y, epsilon)
    }

    @Test
    fun `the sheet edges are inside the mapping and anything past them is not`() {
        val origin = 100f
        val scale = 2f

        val rightEdge = projectScreenToImageNormalized(
            x = origin + sheetWidth * scale,
            y = origin + sheetHeight * scale,
            sheetWidth = sheetWidth,
            sheetHeight = sheetHeight,
            originX = origin,
            originY = origin,
            scaleX = scale,
            scaleY = scale,
        ) ?: fail("the sheet's own corner fell outside the mapping")
        // The drawn boxes reach exactly 1.0; a tap there must still hit the page.
        assertEquals(1f, rightEdge.x, epsilon)
        assertEquals(1f, rightEdge.y, epsilon)

        // …and one pixel beyond must not, or the tap would resolve on a page it is not over.
        assertNull(
            projectScreenToImageNormalized(
                x = origin + sheetWidth * scale + 1f,
                y = origin,
                sheetWidth = sheetWidth,
                sheetHeight = sheetHeight,
                originX = origin,
                originY = origin,
                scaleX = scale,
                scaleY = scale,
            ),
        )
        assertNull(
            projectScreenToImageNormalized(
                x = origin - 1f,
                y = origin,
                sheetWidth = sheetWidth,
                sheetHeight = sheetHeight,
                originX = origin,
                originY = origin,
                scaleX = scale,
                scaleY = scale,
            ),
        )
    }

    @Test
    fun `a degenerate sheet or zoom maps nothing`() {
        assertNull(
            projectScreenToImageNormalized(
                x = 10f,
                y = 10f,
                sheetWidth = 0f,
                sheetHeight = sheetHeight,
                originX = 0f,
                originY = 0f,
                scaleX = 1f,
                scaleY = 1f,
            ),
        )
        assertNull(
            projectScreenToImageNormalized(
                x = 10f,
                y = 10f,
                sheetWidth = sheetWidth,
                sheetHeight = sheetHeight,
                originX = 0f,
                originY = 0f,
                scaleX = 0f,
                scaleY = 1f,
            ),
        )
    }

    @Test
    fun `a touch in view-local space converts back to the overlay point it came from`() {
        for (scale in listOf(1f, 1.5f, 2f, 3f)) {
            for (origin in listOf(0f to 0f, 24f to 380f, -140f to 60f)) {
                val local = OcrPoint(505.5f, 1326.25f)

                // The overlay point the view's local point is drawn at...
                val overlay = overlayPointOfViewLocal(
                    x = local.x,
                    y = local.y,
                    originX = origin.first,
                    originY = origin.second,
                    scaleX = scale,
                    scaleY = scale,
                )
                // ...and back, which is what `findChildViewUnder` and the strip fraction need.
                val back = overlayToViewLocal(
                    x = overlay.x,
                    y = overlay.y,
                    originX = origin.first,
                    originY = origin.second,
                    scaleX = scale,
                    scaleY = scale,
                )

                assertEquals(local.x, back.x, epsilon, "x at scale $scale, origin $origin")
                assertEquals(local.y, back.y, epsilon, "y at scale $scale, origin $origin")
            }
        }
    }

    @Test
    fun `a tap on a drawn block resolves to that block at every zoom`() {
        // The strip sits inside the recycler's layout, so the two views have different origins; both
        // carry the same ancestor zoom. A non-square sheet keeps x and y from cancelling each other.
        val sheetW = 1000f
        val sheetH = 2000f
        val stripOrigin = OcrPoint(40f, 380f)
        val recyclerOrigin = OcrPoint(24f, 0f)
        val block = OcrRect(0.2f, 0.3f, 0.8f, 0.9f)
        val centre = OcrPoint((block.left + block.right) / 2f, (block.top + block.bottom) / 2f)

        for (scale in listOf(1f, 1.5f, 2f, 3f)) {
            // Where the block is drawn, in overlay pixels.
            val drawn = projectImageNormalizedToScreen(
                block = block,
                sheetWidth = sheetW,
                sheetHeight = sheetH,
                originX = stripOrigin.x,
                originY = stripOrigin.y,
                scaleX = scale,
                scaleY = scale,
            )
            val drawnCentre = OcrPoint((drawn.left + drawn.right) / 2f, (drawn.top + drawn.bottom) / 2f)

            // A finger on that point: Android delivers the touch to the recycler in *its* local space,
            // i.e. the window point mapped through the inverse of the ancestors' transform. This step
            // is the platform's, not ours - `window = origin + scale * local`, inverted.
            val tapLocal = OcrPoint(
                (drawnCentre.x - recyclerOrigin.x) / scale,
                (drawnCentre.y - recyclerOrigin.y) / scale,
            )

            // The production conversion the tap path uses...
            val tapOverlay = overlayPointOfViewLocal(
                x = tapLocal.x,
                y = tapLocal.y,
                originX = recyclerOrigin.x,
                originY = recyclerOrigin.y,
                scaleX = scale,
                scaleY = scale,
            )

            // ...must land on the drawn block, i.e. agree with the draw path's own inverse. Dropping
            // the zoom from this conversion leaves the tap in a third space and resolves off-block.
            val resolved = projectScreenToImageNormalized(
                x = tapOverlay.x,
                y = tapOverlay.y,
                sheetWidth = sheetW,
                sheetHeight = sheetH,
                originX = stripOrigin.x,
                originY = stripOrigin.y,
                scaleX = scale,
                scaleY = scale,
            ) ?: fail("the tap fell outside the strip at scale $scale")
            assertEquals(centre.x, resolved.x, epsilon, "x at scale $scale")
            assertEquals(centre.y, resolved.y, epsilon, "y at scale $scale")

            // The same overlay point must also name the same recycler local point for
            // `findChildViewUnder`, or the tap resolves on a different page.
            val childLocal = overlayToViewLocal(
                x = tapOverlay.x,
                y = tapOverlay.y,
                originX = recyclerOrigin.x,
                originY = recyclerOrigin.y,
                scaleX = scale,
                scaleY = scale,
            )
            assertEquals(tapLocal.x, childLocal.x, epsilon, "recycler local x at scale $scale")
            assertEquals(tapLocal.y, childLocal.y, epsilon, "recycler local y at scale $scale")
        }
    }

    @Test
    fun `an overlay distance becomes fewer scroll pixels as the zoom grows`() {
        // `scrollBy` and `computeVerticalScrollOffset` count layout pixels, which the zoom leaves
        // alone, so 500 on-screen pixels are 500 layout pixels at 1x and 250 at 2x.
        assertEquals(500, overlayDistanceToScroll(500f, 1f))
        assertEquals(250, overlayDistanceToScroll(500f, 2f))
        // Repeating the fit's own invariant: the move in overlay pixels is what the popup asks for.
        assertEquals(500f, overlayDistanceToScroll(500f, 2f) * 2f, 0.5f)
        // Rounds rather than truncates (500 / 3 = 166.67), so a fit never under-shoots by a pixel.
        assertEquals(167, overlayDistanceToScroll(500f, 3f))
        // A degenerate scale cannot happen (the zoom is clamped to 0.5..3); it must not overflow.
        assertEquals(0, overlayDistanceToScroll(500f, 0f))
    }

    // ------------------------------------------------------------ the transform token

    private fun token(
        scrollOffset: Int = 1000,
        holderIds: List<Int> = listOf(7, 8),
        holderTransformVersions: List<Long> = listOf(3, 4),
        scaleX: Float = 2f,
        scaleY: Float = 2f,
        translationX: Float = 12f,
        translationY: Float = -8f,
    ) = webtoonOcrTransformToken(
        scrollOffset = scrollOffset,
        holderIds = holderIds,
        holderTransformVersions = holderTransformVersions,
        scaleX = scaleX,
        scaleY = scaleY,
        translationX = translationX,
        translationY = translationY,
    )

    @Test
    fun `a holder's finished decode changes the token`() {
        // The border crop is applied when the page's own decode finishes
        // (`WebtoonPageHolder.onImageDecoded`), while its blocks can resolve before that. This term is
        // what moves a cropped page's boxes to their crop positions without a scroll: drop it and the
        // overlay keeps drawing them at the uncropped positions until the user scrolls.
        assertNotEquals(
            token(holderTransformVersions = listOf(3, 4)),
            token(holderTransformVersions = listOf(3, 5)),
            "a holder whose transform changed must invalidate the overlay",
        )
    }

    @Test
    fun `a scroll, a page turn, a pinch and a pan each change the token`() {
        val base = token()

        // Each of these changes what the overlay must project, so each has to invalidate the draw.
        assertNotEquals(token(scrollOffset = 1001), base, "a scroll")
        assertNotEquals(token(holderIds = listOf(7, 9)), base, "a holder change")
        // The zoom is a layer transform on the recycler, so it moves neither the scroll nor the
        // holders: without these terms the boxes keep the pre-zoom projection for a whole pinch.
        assertNotEquals(token(scaleX = 3f), base, "a pinch on x")
        assertNotEquals(token(scaleY = 3f), base, "a pinch on y")
        assertNotEquals(token(translationX = 13f), base, "a pan on x")
        assertNotEquals(token(translationY = -9f), base, "a pan on y")
    }

    @Test
    fun `the same geometry is the same token`() {
        // Or the overlay would re-project every frame of a still page.
        assertEquals(token(), token())
    }
}
