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

    /**
     * A render that measured fine: three entries, visible, with real height, a laid-out document
     * (`layout_h`/`layout_sh` set) and one expanded dictionary group whose gloss is on screen.
     */
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
        layoutHeight = 900,
        documentHeight = 812,
        groupCount = 1,
        groupHeight = 180,
        groupDisplay = "block",
        groupVisibility = "visible",
        groupOpacity = 1f,
        expandedGroups = 1,
        definitionCount = 2,
        visibleDefinitions = 2,
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

    /**
     * The reported symptom: every entry is an `article.entry` whose definitions live in its
     * `.dictionary-group` children, so measuring only `#entries` and `.entry` passed a page where
     * `.dictionary-group{display:none}` showed headwords with nothing under them.
     */
    @Test
    fun `a hidden dictionary group is reported and repaired`() {
        renderVisibilityProblem(3, visible().copy(groupDisplay = "none")) shouldBe
            RenderVisibilityProblem("group_hidden", shouldGuard = true)
        renderVisibilityProblem(3, visible().copy(groupOpacity = 0f)) shouldBe
            RenderVisibilityProblem("group_hidden", shouldGuard = true)
    }

    /** A visible group whose glosses are gone (`.gloss-content{display:none}`) is just as blank. */
    @Test
    fun `an entry with no visible definition rectangle is reported and repaired`() {
        renderVisibilityProblem(3, visible().copy(visibleDefinitions = 0)) shouldBe
            RenderVisibilityProblem("definitions_hidden", shouldGuard = true)
    }

    /**
     * The kanji shape: `article.entry.kanji-entry` carries `.kanji-gloss-content` and no
     * `.dictionary-group` at all, so `expandedGroups` is always 0 and the term-shape rule could never
     * fire — a page whose kanji glosses are `display:none` measured as fine while the guard both
     * measured and repaired that selector. There is no group there to have been collapsed on
     * purpose, so a hidden gloss is a failure on its own.
     */
    @Test
    fun `a kanji entry with no visible gloss is reported and repaired`() {
        val kanjiHidden = visible().copy(
            groupCount = 0,
            groupHeight = -1,
            groupDisplay = "",
            groupVisibility = "",
            groupOpacity = 1f,
            expandedGroups = 0,
            definitionCount = 2,
            visibleDefinitions = 0,
            kanjiGlossCount = 2,
        )

        renderVisibilityProblem(1, kanjiHidden) shouldBe
            RenderVisibilityProblem(RENDER_REASON_KANJI_DEFINITIONS_HIDDEN, shouldGuard = true)
        // The same entry with its glosses on screen is fine.
        renderVisibilityProblem(1, kanjiHidden.copy(visibleDefinitions = 2)) shouldBe null
        // And a kanji entry with no gloss at all is not judged (nothing to show, as for terms).
        renderVisibilityProblem(
            1,
            kanjiHidden.copy(definitionCount = 0, kanjiGlossCount = 0),
        ) shouldBe null
    }

    /**
     * Two ways definitions are legitimately invisible: the user collapsed the group (`base.css`
     * hides `.definition-list` for `[data-collapsed="true"]`) and a result that carries no
     * glossaries at all. Guarding either would expand what the user collapsed.
     */
    @Test
    fun `collapsed groups and glossless entries are not judged`() {
        renderVisibilityProblem(
            3,
            visible().copy(expandedGroups = 0, visibleDefinitions = 0),
        ) shouldBe null
        renderVisibilityProblem(
            3,
            visible().copy(definitionCount = 0, visibleDefinitions = 0),
        ) shouldBe null
    }

    /**
     * Before the first layout pass every `getBoundingClientRect()` is 0, so a perfectly fine page
     * measured as zero-height and was pinned with `!important` — overriding the user's CSS for no
     * reason. Unmeasurable means report, never repair.
     */
    @Test
    fun `an unlaid-out page is reported but not repaired`() {
        renderVisibilityProblem(
            3,
            visible().copy(
                layoutHeight = 0,
                documentHeight = 0,
                containerHeight = 0,
                containerScrollHeight = 0,
                entryHeight = 0,
                entryScrollHeight = 0,
                groupHeight = 0,
                visibleDefinitions = 0,
            ),
        ) shouldBe RenderVisibilityProblem("layout_unmeasurable", shouldGuard = false)
    }

    /** Style is computable before layout, so a hidden body is still repaired on an unlaid-out page. */
    @Test
    fun `a hidden body is repaired even before the page is laid out`() {
        renderVisibilityProblem(
            3,
            visible().copy(layoutHeight = 0, documentHeight = 0, bodyDisplay = "none"),
        ) shouldBe RenderVisibilityProblem("body_hidden", shouldGuard = true)
    }

    /**
     * The headline defect: `contentReady` starts `true` and is force-set true on renderer death, so
     * the old backstop returned before it ever waited — a shell whose `onPageFinished` never ran (a
     * document that failed to load) was the one blank pane nothing ever reported.
     */
    @Test
    fun `a webview whose document never loaded is not ready`() {
        renderNotReadyReason(
            pageReady = false,
            renderDispatched = false,
            renderReachedReady = false,
            elapsedMs = 3_000,
            timeoutMs = 3_000,
        ) shouldBe SURFACE_REASON_NOT_READY
        renderNotReadyReason(
            pageReady = false,
            renderDispatched = true,
            renderReachedReady = false,
            elapsedMs = 9_000,
            timeoutMs = 3_000,
        ) shouldBe SURFACE_REASON_NOT_READY
    }

    /** A document that loaded but whose render never reached the screen is a stall, not a load failure. */
    @Test
    fun `a dispatched render that never reached the screen is stalled`() {
        renderNotReadyReason(
            pageReady = true,
            renderDispatched = true,
            renderReachedReady = false,
            elapsedMs = 3_500,
            timeoutMs = 3_000,
        ) shouldBe SURFACE_REASON_RENDER_STALLED
    }

    @Test
    fun `a surface is not failed before its timeout, once it painted, or while idle`() {
        // Before the deadline nothing is decided, however little has happened.
        renderNotReadyReason(false, false, false, elapsedMs = 2_999, timeoutMs = 3_000) shouldBe null
        // Painted: a slow-but-successful render ends clean.
        renderNotReadyReason(true, true, true, elapsedMs = 10_000, timeoutMs = 3_000) shouldBe null
        // Loaded and nothing asked of it yet (no payload dispatched): an idle surface.
        renderNotReadyReason(true, false, false, elapsedMs = 10_000, timeoutMs = 3_000) shouldBe null
    }

    /**
     * The watchdog used to disarm at its deadline whenever it could not decide — `loadingNow` was
     * true at 3 s for any first lookup slower than that, so the loop ended for good and a shell that
     * never loaded afterwards was reported by nothing.
     */
    @Test
    fun `a lookup in flight extends the watch instead of disarming it, then fails at the cap`() {
        // At the deadline, still loading, and the document never loaded.
        renderWatchDecision(
            pageReady = false,
            renderDispatched = false,
            renderReachedReady = false,
            loading = true,
            elapsedMs = 3_000,
            windowMs = 3_000,
            maxWaitMs = 15_000,
        ) shouldBe RenderWatchDecision(RenderWatchStep.EXTEND)
        // Still loading at the cap: decide on the evidence (the document never loaded).
        renderWatchDecision(
            pageReady = false,
            renderDispatched = false,
            renderReachedReady = false,
            loading = true,
            elapsedMs = 15_000,
            windowMs = 3_000,
            maxWaitMs = 15_000,
        ) shouldBe RenderWatchDecision(RenderWatchStep.FAILED, SURFACE_REASON_NOT_READY)
        // The lookup ended and the document still never loaded: fail now, without waiting for the cap.
        renderWatchDecision(
            pageReady = false,
            renderDispatched = false,
            renderReachedReady = false,
            loading = false,
            elapsedMs = 5_200,
            windowMs = 3_000,
            maxWaitMs = 15_000,
        ) shouldBe RenderWatchDecision(RenderWatchStep.FAILED, SURFACE_REASON_NOT_READY)
        // A render that was dispatched and never painted is a stall, loading or not, at the cap.
        renderWatchDecision(
            pageReady = true,
            renderDispatched = true,
            renderReachedReady = false,
            loading = true,
            elapsedMs = 4_000,
            windowMs = 3_000,
            maxWaitMs = 15_000,
        ) shouldBe RenderWatchDecision(RenderWatchStep.EXTEND)
        renderWatchDecision(
            pageReady = true,
            renderDispatched = true,
            renderReachedReady = false,
            loading = false,
            elapsedMs = 4_000,
            windowMs = 3_000,
            maxWaitMs = 15_000,
        ) shouldBe RenderWatchDecision(RenderWatchStep.FAILED, SURFACE_REASON_RENDER_STALLED)
    }

    /**
     * The other undecidable branch: at the deadline nothing had been dispatched yet because the
     * payload build was slower than the window. That used to read as "no reason" and end the watch;
     * it must stay armed instead, so the render that is dispatched afterwards is covered, and settle
     * quietly at the cap if no payload ever comes (an idle surface, not a broken one).
     */
    @Test
    fun `a payload that has not been dispatched yet extends the watch, then settles`() {
        renderWatchDecision(
            pageReady = true,
            renderDispatched = false,
            renderReachedReady = false,
            loading = false,
            elapsedMs = 3_100,
            windowMs = 3_000,
            maxWaitMs = 15_000,
        ) shouldBe RenderWatchDecision(RenderWatchStep.EXTEND)
        renderWatchDecision(
            pageReady = true,
            renderDispatched = false,
            renderReachedReady = false,
            loading = false,
            elapsedMs = 15_000,
            windowMs = 3_000,
            maxWaitMs = 15_000,
        ) shouldBe RenderWatchDecision(RenderWatchStep.SETTLED)
        // A dispatch that is then painted ends the watch immediately, whenever it arrives.
        renderWatchDecision(
            pageReady = true,
            renderDispatched = true,
            renderReachedReady = true,
            loading = false,
            elapsedMs = 8_000,
            windowMs = 3_000,
            maxWaitMs = 15_000,
        ) shouldBe RenderWatchDecision(RenderWatchStep.SETTLED)
        // Inside the first window nothing is decided at all.
        renderWatchDecision(
            pageReady = false,
            renderDispatched = false,
            renderReachedReady = false,
            loading = true,
            elapsedMs = 2_000,
            windowMs = 3_000,
            maxWaitMs = 15_000,
        ) shouldBe RenderWatchDecision(RenderWatchStep.WAIT)
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
            layoutHeight = -1,
            documentHeight = -1,
            groupCount = 0,
            groupHeight = -1,
            groupDisplay = "",
            groupVisibility = "",
            groupOpacity = 1f,
            expandedGroups = 0,
            definitionCount = 0,
            visibleDefinitions = 0,
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

    /** The kanji-entry field the kanji-shape decision needs, from the same quoted-JSON shape. */
    @Test
    fun `a kanji gloss count parses from the measurement`() {
        parseRenderMeasurement(
            "\"{\\\"entry_count\\\":1,\\\"definition_count\\\":3,\\\"visible_definitions\\\":0," +
                "\\\"kanji_gloss_count\\\":3}\"",
        ) shouldBe RenderMeasurement(
            entryCount = 1,
            containerHeight = -1,
            containerScrollHeight = -1,
            containerDisplay = "",
            containerVisibility = "",
            containerOpacity = 1f,
            bodyDisplay = "",
            bodyVisibility = "",
            entryHeight = -1,
            entryScrollHeight = -1,
            entryDisplay = "",
            entryVisibility = "",
            entryOpacity = 1f,
            layoutHeight = -1,
            documentHeight = -1,
            groupCount = 0,
            groupHeight = -1,
            groupDisplay = "",
            groupVisibility = "",
            groupOpacity = 1f,
            expandedGroups = 0,
            definitionCount = 3,
            visibleDefinitions = 0,
            kanjiGlossCount = 3,
        )
    }

    /**
     * The guard rewrote the definition layout for every reason: `container_hidden` flipped
     * compact-mode glosses (`display:inline`) to `block` while repairing something that was never
     * about them, and left them that way until the next invalidation. Only a definitions repair may
     * touch `display` on the definitions; every other reason keeps to visibility/opacity and the
     * container's clipping.
     */
    @Test
    fun `only a definitions repair forces the definition layout`() {
        val definitionRule =
            "#entries .dictionary-group,#entries .gloss-content,#entries .definition-list," +
                "#entries .kanji-gloss-content{display:block!important"

        for (reason in RENDER_GUARD_DEFINITION_REASONS) {
            renderGuardScript(setOf(reason)).contains(definitionRule).shouldBe(true)
        }

        for (reason in listOf("container_hidden", "body_hidden", "entry_hidden", "entry_zero_height")) {
            val script = renderGuardScript(setOf(reason))
            // No `display` rule for the definitions, and their nodes are not even queried...
            script.contains("gloss-content").shouldBe(false)
            script.contains(definitionRule).shouldBe(false)
            // ...while the container half is still applied, so the repair itself still happens.
            script.contains("#entries{height:auto!important").shouldBe(true)
            script.contains("#entries .entry{height:auto!important").shouldBe(true)
            script.contains("querySelectorAll('#entries,#entries .entry')").shouldBe(true)
            script.contains("setProperty('visibility','visible','important')").shouldBe(true)
        }
    }

    /**
     * The defect this pins: the decision returned the *first* problem only, so custom CSS that hides
     * both `#entries` and `.gloss-content` reported `container_hidden`, injected a container-only
     * guard, and left the glosses hidden — the headwords came back with nothing under them. Both
     * levels have to be reported by one measurement, and the one guard built from them has to repair
     * both.
     */
    @Test
    fun `two hidden levels are both reported by one measurement`() {
        val bothHidden = visible().copy(
            containerDisplay = "none",
            visibleDefinitions = 0,
        )

        renderVisibilityProblems(3, bothHidden).map { it.reason } shouldBe
            listOf("container_hidden", "definitions_hidden")
        // The outermost is still the first, so single-problem callers are unchanged.
        renderVisibilityProblem(3, bothHidden) shouldBe
            RenderVisibilityProblem("container_hidden", shouldGuard = true)

        // One guard built from both reasons carries the container half *and* the definitions half,
        // in one query: a second, quoted argument to `querySelectorAll` is ignored by the browser.
        val script = renderGuardScript(setOf("container_hidden", "definitions_hidden"))
        script.contains(
            "querySelectorAll('#entries,#entries .entry,#entries .dictionary-group," +
                "#entries .gloss-content,#entries .definition-list,#entries .kanji-gloss-content')",
        ) shouldBe true
        script.contains("#entries{height:auto!important") shouldBe true
        script.contains(
            "#entries .kanji-gloss-content{display:block!important;height:auto!important;",
        ) shouldBe true
    }

    /** A container hidden under a hidden body is still two problems, and both are repaired. */
    @Test
    fun `a hidden body and a hidden container are reported together`() {
        val hidden = visible().copy(bodyDisplay = "none", containerVisibility = "hidden")

        renderVisibilityProblems(3, hidden).map { it.reason } shouldBe
            listOf("body_hidden", "container_hidden")
        // Neither touches the definitions, so the guard stays container-only.
        val script = renderGuardScript(setOf("body_hidden", "container_hidden"))
        script.contains("gloss-content") shouldBe false
    }

    /**
     * Before layout, every rect is 0: the geometry checks would report hidden content that is merely
     * unmeasured. Only the style-derived problems are trusted then, and their repair does not touch
     * the definitions (whose invisibility cannot be told from "not laid out yet").
     */
    @Test
    fun `an unlaid-out page reports the style problems and no geometry ones`() {
        val unlaid = visible().copy(
            layoutHeight = 0,
            documentHeight = 0,
            containerHeight = 0,
            entryHeight = 0,
            visibleDefinitions = 0,
            bodyDisplay = "none",
        )

        renderVisibilityProblems(3, unlaid).map { it.reason } shouldBe
            listOf("body_hidden", "layout_unmeasurable")
        renderVisibilityProblems(3, unlaid).count { it.shouldGuard } shouldBe 1
    }

    /**
     * A ready post that arrived *before* the dispatch was armed cannot be that dispatch's paint.
     * The timestamp version credited it whenever the two landed in the same millisecond, which is how
     * one prewarm/media ready settled the watch on a renderer that had returned early.
     */
    @Test
    fun `a ready counted before the dispatch does not prove it painted`() {
        val armed = RenderDispatch(seq = 1, generation = 0, readyPostCount = 4)

        // The same count (a ready that was already there): not painted.
        dispatchPainted(armed, generation = 0, readyPostCount = 4) shouldBe false
        // One counted since: painted.
        dispatchPainted(armed, generation = 0, readyPostCount = 5) shouldBe true
        // A ready from the previous WebView's generation is not this dispatch's paint either.
        dispatchPainted(armed, generation = 1, readyPostCount = 9) shouldBe false
    }

    /**
     * The per-dispatch window is the *render* window (10 s), not the 3 s document-load window: a big
     * entry that takes five seconds to paint must not be declared stalled, and the flat 3 s did
     * exactly that (and the false failure could only be cleared by Retry).
     */
    @Test
    fun `a slow but real render is inside the dispatched window`() {
        RENDER_DISPATCH_TIMEOUT_MS shouldBe 10_000L

        for (elapsed in listOf(3_500L, 5_000L, 9_000L, 9_999L)) {
            renderWatchDecision(
                pageReady = true,
                renderDispatched = true,
                renderReachedReady = false,
                loading = false,
                elapsedMs = elapsed,
                windowMs = RENDER_DISPATCH_TIMEOUT_MS,
                maxWaitMs = RENDER_WATCH_MAX_WAIT_MS,
            ) shouldBe RenderWatchDecision(RenderWatchStep.WAIT)
        }
        // A render that really never finished is reported at the extended deadline, not before.
        renderWatchDecision(
            pageReady = true,
            renderDispatched = true,
            renderReachedReady = false,
            loading = false,
            elapsedMs = RENDER_DISPATCH_TIMEOUT_MS,
            windowMs = RENDER_DISPATCH_TIMEOUT_MS,
            maxWaitMs = RENDER_WATCH_MAX_WAIT_MS,
        ) shouldBe RenderWatchDecision(RenderWatchStep.FAILED, SURFACE_REASON_RENDER_STALLED)
        // And the document-load window is untouched: "never loaded" still fails at its own 3 s
        // deadline, long before the render window (`RENDER_DISPATCH_TIMEOUT_MS` in the surface).
        renderWatchDecision(
            pageReady = false,
            renderDispatched = false,
            renderReachedReady = false,
            loading = false,
            elapsedMs = 3_100,
            windowMs = 3_000,
            maxWaitMs = RENDER_WATCH_MAX_WAIT_MS,
        ) shouldBe RenderWatchDecision(RenderWatchStep.FAILED, SURFACE_REASON_NOT_READY)
        renderWatchDecision(
            pageReady = false,
            renderDispatched = false,
            renderReachedReady = false,
            loading = false,
            elapsedMs = 3_100,
            windowMs = RENDER_DISPATCH_TIMEOUT_MS,
            maxWaitMs = RENDER_WATCH_MAX_WAIT_MS,
        ) shouldBe RenderWatchDecision(RenderWatchStep.WAIT)
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
