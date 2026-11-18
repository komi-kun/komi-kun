package eu.kanade.tachiyomi.ui.reader.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.ocr.OcrViewport

/**
 * Interface for implementing a viewer.
 */
interface Viewer {

    /**
     * Draws this viewer.
     */
    @Composable
    fun Content(modifier: Modifier)

    // KUN -->
    /**
     * Viewer-specific image<->screen mapping for the OCR overlay, or null when this viewer (or its
     * current page) has no mapping yet. Every backend returns its own adapter; the reader only ever
     * sees the Apache [OcrViewport] seam.
     */
    fun ocrViewport(): OcrViewport? = null
    // KUN <--

    /**
     * Destroys this viewer. Called when leaving the reader or swapping viewers.
     */
    fun destroy() {}

    /**
     * Tells this viewer to set the given [chapters] as active.
     */
    fun setChapters(chapters: ViewerChapters)

    /**
     * Tells this viewer to move to the given [page].
     */
    fun moveToPage(page: ReaderPage)

    /**
     * Moves forward through the pages.
     */
    fun moveForward()

    /**
     * Moves backward through the pages.
     */
    fun moveBackward()

    // KUN -->
    /**
     * Moves backward through the pages for a **vertical** input: the D-pad up, the page up key, the
     * volume keys and the mouse wheel. A pager pans the current page up first and only turns the page
     * once it is at its top (KMK's pan-before-turn); every other viewer scrolls, as [moveBackward]
     * does. The horizontal keys and the tap zones use [moveBackward] and never pan vertically.
     */
    fun moveUp() = moveBackward()

    /**
     * [moveUp] forward, for the vertical inputs that go down.
     */
    fun moveDown() = moveForward()
    // KUN <--
}

/**
 * The reader's grayscale and inverted colours, for page images only: transition, loading and error pages keep
 * theirs. Null when both are off.
 */
val LocalPageColorFilter = compositionLocalOf<ColorFilter?> { null }

/**
 * Turns pages with the mouse wheel instead of letting the content scroll.
 *
 * For the viewers whose pages do not scroll on their own: the pager and the WebGPU ones. The long strip
 * deliberately does not apply it - its `RecyclerView` scrolls the strip natively, and a wheel notch turning
 * one page there would fight that scroll.
 */
fun Modifier.mouseWheelPaging(viewer: Viewer): Modifier = pointerInput(viewer) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type != PointerEventType.Scroll) continue
            val delta = event.changes.first().scrollDelta.y
            when {
                // KUN -->
                // The wheel is a vertical input in the fork, so it pans the current page before it
                // turns it, exactly as the super-class motion handler it replaces did. For every
                // viewer that cannot pan the two calls are moveForward()/moveBackward().
                delta > 0 -> viewer.moveDown()
                delta < 0 -> viewer.moveUp()
                // KUN <--
                else -> continue
            }
            event.changes.forEach { it.consume() }
        }
    }
}
