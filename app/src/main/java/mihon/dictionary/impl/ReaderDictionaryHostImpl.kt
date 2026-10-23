// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.dictionary.impl

import android.content.Context
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPopup
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPopupWebViewWarmup
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.dictionary.PopupRequest
import eu.kanade.tachiyomi.ui.reader.ocr.impl.ReaderOcrPopupAnchor
import kotlinx.coroutines.flow.MutableStateFlow
import mihon.dictionary.host.DictionaryLookupRequest
import mihon.dictionary.host.ReaderDictionaryHost

/**
 * GPL implementation of the Apache [ReaderDictionaryHost] seam (contract §1.2/§2.3).
 *
 * It holds the current popup request as Compose state, warms the popup WebView, and renders the
 * [DictionaryPopup] inside the reader's existing overlay. The reader only ever sees the Apache
 * interface; this class and its GPL imports live in the distinct `mihon.dictionary.impl` package so
 * `verifyGplFree` can separate seam from impl.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ReaderDictionaryHostImpl(
    private val preferences: DictionaryPreferences,
    private val popupAnchor: ReaderOcrPopupAnchor,
) : ReaderDictionaryHost {

    private val request = MutableStateFlow<DictionaryLookupRequest?>(null)

    override fun warm(context: Context, languageCode: String) {
        DictionaryPopupWebViewWarmup.warm(context, languageCode)
    }

    override fun showLookup(request: DictionaryLookupRequest) {
        this.request.value = request
    }

    override fun dismiss() {
        request.value = null
    }

    // KUN -->
    override fun popupBounds(): RectF? =
        if (request.value != null) popupAnchor.popupBounds.value else null

    override fun isOpen(): Boolean = request.value != null
    // KUN <--

    @Composable
    override fun Popup() {
        val current by request.collectAsState()
        val lookup = current ?: return
        DictionaryPopup(
            visible = true,
            request = PopupRequest(
                query = lookup.query,
                sentence = lookup.sentence,
                sentenceOffset = lookup.sentenceOffset,
                anchorX = lookup.anchorRect?.left ?: 0f,
                anchorY = lookup.anchorRect?.bottom ?: 0f,
                vertical = lookup.vertical,
                language = lookup.language,
            ),
            profile = preferences.profileStore.getActiveProfile(),
            onDismiss = { dismiss() },
        )
    }
}
