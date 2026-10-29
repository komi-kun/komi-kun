// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// **Source-level sibling-order guard — not a behavioural test.**
//
// The overlay's *visibility* rule is a pure function, tested behaviourally in
// `ReaderOcrOverlayPolicyTest`. What no JVM test in this module can prove is *where* `ReaderActivity`
// composes the overlay: that class is an Android `Activity` binding ViewModels and a Compose tree, and
// there is no Compose UI test harness here. So this reads the call site, the way
// `OcrPolicySourceWiringTest` guards the other OCR call sites.
//
// The defect it pins was real: the overlay used to be the **last** sibling of the root `Box`, i.e.
// drawn over the whole reader UI instead of under the chrome. In a `Box` the draw order is the
// composition order of the non-composed children, so the ordering facts below *are* the layering:
// `ReaderContentOverlay` (brightness/colour) first, then the side navigator, then the OCR overlay,
// then the top/bottom bars (`AppBars`) last. The OCR overlay therefore draws **above the side
// navigator and below the bars** — the pre-merge z-order, when the navigator was the native
// `ReaderNavigationOverlayView` sibling that preceded the Compose root in `reader_activity.xml`.
//
// The needles follow the Compose reader root (upstream 00335b2a70): the root `Box` is the one that
// paints the reader background, the brightness/colour overlay is `ReaderContentOverlay`, and the
// side navigator is `ReaderNavigationOverlay`.
//
// Two properties keep the source reads from decaying into greps: comments are stripped first, so a
// needle can never be satisfied (or broken) by prose, and the structural assertions slice blocks by
// counting braces/parentheses instead of matching a fixed amount of whitespace, so Spotless
// reformatting cannot fail them and a mutation cannot pass by merely keeping a token present.
//
// Residual gap: source text proves the call order in the file, not the rendered z-order — a nesting
// or modifier change elsewhere could still reorder what is drawn.
// KUN <--
package eu.kanade.tachiyomi.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File
import kotlin.math.min

class ReaderOcrOverlayWiringTest {

    /** The reader activity's source, with comments removed. */
    private fun readerActivity(): String =
        File("src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt").readText().withoutComments()

    /** [needle]'s index in [source], failing loudly when it is absent (a rename must not pass). */
    private fun indexOfCall(source: String, needle: String): Int {
        val at = source.indexOf(needle)
        if (at < 0) fail("missing call site in ReaderActivity.kt: $needle")
        return at
    }

    @Test
    fun `the OCR overlay is composed once, inside the root box, above the side navigator and under the bars`() {
        val source = readerActivity()

        // Exactly one overlay call site: a second one — the shape this replaced was the same call as
        // the Box's last sibling — would paint over the chrome again, and no ordering assert below
        // could see which of the two is which.
        occurrences(source, Regex.fromLiteral("readerOcrHost.Overlay(")) shouldBe 1

        // The root Box is the one painting the reader background: `Box(` alone would also match any
        // Box composed before it, which would make the containment assert below pass vacuously.
        val rootBox = indexOfCall(source, ".background(backgroundColor)")
        val contentOverlay = indexOfCall(source, "ReaderContentOverlay(")
        val overlay = indexOfCall(source, "readerOcrHost.Overlay(")
        val navigationOverlay = indexOfCall(source, "ReaderNavigationOverlay(state = navigationOverlayState)")
        val appBars = indexOfCall(source, "AppBars(state = state)")

        // Root Box first: the overlay must be a child of the page-layer Box, not a sibling of it.
        assertTrue(
            rootBox < overlay,
            "the OCR overlay must be inside the root Box (box=$rootBox, overlay=$overlay)",
        )
        // Inside the Box, after the brightness/colour overlay, so it is drawn on top of the page...
        assertTrue(
            contentOverlay < overlay,
            "the OCR overlay must be composed after ReaderContentOverlay " +
                "(contentOverlay=$contentOverlay, overlay=$overlay)",
        )
        // ...and after the side navigator, so the OCR overlay draws on top of it — the pre-merge
        // z-order. This is the assertion that fails if `ReaderNavigationOverlay` is composed after
        // the OCR block again.
        assertTrue(
            navigationOverlay < overlay,
            "the OCR overlay must be composed after ReaderNavigationOverlay, or the side navigator " +
                "starts covering it (navigationOverlay=$navigationOverlay, overlay=$overlay)",
        )
        // ...and before `AppBars`, so the top/bottom bars paint over it. This is the assertion that
        // fails if the bars are moved back above the OCR block.
        assertTrue(
            overlay < appBars,
            "the OCR overlay must be composed before AppBars, or the chrome stops covering it " +
                "(overlay=$overlay, appBars=$appBars)",
        )
    }

    /**
     * Every entry point that consumes a tap through the OCR host — the outside-tap detector's
     * `hitsBlock`, the long press and the single tap — must ask the **policy** whether a lookup may
     * run at all, and none of them may re-derive "is the overlay on" locally. A hidden overlay that
     * still hit-tests eats the gesture: the page does not turn and the menu does not toggle.
     *
     * Counting the occurrences proves presence only — a local `isEnabled() && …` beside each policy
     * call, or a policy call moved *after* the gesture-consuming call, keeps the counts equal. So
     * each site is checked structurally instead: the decision must be **this** site's guard (nothing
     * that consumes a gesture in between), in the same block as the call it guards, and inside the
     * `if (` condition when that is what consumes the gesture.
     *
     * Source-level, like the rest of this file: the three call sites live in an Android `Activity`
     * and the rule itself is covered behaviourally in [ReaderOcrOverlayPolicyTest].
     */
    @Test
    fun `every OCR tap entry point asks the overlay policy before hit-testing`() {
        val source = readerActivity()

        val hitTests = listOf(
            "readerOcrHost.hitsBlock(",
            "readerOcrHost.onPageLongPress(",
            "readerOcrHost.onPageSingleTap(",
        )
        // Each entry point exists exactly once, so "the guard before this call" is unambiguous.
        hitTests.forEach { occurrences(source, Regex.fromLiteral(it)) shouldBe 1 }
        occurrences(source, Regex.fromLiteral(GATE)) shouldBe hitTests.size
        // ...and every one of them passes the chrome state, which is what stands the hit test down
        // while the bars are up (the overlay is composed with `alpha = 0f` there). A `menuVisible`
        // that defaults (or that one site forgets) puts the invisible-overlay lookup back.
        occurrences(source, Regex("""menuVisible\s*=\s*viewModel\.state\.value\.menuVisible""")) shouldBe
            hitTests.size

        hitTests.forEach { hitTest ->
            val call = indexOfCall(source, hitTest)
            val gate = source.lastIndexOf(GATE, call)
            if (gate < 0) fail("$hitTest is not guarded by $GATE at all")

            // The guard must be *this* site's decision: a call that consumes a gesture between the
            // nearest preceding decision and this one means the decision guards that call instead
            // (the shape a policy call moved after its hit test leaves behind).
            hitTests.filter { it != hitTest }.forEach { other ->
                if (source.substring(gate, call).contains(other)) {
                    fail("$hitTest is guarded by the policy decision of another entry point ($other)")
                }
            }
            // ...and it must live in the same block as the call it guards: a decision left behind in
            // another function or lambda can never gate this one.
            innermostBlock(source, gate) shouldBe innermostBlock(source, call)

            // Where an `if (` condition consumes the gesture, the decision must be inside that same
            // condition.
            enclosingIfCondition(source, call)?.let { condition ->
                if (!condition.contains(GATE)) {
                    fail("$hitTest is consumed by an `if (` condition that does not consult the policy: $condition")
                }
            }
        }

        // No site may re-derive the old inline condition beside the policy call: the whole point of
        // the policy is that "is the overlay on" is decided in exactly one place, in code that a
        // behavioural test covers.
        source shouldNotContainSource (
            """dictionaryOcrPreferences\s*\.\s*isEnabled\(\s*\)\s*&&\s*""" +
                """readerPreferences\s*\.\s*ocrOverlayEnabled"""
            )
    }

    /**
     * The viewport the hit test maps taps through is recorded by `Overlay`, so it must be dropped
     * when the overlay leaves composition — `detach()` only runs when the reader goes away, and is
     * skipped across a configuration change.
     *
     * The same disposal is where an open lookup has to be closed: turning the overlay off removes
     * the per-frame loop (`updatePopupAnchor`) that dismisses a popup and undoes its "keep the block
     * visible" fit, so without this an open lookup stayed at its last rect over a zoomed page with
     * `activeBlock` still set, and only recovered once the overlay was enabled again.
     *
     * Sliced to `Overlay`'s body rather than grepped over the file: a `DisposableEffect` moved out of
     * `Overlay` never runs on the overlay's exit, and an `onDispose` that assigns the viewport back
     * (instead of clearing it) keeps exactly the stale viewport the hit test must not answer from.
     * The dismiss/`restoreFit` needles are absent from the rest of `Overlay`, so they can only be
     * satisfied by the disposal block.
     */
    @Test
    fun `the host drops the recorded viewport and closes the open lookup when the overlay leaves composition`() {
        val source = File(
            "src/main/java/eu/kanade/tachiyomi/ui/reader/ocr/impl/ReaderOcrHostImpl.kt",
        ).readText().withoutComments()

        val overlay = functionBody(source, "override fun Overlay(")

        // The clear is identity-conditional: a rotation disposes the old composition after the new
        // one recorded its viewport, and an unconditional clear would blank that one.
        overlay shouldContainSource (
            """if\s*\(\s*this@ReaderOcrHostImpl\s*\.\s*viewport\s*===\s*latestViewport\s*\.\s*value\s*\)"""
            )
        // The recorded viewport is written in exactly one place inside `Overlay`, and it is that
        // `= null`: no other assignment (in particular none that writes `viewport` back) competes
        // with the disposal.
        occurrences(overlay, Regex("""this@ReaderOcrHostImpl\s*\.\s*viewport\s*=(?!=)""")) shouldBe 1
        occurrences(overlay, Regex("""this@ReaderOcrHostImpl\s*\.\s*viewport\s*=\s*null""")) shouldBe 1

        // An open lookup is dismissed and its fit undone on the same exit...
        overlay shouldContainSource """dictionaryHost\s*\.\s*isOpen\(\s*\)"""
        overlay shouldContainSource """dictionaryHost\s*\.\s*dismiss\(\s*\)"""
        overlay shouldContainSource """latestViewport\s*\.\s*value\s*\?\s*\.\s*restoreFit\(\s*\)"""
        overlay shouldContainSource """activeBlock\s*\.\s*value\s*=\s*null"""
        // ...but not while the activity is being recreated, where the popup must survive.
        overlay shouldContainSource """hostActivity\s*\?\s*\.\s*isChangingConfigurations\s*!=\s*true"""
        // ...and the activity is *unwrapped* rather than cast: `LocalContext.current as? Activity`
        // answers null for a wrapped context, which turned the guard into the destroy branch and made
        // a rotation dismiss the very popup it protects.
        overlay shouldContainSource """hostActivity\s*=\s*LocalContext\s*\.\s*current\s*\.\s*findActivity\(\)"""
        overlay shouldNotContainSource """LocalContext\s*\.\s*current\s+as\?\s+Activity"""
    }

    private companion object {
        /** The one place the "may this gesture be looked up" decision is allowed to live. */
        private const val GATE = "ReaderOcrOverlayPolicy.acceptsLookup("
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
 * The index of the `{` opening the innermost block that contains [at], i.e. an identity for "which
 * function/lambda/`if` body is this code in". Equal indices mean the two positions are in the same
 * block. (Comments are already gone and the counted files have no braces inside raw strings, so the
 * count is exact.)
 */
private fun innermostBlock(source: String, at: Int): Int {
    val open = ArrayDeque<Int>()
    for (i in 0 until at) {
        when (source[i]) {
            '{' -> open.addLast(i)
            '}' -> if (open.isNotEmpty()) open.removeLast()
        }
    }
    return open.lastOrNull() ?: -1
}

/**
 * The condition of the `if (` whose parentheses contain [at], or null when [at] is not inside an
 * `if` condition (a bare expression, or a function body).
 */
private fun enclosingIfCondition(source: String, at: Int): String? {
    var depth = 0
    var i = at
    while (i > 0) {
        i--
        when (source[i]) {
            ')' -> depth++
            '(' -> {
                if (depth > 0) {
                    depth--
                } else {
                    // The innermost unclosed `(`: it is an `if` condition only if `if` precedes it.
                    if (!source.substring(0, i).trimEnd().endsWith("if")) return null
                    return source.substring(i + 1, matchingParen(source, i))
                }
            }
            // A condition never crosses a block boundary: past one, [at] is not inside an `if`
            // condition at all.
            '{', '}' -> if (depth == 0) return null
        }
    }
    return null
}

/** The index of the `)` closing the `(` at [open]. */
private fun matchingParen(source: String, open: Int): Int {
    var depth = 0
    for (i in open until source.length) {
        when (source[i]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return i
            }
        }
    }
    fail("unbalanced parentheses from index $open")
    error("unreachable")
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
 * matched against code. String literals are copied verbatim, which is why a `//` inside one (a URL)
 * cannot swallow the rest of the line.
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
