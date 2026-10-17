// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.model

import androidx.annotation.Keep

/** One deinflected lookup hit. Mirrors `struct LookupResult` (hoshidicts `lookup.hpp`). */
@Keep
data class LookupResult(
    val matched: String,
    val deinflected: String,
    val process: List<TransformGroup>,
    val term: TermResult,
    val preprocessorSteps: Int,
)
