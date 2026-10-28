// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * A discarded popup WebView keeps its `DictionaryWebViewState` (and therefore its bridges) alive
 * until the WebView itself is collected, so `destroy()` is what stops the Anki bridge's coroutine
 * work. It runs on the destroy path of `DictionaryPopupWebViewWarmup.recycle`, where a WebView is
 * thrown away rather than re-warmed.
 *
 * Dispatch itself is not testable here: the module has no `kotlinx-coroutines-test`, and
 * `Dispatchers.Main` cannot dispatch on the JVM, so the scope half of `destroy()` is pinned by
 * behaviour on device only. What is asserted is the teardown contract the call site relies on.
 */
class AnkiJsBridgeTeardownTest {

    @Test
    fun `destroy is idempotent`() {
        val bridge = AnkiJsBridge { null }

        bridge.destroy()

        val second = runCatching { bridge.destroy() }
        second.isSuccess shouldBe true
    }

    @Test
    fun `destroy disarms the callback so no card can be created for the dead popup`() {
        val calls = mutableListOf<Int>()
        val bridge = AnkiJsBridge { null }
        bridge.onAnkiLookup = { index, _, _, _, _ -> calls += index }
        calls.isEmpty() shouldBe true

        bridge.destroy()

        bridge.onAnkiLookup shouldBe null
    }
}
// KUN <--
