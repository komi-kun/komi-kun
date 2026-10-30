// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.model

import androidx.annotation.Keep

/** Frequency values for one dictionary. Mirrors `struct FrequencyEntry` (hoshidicts `query.hpp`). */
@Keep
data class FrequencyEntry(
    val dictName: String,
    val frequencies: List<Frequency>,
)
