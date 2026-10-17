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

@Inject
@SingleIn(AppScope::class)
class ReaderOcrPopupAnchor {
    /** The active block's rect in `compose_overlay` coordinates, or null when no lookup is open. */
    val rect = mutableStateOf<RectF?>(null)

    fun clear() {
        rect.value = null
    }
}
