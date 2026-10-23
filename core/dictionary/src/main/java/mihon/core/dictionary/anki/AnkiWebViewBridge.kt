// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Adapted from Chimahon's `AnkiWebViewBridge.kt` (GPL-3.0). Resolves the renderer's numeric
// `addToAnki(resultIndex, glossaryIndex)` call to the pending [LookupResult].
package mihon.core.dictionary.anki

import android.webkit.JavascriptInterface
import mihon.core.dictionary.model.LookupResult

/** Direct bridge (callback fixed at construction). */
class AnkiWebViewBridge(
    private val resultsProvider: () -> List<LookupResult>,
    private val onAddToAnki: (LookupResult, Int?) -> Unit,
) {
    @JavascriptInterface
    fun addToAnki(resultIndexStr: String, glossaryIndexStr: String) {
        val resultIndex = resultIndexStr.toIntOrNull() ?: return
        val glossaryIndex = glossaryIndexStr.toIntOrNull()
        val results = resultsProvider()
        if (resultIndex in results.indices) onAddToAnki(results[resultIndex], glossaryIndex)
    }
}

/**
 * Stable bridge registered on the WebView once; delegates to a mutable callback updated on
 * recompose so the WebView never holds a stale reference.
 */
class DelegatingWebViewBridge(
    private val resultsProvider: () -> List<LookupResult>,
) {
    var onAddToAnki: ((LookupResult, Int?) -> Unit)? = null

    @JavascriptInterface
    fun addToAnki(resultIndexStr: String, glossaryIndexStr: String) {
        val resultIndex = resultIndexStr.toIntOrNull() ?: return
        val glossaryIndex = glossaryIndexStr.toIntOrNull()
        val results = resultsProvider()
        if (resultIndex in results.indices) onAddToAnki?.invoke(results[resultIndex], glossaryIndex)
    }
}
// KUN <--
