// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.ui.reader.viewer

import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.max

/**
 * Recognizes a two-finger tap: two pointers go down, neither drags beyond [touchSlop], and they lift
 * within [tapTimeoutMillis]. GPL-free pure gesture logic (no reader/dictionary types) so the
 * Apache-headed `ReaderActivity` can own it.
 *
 * It is **non-consuming**: the reader feeds every dispatched touch to [onTouchEvent] and still passes
 * it on, so page turns, pinch-zoom, pan and the WebGPU viewer's Compose gestures are untouched. It is
 * gated by [isEnabled] (the `ReaderPreferences.ocrTwoFingerGestureEnabled` setting) and is a no-op
 * when disabled, which is the default.
 */
class OcrTwoFingerTapDetector(
    private val touchSlop: Float,
    private val tapTimeoutMillis: Long,
) {
    /** Dynamic gate (the reader OCR two-finger setting); consulted when the second finger lands. */
    var isEnabled: () -> Boolean = { false }

    /** Invoked once when a two-finger tap is confirmed. */
    var onTap: (() -> Unit)? = null

    private var handling = false
    private var maxPointers = 0
    private var moved = false
    private var downTime = 0L
    private var startX0 = 0f
    private var startY0 = 0f
    private var startX1 = 0f
    private var startY1 = 0f

    fun onTouchEvent(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handling = false
                maxPointers = 1
                moved = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = max(maxPointers, ev.pointerCount)
                if (ev.pointerCount == 2 && !handling && onTap != null && isEnabled()) {
                    handling = true
                    moved = false
                    downTime = ev.eventTime
                    startX0 = ev.getX(0)
                    startY0 = ev.getY(0)
                    startX1 = ev.getX(1)
                    startY1 = ev.getY(1)
                } else if (ev.pointerCount > 2) {
                    // Three or more fingers: not a two-finger tap.
                    moved = true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (handling && !moved && ev.pointerCount >= 2 &&
                    (
                        abs(ev.getX(0) - startX0) > touchSlop ||
                            abs(ev.getY(0) - startY0) > touchSlop ||
                            abs(ev.getX(1) - startX1) > touchSlop ||
                            abs(ev.getY(1) - startY1) > touchSlop
                        )
                ) {
                    moved = true
                }
            }

            MotionEvent.ACTION_UP -> {
                if (handling) {
                    if (!moved && maxPointers == 2 && ev.eventTime - downTime <= tapTimeoutMillis) {
                        onTap?.invoke()
                    }
                    handling = false
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                handling = false
            }
        }
    }
}
