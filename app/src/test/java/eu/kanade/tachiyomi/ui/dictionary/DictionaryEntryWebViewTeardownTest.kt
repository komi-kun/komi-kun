// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// **Source-level teardown guard — not a behavioural test.**
//
// `DictionaryEntryWebView` is a Compose `AndroidView` binding a real `WebView`, and this module has
// no Compose or Robolectric harness, so the `onRelease` callback cannot be invoked from a JVM test.
// What *is* provable is that the disposal path tears the Anki bridge down instead of merely clearing
// its callback: `update` reassigns that callback on the next composition, and the bridge's scope —
// which is what actually delivers a card creation — would survive the page.
//
// The bridge-side contract (`destroy()` is idempotent, and a destroyed bridge stays dead even when
// its callback is re-armed) is covered behaviourally in `:core:dictionary`'s
// `AnkiJsBridgeTeardownTest`. Residual gap: that Compose really calls `onRelease` on disposal is
// Android's guarantee, not something asserted here.
// KUN <--
package eu.kanade.tachiyomi.ui.dictionary

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File

class DictionaryEntryWebViewTeardownTest {

    private val source: String by lazy {
        File("src/main/java/eu/kanade/tachiyomi/ui/dictionary/DictionaryEntryWebView.kt")
            .readText()
            .withoutCommentLines()
    }

    @Test
    fun `the entry web view destroys the anki bridge when it is disposed`() {
        val release = blockAt("onRelease = {")

        release shouldMatchSource """ankiJsBridge\s*\?\s*\.\s*destroy\(\s*\)"""
        // Clearing the callback is not a teardown: the `update` block sets it again, so leaving it
        // as the only action in `onRelease` is exactly the defect this pins.
        release shouldNotMatchSource """ankiJsBridge\s*\?\s*\.\s*onAnkiLookup\s*="""
    }

    /** Source from [needle] to the end of its enclosing lambda (brace counting, no fixed layout). */
    private fun blockAt(needle: String): String {
        val start = source.indexOf(needle)
        if (start < 0) fail("missing call site in DictionaryEntryWebView.kt: $needle")
        var depth = 0
        for (index in start until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        fail("unterminated block for: $needle")
        error("unreachable")
    }
}

/** [pattern] must occur somewhere in this source. */
private infix fun String.shouldMatchSource(pattern: String) {
    if (!Regex(pattern).containsMatchIn(this)) fail("expected the source to contain /$pattern/")
}

/** The mirror: [pattern] must not occur anywhere in this source. */
private infix fun String.shouldNotMatchSource(pattern: String) {
    if (Regex(pattern).containsMatchIn(this)) fail("expected the source NOT to contain /$pattern/")
}

/** Drops `//` line comments, so the file's own prose cannot satisfy a needle. */
private fun String.withoutCommentLines(): String = lines()
    .filterNot { it.trimStart().startsWith("//") }
    .joinToString("\n")
