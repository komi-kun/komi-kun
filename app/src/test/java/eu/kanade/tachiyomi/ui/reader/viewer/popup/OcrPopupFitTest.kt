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

    // popupHeight = 0.4 * 1000 = 400px, margin = 20px.
    private val popupHeight = 400f

    @Test
    fun `an upper block keeps the popup below it`() {
        // 660px of room below the block: plenty.
        assertEquals(PopupSide.Below, popupSide(300f, 340f, overlayHeight, popupHeight))
        assertEquals(340f, popupTopFor(300f, 340f, overlayHeight, popupHeight))
    }

    @Test
    fun `a block at the very bottom flips the popup above it`() {
        // Only 160px below the block - less than the popup - but 800px above it.
        assertEquals(PopupSide.Above, popupSide(800f, 840f, overlayHeight, popupHeight))
        // Popup ends at the block's top edge.
        assertEquals(800f - popupHeight, popupTopFor(800f, 840f, overlayHeight, popupHeight))
    }

    @Test
    fun `a block just past the lower band flips above`() {
        // Bottom edge at 585: 415px below, just under the 400px popup plus the 20px margin.
        assertEquals(PopupSide.Above, popupSide(545f, 585f, overlayHeight, popupHeight))
    }

    @Test
    fun `a block so tall the popup fits neither way is clamped to the roomier side`() {
        // 100px above, 100px below the popup: neither holds it.
        assertEquals(PopupSide.Partial, popupSide(100f, 900f, overlayHeight, popupHeight))
        // Equal slack resolves to below (the natural anchor), still clamped by the caller.
        assertEquals(900f, popupTopFor(100f, 900f, overlayHeight, popupHeight))
        // More room below than above keeps it below too.
        assertEquals(PopupSide.Partial, popupSide(50f, 700f, overlayHeight, popupHeight))
    }
}
// KUN <--
