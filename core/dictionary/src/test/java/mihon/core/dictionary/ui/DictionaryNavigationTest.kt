// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.ui

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A definition link in a Jitendex/Wiktionary-style glossary (`<a href="https://…">`) used to fall
 * through `shouldOverrideUrlLoading` as `false`, so the WebView navigated the popup shell itself
 * away — under `blockNetworkLoads=true` that leaves a dead document, and because the shell is only
 * reloaded when `bootstrapHtml` changes, every later lookup rendered into it until process death.
 *
 * The wiring half is pinned against the source: a `WebViewClient` cannot be exercised on the JVM.
 */
class DictionaryNavigationTest {
    private val source = File("src/main/java/mihon/core/dictionary/ui/DictionaryWebView.kt").readText()

    @Test
    fun `the popup webview never navigates itself away`() {
        val override = functionBody("override fun shouldOverrideUrlLoading(")

        override shouldNotContain "return false"
        override shouldContain "classifyDictionaryNavigation("
        override shouldContain "openExternalUrl("
    }

    @Test
    fun `komikun urls stay inside the popup`() {
        classifyDictionaryNavigation("komikun://lookup?q=%E6%AD%A6") shouldBe DictionaryNavigation.INTERNAL
        classifyDictionaryNavigation("KOMIKUN://tab?index=2") shouldBe DictionaryNavigation.INTERNAL
    }

    @Test
    fun `definition links are handed to an external app instead of the popup`() {
        classifyDictionaryNavigation("https://jisho.org/search/%E6%AD%A6") shouldBe DictionaryNavigation.EXTERNAL
        classifyDictionaryNavigation("http://example.invalid/entry") shouldBe DictionaryNavigation.EXTERNAL
        classifyDictionaryNavigation("HTTPS://EXAMPLE.INVALID/ENTRY") shouldBe DictionaryNavigation.EXTERNAL
    }

    @Test
    fun `everything else is consumed without navigating`() {
        for (url in listOf(
            "javascript:alert(document.cookie)",
            "data:text/html,<script>alert(1)</script>",
            "file:///data/data/app.komikun/files/glossary.html",
            "content://com.android.providers.downloads.documents/1",
            "blob:https://komikun.local/popup/1",
            "intent://scan/#Intent;scheme=zxing;end",
            "about:blank",
            "not a url",
            "",
        )) {
            classifyDictionaryNavigation(url) shouldBe DictionaryNavigation.DROP
        }
        classifyDictionaryNavigation(null) shouldBe DictionaryNavigation.DROP
    }

    /** Source of `declaration` up to its balanced closing brace. */
    private fun functionBody(declaration: String): String {
        val start = source.indexOf(declaration)
        check(start >= 0) { "$declaration is missing from DictionaryWebView.kt" }
        var depth = 0
        for (index in start..source.lastIndex) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("unbalanced braces after $declaration")
    }
}
// KUN <--
