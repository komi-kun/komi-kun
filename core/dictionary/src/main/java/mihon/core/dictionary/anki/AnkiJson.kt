// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Shared kotlinx.serialization entry point for the Anki feature (plan 05 §A3): replaces
// Chimahon's `org.json` usage so the profile store and marker engine run on the JVM.
package mihon.core.dictionary.anki

import kotlinx.serialization.json.Json

/** Lenient JSON used for Anki profile persistence and structured-glossary parsing. */
internal val AnkiJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
    explicitNulls = false
}
// KUN <--
