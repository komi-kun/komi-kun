// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Ported from Chimahon (https://github.com/Chimahon/Chimahon, GPL-3.0-or-later),
// `chimahon/src/main/java/chimahon/ocr/MokuroParser.kt` @ f85c063.
// KUN <--
package mihon.core.ocr.mokuro

import kotlinx.serialization.json.Json

private val mokuroJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** Parses a `.mokuro` sidecar leniently; returns null on malformed JSON. */
fun parseMokuro(content: String): MokuroVolume? {
    return runCatching {
        mokuroJson.decodeFromString<MokuroVolume>(content)
    }.getOrNull()
}
