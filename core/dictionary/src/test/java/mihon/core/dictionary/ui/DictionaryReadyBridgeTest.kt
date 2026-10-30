// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.ui

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Validates the contract of [DictionaryReadyBridge]:
 * Signals content readiness on the main looper without deadlocking on `postVisualStateCallback`.
 *
 * In Android hardware-accelerated rendering, views with `alpha == 0f` skip `onDraw()`,
 * which deadlocks `postVisualStateCallback` because Chromium waits for a frame draw that
 * never happens. Signaling directly via `webView.post` avoids this deadlock.
 */
class DictionaryReadyBridgeTest {
    private val source = File("src/main/java/mihon/core/dictionary/ui/DictionaryWebViewBridges.kt").readText()

    @Test
    fun `contentReady does not use postVisualStateCallback to avoid deadlock`() {
        val function = functionBody("fun contentReady()")
        function shouldNotContain "postVisualStateCallback"
        function shouldContain "stateProvider()?.onContentReady?.invoke()"
        function shouldContain "state.contentReadyGeneration != generation"
    }

    private fun functionBody(declaration: String): String {
        val start = source.indexOf(declaration)
        check(start >= 0) { "$declaration is missing from DictionaryWebViewBridges.kt" }
        val open = source.indexOf('{', start)
        var depth = 1
        var i = open + 1
        while (i < source.length && depth > 0) {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return source.substring(open, i)
    }
}
// KUN <--
