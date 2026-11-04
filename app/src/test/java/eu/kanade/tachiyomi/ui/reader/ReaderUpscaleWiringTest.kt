// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// **Source-level wiring guards for reader upscaling — not behavioural tests.**
//
// The rules themselves are covered behaviourally: the pre-decode policy in
// `UpscaleRuntimePolicyTest`, the session's queue/cache behaviour in `ReaderUpscaleSessionImplTest`,
// the service in `:core:upscale`. What no JVM test in this module can prove is that the three decode
// paths still *use* them: the holders are Android views around a decoder, and there is no UI harness
// here. So this reads the call sites, the way `ReaderOcrOverlayWiringTest` guards the OCR ones.
//
// The defect it pins was real and silent: each viewer `request`ed the page **before** looking it up,
// so a page that was already upscaled was decoded again for the upscaler on every page turn — and
// with no test failing, because the result was still correct, only slow. The assertions are therefore
// structural rather than a grep for the two call names: every `request` must sit behind an
// `if (x == null)` whose `x` is the value the `open` lookup just returned, and the page's own stream
// must still be the fallback.
//
// Residual gap: source text proves the shape of the call, not that the reader ran it.
// KUN <--
package eu.kanade.tachiyomi.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File
import kotlin.math.min

class ReaderUpscaleWiringTest {

    private fun source(path: String): String = File("src/main/java/$path").readText().withoutComments()

    /**
     * Catches the page-turn regression this fix exists for: a viewer that queues the page before (or
     * without) resolving the cache, so an already-upscaled page is decoded again on every turn. It
     * also catches an inverted or dropped guard, and a viewer that stopped falling back to the page's
     * own bytes.
     */
    @Test
    fun `every viewer requests a page only when the cache had nothing, and keeps the source fallback`() {
        val viewers = listOf(
            Viewer(
                path = "eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt",
                // A merged sheet reads its second half through its own supplier.
                fallbacks = listOf("""\?:\s*streamFn\(\)""", """\?:\s*streamFn2\?\.invoke\(\)"""),
            ),
            Viewer(
                path = "eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonPageHolder.kt",
                fallbacks = listOf("""\?:\s*streamFn\(\)"""),
            ),
            Viewer(
                path = "eu/kanade/tachiyomi/ui/reader/viewer/webgpu/WebGpuViewer.kt",
                fallbacks = listOf("""\?:\s*page\.page\.stream\?\.invoke\(\)"""),
            ),
        )

        for (viewer in viewers) {
            val source = source(viewer.path)
            occurrences(source, Regex("""readerUpscale""")) shouldBe 1

            // Every request, and no others: the guard count must match the call count exactly.
            val requests = GUARDED_REQUEST.findAll(source).toList()
            occurrences(source, Regex("""\.request\(""")) shouldBe requests.size
            assertTrue(requests.isNotEmpty(), "${viewer.path} never queues a page")

            for (request in requests) {
                // The decision is *this* lookup's: the guarded value came out of `open`.
                val cached = request.groupValues[1]
                if (!Regex("""\b$cached\s*=\s*[^\n]*?\.open\(""").containsMatchIn(source)) {
                    fail("${viewer.path}: '$cached == null' guards a request but is not an open() result")
                }
            }

            // The page's own bytes are still what a page without an upscale decodes from.
            for (fallback in viewer.fallbacks) {
                if (!Regex(fallback).containsMatchIn(source)) {
                    fail("${viewer.path} lost its source fallback: /$fallback/")
                }
            }
        }
    }

    /**
     * The session is per reader, so the activity that creates it must be the one that closes it:
     * created in `onCreate`, reset (not closed) when the same activity opens another chapter, and
     * closed on **every** destroy — with the recreation argument, so a rotation keeps the queued work
     * the recreated reader reads back out of the app-scoped cache.
     */
    @Test
    fun `the reader activity creates one upscaling session and closes it on every destroy`() {
        val source = source("eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt")

        val onCreate = functionBody(source, "override fun onCreate(")
        val onDestroy = functionBody(source, "override fun onDestroy(")
        val onNewIntent = functionBody(source, "override fun onNewIntent(")

        // One session per reader, created by the activity itself, before the Compose tree that holds
        // the viewers is installed.
        occurrences(source, Regex("""readerUpscaleHost\s*\.\s*newSession\(\)""")) shouldBe 1
        onCreate shouldContainSource """upscaleSession\s*=\s*readerUpscaleHost\s*\.\s*newSession\(\)"""
        assertTrue(
            onCreate.indexOf("readerUpscaleHost.newSession()") < onCreate.indexOf("setComposeContent"),
            "the upscaling session must exist before the Compose tree that decodes through it",
        )

        // Closed on every destroy, with the recreation argument rather than a guard that skips it.
        onDestroy shouldContainSource (
            """upscaleSession\s*\?\s*\.\s*close\(\s*recreating\s*=\s*isChangingConfigurations\s*&&\s*!\s*isFinishing\s*\)"""
            )
        onDestroy shouldContainSource """upscaleSession\s*=\s*null"""

        // The chapter switch inside a live reader resets the queue and nothing else.
        onNewIntent shouldContainSource """upscaleSession\s*\?\s*\.\s*reset\(\s*\)"""
        onNewIntent shouldNotContainSource """upscaleSession\s*\?\s*\.\s*close\("""

        // The app-scoped host only hands out sessions.
        occurrences(source, Regex("""readerUpscaleHost\s*\.""")) shouldBe 1
    }

    private class Viewer(val path: String, val fallbacks: List<String>)

    private companion object {
        /**
         * `if (<value> == null) … .request(` — with an optional leading conjunct, so the merged
         * sheet's `if (extraPage != null && cachedExtra == null)` is matched too, and the captured
         * group is always the value the guard tests.
         */
        val GUARDED_REQUEST = Regex("""if\s*\(\s*(?:[^()]*?&&\s*)?(\w+)\s*==\s*null\s*\)[^\n]*?\.request\(""")
    }
}

/** How many times [pattern] occurs in this text. */
private fun occurrences(source: String, pattern: Regex): Int = pattern.findAll(source).count()

/** [pattern] must occur somewhere in this text. */
private infix fun String.shouldContainSource(pattern: String) {
    if (!Regex(pattern).containsMatchIn(this)) {
        fail("expected the source to contain /$pattern/")
    }
}

/** The mirror: [pattern] must not occur in this text. */
private infix fun String.shouldNotContainSource(pattern: String) {
    if (Regex(pattern).containsMatchIn(this)) {
        fail("expected the source not to contain /$pattern/")
    }
}

/**
 * The body of the function starting at [signature], found by counting braces from its opening brace,
 * so the slice survives reformatting.
 */
private fun functionBody(source: String, signature: String): String {
    val start = source.indexOf(signature)
    if (start < 0) fail("missing call site: $signature")
    val open = source.indexOf('{', start)
    if (open < 0) fail("no block for: $signature")

    var depth = 0
    for (i in open until source.length) {
        when (source[i]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return source.substring(open, i + 1)
            }
        }
    }
    fail("unterminated body for: $signature")
    error("unreachable")
}

/**
 * This source with `//` line comments and slash-star block comments removed, so a needle is only ever
 * matched against code. String literals are copied verbatim, which is why a `//` inside one cannot
 * swallow the rest of the line.
 */
private fun String.withoutComments(): String {
    val out = StringBuilder(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        when {
            c == '"' -> {
                out.append(c)
                i++
                while (i < length && this[i] != '"') {
                    if (this[i] == '\\' && i + 1 < length) {
                        out.append(this[i])
                        i++
                    }
                    out.append(this[i])
                    i++
                }
                if (i < length) {
                    out.append(this[i])
                    i++
                }
            }
            c == '/' && i + 1 < length && this[i + 1] == '/' -> {
                while (i < length && this[i] != '\n') i++
            }
            c == '/' && i + 1 < length && this[i + 1] == '*' -> {
                i += 2
                while (i + 1 < length && !(this[i] == '*' && this[i + 1] == '/')) i++
                i = min(i + 2, length)
            }
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return out.toString()
}
