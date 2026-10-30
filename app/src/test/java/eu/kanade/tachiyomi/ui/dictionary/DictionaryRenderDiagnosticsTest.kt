// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The hidden-document decision and the two one-shot diagnostics, on the JVM. The WebView plumbing
// that runs these (measuring, injecting the guard, recreating a dead renderer's view) is not
// covered here — the module has no Compose/WebView harness; see `DictionaryEntryWebViewTeardownTest`
// for the same limitation stated on the disposal path.
package eu.kanade.tachiyomi.ui.dictionary

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DictionaryRenderDiagnosticsTest {

    /** A render that measured fine: three entries, visible, with real height. */
    private fun visible(entries: Int = 3) = RenderMeasurement(
        entryCount = entries,
        containerHeight = 812,
        containerScrollHeight = 812,
        containerDisplay = "block",
        containerVisibility = "visible",
        containerOpacity = 1f,
        bodyDisplay = "block",
        bodyVisibility = "visible",
        entryHeight = 240,
        entryScrollHeight = 240,
        entryDisplay = "block",
        entryVisibility = "visible",
        entryOpacity = 1f,
    )

    @Test
    fun `a render that measured fine is not reported`() {
        renderVisibilityProblem(expectedEntries = 3, measurement = visible()) shouldBe null
    }

    /**
     * The bug this pins: an empty lookup and the idle placeholder are legitimately an empty
     * document, and the guard forces `#entries` visible. Judging those renders would report a
     * problem on every "No results for …" frame and override the placeholder's own styling.
     */
    @Test
    fun `an empty lookup is never judged hidden`() {
        val emptyDocument = visible(entries = 0).copy(containerHeight = 0, containerScrollHeight = 0)

        renderVisibilityProblem(expectedEntries = 0, measurement = emptyDocument) shouldBe null
        renderVisibilityProblem(expectedEntries = 0, measurement = null) shouldBe null
    }

    /**
     * The reported failure: imported custom CSS is injected verbatim as a global `<style>`, so
     * `#entries{display:none}` hides every entry while the lookup and the render both succeed.
     */
    @Test
    fun `a hidden entries container is reported and repaired`() {
        val hidden = visible().copy(containerDisplay = "none")

        renderVisibilityProblem(expectedEntries = 3, measurement = hidden) shouldBe
            RenderVisibilityProblem("container_hidden", shouldGuard = true)
    }

    @Test
    fun `a hidden body is reported and repaired`() {
        val hidden = visible().copy(bodyDisplay = "none")

        renderVisibilityProblem(expectedEntries = 3, measurement = hidden) shouldBe
            RenderVisibilityProblem("body_hidden", shouldGuard = true)
    }

    @Test
    fun `a zero-opacity container counts as hidden`() {
        val faded = visible().copy(containerOpacity = 0f)

        renderVisibilityProblem(3, faded) shouldBe RenderVisibilityProblem("container_hidden", true)
    }

    /**
     * A container that clips its own content (`height: 0; overflow: hidden`) has a positive
     * `scrollHeight`: the entries are in the DOM and unreachable, which is a different repair story
     * from a container that reports nothing at all. Collapsing the two loses that evidence.
     */
    @Test
    fun `a container that clips its entries is distinguished from an empty one`() {
        renderVisibilityProblem(
            3,
            visible().copy(containerHeight = 0, containerScrollHeight = 640),
        ) shouldBe RenderVisibilityProblem("container_clipped", true)

        renderVisibilityProblem(
            3,
            visible().copy(containerHeight = 0, containerScrollHeight = 0),
        ) shouldBe RenderVisibilityProblem("container_zero_height", true)
    }

    @Test
    fun `a hidden or collapsed entry is reported`() {
        renderVisibilityProblem(
            3,
            visible().copy(entryVisibility = "hidden"),
        ) shouldBe RenderVisibilityProblem("entry_hidden", true)

        renderVisibilityProblem(
            3,
            visible().copy(entryVisibility = "collapse"),
        ) shouldBe RenderVisibilityProblem("entry_hidden", true)

        renderVisibilityProblem(
            3,
            visible().copy(entryDisplay = "none"),
        ) shouldBe RenderVisibilityProblem("entry_hidden", true)

        renderVisibilityProblem(
            3,
            visible().copy(entryHeight = 0, entryScrollHeight = 0),
        ) shouldBe RenderVisibilityProblem("entry_zero_height", true)
    }

    @Test
    fun `entries the frame claims but the dom does not have are reported`() {
        renderVisibilityProblem(
            3,
            visible(entries = 0).copy(entryHeight = -1, entryScrollHeight = -1),
        ) shouldBe RenderVisibilityProblem("entries_missing", true)
    }

    /**
     * Nothing was measured, so nothing may be "repaired": a document whose scripts never ran is not
     * evidence of hidden CSS, and injecting a guard into it would hide the real cause.
     */
    @Test
    fun `an unmeasurable page is reported but not repaired`() {
        renderVisibilityProblem(expectedEntries = 3, measurement = null) shouldBe
            RenderVisibilityProblem("measurement_missing", shouldGuard = false)
    }

    @Test
    fun `a measurement parses from the quoted json string a webview returns`() {
        val raw = "\"{\\\"entry_count\\\":2,\\\"container_h\\\":640,\\\"container_sh\\\":700," +
            "\\\"container_display\\\":\\\"block\\\",\\\"container_visibility\\\":\\\"visible\\\"," +
            "\\\"container_opacity\\\":0.5,\\\"body_display\\\":\\\"block\\\"," +
            "\\\"body_visibility\\\":\\\"visible\\\",\\\"entry_h\\\":300,\\\"entry_sh\\\":300," +
            "\\\"entry_display\\\":\\\"flex\\\",\\\"entry_visibility\\\":\\\"visible\\\"," +
            "\\\"entry_opacity\\\":1}\""

        parseRenderMeasurement(raw) shouldBe RenderMeasurement(
            entryCount = 2,
            containerHeight = 640,
            containerScrollHeight = 700,
            containerDisplay = "block",
            containerVisibility = "visible",
            containerOpacity = 0.5f,
            bodyDisplay = "block",
            bodyVisibility = "visible",
            entryHeight = 300,
            entryScrollHeight = 300,
            entryDisplay = "flex",
            entryVisibility = "visible",
            entryOpacity = 1f,
        )
    }

    @Test
    fun `a measurement parses from an unquoted json object`() {
        parseRenderMeasurement("""{"entry_count":1,"container_h":10}""")?.let { measurement ->
            measurement.entryCount shouldBe 1
            measurement.containerHeight shouldBe 10
        } ?: error("expected an unquoted JSON object to parse")
    }

    /** A dead renderer, a page that threw, or an idle document all read as "not measured". */
    @Test
    fun `an unusable measurement reads as missing`() {
        parseRenderMeasurement(null) shouldBe null
        parseRenderMeasurement("") shouldBe null
        parseRenderMeasurement("null") shouldBe null
        parseRenderMeasurement("\"err:document is gone\"") shouldBe null
        parseRenderMeasurement("42") shouldBe null
    }

    /**
     * The traffic this pins: the custom CSS is the one input that can hide the whole document, so it
     * must appear in the log the first time it is used ("Reset custom CSS" then shows up as
     * `custom_css_len=0`), and two identical renders must not repeat the line.
     */
    @Test
    fun `the custom css length is logged once per distinct value`() {
        val gate = CustomCssLogGate()

        gate.shouldLog(1_284) shouldBe true
        gate.shouldLog(1_284) shouldBe false
        gate.shouldLog(0) shouldBe true
        gate.shouldLog(0) shouldBe false
        gate.shouldLog(512) shouldBe true
    }

    @Test
    fun `the environment line names the webview, its chrome version and the abis`() {
        val line = webViewEnvironmentLine(
            packageName = "com.android.webview",
            versionName = "131.0.6778.200",
            userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel Tablet) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Chrome/131.0.6778.200 Safari/537.36",
            sdkInt = 34,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            device = "Google Pixel Tablet",
        )

        line shouldBe "webview_env package=com.android.webview version=131.0.6778.200 " +
            "chrome=131.0.6778.200 sdk=34 abis=arm64-v8a,armeabi-v7a device=Google Pixel Tablet"
    }

    @Test
    fun `a missing webview package still names the environment`() {
        webViewEnvironmentLine(
            packageName = null,
            versionName = null,
            userAgent = null,
            sdkInt = 26,
            supportedAbis = listOf("x86_64"),
            device = "unknown sdk_phone_x86_64",
        ) shouldBe "webview_env package=unknown version=unknown chrome=unknown sdk=26 " +
            "abis=x86_64 device=unknown sdk_phone_x86_64"
    }

    @Test
    fun `the chrome version token is not taken from an unrelated agent`() {
        chromeVersion("Mozilla/5.0 Chrome/120.0.6099.230 Safari/537.36") shouldBe "120.0.6099.230"
        chromeVersion("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36") shouldBe null
        chromeVersion(null) shouldBe null
    }
}
// KUN <--
