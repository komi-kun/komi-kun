// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.model

import androidx.annotation.Keep

/** A single glossary definition. Mirrors `struct GlossaryEntry` (hoshidicts `query.hpp`). */
@Keep
data class GlossaryEntry(
    val dictName: String,
    val glossary: String,
    val definitionTags: String,
    val termTags: String,
)
