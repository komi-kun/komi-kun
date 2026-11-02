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
    fun `a streaming snapshot reconciles the open picker instead of rebuilding it`() {
        val menu = functionBlock(rendererJs, "function showAudioMenu(")
        // The open menu for the same button is *reused*; only a menu that is gone (or belongs to
        // another entry) is closed. Rebuilding it on every snapshot detached the node under a finger
        // that was between `pointerdown` and `click` — the tap was silently lost — reset
        // keyboard/TalkBack focus and made the menu flicker and reposition once per source.
        menu shouldContain
            "const reuse = !!(_audioMenuNode && _audioMenuNode.isConnected && _audioMenuButton === button);"
        menu shouldContain "if (!reuse) closeAudioMenu();"
        menu shouldContain "const menu = reuse ? _audioMenuNode : document.createElement('div');"
        // The node is only mounted, classed and listener-bound for a fresh menu.
        Regex("if\\s*\\(\\s*!reuse\\s*\\)\\s*\\{\\s*document\\.body\\.appendChild\\(menu\\);")
            .containsMatchIn(menu) shouldBe true
        Regex("\\bdocument\\.body\\.appendChild\\(menu\\);").findAll(menu).count() shouldBe 1
        // A source that resolves after a later one is *inserted* at its sorted position — selected
        // from the groups already shown (or ahead of the status row), not simply appended.
        menu shouldContain "const before = shown[position] || statusRow();"
        menu shouldContain "menu.insertBefore(record.group, before)"
        // Terminal/loading rows are reconciled, not rebuilt.
        menu shouldContain "state.loadingItem.remove()"
        menu shouldContain "state.emptyItem = appendItem(menu, null, 'No audio found', false, true).item;"
        // The "default" badge follows the new ordering: it is *taken off* the item that carried it
        // and put on the new first candidate, instead of staying on the first arrival.
        menu shouldContain "setDefaultBadge(item, url === firstUrl)"
        val badge = functionBlock(rendererJs, "const setDefaultBadge = (item, isDefault) => {")
        badge shouldContain "item.classList.toggle('word-audio-menu-item-default', isDefault);"
        badge shouldContain "if (!isDefault && badge) {"
        badge shouldContain "badge.remove();"
        // Geometry is only re-applied when the measured size changed and no pointer is down.
        menu shouldContain "if (state.pointerDown) return;"
        menu shouldContain "if (size !== state.size) {"
        // The streaming path never closes the menu it is updating.
        functionBlock(rendererJs, "function updateAudioMenu(") shouldNotContain "closeAudioMenu"
    }

    @Test
    fun `a snapshot that lands while a pointer is down defers every row change`() {
        val menu = functionBlock(rendererJs, "function showAudioMenu(")
        // Producer: the flag is set from the document pointerdown capture listener...
        menu shouldContain "if (ev && ev.type === 'pointerdown') trackPointer(ev);"
        // ...and every snapshot that arrives while it is set is parked instead of mutating the row
        // list (an inserted/appended/moved row would take the tap that is still in flight).
        menu shouldContain "if (reuse && _audioMenuState.pointerDown) {"
        menu shouldContain "_audioMenuState.deferred = { payload, loading };"
        val deferral = menu.indexOf("_audioMenuState.deferred = { payload, loading };")
        // Nothing is created or moved before the deferral returns.
        (menu.indexOf("state.groups.set(") > deferral) shouldBe true
        (menu.indexOf("state.items.set(") > deferral) shouldBe true
        // The parked snapshot is replayed once from the release handler, then the deferred reflow.
        menu shouldContain "const deferred = state.deferred;"
        menu shouldContain "state.deferred = null;"
        menu shouldContain "showAudioMenu(button, deferred.payload, deferred.loading);"
        (
            menu.indexOf("showAudioMenu(button, deferred.payload, deferred.loading);") >
                menu.indexOf("state.deferred = null;")
            ) shouldBe true
        menu shouldContain "document.addEventListener('pointerup', _audioMenuPointerUp, true);"
        menu shouldContain "document.addEventListener('pointercancel', _audioMenuPointerUp, true);"
        // A menu that was closed (or replaced) in between is not this state, so a stale snapshot
        // cannot be replayed onto it.
        menu shouldContain "if (deferred && _audioMenuNode === menu && _audioMenuState === state) {"
        // Teardown removes the release listeners with the menu.
        val close = functionBlock(rendererJs, "function closeAudioMenu()")
        close shouldContain "document.removeEventListener('pointerup', _audioMenuPointerUp, true);"
        close shouldContain "document.removeEventListener('pointercancel', _audioMenuPointerUp, true);"
    }

    @Test
    fun `the pointer gesture is owned by one pointer and recovers a lost release`() {
        val menu = functionBlock(rendererJs, "function showAudioMenu(")
        // A gesture is tracked once: state.pointerId names it, and a second finger's pointerdown
        // must not overwrite it (otherwise its release would replay the parked snapshot under the
        // finger that is still pressing a row).
        val track = functionBlock(rendererJs, "const trackPointer = (ev) => {")
        track shouldContain "if (state.pointerDown) return;"
        track shouldContain "state.pointerId = (ev && typeof ev.pointerId === 'number') ? ev.pointerId : null;"
        (
            track.indexOf("if (state.pointerDown) return;") <
                track.indexOf("state.pointerId =")
            ) shouldBe true
        // Only the matching pointer's release ends it.
        val release = functionBlock(rendererJs, "const releasePointer = (ev) => {")
        release shouldContain "if (!state.pointerDown) return;"
        release shouldContain "ev.pointerId !== state.pointerId"
        release shouldContain "state.pointerDown = false;"
        release shouldContain "state.pointerId = null;"
        // The release listeners pass the event through, so the id check above is reachable.
        menu shouldContain "_audioMenuPointerUp = (ev) => releasePointer(ev);"
        // A lost release (page hidden, window blurred, pointer left the window) clears the flag and
        // replays the parked snapshot instead of freezing the picker forever.
        menu shouldContain "document.addEventListener('visibilitychange', _audioMenuPointerLost, true);"
        // A capture-phase `blur` listener would also fire for element blurs inside the popup and end
        // the gesture on the press that started it, so the window listener must stay in the default
        // (target/bubble) phase.
        menu shouldContain "window.addEventListener('blur', _audioMenuPointerLost);"
        menu shouldNotContain "window.addEventListener('blur', _audioMenuPointerLost, true);"
        menu shouldContain "document.addEventListener('pointerout', _audioMenuPointerLost, true);"
        val lost = functionBlock(rendererJs, "_audioMenuPointerLost = (ev) => {")
        lost shouldContain "if (ev.type === 'visibilitychange' && document.visibilityState !== 'hidden') return;"
        lost shouldContain "if (ev.type === 'pointerout' && ev.relatedTarget) return;"
        lost shouldContain "releasePointer(ev);"
        // ...and teardown removes them with the menu.
        val close = functionBlock(rendererJs, "function closeAudioMenu()")
        close shouldContain "document.removeEventListener('visibilitychange', _audioMenuPointerLost, true);"
        close shouldContain "window.removeEventListener('blur', _audioMenuPointerLost);"
        close shouldNotContain "window.removeEventListener('blur', _audioMenuPointerLost, true);"
        close shouldContain "document.removeEventListener('pointerout', _audioMenuPointerLost, true);"
    }

    @Test
    fun `dropping an audio request cancels the native stream`() {
        // A prune drops the handler *and* tells the bridge: otherwise it keeps resolving every
        // configured source for a word nobody displays and posts snapshots for an ignored id.
        val prune = functionBlock(rendererJs, "function pruneAudioRequests(")
        prune shouldContain "_audioCallbacks.delete(callbackId)"
        prune shouldContain "cancelAudioRequest(callbackId)"
        // `clear()` is the wholesale reset and cancels every callback it drops itself (the dropped
        // keys and the cancel share the same source, so neither can silently disappear).
        val clear = functionBlock(rendererJs, "function clear()")
        clear shouldContain "Array.from(_audioCallbacks.keys()).forEach(cancelAudioRequest)"
        clear shouldContain "_audioCallbacks.clear()"
        (clear.indexOf("forEach(cancelAudioRequest)") < clear.indexOf("_audioCallbacks.clear()")) shouldBe true
        // Guarded: the renderer also runs where the bridge (or this method) does not exist.
        val cancel = functionBlock(rendererJs, "function cancelAudioRequest(")
        cancel shouldContain "typeof bridge.cancelAudio === 'function'"
        cancel shouldContain "_audioNative()"
    }

    @Test
    fun `the bridge can abandon a stream and forgets the finished job`() {
        Regex("@JavascriptInterface\\s+fun cancelAudio\\(callbackId: String\\)")
            .containsMatchIn(bridgeSource) shouldBe true
        val cancel = functionBlock(bridgeSource, "fun cancelAudio(")
        // Unknown / already finished ids are a no-op instead of an exception or a re-delivery.
        cancel shouldContain "fetchJobs.remove(callbackId) ?: return"
        cancel shouldContain "job.cancel()"
        cancel shouldNotContain "deliver("
        // One job per callback id, registered *before* it starts (a lazy launch, so a cancel that
        // arrives immediately after `fetchAudio` finds it) and dropped when it completes.
        val fetch = functionBlock(bridgeSource, "fun fetchAudio(")
        fetch shouldContain "scope.launch(start = CoroutineStart.LAZY)"
        bridgeSource shouldContain "import kotlinx.coroutines.CoroutineStart"
        fetch shouldContain "fetchJobs[callbackId] = job"
        fetch shouldContain "job.invokeOnCompletion { fetchJobs.remove(callbackId, job) }"
        val register = fetch.indexOf("fetchJobs[callbackId] = job")
        val start = fetch.indexOf("job.start()")
        (register >= 0 && start > register) shouldBe true
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
