// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// **Source-level guards — not behavioural tests.**
//
// The word-audio renderer half is plain `renderer.js` executed by the popup WebView, and this module
// has no JavaScript runtime or WebView harness, so its behaviour cannot be driven from a JVM test.
// What *is* provable from the sources is the shape the two bugs were: audio resolved and prefetched
// while *rendering* an entry (so every drawn entry hit the network and the picker waited for the
// slowest source), and one group per configured source whether or not it produced a sample. The
// behaviour itself is covered by `AudioResolverTest.stream*` (partial delivery) and
// `AudioCacheTest` (in-flight dedupe); the native half's `deliver(…, done)` and this contract are
// not exercised end-to-end anywhere.
// KUN <--
package mihon.core.dictionary.audio

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File

class AudioLazyResolutionGuardTest {
    private val rendererJs = File("src/main/assets/dictionary/renderer.js").readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .lines()
        .filterNot { it.trimStart().startsWith('*') || it.trimStart().startsWith("//") }
        .joinToString("\n")

    private val bridgeSource = File("src/main/java/mihon/core/dictionary/audio/WordAudioBridge.kt").readText()

    @Test
    fun `rendering an entry never asks for audio`() {
        // Resolution has exactly three touch points: the definition and the two user-action
        // handlers (play tap, picker open). Any other call site — a rendered entry, `renderEntry` —
        // is a render-time resolve, whatever callback shape it uses (`null`, `() => {}`, …).
        val callSites = Regex("requestAudioCandidates\\s*\\(").findAll(rendererJs).map { it.range.first }.toList()
        callSites.size shouldBe 3
        val buttonStart = rendererJs.indexOf("function createAudioButton(")
        val createButton = functionBlock(rendererJs, "function createAudioButton(")
        val buttonRange = buttonStart until (buttonStart + createButton.length)
        // Exactly two call sites live inside `createAudioButton` (its two user-action handlers) and
        // one outside it (the definition) — selected by range, not by file order.
        callSites.count { it in buttonRange } shouldBe 2
        callSites.count { it !in buttonRange } shouldBe 1
        createButton shouldContain "const playDefault = () => {"
        createButton shouldContain "const openMenu = (ev) => {"
        Regex("requestAudioCandidates\\(expression, reading, \\(payload, done\\) =>").findAll(createButton)
            .count() shouldBe 2
    }

    @Test
    fun `audio results carry a done flag and a missing one counts as done`() {
        rendererJs shouldContain "onAudioResults: (id, payload, done) =>"
        // A payload from a renderer that predates the streaming contract is a single, final one.
        rendererJs shouldContain "done === undefined ? true : done === true"
        // Partial snapshots are merged into the cache, not just stored, so a later snapshot does not
        // drop the samples an earlier one carried.
        rendererJs shouldContain "mergeAudioSnapshot(key, payload, isDone)"
    }

    @Test
    fun `the audio picker never renders a group for an empty source`() {
        val menu = functionBlock(rendererJs, "function showAudioMenu(")
        // No group is created for a source without samples...
        Regex("if \\(\\s*!entries\\s*\\|\\|\\s*entries\\.length === 0\\s*\\)\\s*return;").containsMatchIn(menu) shouldBe true
        // ...and the only per-source iteration is over the *rendered* sources; a plain
        // `sources.forEach` would rebuild the empty-group row this guards against.
        Regex("\\bsources\\s*\\.\\s*forEach\\s*\\(").containsMatchIn(menu) shouldBe false
        menu shouldContain "renderedSources"
        // Empty-source rows are gone; the only "No audio found" is the terminal status row.
        functionBlock(rendererJs, "const renderGroup = (group, entries) => {") shouldNotContain "No audio found"
    }

    @Test
    fun `a snapshot replaces the cached candidate order instead of appending it`() {
        val merge = functionBlock(rendererJs, "function mergeAudioSnapshot(")
        // Native snapshots are cumulative and already in configured source order; appending arrivals
        // would order the cache (and the "default" badge / playDefault list) fastest-first.
        merge shouldContain "candidates: ordered"
        // Nothing is appended to the *previous* list: the only build target is the payload's own
        // list (a regression would append the earlier entry's candidates to it).
        merge shouldNotContain "previous.candidates"
        rendererJs shouldContain "const ordered = [];"
        rendererJs shouldContain "ordered.push(candidate);"
    }

    @Test
    fun `a streaming snapshot never re-opens a dismissed picker`() {
        // The streaming handler updates an open picker only; a dismissed menu stays dismissed.
        rendererJs shouldContain "function updateAudioMenu(button, payload, loading) {"
        functionBlock(rendererJs, "function updateAudioMenu(") shouldContain "_audioMenuNode.isConnected"
        functionBlock(rendererJs, "function updateAudioMenu(") shouldContain "_audioMenuButton !== button"
        rendererJs shouldContain "updateAudioMenu(audioBtn, payload, !done)"
        // ...and the open path records which button the menu belongs to.
        functionBlock(rendererJs, "function showAudioMenu(") shouldContain "_audioMenuButton = button;"
        // The only delivery entry point has no unreachable callback branch.
        rendererJs shouldNotContain "typeof entry === 'function'"
    }

    @Test
    fun `a partial cache entry is evicted like any other`() {
        val cache = functionBlock(rendererJs, "function cacheAudioState(")
        // An interrupted lookup leaves a partial entry whose final `done` never arrives, so evicting
        // by entry state would pin it in the bounded cache forever. (`oldest.done` is the Map
        // *iterator's* exhaustion flag, not an entry predicate, so it is allowed.)
        Regex("\\b(entry|item|victim|candidate)\\s*\\.\\s*done\\b").containsMatchIn(cache) shouldBe false
        Regex("\\[\\s*[\"']done[\"']\\s*\\]").containsMatchIn(cache) shouldBe false
        // No scan-for-a-victim loop at all: the oldest key is taken directly.
        cache shouldNotContain "for (const"
        cache shouldContain "_audioCandidatesCache.keys().next()"
    }

    @Test
    fun `the first long press opens the picker before any audio is cached`() {
        val openMenu = functionBlock(rendererJs, "const openMenu = (ev) => {")
        // With lazy resolution the first press has no cache entry at all; the picker must be created
        // right away in its loading state, because a snapshot can only update a menu that exists.
        Regex(
            "showAudioMenu\\(\\s*audioBtn\\s*,\\s*\\{\\s*sources\\s*:\\s*\\[\\s*\\]\\s*," +
                "\\s*candidates\\s*:\\s*\\[\\s*\\]\\s*\\}\\s*,\\s*true\\s*\\)",
        ).containsMatchIn(openMenu) shouldBe true
        openMenu shouldContain "updateAudioMenu(audioBtn, payload, !done)"
        // The terminal empty snapshot leaves one non-interactive row rather than an empty menu.
        val menu = functionBlock(rendererJs, "function showAudioMenu(")
        menu shouldContain "'No audio found'"
        menu shouldContain "'Loading audio…'"
        // Both handlers register on the same button but with a distinct *kind*, so repeating one
        // action replaces only its own waiter: a long-press while a play tap waits must not drop the
        // play waiter (that press would then never play and never report a failure).
        val createButton = functionBlock(rendererJs, "function createAudioButton(")
        Regex("\\}\\s*,\\s*audioBtn\\s*,\\s*'play'\\s*\\)").findAll(createButton).count() shouldBe 1
        Regex("\\}\\s*,\\s*audioBtn\\s*,\\s*'menu'\\s*\\)").findAll(createButton).count() shouldBe 1
        // Replacement is keyed on the (owner, kind) pair, not on the owner alone.
        val request = functionBlock(rendererJs, "function requestAudioCandidates(")
        request shouldContain "w.owner === waiter.owner && w.kind === waiter.kind"
        Regex("w\\.owner !== waiter\\.owner\\s*\\}\\s*;").containsMatchIn(request) shouldBe false
    }

    @Test
    fun `a new lookup drops audio requests for words it no longer shows`() {
        rendererJs shouldContain "pruneAudioRequests(audioKeysOfResults(results))"
        val prune = functionBlock(rendererJs, "function pruneAudioRequests(")
        prune shouldContain "_audioCallbacks.delete(callbackId)"
        prune shouldContain "_pendingAudioRequests.delete(key)"
        // Dropping a request only forgets handlers: it starts no playback (and, because every render
        // already closed the picker, it does not touch the menu either).
        prune shouldNotContain "playAudio"
        prune shouldNotContain "closeAudioMenu"
        // Nor does the wholesale reset in `clear()`.
        functionBlock(rendererJs, "function clear()") shouldNotContain "playAudio"
    }

    @Test
    fun `the bridge does not prefetch while delivering candidates`() {
        val fetchAudio = functionBlock(bridgeSource, "fun fetchAudio(")
        // Prefetching in the bridge meant every entry's clips were downloaded the moment its results
        // arrived, whether or not anything was ever played.
        fetchAudio shouldNotContain "player.prefetch("
        // The streaming path is what is collected instead.
        fetchAudio shouldContain ".stream("
        fetchAudio shouldContain "deliver(webView, callbackId, update.resolution, update.done, update.playbackReady)"
        // The explicit interface stays; it is simply no longer called from the render path.
        bridgeSource shouldContain "fun prefetchAudio(urlsJson: String)"
    }

    @Test
    fun `a missing audio config still finishes the payload`() {
        val fetchAudio = functionBlock(bridgeSource, "fun fetchAudio(")
        // `configProvider == null` disables resolution; returning without a payload would leave the
        // renderer's "Loading audio…" row up forever, because it only clears on `done`.
        fetchAudio shouldContain "val config = configProvider?.invoke()"
        fetchAudio shouldContain "if (config == null) {"
        fetchAudio shouldContain "done = true, playbackReady = false"
    }

    @Test
    fun `playback starts on source priority rather than the first candidate`() {
        val playDefault = functionBlock(rendererJs, "const playDefault = () => {")
        // The gate: a snapshot may start playback only when the highest-priority source that will
        // ever have a sample is known — never merely because it carries candidates.
        playDefault shouldContain "payload.playbackReady === true"
        playDefault shouldNotContain "Array.isArray(payload.candidates)"
        // The cached path is priority-decided too, so a finalised entry plays without re-resolving.
        playDefault shouldContain "cached.playbackReady"
        // ...and the cached payload carries the flag.
        functionBlock(rendererJs, "function mergeAudioSnapshot(") shouldContain "playbackReady:"

        // The picker is display-only: it must show every snapshot, not wait for the flag.
        functionBlock(rendererJs, "function showAudioMenu(") shouldNotContain "playbackReady"
        functionBlock(rendererJs, "const openMenu = (ev) => {") shouldNotContain "playbackReady"

        // The bridge sends the flag on every snapshot, including the empty ones.
        functionBlock(bridgeSource, "private fun deliver(") shouldContain "put(\"playbackReady\", playbackReady)"
        bridgeSource shouldContain "deliver(webView, callbackId, update.resolution, update.done, update.playbackReady)"
    }
}

/** Source from [needle] to the end of its enclosing brace block (brace counting, no fixed layout). */
private fun functionBlock(source: String, needle: String): String {
    val start = source.indexOf(needle)
    if (start < 0) fail("missing declaration in source: $needle")
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
// KUN <--
