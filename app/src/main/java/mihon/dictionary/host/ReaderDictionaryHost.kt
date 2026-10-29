// SPDX-License-Identifier: Apache-2.0
package mihon.dictionary.host

import android.content.Context
import android.graphics.RectF
import androidx.compose.runtime.Composable

/**
 * Apache-2.0 reader→dictionary seam (contract §1.2/§2.3). GPL-free: the GPL impl
 * (`mihon.dictionary.impl.ReaderDictionaryHostImpl`) is obtained only through this interface.
 */
interface ReaderDictionaryHost {
    @Composable fun Popup()

    fun warm(context: Context, languageCode: String)

    fun showLookup(request: DictionaryLookupRequest)

    fun dismiss()

    // KUN -->
    /**
     * The visible popup's bounds in window-local coordinates anchored to the Compose content root
     * (`activity.composeOverlayRoot`), or null when no popup is shown. The Apache-headed reader uses
     * it to dismiss the non-modal popup on an outside tap without installing a gesture-swallowing
     * scrim.
     */
    fun popupBounds(): RectF?

    /**
     * Whether a lookup popup is currently shown. The reader's OCR host uses it to notice a close it
     * did not initiate (outside tap, back) so it can restore the page position.
     */
    fun isOpen(): Boolean
    // KUN <--
}

/** The single OCR→dictionary DTO (contract §2.3). */
data class DictionaryLookupRequest(
    val query: String,
    val sentence: String,
    val sentenceOffset: Int,
    val anchorRect: RectF?,
    val vertical: Boolean,
    val language: String,
)
