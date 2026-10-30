// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.model

import androidx.annotation.Keep

/** A kanji dictionary entry. Mirrors `struct KanjiEntry` (hoshidicts `query.hpp`). */
@Keep
data class KanjiEntry(
    val dictName: String,
    val onyomi: String,
    val kunyomi: String,
    val tags: String,
    val definitions: List<String>,
    val stats: Map<String, String>,
)
