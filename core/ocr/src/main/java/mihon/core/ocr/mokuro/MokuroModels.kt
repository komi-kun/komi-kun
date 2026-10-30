// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Ported from Chimahon (https://github.com/Chimahon/Chimahon, GPL-3.0-or-later),
// `chimahon/src/main/java/chimahon/ocr/MokuroModels.kt` @ f85c063.
// KUN <--
package mihon.core.ocr.mokuro

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MokuroVolume(
    val version: String,
    val title: String,
    @SerialName("title_uuid") val titleUuid: String,
    val volume: String,
    @SerialName("volume_uuid") val volumeUuid: String,
    val pages: List<MokuroPage>,
)

@Serializable
data class MokuroPage(
    @SerialName("img_path") val imgPath: String,
    @SerialName("img_width") val imgWidth: Int,
    @SerialName("img_height") val imgHeight: Int,
    val blocks: List<MokuroBlock>,
)

@Serializable
data class MokuroBlock(
    val box: List<Float>,
    val vertical: Boolean,
    val lines: List<String>,
    @SerialName("lines_coords") val linesCoords: List<List<List<Float>>> = emptyList(),
)
