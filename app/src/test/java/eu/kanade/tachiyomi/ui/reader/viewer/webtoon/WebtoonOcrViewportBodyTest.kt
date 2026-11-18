// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The webtoon adapter's *body*, not just its arithmetic: the two conversions that used to be pinned
// only by reading the source.
//
//  - `overlayPointForRecyclerLocal` (behind `overlayPointOfRecyclerLocal`, the tap path) reads the
//    recycler's live ancestor zoom and hands it to the pure conversion. Before this test the only
//    guard was a source assertion that the caller invokes it, so replacing the scale with `1f` -
//    recreating the `origin + local` space `ea8552cb10` fixed - kept every arithmetic test green.
//    The mock supplies a non-unit zoom, so the expected overlay point moves if the live zoom stops
//    being used. The *origin* half still goes through `OcrOverlayCoords.offsetOf`, whose `PointF` is
//    a stub on this classpath and therefore reads back as (0, 0) here; that half is pinned by the
//    source guard in `WebtoonOcrViewportScaleWiringTest`.
//  - `overlayRectOfViewRect` is the SSIV escape hatch's draw path (`alwaysDecodeLongStripWithSSIV`,
//    i.e. the branch `WebtoonOcrViewportScaleWiringTest` used to inspect only for the strip case).
//    Dropping `* scale.x` from it restores the bug for that configuration with the strip tests green.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.view.View
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WebtoonOcrViewportBodyTest {

    private val epsilon = 1e-5f

    /**
     * A viewport whose just-mocked recycler reports [scaleX]/[scaleY] as the zoom it (and its
     * children) are drawn under, exactly as `WebtoonRecyclerView.setScaleRate` sets it.
     */
    private fun viewportUnderZoom(scaleX: Float, scaleY: Float): WebtoonOcrViewport {
        val overlay = mockk<View>()
        every { overlay.parent } returns null
        every { overlay.getLocationInWindow(any()) } answers {
            firstArg<IntArray>()[0] = 0
            firstArg<IntArray>()[1] = 0
        }
        val activity = mockk<ReaderActivity>()
        every { activity.composeOverlayRoot } returns overlay

        val recycler = mockk<WebtoonRecyclerView>()
        every { recycler.parent } returns null
        every { recycler.scaleX } returns scaleX
        every { recycler.scaleY } returns scaleY
        every { recycler.getLocationInWindow(any()) } answers {
            firstArg<IntArray>()[0] = 0
            firstArg<IntArray>()[1] = 0
        }

        val viewer = mockk<WebtoonViewer>()
        every { viewer.activity } returns activity
        every { viewer.recycler } returns recycler
        return WebtoonOcrViewport(viewer)
    }

    @Test
    fun `a tap point from the recycler carries the live ancestor zoom into overlay space`() {
        val point = viewportUnderZoom(scaleX = 2f, scaleY = 3f).overlayPointForRecyclerLocal(x = 10f, y = 20f)

        // origin + scale ⊙ local, with the origin (0, 0) here (see the file header): the extent has
        // to be doubled on x and tripled on y. With the zoom dropped - `1f` for both - this is
        // (10, 20), i.e. the tap resolved in `origin + local` while the box was drawn zoomed.
        assertEquals(20f, point.x, epsilon)
        assertEquals(60f, point.y, epsilon)
    }

    @Test
    fun `at scale 1 the tap point is the plain offset conversion`() {
        val point = viewportUnderZoom(scaleX = 1f, scaleY = 1f).overlayPointForRecyclerLocal(x = 10f, y = 20f)

        // The identity case must not be perturbed: it is what the pre-fix conversion looked like.
        assertEquals(10f, point.x, 0f)
        assertEquals(20f, point.y, 0f)
    }

    // ------------------------------------------------------------ SSIV escape hatch, draw path

    @Test
    fun `the escape hatch's view rect is scaled per axis into overlay space`() {
        val rect = overlayRectOfViewRect(
            left = 100f,
            top = 50f,
            right = 300f,
            bottom = 250f,
            originX = 10f,
            originY = 20f,
            scaleX = 2f,
            scaleY = 3f,
        )

        // x doubled, y tripled - one shared factor would put the box in the wrong place on one axis.
        assertEquals(10f + 100f * 2f, rect.left, epsilon)
        assertEquals(20f + 50f * 3f, rect.top, epsilon)
        assertEquals(10f + 300f * 2f, rect.right, epsilon)
        assertEquals(20f + 250f * 3f, rect.bottom, epsilon)
    }

    @Test
    fun `the escape hatch's rect is the box around both corners whoever is larger`() {
        // `sourceToViewCoord` corners are unordered when the page is paged RTL or scrolled: the rect
        // has to be the bbox either way, or the box is drawn with negative extents.
        val rect = overlayRectOfViewRect(
            left = 300f,
            top = 250f,
            right = 100f,
            bottom = 50f,
            originX = 0f,
            originY = 0f,
            scaleX = 2f,
            scaleY = 2f,
        )

        assertEquals(200f, rect.left, epsilon)
        assertEquals(100f, rect.top, epsilon)
        assertEquals(600f, rect.right, epsilon)
        assertEquals(500f, rect.bottom, epsilon)
    }
}
