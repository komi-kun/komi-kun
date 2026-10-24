// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.model

import androidx.annotation.Keep

/**
 * One deinflection candidate produced by the vendored engine's `Deinflector` (hoshidicts
 * `deinflector.hpp`). Mirrors `struct DeinflectionResult` across the JNI boundary.
 *
 * [text] is the deinflected form, [trace] the transforms that produced it and [conditions] the
 * engine's POS bitmask for [text] (`0` when no transform was applied). See
 * `mihon.core.dictionary.engine.DictionaryTransforms` for the exact ordering/bitmask semantics.
 */
@Keep
data class DeinflectionResult(
    val text: String,
    val conditions: Int,
    val trace: List<TransformGroup>,
)
