// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.model

import androidx.annotation.Keep

/**
 * Result of importing a Yomitan dictionary ZIP. Mirrors `struct ImportResult` in
 * `hoshidicts/include/hoshidicts/importer.hpp` and the JNI constructor signature in
 * `core/dictionary/src/main/cpp/hoshidicts_jni.cpp` (contract §2.1/§3.5).
 */
@Keep
data class ImportResult(
    val success: Boolean,
    val title: String,
    val termCount: Long,
    val metaCount: Long,
    val freqCount: Long,
    val pitchCount: Long,
    val kanjiCount: Long,
    val mediaCount: Long,
    val errors: List<String> = emptyList(),
)
