// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.engine

import mihon.core.dictionary.model.DeinflectionResult

/**
 * Dictionary-independent transform surface of the vendored `hoshidicts` engine.
 *
 * This is the seam the Yomitan conformance runner (T2,
 * `docs/design/implementation/05-testing-and-anki.md` §A1.5) executes: no session, no dictionary
 * data, the same native code path the lookup uses internally. `internal`, because the app
 * consumes transforms through [DictionarySession.lookup]/[mihon.core.dictionary.model.LookupResult]
 * only — nothing outside a test may depend on it.
 *
 * ## Semantics (must match `hoshidicts`, not Yomitan's JavaScript)
 *
 * - [deinflect] returns **every** candidate `Deinflector::deinflect` produces, including the
 *   identity candidate ([DeinflectionResult.text] == input with an empty trace and
 *   `conditions == 0`). Candidates are unordered: the engine does not rank here (ranking happens
 *   in `Lookup`).
 * - [DeinflectionResult.trace] is in **application order**: the transform nearest the source
 *   suffix first, the dictionary-form transform last. Yomitan's `LanguageTransformer` prepends
 *   each new frame (`_extendTrace`), so its fixtures list the same transforms in the **reverse**
 *   order. Compare with `trace.map { it.name }.reversed()`.
 * - [DeinflectionResult.conditions] is the `conditionsOut` bitmask of the last applied rule
 *   (`0` for the identity candidate). [posConditions] ORs together the masks of the tags the
 *   engine knows (`v1`, `v5`, `vk`, `vs`, `vz`, `adj-i`); **tags it does not know are silently
 *   ignored**, so a mixed list yields the partial mask of its known tags, and only a list with no
 *   known tag at all yields `0`. Callers must therefore treat `0` as "unmapped, cannot be checked",
 *   never as "matches everything": a partially mapped list must not be used as a condition
 *   constraint either, which is why the conformance runner only checks `rule` when the whole tag
 *   set is mapped.
 * - [normalizationVariants] returns the **whole** preprocessing variant set
 *   (`text_processor::process`): every combination of katakana↔hiragana, emphatic collapsing
 *   (partial and full), NFKC, full-width alphanumerics, kanji standardisation, iteration-mark
 *   expansion and number-to-kanji forms, in code-point order. It is a *set*, not a single result:
 *   the engine fuses Yomitan's individual `japanese-util` ops into one pipeline and has no per-op
 *   entry point, so a conformance expectation is "this output is reachable", not "this is the
 *   output".
 */
internal object DictionaryTransforms {

    /** All deinflections of [text]; empty source text yields the identity candidate only. */
    fun deinflect(text: String): List<DeinflectionResult> {
        ensureLoaded()
        return HoshiDictsNative.deinflect(text).toList()
    }

    /**
     * Digest of the engine sources the loaded library was compiled from (host builds only; the
     * Android `.so` returns `""`). The T2 runner compares it against the `source_fingerprint`
     * recorded next to the host library, so a library that does not belong to its build record —
     * i.e. a stale engine — cannot silently be the one under test.
     */
    fun buildFingerprint(): String {
        ensureLoaded()
        return HoshiDictsNative.engineBuildFingerprint()
    }

    /** Engine POS bitmask for [partOfSpeech]; `0` only when *no* tag is mapped (see the class doc). */
    fun posConditions(partOfSpeech: List<String>): Int {
        ensureLoaded()
        return HoshiDictsNative.posToConditions(partOfSpeech.toTypedArray())
    }

    /** Every preprocessing variant of [text] (see the class doc for the pipeline). */
    fun normalizationVariants(text: String): List<String> {
        ensureLoaded()
        return HoshiDictsNative.textVariants(text).toList()
    }

    /** `hoshidicts_jni` is loaded lazily; a plain JVM without the host library must fail loudly. */
    private fun ensureLoaded() {
        check(runCatching { HoshiDictsNative.ensureLoaded() }.isSuccess) {
            "hoshidicts_jni is not available on this platform (expected a host build on java.library.path)"
        }
    }
}
// KUN <--
