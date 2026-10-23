// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.ui

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Regression guard for the invisible word-audio picker.
 *
 * This app's dictionary WebViews report **0** for every viewport-height unit (`vh`, `dvh`, `svh`,
 * `lvh`) and for height media queries, while `window.innerWidth/innerHeight` are correct. A
 * `max-height: 60vh` therefore computed to `max-height: 0px`: the menu collapsed to its padding
 * box and `overflow-y: auto` clipped every item, so it existed in the DOM and the accessibility
 * tree but never painted. The size cap has to come from the measured viewport in pixels instead.
 */
class AudioMenuViewportHeightGuardTest {
    private val moduleDir = File("src/main/assets/dictionary")

    @Test
    fun `audio picker assets do not size themselves with viewport-height units`() {
        val css = File(moduleDir, "base.css").readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        val js = File(moduleDir, "renderer.js").readText()

        // Any `height`/`max-height`/`min-height: <n>vh`-family declaration would collapse to 0 here.
        val viewportHeightUnit = Regex(":\\s*[0-9.]+(vh|dvh|svh|lvh)\\b")
        viewportHeightUnit.containsMatchIn(css) shouldBe false

        // ...so the picker must cap itself in pixels from the measured viewport before measuring.
        js shouldContain "menu.style.maxHeight"
        js shouldContain "window.innerHeight"
    }
}
// KUN <--
