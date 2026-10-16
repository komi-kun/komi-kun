// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.model

import androidx.annotation.Keep

/** A term (word) result. Mirrors `struct TermResult` (hoshidicts `query.hpp`). */
@Keep
data class TermResult(
    val expression: String,
    val reading: String,
    val rules: String,
    val score: Int,
    val glossaries: List<GlossaryEntry>,
    val frequencies: List<FrequencyEntry>,
    val pitches: List<PitchEntry>,
)
