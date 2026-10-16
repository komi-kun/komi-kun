// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.model

import androidx.annotation.Keep

/** A single frequency value. Mirrors `struct Frequency` (hoshidicts `query.hpp`). */
@Keep
data class Frequency(
    val value: Int,
    val displayValue: String,
)
