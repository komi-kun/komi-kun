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

/** How many times one surface recreates its WebView before it draws a native failure instead. */
internal const val MAX_RENDERER_RECOVERIES = 2

/**
 * What the page reports about itself after `contentReady`. Heights are CSS pixels (the WebView's
 * device-independent viewport), `-1` when the element does not exist.
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
)

/**
 * A render that finished but cannot be seen: [reason] is the log token, [shouldGuard] says whether
 * forcing the entries visible is the right repair (it is not when nothing could be measured — the
 * page may be a document that never loaded, and injecting CSS into it would prove nothing).
 */
internal data class RenderVisibilityProblem(val reason: String, val shouldGuard: Boolean)

/**
 * The hidden-document decision, in one place and free of Android types.
 *
 * Only fires when the frame was supposed to draw [expectedEntries] entries: an empty lookup or the
 * idle placeholder is legitimately a short/empty document, and overriding it would be a lie. A
 * visible entry is any height ≥ 1 px, since `getBoundingClientRect()` is rounded to whole pixels.
 */
internal fun renderVisibilityProblem(
    expectedEntries: Int,
    measurement: RenderMeasurement?,
): RenderVisibilityProblem? {
    if (expectedEntries <= 0) return null
    val m = measurement
        ?: return RenderVisibilityProblem("measurement_missing", shouldGuard = false)
    if (isHidden(m.bodyDisplay, m.bodyVisibility, 1f)) {
        return RenderVisibilityProblem("body_hidden", shouldGuard = true)
    }
    if (isHidden(m.containerDisplay, m.containerVisibility, m.containerOpacity)) {
        return RenderVisibilityProblem("container_hidden", shouldGuard = true)
    }
    if (m.containerHeight < 1) {
        return RenderVisibilityProblem(
            // Entries exist but the container clips them, versus a container that reports nothing.
            if (m.containerScrollHeight >= 1) "container_clipped" else "container_zero_height",
            shouldGuard = true,
        )
    }
    if (m.entryCount <= 0) {
        // The frame claims entries but the DOM has none: the renderer drew nothing at all.
        return RenderVisibilityProblem("entries_missing", shouldGuard = true)
    }
    if (isHidden(m.entryDisplay, m.entryVisibility, m.entryOpacity)) {
        return RenderVisibilityProblem("entry_hidden", shouldGuard = true)
    }
    if (m.entryHeight < 1 && m.entryScrollHeight < 1) {
        return RenderVisibilityProblem("entry_zero_height", shouldGuard = true)
    }
    return null
}

/** Whether a computed `display`/`visibility`/`opacity` triple takes an element out of sight. */
internal fun isHidden(display: String, visibility: String, opacity: Float): Boolean =
    display.equals("none", ignoreCase = true) ||
        visibility.equals("hidden", ignoreCase = true) ||
        visibility.equals("collapse", ignoreCase = true) ||
        opacity < 0.05f

/**
 * Asks the page to report its own geometry and computed style for `main#entries` / `.entry`.
 *
 * Returns a JSON string (the WebView hands back the expression's value), or `err:…` when the page
 * threw — a document whose scripts failed cannot be measured, and saying so beats guessing.
 */
internal const val RENDER_MEASURE_SCRIPT: String =
    "(function(){" +
        "try{" +
        "var main=document.getElementById('entries');" +
        "var body=document.body;" +
        "var entries=document.querySelectorAll('#entries .entry');" +
        "var first=entries.length?entries[0]:null;" +
        "function cs(n){return (n&&window.getComputedStyle)?window.getComputedStyle(n):null;}" +
        "function h(n){return (n&&n.getBoundingClientRect)?Math.round(n.getBoundingClientRect().height):-1;}" +
        "function sh(n){return n?Math.round(n.scrollHeight||0):-1;}" +
        "var mc=cs(main),bc=cs(body),fc=cs(first);" +
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
        "entry_opacity:(fc?parseFloat(fc.opacity):1)" +
        "});" +
        "}catch(e){return 'err:'+e.message;}" +
        "})()"

/**
 * The repair for a measured-hidden render: force the document, the entries container and every entry
 * visible. Applied as inline `!important`, which outranks a stylesheet `!important` — including the
 * custom CSS that caused this — while leaving the rest of the custom CSS alone. Idempotent, and
 * undoable by [RENDER_GUARD_REMOVE_SCRIPT].
 */
internal const val RENDER_GUARD_SCRIPT: String =
    "(function(){" +
        "var s=document.getElementById('komikun-visibility-guard');" +
        "if(!s){s=document.createElement('style');s.id='komikun-visibility-guard';" +
        "(document.head||document.documentElement).appendChild(s);}" +
        "s.textContent=" +
        "'#entries{height:auto!important;max-height:none!important;overflow:visible!important;}'" +
        "+'#entries .entry{height:auto!important;overflow:visible!important;}';" +
        "var nodes=[document.documentElement,document.body,document.getElementById('entries')];" +
        "var entries=document.querySelectorAll('#entries .entry');" +
        "for(var i=0;i<entries.length;i++){nodes.push(entries[i]);}" +
        "for(var j=0;j<nodes.length;j++){var n=nodes[j];if(!n||!n.style){continue;}" +
        "n.style.setProperty('display','block','important');" +
        "n.style.setProperty('visibility','visible','important');" +
        "n.style.setProperty('opacity','1','important');}" +
        "})()"

/** Undo [RENDER_GUARD_SCRIPT], so a later render is judged on the document's own styles again. */
internal const val RENDER_GUARD_REMOVE_SCRIPT: String =
    "(function(){" +
        "var s=document.getElementById('komikun-visibility-guard');if(s){s.remove();}" +
        "var nodes=[document.documentElement,document.body,document.getElementById('entries')];" +
        "var entries=document.querySelectorAll('#entries .entry');" +
        "for(var i=0;i<entries.length;i++){nodes.push(entries[i]);}" +
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
    )
}

/** An opacity that is absent, null or `NaN` (JS `parseFloat` of a non-numeric style) is opaque. */
private fun opacityOf(obj: JSONObject, key: String): Float =
    obj.optDouble(key, 1.0).let { value -> if (value.isNaN()) 1f else value.toFloat() }

/**
 * Measure the just-rendered page and repair it when the frame claims entries the user cannot see.
 *
 * Runs on every `contentReady`. A successful render logs one short line so "lookup produced entries
 * and the renderer drew them, but the page was invisible" is distinguishable from "the lookup was
 * empty" without a debugger; a measured-hidden render additionally gets [RENDER_GUARD_SCRIPT].
 */
internal fun verifyRenderedVisibility(webView: WebView) {
    val state = webView.tag as? DictionaryWebViewState ?: return
    if (state.rendererDead) return
    val expectedEntries = state.payloadBridge.rawEntryJsons.size
    webView.evaluateJavascript(RENDER_MEASURE_SCRIPT) { raw ->
        val measurement = parseRenderMeasurement(raw)
        val problem = renderVisibilityProblem(expectedEntries, measurement)
        val measured = "entries=$expectedEntries dom_entries=${measurement?.entryCount ?: -1} " +
            "container_h=${measurement?.containerHeight ?: -1} entry_h=${measurement?.entryHeight ?: -1}"
        if (problem == null) {
            Log.i("DictionaryRender", "render_visible $measured")
            return@evaluateJavascript
        }
        Log.w(
            "DictionaryRender",
            "render_hidden reason=${problem.reason} guard=${problem.shouldGuard} $measured",
        )
        if (problem.shouldGuard) webView.evaluateJavascript(RENDER_GUARD_SCRIPT, null)
    }
}

/**
 * Drop [RENDER_GUARD_SCRIPT]'s overrides. Called when a render is invalidated, so every render is
 * judged on the document's own styles and a fixed (or reset) custom CSS takes effect again.
 */
internal fun removeVisibilityGuard(webView: WebView) {
    if ((webView.tag as? DictionaryWebViewState)?.rendererDead == true) return
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
