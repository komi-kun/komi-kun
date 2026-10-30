// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.ui

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Test

/**
 * A discarded popup WebView keeps its `DictionaryWebViewState` (and therefore its bridges) alive
 * until the WebView itself is collected, so `destroy()` is what stops the Anki bridge's coroutine
 * work. It runs on the destroy path of `DictionaryPopupWebViewWarmup.recycle`, where a WebView is
 * thrown away rather than re-warmed.
 *
 * What is asserted is the contract the call site relies on, through the bridge's public API: a card
 * creation requested after `destroy()` must not reach the lookup callback, because that callback is
 * what creates the card for a document that no longer exists. The bridge takes its dispatcher as a
 * parameter, so these tests run the lookup coroutine immediately (the JVM has no `Dispatchers.Main`)
 * and the armed case is a real positive control rather than an untaken branch.
 */
class AnkiJsBridgeTeardownTest {

    @Test
    fun `destroy is idempotent`() {
        val bridge = AnkiJsBridge(webViewProvider = { null }, dispatcher = Dispatchers.Unconfined)

        bridge.destroy()

        val second = runCatching { bridge.destroy() }
        second.isSuccess shouldBe true
    }

    /**
     * The defect this pins: `destroy()` only cancelled the scope, so a recycled popup could still
     * complete a card creation for its dead document. Following `onAnkiLookup` — the only thing a
     * card creation side effect comes from — shows the request is dropped instead.
     */
    @Test
    fun `addToAnki after destroy requests no lookup`() {
        val lookups = mutableListOf<Pair<Int, Boolean>>()
        val bridge = AnkiJsBridge(webViewProvider = { null }, dispatcher = Dispatchers.Unconfined)
        bridge.onAnkiLookup = { index, _, _, _, fromOpen -> lookups += index to fromOpen }

        // Positive control: while the bridge is armed, both entry points really do request a lookup,
        // so the assertions below are not satisfied by a coroutine that never runs.
        bridge.addToAnki(index = "7", glossary = "0", selectedDict = "dict", popupSelection = "")
        bridge.openInAnki(index = "8", glossary = "0", selectedDict = "dict", popupSelection = "")
        lookups shouldBe listOf(7 to false, 8 to true)

        bridge.destroy()

        bridge.addToAnki(index = "9", glossary = "0", selectedDict = "dict", popupSelection = "")
        bridge.openInAnki(index = "10", glossary = "0", selectedDict = "dict", popupSelection = "")
        // No card creation was requested for the dead popup — for either entry point.
        lookups shouldBe listOf(7 to false, 8 to true)
    }

    /**
     * The defect this pins: teardown that only clears `onAnkiLookup` is undone by the next wiring
     * pass — every `DictionaryEntryWebView` `update` reassigns the callback for the results it is
     * showing — so a discarded page would start creating cards again. `destroy()` cancels the
     * bridge's scope too, so re-arming the callback cannot bring the dead page back.
     */
    @Test
    fun `re-arming the callback after destroy does not revive the bridge`() {
        val lookups = mutableListOf<Int>()
        val bridge = AnkiJsBridge(webViewProvider = { null }, dispatcher = Dispatchers.Unconfined)

        bridge.destroy()
        bridge.onAnkiLookup = { index, _, _, _, _ -> lookups += index }

        bridge.addToAnki(index = "3", glossary = "0", selectedDict = "dict", popupSelection = "")

        lookups shouldBe emptyList<Int>()
    }
}
// KUN <--
