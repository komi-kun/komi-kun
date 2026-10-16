// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.model

import androidx.annotation.Keep

/** Per-dictionary CSS styles. Mirrors `struct DictionaryStyle` (hoshidicts `query.hpp`). */
@Keep
data class DictionaryStyle(
    val dictName: String,
    val styles: String,
)
