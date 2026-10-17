// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.model

import androidx.annotation.Keep

/** Pitch-accent positions for one dictionary. Mirrors `struct PitchEntry` (hoshidicts `query.hpp`). */
@Keep
data class PitchEntry(
    val dictName: String,
    val pitchPositions: IntArray,
    val transcriptions: List<String>,
)
