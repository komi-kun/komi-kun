// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package eu.kanade.tachiyomi.ui.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The popup size is a percentage of an adaptive, screen-relative baseline, so the default popup
 * grows with the screen (a tablet's is larger than a phone's) and the size preference scales it.
 */
class DictionaryPopupSizingTest {

    @Test
    fun `default scales with the screen`() {
        val phone = DictionaryPopupSizing.resolve(
            screenWidthDp = 360,
            screenHeightDp = 800,
            percent = DictionaryPopupSizing.DEFAULT_PERCENT,
            overrideWidthDp = 0,
            overrideHeightDp = 0,
        )
        val tablet = DictionaryPopupSizing.resolve(
            screenWidthDp = 800,
            screenHeightDp = 1280,
            percent = DictionaryPopupSizing.DEFAULT_PERCENT,
            overrideWidthDp = 0,
            overrideHeightDp = 0,
        )
        assertTrue(tablet.widthDp > phone.widthDp, "a tablet gets a wider default popup")
        assertTrue(tablet.heightDp > phone.heightDp, "a tablet gets a taller default popup")
    }

    @Test
    fun `percentage scales the baseline`() {
        fun at(percent: Int) = DictionaryPopupSizing.resolve(360, 800, percent, 0, 0)
        val base = at(100)
        val bigger = at(150)
        val smaller = at(50)
        assertTrue(bigger.widthDp > base.widthDp)
        assertTrue(bigger.heightDp > base.heightDp)
        assertTrue(smaller.widthDp < base.widthDp)
        assertTrue(smaller.heightDp < base.heightDp)
    }

    @Test
    fun `explicit dp overrides win over the adaptive baseline`() {
        val size = DictionaryPopupSizing.resolve(1200, 1600, 100, overrideWidthDp = 500, overrideHeightDp = 200)
        assertEquals(500, size.widthDp)
        assertEquals(200, size.heightDp)
    }

    @Test
    fun `size is clamped into the screen`() {
        val size = DictionaryPopupSizing.resolve(320, 480, 200, 0, 0)
        assertTrue(size.widthDp <= 320)
        assertTrue(size.heightDp <= 480)
    }
}
// KUN <--
