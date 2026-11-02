// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Swipe-to-dismiss of the dictionary popup.
//
// The gesture used to live on the popup's `Surface`, i.e. on an **ancestor** of the definition
// WebView. A detector there consumes the drag, Compose's AndroidView interop then cancels the touch
// stream, and the WebView's own scroll stops after roughly one touch-slop — the definition body
// could be scrolled only a few pixels before it froze. The fixes are: the drag lives on the
// dedicated handle only, the `Surface`/`Column`/`DictionaryLookupPane` carry no pointer input at
// all, the popup's own chrome (handle, hint row) swallows taps so they do not fall through to the
// reader viewer, and the drag arithmetic is a pure function (tested here). The Compose/WebView side
// of that is not exercised by any test in this module: there is no Compose or Robolectric harness,
// so the layout half is a source-level guard.
// KUN <--
package eu.kanade.tachiyomi.ui.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File

class DictionaryPopupSwipeHandleTest {

    private val source: String by lazy {
        File("src/main/java/eu/kanade/tachiyomi/ui/dictionary/DictionaryPopup.kt")
            .readText()
            .lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
    }

    // --- the drag arithmetic ------------------------------------------------------------------

    @Test
    fun `drag offset accumulates downward and clamps at the anchor`() {
        assertEquals(40f, accumulateDragOffset(0f, 40f), 0.001f)
        assertEquals(90f, accumulateDragOffset(40f, 50f), 0.001f)
        assertEquals(30f, accumulateDragOffset(40f, -10f), 0.001f)
        // Dragging back past the anchor never lifts the popup above its position.
        assertEquals(0f, accumulateDragOffset(10f, -40f), 0.001f)
        // An upward drag while the popup is at rest is ignored entirely.
        assertEquals(0f, accumulateDragOffset(0f, -30f), 0.001f)
    }

    @Test
    fun `dismissal requires the drag to pass the threshold`() {
        assertTrue(shouldDismissForSwipe(accumulatedOffsetPx = 120f, thresholdPx = 100f))
        assertFalse(shouldDismissForSwipe(accumulatedOffsetPx = 100f, thresholdPx = 100f))
        assertFalse(shouldDismissForSwipe(accumulatedOffsetPx = 99f, thresholdPx = 100f))
    }

    // --- the layout guard ---------------------------------------------------------------------

    @Test
    fun `nothing above the web view carries pointer input`() {
        // The modifier chain of the Surface that wraps the WebView: everything before its `color`.
        val surfaceChain = source.substring(
            source.indexOf("Surface("),
            source.indexOf("color = bg,"),
        )
        assertFalse(
            surfaceChain.contains("pointerInput"),
            "the Surface is an ancestor of the definition WebView; a detector here cancels its scroll",
        )
        assertFalse(surfaceChain.contains("detectVerticalDragGestures"), surfaceChain)
        assertFalse(surfaceChain.contains("detectTapGestures"), surfaceChain)

        // The pane is the direct wrapper of the AndroidView: not a single pointer node there.
        val paneCall = parenBlock(source, "DictionaryLookupPane(")
        assertFalse(paneCall.contains("pointerInput"), paneCall)

        // Every pointerInput in the file must be one of the two chrome hit targets below.
        val handle = blockRange(source, "if (swipeToDismiss) {")
        val hint = blockRange(source, "if (!selectionHintDismissed) {")
        val calls = Regex("pointerInput\\(").findAll(source).map { it.range.first }.toList()
        assertEquals(3, calls.size, "expected the handle (tap + drag) and the hint row (tap) only")
        calls.forEach { index ->
            assertTrue(
                index in handle || index in hint,
                "pointerInput at offset $index is outside the popup chrome: " +
                    source.substring(index, minOf(index + 40, source.length)),
            )
        }
    }

    @Test
    fun `the drag detector is attached to the handle only`() {
        assertEquals(
            1,
            Regex("detectVerticalDragGestures\\(").findAll(source).count(),
            "exactly one vertical-drag detector may exist (the drag handle)",
        )
        val handle = blockOf(source, "if (swipeToDismiss) {")
        assertTrue(handle.contains("detectVerticalDragGestures("), handle)
        // A real touch target around the 4dp pill, and the preference still gates its presence.
        assertTrue(handle.contains(".fillMaxWidth()"), handle)
        assertTrue(handle.contains(".height(DragHandleHeight)"), handle)
    }

    @Test
    fun `the popup chrome swallows taps instead of letting them reach the viewer`() {
        // With no pointer node in the popup chrome the tap hit-tests through to the reader viewer.
        val handle = blockRange(source, "if (swipeToDismiss) {")
        val hint = blockRange(source, "if (!selectionHintDismissed) {")
        assertTrue(
            hasTapConsumer(handle),
            "the drag handle must consume taps: ${source.substring(handle.first, handle.last + 1)}",
        )
        assertTrue(
            hasTapConsumer(hint),
            "the selection-hint row must consume taps: ${source.substring(hint.first, hint.last + 1)}",
        )
    }

    /** A no-op tap consumer inside [range] (spacing/form-insensitive). */
    private fun hasTapConsumer(range: IntRange): Boolean =
        Regex("detectTapGestures\\s*\\{\\s*\\}").findAll(source).any { it.range.first in range }
}

/** Source from [needle] to the end of its enclosing brace block (brace counting, no fixed layout). */
private fun blockOf(source: String, needle: String): String {
    val range = blockRange(source, needle)
    return source.substring(range.first, range.last + 1)
}

/** Source from the `(` of [needle] to its matching `)` (paren counting, no fixed layout). */
private fun parenBlock(source: String, needle: String): String {
    val start = source.indexOf(needle)
    if (start < 0) fail("missing call in DictionaryPopup.kt: $needle")
    var depth = 0
    for (index in start until source.length) {
        when (source[index]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return source.substring(start, index + 1)
            }
        }
    }
    fail("unterminated call for: $needle")
    error("unreachable")
}

/** The char range of the brace block that follows [needle] (its `{` … matching `}` inclusive). */
private fun blockRange(source: String, needle: String): IntRange {
    val start = source.indexOf(needle)
    if (start < 0) fail("missing declaration in DictionaryPopup.kt: $needle")
    var depth = 0
    var opened = false
    for (index in start until source.length) {
        when (source[index]) {
            '{' -> {
                depth++
                opened = true
            }
            '}' -> {
                depth--
                if (opened && depth == 0) return start..index
            }
        }
    }
    fail("unterminated block for: $needle")
    error("unreachable")
}
// KUN <--
