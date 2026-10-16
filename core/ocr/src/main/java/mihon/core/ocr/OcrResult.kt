// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Ported from Chimahon (https://github.com/Chimahon/Chimahon, GPL-3.0-or-later),
// `chimahon/src/main/java/chimahon/ocr/OcrResult.kt` @ f85c063.
// KUN <--
package mihon.core.ocr

/** Pixel-relative (not normalized) bounding box: origin + size, with optional rotation in degrees. */
data class BoundingBox(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val rotation: Double? = null,
)

/** A merged paragraph produced by the merger, before it is flattened into an [OcrTextBlock]. */
data class OcrResult(
    val text: String,
    val tightBoundingBox: BoundingBox,
    val isMerged: Boolean? = null,
    val forcedOrientation: String? = null,
    val constituentBoxes: List<BoundingBox>? = null,
)

/** One pre-merge image chunk and where it sits inside the full page. */
data class RawChunk(
    val lines: List<OcrResult>,
    val width: Int,
    val height: Int,
    val globalY: Int,
    val fullWidth: Int,
    val fullHeight: Int,
)

/** Debug/benchmark view of the merger input and output. */
data class OcrDebugResult(
    val rawChunks: List<RawChunk>,
    val mergedResults: List<OcrResult>,
)

/** Tuning knobs for the line merger. Image dimensions are filled in by the pipeline. */
data class MergeConfig(
    val enabled: Boolean = true,
    val language: OcrLanguage = OcrLanguage.JAPANESE,
    val addSpaceOnMerge: Boolean? = null,
    val furiganaFilter: Boolean = true,
    val mergeCloseParagraphs: Boolean = true,
    val supportCenterAlignedText: Boolean = true,
    val imageWidth: Double? = null,
    val imageHeight: Double? = null,
    // Legacy field kept for backward compat with the old Lens merger.
    @Deprecated("Use characterSize-relative thresholds instead")
    val fontSizeRatio: Double = 3.0,
)
