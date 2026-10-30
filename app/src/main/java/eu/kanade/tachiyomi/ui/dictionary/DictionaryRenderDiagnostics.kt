// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package eu.kanade.tachiyomi.ui.dictionary

import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import mihon.core.dictionary.ui.DictionaryWebViewState
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.atomic.AtomicBoolean

// Native-side evidence that a dictionary render is *visible*, not merely finished.
//
// `contentReady` says the renderer finished; it cannot say that the result is on screen. The
// remaining ways an entry surface stays blank all look identical from Kotlin - a lookup that ran and
// produced entries, a render that completed, and an empty pane:
//
//  1. the renderer process died (nothing can be scripted into that document ever again);
//  2. imported custom CSS hid the document (it is injected verbatim as a global `<style>`, so it
//     bypasses the markup sanitiser and can say `#entries{display:none}`);
//  3. the WebView implementation itself (the thing that differs between devices).
//
// The decisions here are pure so they can be tested on the JVM; the WebView plumbing stays in
// `DictionaryEntryWebView`.

/** Reason token for a renderer process that stopped responding. */
internal const val RENDER_REASON_UNRESPONSIVE = "renderer_unresponsive"

/** Reason token for a renderer process Android reports as crashed. */
internal const val RENDER_REASON_CRASHED = "renderer_crashed"

/** `renderFailure`-style reason for a renderer the system killed (memory pressure). */
internal const val RENDER_REASON_KILLED = "renderer_killed"

/** Surface reason for a renderer that died once too often to be recreated again. */
internal const val RENDER_REASON_EXHAUSTED = "renderer_exhausted"

/**
 * `render_hidden` reason for a kanji entry whose `.kanji-gloss-content` nodes are all invisible: the
 * glosses are the whole content of that shape, and there is no group to have been collapsed.
 */
internal const val RENDER_REASON_KANJI_DEFINITIONS_HIDDEN = "kanji_definitions_hidden"

/** How many times one surface recreates its WebView before it draws a native failure instead. */
internal const val MAX_RENDERER_RECOVERIES = 2

/**
 * What the page reports about itself after `contentReady`. Heights are CSS pixels (the WebView's
 * device-independent viewport), `-1` when the element does not exist.
 *
 * [layoutHeight]/[documentHeight] are the document element's `clientHeight`/`scrollHeight`: they are
 * the only fields that say whether the page has been laid out at all, since every `getBoundingClientRect`
 * is `0` before the first layout pass (a page that is perfectly fine would otherwise be "measured" as
 * zero-height and painted with the visibility guard).
 *
 * [groupCount]/[groupHeight]/[groupDisplay]… and [definitionCount]/[visibleDefinitions] describe the
 * first entry's *definitions*: an entry is an `article.entry` whose `div.dictionary-group` children
 * carry the glosses, so `#entries .entry` can be measured visible while every definition inside it is
 * `display:none` — headwords with nothing under them.
 */
internal data class RenderMeasurement(
    val entryCount: Int,
    val containerHeight: Int,
    val containerScrollHeight: Int,
    val containerDisplay: String,
    val containerVisibility: String,
    val containerOpacity: Float,
    val bodyDisplay: String,
    val bodyVisibility: String,
    val entryHeight: Int,
    val entryScrollHeight: Int,
    val entryDisplay: String,
    val entryVisibility: String,
    val entryOpacity: Float,
    val layoutHeight: Int,
    val documentHeight: Int,
    val groupCount: Int,
    val groupHeight: Int,
    val groupDisplay: String,
    val groupVisibility: String,
    val groupOpacity: Float,
    /** Groups the renderer left expanded; a collapsed group hides its definitions by design. */
    val expandedGroups: Int,
    val definitionCount: Int,
    /** Definition containers inside the first entry with a visible, non-zero text rectangle. */
    val visibleDefinitions: Int,
    /**
     * `.kanji-gloss-content` nodes inside the first entry. A kanji entry
     * (`article.entry.kanji-entry`) carries these and **no** `.dictionary-group`, so [expandedGroups]
     * says nothing about it: there is no group that could have been collapsed on purpose.
     */
    val kanjiGlossCount: Int = 0,
)

/**
 * A render that finished but cannot be seen: [reason] is the log token, [shouldGuard] says whether
 * forcing the entries visible is the right repair (it is not when nothing could be measured — the
 * page may be a document that never loaded, and injecting CSS into it would prove nothing).
 */
internal data class RenderVisibilityProblem(val reason: String, val shouldGuard: Boolean)

/**
 * The hidden-document decision, in one place and free of Android types: **every** way the measured
 * frame fails to show the entry, outermost first.
 *
 * Only fires when the frame was supposed to draw [expectedEntries] entries: an empty lookup or the
 * idle placeholder is legitimately a short/empty document, and overriding it would be a lie. A
 * visible entry is any height ≥ 1 px, since `getBoundingClientRect()` is rounded to whole pixels.
 *
 * All of them, not only the first: one measurement can find more than one hidden level — custom CSS
 * that says `#entries{display:none}` *and* `.gloss-content{display:none}` hides both — and repairing
 * only the outermost left the rest hidden, so the guard has to be built from the whole set.
 */
internal fun renderVisibilityProblems(
    expectedEntries: Int,
    measurement: RenderMeasurement?,
): List<RenderVisibilityProblem> {
    if (expectedEntries <= 0) return emptyList()
    val m = measurement
        ?: return listOf(RenderVisibilityProblem("measurement_missing", shouldGuard = false))
    val problems = mutableListOf<RenderVisibilityProblem>()
    if (isHidden(m.bodyDisplay, m.bodyVisibility, 1f)) {
        problems += RenderVisibilityProblem("body_hidden", shouldGuard = true)
    }
    if (isHidden(m.containerDisplay, m.containerVisibility, m.containerOpacity)) {
        problems += RenderVisibilityProblem("container_hidden", shouldGuard = true)
    }
    if (m.entryCount <= 0) {
        // The frame claims entries but the DOM has none: the renderer drew nothing at all, so
        // nothing measured inside an entry can say anything more.
        problems += RenderVisibilityProblem("entries_missing", shouldGuard = true)
        return problems
    }
    if (isHidden(m.entryDisplay, m.entryVisibility, m.entryOpacity)) {
        problems += RenderVisibilityProblem("entry_hidden", shouldGuard = true)
    }
    if (isHidden(m.groupDisplay, m.groupVisibility, m.groupOpacity)) {
        // The entry article is visible but the group that carries its glosses is not: the user gets
        // a headword and nothing under it.
        problems += RenderVisibilityProblem("group_hidden", shouldGuard = true)
    }
    // Computed styles are valid before layout, rect heights are not: a document whose client and
    // scroll height are both 0 has not been laid out yet, so the height and definition checks below
    // would read 0 for a page that may be perfectly fine. Report that instead of pinning the page
    // with `!important`, and judge nothing that needs geometry.
    if (m.layoutHeight < 1 && m.documentHeight < 1) {
        problems += RenderVisibilityProblem("layout_unmeasurable", shouldGuard = false)
        return problems
    }
    if (m.containerHeight < 1) {
        problems += RenderVisibilityProblem(
            // Entries exist but the container clips them, versus a container that reports nothing.
            if (m.containerScrollHeight >= 1) "container_clipped" else "container_zero_height",
            shouldGuard = true,
        )
    }
    if (m.entryHeight < 1 && m.entryScrollHeight < 1) {
        problems += RenderVisibilityProblem("entry_zero_height", shouldGuard = true)
    }
    // Definitions hidden inside an entry that is itself visible. A term entry hides them on purpose
    // when the user collapsed its group (`base.css`), so that shape is only judged when the renderer
    // left at least one group expanded. A kanji entry has no `.dictionary-group` at all — its glosses
    // are `.kanji-gloss-content` under the entry — so nothing there could have been collapsed and a
    // hidden gloss is always a failure.
    if (m.definitionCount > 0 && m.visibleDefinitions <= 0) {
        if (m.kanjiGlossCount > 0) {
            problems += RenderVisibilityProblem(RENDER_REASON_KANJI_DEFINITIONS_HIDDEN, shouldGuard = true)
        } else if (m.expandedGroups > 0) {
            problems += RenderVisibilityProblem("definitions_hidden", shouldGuard = true)
        }
    }
    return problems
}

/** The outermost problem of [renderVisibilityProblems], for a caller that reports only one. */
internal fun renderVisibilityProblem(
    expectedEntries: Int,
    measurement: RenderMeasurement?,
): RenderVisibilityProblem? = renderVisibilityProblems(expectedEntries, measurement).firstOrNull()

/** `renderFailure`/surface reason for a WebView whose document never finished loading. */
internal const val SURFACE_REASON_NOT_READY = "webview_not_ready"

/** Surface reason for a document that loaded but whose render never reported ready. */
internal const val SURFACE_REASON_RENDER_STALLED = "render_stalled"

/**
 * The one decision behind the Compose failure surface: given how far a *single WebView instance* got
 * [timeoutMs] after it was created, why is it not showing anything?
 *
 * `null` means the surface is fine (or has not had its time yet). [renderReachedReady] is the only
 * proof that a render reached the screen — a `renderDispatched` renderer that never posted ready is
 * the stall that used to leave a permanently transparent pane, and a document that never loaded
 * (`!pageReady`) cannot even be scripted, so it can never post anything at all. Waiting on
 * `renderDispatched` keeps an idle surface (no payload yet, nothing asked of it) out of it.
 */
internal fun renderNotReadyReason(
    pageReady: Boolean,
    renderDispatched: Boolean,
    renderReachedReady: Boolean,
    elapsedMs: Long,
    timeoutMs: Long,
): String? {
    if (elapsedMs < timeoutMs) return null
    return when {
        !pageReady -> SURFACE_REASON_NOT_READY
        renderDispatched && !renderReachedReady -> SURFACE_REASON_RENDER_STALLED
        else -> null
    }
}

/**
 * The longest the readiness watch stays armed when its first window expired before a decision was
 * possible (a lookup still in flight, a payload still being built). Past this it decides on the
 * evidence it has, so a broken surface cannot hide behind "not decided yet" forever.
 */
internal const val RENDER_WATCH_MAX_WAIT_MS = 15_000L

/**
 * How long a *dispatched* render has to reach the screen before the surface calls it stalled.
 *
 * Longer than the creation window on purpose: a big entry with many glosses and images can render
 * for several seconds, and failing it at the creation window's 3 s was a false positive on a render
 * that was working. A stall that is still reported is no longer destructive either (the WebView
 * stays alive and a late ready restores it), so the cost of waiting is a message that arrives later,
 * not a lost pane.
 */
internal const val RENDER_DISPATCH_TIMEOUT_MS = 10_000L

/**
 * One render payload handed to a WebView document, as the per-dispatch readiness watch sees it.
 *
 * [readyPostCount] is how many `contentReady` posts had been counted when this dispatch was armed,
 * so a ready post only proves *this* render painted when the count is higher than that: a ready that
 * arrived before the dispatch (a font prewarm, a media-URI patch) is already inside the snapshot and
 * cannot be credited to it. Counters rather than timestamps because `elapsedRealtime` cannot order a
 * ready that landed in the same millisecond as the arming against it, while a counter can — as long
 * as it stays monotonic, so a WebView recreation must not reset it. `seq` makes two dispatches of the
 * same generation distinct state, so the watch is re-armed for each.
 */
internal data class RenderDispatch(
    val seq: Int,
    val generation: Int,
    val readyPostCount: Int,
)

/** Whether [dispatch] has painted on the WebView of [generation]: a ready post counted since it. */
internal fun dispatchPainted(
    dispatch: RenderDispatch,
    generation: Int,
    readyPostCount: Int,
): Boolean = dispatch.generation == generation && readyPostCount > dispatch.readyPostCount

/** What one poll of the readiness watch does. */
internal enum class RenderWatchStep {
    /** The window has not expired: poll again. */
    WAIT,

    /**
     * The window expired but nothing can be decided yet: stay armed (up to
     * [RENDER_WATCH_MAX_WAIT_MS]). This is the case the watch used to disarm on, which is how a
     * silent blank survived: at the 3 s deadline a first lookup is still in flight (`loading`) and a
     * payload build slower than the window has not dispatched anything, and both read as "no
     * decision", not as "fine".
     */
    EXTEND,

    /** Stop watching with nothing to report: the surface painted, is idle, or states its own blank. */
    SETTLED,

    /** Stop watching and draw the failure surface with [RenderWatchDecision.reason]. */
    FAILED,
}

internal data class RenderWatchDecision(val step: RenderWatchStep, val reason: String? = null)

/**
 * The one decision behind the readiness watch, per poll.
 *
 * [renderNotReadyReason] answers "is this instance broken *now*"; this answers "should the watch act
 * on that, or wait for a decision to become possible". Returning on `reason == null` was the bug: an
 * undecidable surface (a lookup in flight, a payload not dispatched yet) ended the watch for good, so
 * a stall that began afterwards was never reported by anything.
 */
internal fun renderWatchDecision(
    pageReady: Boolean,
    renderDispatched: Boolean,
    renderReachedReady: Boolean,
    loading: Boolean,
    elapsedMs: Long,
    windowMs: Long,
    maxWaitMs: Long,
): RenderWatchDecision {
    if (elapsedMs < windowMs) return RenderWatchDecision(RenderWatchStep.WAIT)
    val reason = renderNotReadyReason(pageReady, renderDispatched, renderReachedReady, elapsedMs, windowMs)
    if (reason == null) {
        // Nothing was dispatched and nothing painted: the payload may still be building (a build
        // slower than the window) — or this surface is simply idle and was never asked to render.
        // Only the cap tells those apart, so keep the watch armed until it does.
        return if (!renderDispatched && !renderReachedReady && elapsedMs < maxWaitMs) {
            RenderWatchDecision(RenderWatchStep.EXTEND)
        } else {
            RenderWatchDecision(RenderWatchStep.SETTLED)
        }
    }
    // A lookup in flight is a stated blank: the caller draws its own loading state, and the shell may
    // still be loading. Extending the window is what stops the failure from being either missed (the
    // old `return` when `loadingNow`) or shouted while the user is being told to wait.
    if (loading && elapsedMs < maxWaitMs) return RenderWatchDecision(RenderWatchStep.EXTEND)
    return RenderWatchDecision(RenderWatchStep.FAILED, reason)
}

/** Whether a computed `display`/`visibility`/`opacity` triple takes an element out of sight. */
internal fun isHidden(display: String, visibility: String, opacity: Float): Boolean =
    display.equals("none", ignoreCase = true) ||
        visibility.equals("hidden", ignoreCase = true) ||
        visibility.equals("collapse", ignoreCase = true) ||
        opacity < 0.05f

/**
 * Asks the page to report its own geometry and computed style for the document, `main#entries`, the
 * first `.entry` and the definitions inside it (`.dictionary-group` / `.gloss-content`), plus whether
 * the document has been laid out at all.
 *
 * Returns a JSON string (the WebView hands back the expression's value), or `err:…` when the page
 * threw — a document whose scripts failed cannot be measured, and saying so beats guessing.
 */
internal const val RENDER_MEASURE_SCRIPT: String =
    "(function(){" +
        "try{" +
        "var doc=document.documentElement;" +
        "var main=document.getElementById('entries');" +
        "var body=document.body;" +
        "var entries=document.querySelectorAll('#entries .entry');" +
        "var first=entries.length?entries[0]:null;" +
        "function cs(n){return (n&&window.getComputedStyle)?window.getComputedStyle(n):null;}" +
        "function h(n){return (n&&n.getBoundingClientRect)?Math.round(n.getBoundingClientRect().height):-1;}" +
        "function w(n){return (n&&n.getBoundingClientRect)?Math.round(n.getBoundingClientRect().width):-1;}" +
        "function sh(n){return n?Math.round(n.scrollHeight||0):-1;}" +
        "function off(c){return !!c&&(c.display==='none'||c.visibility==='hidden'||" +
        "c.visibility==='collapse'||parseFloat(c.opacity)<0.05);}" +
        "var groups=first?first.querySelectorAll('.dictionary-group'):[];" +
        "var g0=groups.length?groups[0]:null;" +
        "var defs=first?first.querySelectorAll(" +
        "'.dictionary-group .gloss-content,.kanji-gloss-content'):[];" +
        "var kanjiGlosses=first?first.querySelectorAll('.kanji-gloss-content'):[];" +
        "var visibleDefs=0;" +
        "for(var i=0;i<defs.length;i++){var d=defs[i];if(off(cs(d))){continue;}" +
        "if(h(d)>=1&&w(d)>=1){visibleDefs++;}}" +
        "var expanded=0;" +
        "for(var j=0;j<groups.length;j++){" +
        "if(groups[j].dataset&&groups[j].dataset.collapsed!=='true'){expanded++;}}" +
        "var mc=cs(main),bc=cs(body),fc=cs(first),gc=cs(g0);" +
        "return JSON.stringify({" +
        "entry_count:entries.length," +
        "container_h:h(main)," +
        "container_sh:sh(main)," +
        "container_display:(mc?String(mc.display):'')," +
        "container_visibility:(mc?String(mc.visibility):'')," +
        "container_opacity:(mc?parseFloat(mc.opacity):1)," +
        "body_display:(bc?String(bc.display):'')," +
        "body_visibility:(bc?String(bc.visibility):'')," +
        "entry_h:h(first)," +
        "entry_sh:sh(first)," +
        "entry_display:(fc?String(fc.display):'')," +
        "entry_visibility:(fc?String(fc.visibility):'')," +
        "entry_opacity:(fc?parseFloat(fc.opacity):1)," +
        "layout_h:(doc?doc.clientHeight:-1)," +
        "layout_sh:(doc?doc.scrollHeight:-1)," +
        "group_count:groups.length," +
        "group_h:h(g0)," +
        "group_display:(gc?String(gc.display):'')," +
        "group_visibility:(gc?String(gc.visibility):'')," +
        "group_opacity:(gc?parseFloat(gc.opacity):1)," +
        "expanded_groups:expanded," +
        "definition_count:defs.length," +
        "visible_definitions:visibleDefs," +
        "kanji_gloss_count:kanjiGlosses.length" +
        "});" +
        "}catch(e){return 'err:'+e.message;}" +
        "})()"

/**
 * Every node the guard's container half forces visible, and that its removal restores.
 *
 * Unquoted on purpose: the two halves are joined into one `querySelectorAll` argument, and a quoted
 * half would become a second, ignored argument — the definitions were never queried that way.
 */
private const val RENDER_GUARD_CONTAINER_NODES: String =
    "#entries,#entries .entry"

/** The nodes only a definitions repair touches. */
private const val RENDER_GUARD_DEFINITION_NODES: String =
    "#entries .dictionary-group,#entries .gloss-content,#entries .definition-list," +
        "#entries .kanji-gloss-content"

/** Container CSS of the guard: heights/clipping only, so it never rewrites an element's `display`. */
private const val RENDER_GUARD_CONTAINER_CSS: String =
    "'#entries{height:auto!important;max-height:none!important;overflow:visible!important;}'" +
        "+'#entries .entry{height:auto!important;overflow:visible!important;}'"

/** Definition CSS of the guard: the only rule that sets `display`, and only for the shapes that need it. */
private const val RENDER_GUARD_DEFINITION_CSS: String =
    "'#entries .dictionary-group,#entries .gloss-content,#entries .definition-list," +
        "#entries .kanji-gloss-content{display:block!important;height:auto!important;" +
        "max-height:none!important;overflow:visible!important;}'"

/**
 * The reasons whose repair has to touch the definition containers at all.
 *
 * Every other reason (a hidden body, container or entry) is repaired with visibility/opacity and the
 * container's clipping only: forcing `display:block` on `.gloss-content` for those flips compact-mode
 * glosses (`display:inline`, `base.css`) to block until the next invalidation, i.e. it rewrites the
 * user's layout to repair something unrelated.
 */
internal val RENDER_GUARD_DEFINITION_REASONS: Set<String> = setOf(
    "group_hidden",
    "definitions_hidden",
    RENDER_REASON_KANJI_DEFINITIONS_HIDDEN,
)

/**
 * The repair for a measured-hidden render, for every reason in [reasons]: force the document, the
 * entries container and every entry visible, and — only when the definitions themselves were one of
 * the problems — their containers too. Applied as inline `!important`, which outranks a stylesheet
 * `!important` (including the custom CSS that caused this) while leaving the rest of the custom CSS
 * alone. Idempotent, and undoable by [RENDER_GUARD_REMOVE_SCRIPT].
 *
 * The whole set at once, because one injected guard per generation is all there is: a guard built
 * from only the first reason repaired the container and left the glosses hidden by a second rule
 * until the next invalidation.
 */
internal fun renderGuardScript(reasons: Collection<String>): String {
    val withDefinitions = reasons.any { it in RENDER_GUARD_DEFINITION_REASONS }
    val nodes = if (withDefinitions) {
        RENDER_GUARD_CONTAINER_NODES + "," + RENDER_GUARD_DEFINITION_NODES
    } else {
        RENDER_GUARD_CONTAINER_NODES
    }
    val css = if (withDefinitions) {
        RENDER_GUARD_CONTAINER_CSS + "+" + RENDER_GUARD_DEFINITION_CSS
    } else {
        RENDER_GUARD_CONTAINER_CSS
    }
    return "(function(){" +
        "var s=document.getElementById('komikun-visibility-guard');" +
        "if(!s){s=document.createElement('style');s.id='komikun-visibility-guard';" +
        "(document.head||document.documentElement).appendChild(s);}" +
        "s.textContent=" + css + ";" +
        "var q=document.querySelectorAll('" + nodes + "');" +
        "var nodes=[document.documentElement,document.body];" +
        "for(var i=0;i<q.length;i++){nodes.push(q[i]);}" +
        "for(var j=0;j<nodes.length;j++){var n=nodes[j];if(!n||!n.style){continue;}" +
        "n.style.setProperty('display','block','important');" +
        "n.style.setProperty('visibility','visible','important');" +
        "n.style.setProperty('opacity','1','important');}" +
        "})()"
}

/** Undo [renderGuardScript], so a later render is judged on the document's own styles again. */
internal const val RENDER_GUARD_REMOVE_SCRIPT: String =
    "(function(){" +
        "var s=document.getElementById('komikun-visibility-guard');if(s){s.remove();}" +
        "var q=document.querySelectorAll('" + RENDER_GUARD_CONTAINER_NODES + "," +
        RENDER_GUARD_DEFINITION_NODES + "');" +
        "var nodes=[document.documentElement,document.body];" +
        "for(var i=0;i<q.length;i++){nodes.push(q[i]);}" +
        "for(var j=0;j<nodes.length;j++){var n=nodes[j];if(!n||!n.style){continue;}" +
        "n.style.removeProperty('display');" +
        "n.style.removeProperty('visibility');" +
        "n.style.removeProperty('opacity');}" +
        "})()"

/**
 * Parse what [RENDER_MEASURE_SCRIPT] returned. The WebView hands back a JSON string literal, some
 * versions hand back the object itself, and a dead/never-loaded page hands back `null` or nothing —
 * all three are handled, and anything unparseable reads as "not measured".
 */
internal fun parseRenderMeasurement(raw: String?): RenderMeasurement? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty() || text == "null" || text.startsWith("err:")) return null
    val obj = runCatching {
        when (val value = JSONTokener(text).nextValue()) {
            is JSONObject -> value
            is String -> JSONObject(value)
            else -> null
        }
    }.getOrNull() ?: return null
    if (obj.length() == 0) return null
    return RenderMeasurement(
        entryCount = obj.optInt("entry_count", 0),
        containerHeight = obj.optInt("container_h", -1),
        containerScrollHeight = obj.optInt("container_sh", -1),
        containerDisplay = obj.optString("container_display", ""),
        containerVisibility = obj.optString("container_visibility", ""),
        containerOpacity = opacityOf(obj, "container_opacity"),
        bodyDisplay = obj.optString("body_display", ""),
        bodyVisibility = obj.optString("body_visibility", ""),
        entryHeight = obj.optInt("entry_h", -1),
        entryScrollHeight = obj.optInt("entry_sh", -1),
        entryDisplay = obj.optString("entry_display", ""),
        entryVisibility = obj.optString("entry_visibility", ""),
        entryOpacity = opacityOf(obj, "entry_opacity"),
        layoutHeight = obj.optInt("layout_h", -1),
        documentHeight = obj.optInt("layout_sh", -1),
        groupCount = obj.optInt("group_count", 0),
        groupHeight = obj.optInt("group_h", -1),
        groupDisplay = obj.optString("group_display", ""),
        groupVisibility = obj.optString("group_visibility", ""),
        groupOpacity = opacityOf(obj, "group_opacity"),
        expandedGroups = obj.optInt("expanded_groups", 0),
        definitionCount = obj.optInt("definition_count", 0),
        visibleDefinitions = obj.optInt("visible_definitions", 0),
        kanjiGlossCount = obj.optInt("kanji_gloss_count", 0),
    )
}

/** An opacity that is absent, null or `NaN` (JS `parseFloat` of a non-numeric style) is opaque. */
private fun opacityOf(obj: JSONObject, key: String): Float =
    obj.optDouble(key, 1.0).let { value -> if (value.isNaN()) 1f else value.toFloat() }

/**
 * Measure the just-rendered page and repair it when the frame claims entries the user cannot see.
 *
 * Runs on every `contentReady`, but only when entries are actually expected: an empty lookup or the
 * idle placeholder is legitimately a short/empty document, and judging (or guarding) it would report
 * a problem on every "No results for …" frame and override the placeholder's own styling.
 *
 * One line and one guard per render: `contentReadyGeneration` changes with every flush, so a renderer
 * that posts ready more than once for the same render is measured again but does not repeat the line,
 * and — the guard being a `display`/`visibility` override the user's own styles would keep — it is
 * not injected again either. The repair covers *every* reason the measurement found, in one script.
 * `removeVisibilityGuard` runs on the next invalidation, so a later generation is judged (and
 * repaired) from scratch.
 */
internal fun verifyRenderedVisibility(webView: WebView) {
    val state = webView.tag as? DictionaryWebViewState ?: return
    if (state.rendererDead) return
    val expectedEntries = state.payloadBridge.rawEntryJsons.size
    if (expectedEntries <= 0) return
    val generation = state.contentReadyGeneration
    val shouldLog = claimVisibilityJudgement(webView, generation)
    webView.evaluateJavascript(RENDER_MEASURE_SCRIPT) { raw ->
        val measurement = parseRenderMeasurement(raw)
        val problems = renderVisibilityProblems(expectedEntries, measurement)
        val measured = "entries=$expectedEntries dom_entries=${measurement?.entryCount ?: -1} " +
            "container_h=${measurement?.containerHeight ?: -1} entry_h=${measurement?.entryHeight ?: -1} " +
            "group_h=${measurement?.groupHeight ?: -1} defs=${measurement?.visibleDefinitions ?: -1}" +
            "/${measurement?.definitionCount ?: -1} kanji_defs=${measurement?.kanjiGlossCount ?: -1}"
        if (problems.isEmpty()) {
            if (shouldLog) Log.i("DictionaryRender", "render_visible $measured")
            return@evaluateJavascript
        }
        val repairable = problems.filter { it.shouldGuard }.map { it.reason }
        if (shouldLog) {
            Log.w(
                "DictionaryRender",
                "render_hidden reason=${problems.joinToString("+") { it.reason }} " +
                    "guard=${repairable.isNotEmpty()} $measured",
            )
        }
        if (repairable.isNotEmpty() && claimGuardInjection(webView, generation)) {
            webView.evaluateJavascript(renderGuardScript(repairable), null)
        }
    }
}

/** Last `contentReadyGeneration` judged per WebView, so one render logs once. */
private val visibilityJudgements = java.util.WeakHashMap<WebView, Long>()

private fun claimVisibilityJudgement(webView: WebView, generation: Long): Boolean =
    synchronized(visibilityJudgements) {
        if (visibilityJudgements[webView] == generation) {
            false
        } else {
            visibilityJudgements[webView] = generation
            true
        }
    }

/**
 * The `contentReadyGeneration` each WebView's guard was injected for, so a renderer that posts ready
 * more than once for one render injects it once (the KDoc above says so, and a second injection is a
 * script call per duplicate ready for nothing). [removeVisibilityGuard] forgets it, which is also how
 * a generation whose first measurement was too early to repair ([RenderVisibilityProblem.shouldGuard]
 * is false then) still gets its guard from a later ready of that same generation.
 */
private val guardInjections = java.util.WeakHashMap<WebView, Long>()

private fun claimGuardInjection(webView: WebView, generation: Long): Boolean =
    synchronized(guardInjections) {
        if (guardInjections[webView] == generation) {
            false
        } else {
            guardInjections[webView] = generation
            true
        }
    }

/**
 * Drop [renderGuardScript]'s overrides. Called when a render is invalidated, so every render is
 * judged on the document's own styles and a fixed (or reset) custom CSS takes effect again.
 */
internal fun removeVisibilityGuard(webView: WebView) {
    if ((webView.tag as? DictionaryWebViewState)?.rendererDead == true) return
    synchronized(guardInjections) { guardInjections.remove(webView) }
    webView.evaluateJavascript(RENDER_GUARD_REMOVE_SCRIPT, null)
}

// ---------------------------------------------------------------------------------------------
// One-shot diagnostics: the custom CSS (cause) and the WebView implementation (environment).
// ---------------------------------------------------------------------------------------------

/**
 * Logs a custom-CSS length once per distinct value, never once per render.
 *
 * The length is the only thing worth logging — the CSS itself can be megabytes and is the user's
 * own — and a change is what matters: setting a stylesheet must be visible in the log as
 * `custom_css_len=<n>`, and the settings screen's "Reset custom CSS" as `custom_css_len=0`. A gate
 * rather than a call-site flag so the popup, the tab and the reader popup (three callers) share it.
 */
internal class CustomCssLogGate {
    private var lastLogged: Int? = null

    /** Whether [length] should be logged, recording it so the same value is not logged twice. */
    fun shouldLog(length: Int): Boolean = synchronized(this) {
        if (lastLogged == length) {
            false
        } else {
            lastLogged = length
            true
        }
    }
}

private val customCssLogGate = CustomCssLogGate()

/** Log [cssLength] when it differs from the last length logged in this process. */
internal fun logCustomCssLength(cssLength: Int) {
    if (!customCssLogGate.shouldLog(cssLength)) return
    Log.i("DictionaryRender", "custom_css_len=$cssLength")
}

/**
 * The WebView implementation, logged once per process. This is the piece that differs between two
 * devices rendering the same payload — an emulator's bundled WebView and a tablet's updated Chrome
 * are different browsers — so the first dictionary surface in a session names it in the log.
 */
internal fun webViewEnvironmentLine(
    packageName: String?,
    versionName: String?,
    userAgent: String?,
    sdkInt: Int,
    supportedAbis: List<String>,
    device: String,
): String = "webview_env package=${packageName ?: "unknown"} " +
    "version=${versionName ?: "unknown"} " +
    "chrome=${chromeVersion(userAgent) ?: "unknown"} " +
    "sdk=$sdkInt abis=${supportedAbis.joinToString(",")} device=$device"

/** The `Chrome/<version>` token of a WebView user agent, which is its engine version. */
internal fun chromeVersion(userAgent: String?): String? =
    userAgent?.let { CHROME_TOKEN.find(it)?.groupValues?.getOrNull(1) }

private val CHROME_TOKEN = Regex("""Chrome/([0-9][0-9.]*)""")

private val webViewEnvironmentLogged = AtomicBoolean(false)

/** Log [webViewEnvironmentLine] for this process, the first time a dictionary WebView exists. */
internal fun logWebViewEnvironmentOnce(context: Context) {
    if (!webViewEnvironmentLogged.compareAndSet(false, true)) return
    val packageInfo = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
    val userAgent = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()
    Log.i(
        "DictionaryRender",
        webViewEnvironmentLine(
            packageName = packageInfo?.packageName,
            versionName = packageInfo?.versionName,
            userAgent = userAgent,
            sdkInt = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
        ),
    )
}
// KUN <--
