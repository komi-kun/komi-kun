// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package eu.kanade.tachiyomi.ui.reader.viewer.popup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The fit rule: a block already inside the safe band above the popup is left alone; otherwise it is
 * moved so its bottom edge comes to rest just above the popup (which then fills the lower region).
 */
class OcrPopupFitTest {

    // 1000px-tall viewport, popup covers the bottom 40% → popup top at 600px, margin 20px.
    private val overlayHeight = 1000f
    private val lowerFraction = 0.4f
    private val height = 40f

    @Test
    fun `a comfortably visible block needs no move`() {
        val target = popupFitTarget(blockCenterY = 320f, blockHeight = height, overlayHeight, lowerFraction)
        assertTrue(isComfortablyVisible(300f, 340f, target))
        assertEquals(320f, target.targetCenterY)
    }

    @Test
    fun `a block under the popup is moved just above it`() {
        val target = popupFitTarget(blockCenterY = 820f, blockHeight = height, overlayHeight, lowerFraction)
        assertFalse(isComfortablyVisible(800f, 840f, target))
        // Bottom edge lands at the safe bottom (popup top minus the margin).
        assertEquals(580f, target.targetCenterY + height / 2f, 0.5f)
    }

    @Test
    fun `a block above the safe top is moved down`() {
        val target = popupFitTarget(blockCenterY = 20f, blockHeight = height, overlayHeight, lowerFraction)
        assertFalse(isComfortablyVisible(0f, 40f, target))
        assertEquals(20f, target.targetCenterY - height / 2f, 0.5f)
    }
}
// KUN <--
