// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Ported from Chimahon (https://github.com/Chimahon/Chimahon, GPL-3.0-or-later),
// `chimahon/src/main/java/chimahon/ocr/OcrBlockData.kt` @ f85c063.
// The Chimahon `OcrTextBlock` DTO is *not* ported here: it is unified with the viewer model in
// `mihon.core.ocr.model.OcrTextBlock` (contract §2.2, 03 §3.3) to avoid two parallel block types.
// KUN <--
package mihon.core.ocr

import kotlinx.serialization.Serializable
import mihon.core.ocr.model.OcrTextBlock

/** Geometry of an individual line inside a block, normalized 0–1 with rotation in degrees. */
@Serializable
data class OcrLineGeometry(
    val xmin: Float,
    val ymin: Float,
    val xmax: Float,
    val ymax: Float,
    val rotation: Float = 0f,
)

/** Serializable cache DTO for one OCR block (contract §2.2). */
@Serializable
data class OcrBlockData(
    val xmin: Float,
    val ymin: Float,
    val xmax: Float,
    val ymax: Float,
    val lines: List<String>,
    val vertical: Boolean,
    val lineGeometries: List<OcrLineGeometry>? = null,
    val language: String = "",
)

/** Serializable cache DTO for one OCR page: its blocks plus the language they were read in. */
@Serializable
data class OcrPageData(
    val blocks: List<OcrBlockData>,
    val language: String,
    val version: Int = 2,
)

/** Maps a viewer block to its serializable cache form. */
fun OcrTextBlock.toBlockData(): OcrBlockData = OcrBlockData(
    xmin = xmin,
    ymin = ymin,
    xmax = xmax,
    ymax = ymax,
    lines = lines,
    vertical = vertical,
    lineGeometries = lineGeometries,
    language = language,
)

/** Maps a serialized cache block back to the viewer model. */
fun OcrBlockData.toTextBlock(): OcrTextBlock = OcrTextBlock(
    xmin = xmin,
    ymin = ymin,
    xmax = xmax,
    ymax = ymax,
    lines = lines,
    vertical = vertical,
    lineGeometries = lineGeometries,
    language = language,
)
