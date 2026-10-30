// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.ui

import android.util.Log
import android.webkit.WebView
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Validates the contract of [DictionaryReadyBridge]:
 * - Signals content readiness on the main thread without deadlocking on `postVisualStateCallback`.
 * - Drops callbacks if a new generation started before the posted runnable ran.
 * - Deduplicates the `webview_ready` log to fire at most once per generation.
 * - Forwards JavaScript render error reports.
 */
class DictionaryReadyBridgeTest {
    private val webView: WebView = mockk()

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `contentReady does not use postVisualStateCallback to avoid deadlock`() {
        val file = File("src/main/java/mihon/core/dictionary/ui/DictionaryWebViewBridges.kt")
        val content = if (file.exists()) {
            file.readText()
        } else {
            File("core/dictionary/src/main/java/mihon/core/dictionary/ui/DictionaryWebViewBridges.kt").readText()
        }
        val function = functionBody(content, "fun contentReady()")
        function shouldNotContain "postVisualStateCallback"
    }

    private fun functionBody(source: String, declaration: String): String {
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

    @Test
    fun `contentReady invokes onContentReady when generation matches`() {
        var runnable: Runnable? = null
        every { webView.post(any()) } answers {
            runnable = firstArg<Runnable>()
            true
        }
        every { webView.alpha } returns 1f

        val state = mockk<DictionaryWebViewState>(relaxed = true)
        var readyCalled = 0
        every { state.contentReadyGeneration } returns 1L
        every { state.onContentReady } returns { readyCalled++ }
        every { state.payloadBridge } returns PayloadBridge()

        val bridge = DictionaryReadyBridge(webViewProvider = { webView }, stateProvider = { state })
        bridge.contentReady()

        runnable?.run()
        readyCalled shouldBe 1
    }

    @Test
    fun `contentReady drops callback when generation is stale`() {
        var runnable: Runnable? = null
        every { webView.post(any()) } answers {
            runnable = firstArg<Runnable>()
            true
        }
        every { webView.alpha } returns 1f

        var currentGeneration = 1L
        val state = mockk<DictionaryWebViewState>(relaxed = true)
        var readyCalled = 0
        every { state.contentReadyGeneration } answers { currentGeneration }
        every { state.onContentReady } returns { readyCalled++ }
        every { state.payloadBridge } returns PayloadBridge()

        val bridge = DictionaryReadyBridge(webViewProvider = { webView }, stateProvider = { state })
        bridge.contentReady()

        // Bump generation to simulate a newer render/reload before runnable executed
        currentGeneration = 2L

        runnable?.run()
        readyCalled shouldBe 0
    }

    @Test
    fun `contentReady only logs webview_ready once per generation`() {
        val runnables = mutableListOf<Runnable>()
        every { webView.post(any()) } answers {
            runnables.add(firstArg<Runnable>())
            true
        }
        every { webView.alpha } returns 1f

        val state = mockk<DictionaryWebViewState>(relaxed = true)
        var lastLogged: Long = -1L
        every { state.contentReadyGeneration } returns 1L
        every { state.lastLoggedReadyGeneration } answers { lastLogged }
        every { state.lastLoggedReadyGeneration = any() } answers { lastLogged = firstArg<Long>() }
        every { state.payloadBridge } returns PayloadBridge()

        val bridge = DictionaryReadyBridge(webViewProvider = { webView }, stateProvider = { state })

        // First ready signal
        bridge.contentReady()
        runnables.removeFirst().run()

        // Repeated ready signal with same generation
        bridge.contentReady()
        runnables.removeFirst().run()

        verify(exactly = 1) {
            Log.i("DictionaryRender", match { it.contains("webview_ready") })
        }
    }

    @Test
    fun `reportRenderError posts error message to onRenderError`() {
        var runnable: Runnable? = null
        every { webView.post(any()) } answers {
            runnable = firstArg<Runnable>()
            true
        }

        val state = mockk<DictionaryWebViewState>(relaxed = true)
        var reportedError: String? = null
        every { state.onRenderError } returns { reportedError = it }

        val bridge = DictionaryReadyBridge(webViewProvider = { webView }, stateProvider = { state })
        bridge.reportRenderError("ReferenceError: foo is not defined")

        runnable?.run()
        reportedError shouldBe "ReferenceError: foo is not defined"
    }
}
// KUN <--
