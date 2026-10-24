// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Live screen-space anchor of the OCR block whose dictionary popup is open.
//
// `ReaderOcrHostImpl` writes the anchor every frame from the current [OcrViewport] (so the popup
// follows page turns / zoom / scroll - plan 04 §4.5), and the GPL `DictionaryPopup` reads it to
// position itself. It is app-graph scoped (`context.appGraph`) so the writer and the popup share a
// single Compose snapshot state without changing the Apache seam interfaces.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.ocr.impl

import android.graphics.RectF
import androidx.compose.runtime.mutableStateOf
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import mihon.core.dictionary.anki.AnkiScreenshotProvider

@Inject
@SingleIn(AppScope::class)
class ReaderOcrPopupAnchor {
    /** The active block's rect in `compose_overlay` coordinates, or null when no lookup is open. */
    val rect = mutableStateOf<RectF?>(null)

    /**
     * The visible popup's own bounds in `compose_overlay` coordinates while it is laid out, or null.
     * The reader reads this (through the Apache seam) to tell an outside tap from a tap on the popup.
     */
    val popupBounds = mutableStateOf<RectF?>(null)

    /**
     * The `{screenshot}` source for the open lookup: the reader writes a provider for the tapped
     * page/block, the popup hands it to `DictionaryEntryWebView` when a card is created (plan 05
     * §B4). Null when the screenshot mode takes no image, so the marker renders empty.
     */
    val screenshot = mutableStateOf<AnkiScreenshotProvider?>(null)

    fun clear() {
        rect.value = null
        popupBounds.value = null
        screenshot.value = null
    }
}
