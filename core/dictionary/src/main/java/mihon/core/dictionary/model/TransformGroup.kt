// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.model

import androidx.annotation.Keep

/** One deinflection step. Mirrors `struct TransformGroup` (hoshidicts `lookup.hpp`). */
@Keep
data class TransformGroup(
    val name: String,
    val description: String,
)
