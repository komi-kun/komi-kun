// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.model

import androidx.annotation.Keep

/** A kanji lookup result. Mirrors `struct KanjiResult` (hoshidicts `query.hpp`). */
@Keep
data class KanjiResult(
    val character: String,
    val entries: List<KanjiEntry>,
)
