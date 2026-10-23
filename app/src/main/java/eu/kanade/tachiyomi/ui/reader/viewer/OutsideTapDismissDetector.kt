// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.ui.reader.viewer

import android.view.MotionEvent
import kotlin.math.abs

/**
 * Recognizes a single-finger tap that lands **outside** a given on-screen rectangle, so the
 * non-modal dictionary popup can be dismissed by tapping the page around it.
 *
 * It is deliberately **non-consuming**: the reader feeds every dispatched touch to [onTouchEvent]
 * and still passes it on, so page turns, pinch-zoom, pan, long-press lookup and the two-finger OCR
 * overlay toggle are untouched. That is what keeps the popup non-modal — the popup only *observes*
 * outside taps instead of swallowing the whole page with a scrim.
 *
 * GPL-free pure gesture logic (no reader/dictionary types) so the Apache-headed `ReaderActivity`
 * can own it. It is a no-op while [boundsProvider] returns null (no popup open).
 */
class OutsideTapDismissDetector(
    private val touchSlop: Float,
    private val tapTimeoutMillis: Long,
) {
    /**
     * Abstract "is this point inside the popup" test. It keeps the detector free of Android
     * graphics types so its logic is unit-testable on the JVM; the reader adapts the popup's
     * `RectF` bounds to one of these.
     */
    fun interface Bounds {
        fun contains(x: Float, y: Float): Boolean
    }

    /** The popup's current bounds, or null when closed (then the detector is a no-op). */
    var boundsProvider: () -> Bounds? = { null }

    /** Invoked once when a qualifying tap lands outside [boundsProvider], with the tap point in the
     * same coordinate space as [Bounds.contains]. */
    var onOutsideTap: ((x: Float, y: Float) -> Unit)? = null

    private var tracking = false
    private var moved = false
    private var multiPointer = false
    private var inside = false
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f

    /**
     * @param x the touch x in the same coordinate space as [boundsProvider] (the reader converts
     *   screen coordinates into the compose-overlay space before calling).
     */
    fun onTouchEvent(ev: MotionEvent, x: Float, y: Float) {
        onGesture(ev.actionMasked, ev.eventTime, ev.pointerCount, x, y)
    }

    /** Pure gesture core, extracted so it is unit-testable without a real `MotionEvent`. */
    internal fun onGesture(action: Int, eventTime: Long, pointerCount: Int, x: Float, y: Float) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                val bounds = boundsProvider()
                tracking = bounds != null && onOutsideTap != null
                moved = false
                multiPointer = false
                downTime = eventTime
                downX = x
                downY = y
                inside = bounds?.contains(x, y) == true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerCount > 1) multiPointer = true
            }

            MotionEvent.ACTION_MOVE -> {
                if (tracking && !moved &&
                    (abs(x - downX) > touchSlop || abs(y - downY) > touchSlop)
                ) {
                    moved = true
                }
            }

            MotionEvent.ACTION_UP -> {
                if (tracking && !moved && !multiPointer && !inside &&
                    eventTime - downTime <= tapTimeoutMillis
                ) {
                    onOutsideTap?.invoke(x, y)
                }
                tracking = false
            }

            MotionEvent.ACTION_CANCEL -> {
                tracking = false
            }
        }
    }
}
