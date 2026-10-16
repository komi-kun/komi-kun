// SPDX-License-Identifier: Apache-2.0
package tachiyomi.core.common.ocr

/**
 * Per-manga OCR source policy (contract §1.3).
 *
 * Apache-2.0 so both the GPL `:core:ocr` services and GPL-free `:app` files (e.g. `ReaderPreferences`)
 * can share it without either side importing the other's package. Shrunk from Chimahon's original:
 * only `AUTOMATIC`/`MOKURO`/`PADDLE` remain (`GOOGLE_LENS`/`LOCAL` and the `recognitionEngine`/
 * `OcrEngineType` coupling are dropped — the port is Paddle-only).
 */
enum class ReaderOcrSource(
    val usesMokuro: Boolean,
    val usesPersistentCache: Boolean,
    val persistentCacheVariant: String? = null,
) {
    AUTOMATIC(
        usesMokuro = true,
        usesPersistentCache = true,
    ),
    MOKURO(
        usesMokuro = true,
        usesPersistentCache = false,
    ),
    PADDLE(
        usesMokuro = false,
        usesPersistentCache = false,
        persistentCacheVariant = "paddle",
    ),
    ;

    val persistsOcrResults: Boolean
        get() = usesPersistentCache || persistentCacheVariant != null

    companion object {
        fun availableSources(localOcrAvailable: Boolean, mokuroAvailable: Boolean): List<ReaderOcrSource> {
            return entries.filter { source ->
                when (source) {
                    PADDLE -> localOcrAvailable
                    MOKURO -> mokuroAvailable
                    else -> true
                }
            }
        }
    }
}
