// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.ui

import android.util.Log
import android.webkit.WebView
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

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
