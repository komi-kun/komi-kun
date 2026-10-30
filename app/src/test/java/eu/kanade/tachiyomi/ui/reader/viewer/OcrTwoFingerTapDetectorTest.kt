// SPDX-License-Identifier: Apache-2.0
// KUN -->
package eu.kanade.tachiyomi.ui.reader.viewer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OcrTwoFingerTapDetectorTest {

    private val down = 0
    private val up = 1
    private val move = 2
    private val cancel = 3
    private val pointerDown = 5
    private val pointerUp = 6

    private val slop = 20f
    private val timeout = 500L

    private fun createDetector(): Pair<OcrTwoFingerTapDetector, () -> Int> {
        var taps = 0
        val detector = OcrTwoFingerTapDetector(slop, timeout).apply {
            isEnabled = { true }
            onTap = { taps++ }
        }
        return detector to { taps }
    }

    @Test
    fun `two finger tap fires on pointer up`() {
        val (detector, tapCount) = createDetector()
        val p0 = 100f to 100f
        val p1 = 200f to 200f

        detector.onGesture(down, 0L, 1, p0, null)
        detector.onGesture(pointerDown, 20L, 2, p0, p1)
        detector.onGesture(pointerUp, 150L, 2, p0, p1)
        assertEquals(1, tapCount())

        // Trailing single-finger up does not fire again
        detector.onGesture(up, 180L, 1, p0, null)
        assertEquals(1, tapCount())
    }

    @Test
    fun `two finger tap fires on up if pointer up not received`() {
        val (detector, tapCount) = createDetector()
        val p0 = 100f to 100f
        val p1 = 200f to 200f

        detector.onGesture(down, 0L, 1, p0, null)
        detector.onGesture(pointerDown, 20L, 2, p0, p1)
        detector.onGesture(up, 150L, 2, p0, p1)
        assertEquals(1, tapCount())
    }

    @Test
    fun `two finger drag does not fire tap`() {
        val (detector, tapCount) = createDetector()
        val p0 = 100f to 100f
        val p1 = 200f to 200f

        detector.onGesture(down, 0L, 1, p0, null)
        detector.onGesture(pointerDown, 20L, 2, p0, p1)
        // Drag p0 by 100px (> 2 * slop)
        detector.onGesture(move, 50L, 2, 200f to 100f, p1)
        detector.onGesture(pointerUp, 150L, 2, 200f to 100f, p1)
        detector.onGesture(up, 180L, 1, 200f to 100f, null)
        assertEquals(0, tapCount())
    }

    @Test
    fun `two finger tap exceeding timeout does not fire`() {
        val (detector, tapCount) = createDetector()
        val p0 = 100f to 100f
        val p1 = 200f to 200f

        detector.onGesture(down, 0L, 1, p0, null)
        detector.onGesture(pointerDown, 20L, 2, p0, p1)
        // 600ms hold (> 500ms timeout)
        detector.onGesture(pointerUp, 620L, 2, p0, p1)
        detector.onGesture(up, 650L, 1, p0, null)
        assertEquals(0, tapCount())
    }

    @Test
    fun `three fingers does not fire two finger tap`() {
        val (detector, tapCount) = createDetector()
        val p0 = 100f to 100f
        val p1 = 200f to 200f

        detector.onGesture(down, 0L, 1, p0, null)
        detector.onGesture(pointerDown, 20L, 2, p0, p1)
        detector.onGesture(pointerDown, 40L, 3, p0, p1)
        detector.onGesture(pointerUp, 100L, 3, p0, p1)
        detector.onGesture(up, 150L, 1, p0, null)
        assertEquals(0, tapCount())
    }

    @Test
    fun `secondary click fires tap`() {
        val (detector, tapCount) = createDetector()
        detector.onGesture(down, 0L, 1, 100f to 100f, null, isSecondaryClick = true)
        assertEquals(1, tapCount())
    }

    @Test
    fun `disabled detector does not fire`() {
        var taps = 0
        val detector = OcrTwoFingerTapDetector(slop, timeout).apply {
            isEnabled = { false }
            onTap = { taps++ }
        }
        val p0 = 100f to 100f
        val p1 = 200f to 200f

        detector.onGesture(down, 0L, 1, p0, null)
        detector.onGesture(pointerDown, 20L, 2, p0, p1)
        detector.onGesture(pointerUp, 150L, 2, p0, p1)
        detector.onGesture(up, 180L, 1, p0, null)
        assertEquals(0, taps)
    }
}
// KUN <--
