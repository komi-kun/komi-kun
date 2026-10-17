package eu.kanade.tachiyomi.ui.reader.viewer

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies the non-consuming outside-tap dismissal semantics that keep the dictionary popup
 * non-modal: only a clean single-finger tap outside the popup dismisses it, and drags, multi-finger
 * gestures and taps inside the popup are left untouched (so the viewer keeps scrolling/zooming and
 * the two-finger OCR toggle keeps working).
 *
 * Action codes mirror `android.view.MotionEvent`; the pure [OutsideTapDismissDetector.onGesture]
 * core is exercised so no Android graphics/framework types are needed.
 */
class OutsideTapDismissDetectorTest {

    private val down = 0
    private val up = 1
    private val move = 2
    private val cancel = 3
    private val pointerDown = 5

    private val slop = 20f
    private val timeout = 500L

    /** Bounds `[100,100]..[400,400]`. */
    private val bounds = OutsideTapDismissDetector.Bounds { x, y ->
        x in 100f..400f && y in 100f..400f
    }

    private fun detector(): Pair<OutsideTapDismissDetector, () -> Int> {
        var dismissals = 0
        val detector = OutsideTapDismissDetector(slop, timeout).apply {
            boundsProvider = { if (open) bounds else null }
            onOutsideTap = { dismissals++ }
        }
        return detector to { dismissals }
    }

    private var open = true

    @Test
    fun `single tap outside dismisses`() {
        val (detector, count) = detector()
        detector.onGesture(down, 0L, 1, 50f, 50f)
        detector.onGesture(up, 100L, 1, 50f, 50f)
        assertTrue(count() == 1)
    }

    @Test
    fun `single tap inside does not dismiss`() {
        val (detector, count) = detector()
        detector.onGesture(down, 0L, 1, 200f, 200f)
        detector.onGesture(up, 100L, 1, 200f, 200f)
        assertTrue(count() == 0)
    }

    @Test
    fun `drag outside does not dismiss`() {
        val (detector, count) = detector()
        detector.onGesture(down, 0L, 1, 50f, 50f)
        detector.onGesture(move, 50L, 1, 50f, 140f) // 90px down, beyond slop
        detector.onGesture(up, 100L, 1, 50f, 140f)
        assertTrue(count() == 0)
    }

    @Test
    fun `two finger tap does not dismiss`() {
        val (detector, count) = detector()
        detector.onGesture(down, 0L, 1, 50f, 50f)
        detector.onGesture(pointerDown, 20L, 2, 50f, 50f)
        detector.onGesture(up, 100L, 1, 50f, 50f)
        assertTrue(count() == 0)
    }

    @Test
    fun `long press outside does not dismiss`() {
        val (detector, count) = detector()
        detector.onGesture(down, 0L, 1, 50f, 50f)
        detector.onGesture(up, timeout + 100L, 1, 50f, 50f)
        assertTrue(count() == 0)
    }

    @Test
    fun `tap while no popup is open does not dismiss`() {
        open = false
        val (detector, count) = detector()
        detector.onGesture(down, 0L, 1, 50f, 50f)
        detector.onGesture(up, 100L, 1, 50f, 50f)
        open = true
        assertTrue(count() == 0)
    }

    @Test
    fun `cancelled gesture does not dismiss`() {
        val (detector, count) = detector()
        detector.onGesture(down, 0L, 1, 50f, 50f)
        detector.onGesture(cancel, 50L, 1, 50f, 50f)
        detector.onGesture(up, 100L, 1, 50f, 50f)
        assertTrue(count() == 0)
    }

    @Test
    fun `movement within slop still dismisses`() {
        val (detector, count) = detector()
        detector.onGesture(down, 0L, 1, 50f, 50f)
        detector.onGesture(move, 30L, 1, 55f, 55f) // 5px, within slop
        detector.onGesture(up, 100L, 1, 55f, 55f)
        assertTrue(count() == 1)
    }

    @Test
    fun `no bounds means no false negatives about inside`() {
        // Guard: when bounds exist, an outside tap is required (sanity check on the predicate).
        assertFalse(bounds.contains(500f, 500f))
        assertTrue(bounds.contains(200f, 200f))
    }
}
