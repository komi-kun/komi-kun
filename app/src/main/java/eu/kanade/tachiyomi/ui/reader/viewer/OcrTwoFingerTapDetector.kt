// SPDX-License-Identifier: Apache-2.0
// KUN -->
package eu.kanade.tachiyomi.ui.reader.viewer

import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.max

/**
 * Recognizes a two-finger tap / click: two pointers go down, neither drags beyond [multiTouchSlop],
 * and they lift within [tapTimeoutMillis]. Also recognizes secondary / right clicks from a trackpad
 * or mouse (which Android emits on a two-finger click).
 *
 * It is **non-consuming**: the reader feeds every dispatched touch to [onTouchEvent] and still passes
 * it on, so page turns, pinch-zoom, pan and the WebGPU viewer's Compose gestures are untouched. It is
 * gated by [isEnabled] (the `ReaderPreferences.ocrTwoFingerGestureEnabled` setting).
 */
class OcrTwoFingerTapDetector(
    private val touchSlop: Float,
    private val tapTimeoutMillis: Long,
) {
    /** Dynamic gate (the reader OCR two-finger setting); consulted when the second finger lands. */
    var isEnabled: () -> Boolean = { true }

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
    private var pointerId0 = -1
    private var pointerId1 = -1

    // Allow a more forgiving touch slop for two fingers (2.0x standard slop) because landing two
    // fingers on a high-DPI glass screen naturally exhibits slight finger spread or micro-jitter.
    private val multiTouchSlop = touchSlop * 2.0f

    fun onTouchEvent(ev: MotionEvent) {
        // Trackpad / mouse two-finger click emits secondary button clicks
        if (isEnabled() && onTap != null) {
            val isSecondaryClick = (
                ev.actionMasked == MotionEvent.ACTION_DOWN ||
                    ev.actionMasked == MotionEvent.ACTION_BUTTON_PRESS
                ) &&
                (
                    (ev.buttonState and MotionEvent.BUTTON_SECONDARY != 0) ||
                        ev.actionButton == MotionEvent.BUTTON_SECONDARY
                    )
            if (isSecondaryClick) {
                onTap?.invoke()
                return
            }
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handling = false
                maxPointers = 1
                moved = false
                pointerId0 = ev.getPointerId(0)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = max(maxPointers, ev.pointerCount)
                if (ev.pointerCount == 2 && !handling && onTap != null && isEnabled()) {
                    handling = true
                    moved = false
                    downTime = ev.eventTime
                    pointerId0 = ev.getPointerId(0)
                    pointerId1 = ev.getPointerId(1)
                    val idx0 = ev.findPointerIndex(pointerId0).coerceAtLeast(0)
                    val idx1 = ev.findPointerIndex(pointerId1).coerceAtLeast(0)
                    startX0 = ev.getX(idx0)
                    startY0 = ev.getY(idx0)
                    startX1 = ev.getX(idx1)
                    startY1 = ev.getY(idx1)
                } else if (ev.pointerCount > 2) {
                    // Three or more fingers: not a two-finger tap.
                    moved = true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (handling && !moved && ev.pointerCount >= 2) {
                    val idx0 = ev.findPointerIndex(pointerId0)
                    val idx1 = ev.findPointerIndex(pointerId1)
                    if (idx0 >= 0 && idx1 >= 0) {
                        val dx0 = abs(ev.getX(idx0) - startX0)
                        val dy0 = abs(ev.getY(idx0) - startY0)
                        val dx1 = abs(ev.getX(idx1) - startX1)
                        val dy1 = abs(ev.getY(idx1) - startY1)
                        if (dx0 > multiTouchSlop || dy0 > multiTouchSlop ||
                            dx1 > multiTouchSlop || dy1 > multiTouchSlop
                        ) {
                            moved = true
                        }
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // When one of the two fingers lifts, if the gesture didn't drag and happened within
                // the tap window, confirm the tap immediately without waiting for the lingering finger.
                if (handling) {
                    if (!moved && maxPointers == 2 && ev.eventTime - downTime <= tapTimeoutMillis) {
                        onTap?.invoke()
                    }
                    handling = false
                }
            }

            MotionEvent.ACTION_UP -> {
                // Fallback in case both fingers lifted in the same event
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

    /** Pure gesture core, extracted so it is unit-testable without a real [MotionEvent]. */
    internal fun onGesture(
        action: Int,
        eventTime: Long,
        pointerCount: Int,
        p0: Pair<Float, Float>?,
        p1: Pair<Float, Float>?,
        isSecondaryClick: Boolean = false,
    ) {
        if (isEnabled() && onTap != null && isSecondaryClick) {
            onTap?.invoke()
            return
        }

        when (action) {
            0 -> { // ACTION_DOWN
                handling = false
                maxPointers = 1
                moved = false
            }
            5 -> { // ACTION_POINTER_DOWN
                maxPointers = max(maxPointers, pointerCount)
                if (pointerCount == 2 && !handling && onTap != null && isEnabled()) {
                    handling = true
                    moved = false
                    downTime = eventTime
                    startX0 = p0?.first ?: 0f
                    startY0 = p0?.second ?: 0f
                    startX1 = p1?.first ?: 0f
                    startY1 = p1?.second ?: 0f
                } else if (pointerCount > 2) {
                    moved = true
                }
            }
            2 -> { // ACTION_MOVE
                if (handling && !moved && pointerCount >= 2 && p0 != null && p1 != null) {
                    val dx0 = abs(p0.first - startX0)
                    val dy0 = abs(p0.second - startY0)
                    val dx1 = abs(p1.first - startX1)
                    val dy1 = abs(p1.second - startY1)
                    if (dx0 > multiTouchSlop || dy0 > multiTouchSlop ||
                        dx1 > multiTouchSlop || dy1 > multiTouchSlop
                    ) {
                        moved = true
                    }
                }
            }
            6 -> { // ACTION_POINTER_UP
                if (handling) {
                    if (!moved && maxPointers == 2 && eventTime - downTime <= tapTimeoutMillis) {
                        onTap?.invoke()
                    }
                    handling = false
                }
            }
            1 -> { // ACTION_UP
                if (handling) {
                    if (!moved && maxPointers == 2 && eventTime - downTime <= tapTimeoutMillis) {
                        onTap?.invoke()
                    }
                    handling = false
                }
            }
            3 -> { // ACTION_CANCEL
                handling = false
            }
        }
    }
}
// KUN <--
