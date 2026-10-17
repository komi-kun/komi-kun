// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Ported from Chimahon (https://github.com/Chimahon/Chimahon, GPL-3.0-or-later),
// `chimahon/src/main/java/chimahon/ocr/CropPresets.kt` @ f85c063.
// KUN <--
package mihon.core.ocr

object CropPresets {

    data class AspectRatioPreset(
        val key: String,
        val label: String,
        val x: Int,
        val y: Int,
    )

    /** Aspect ratio presets — auto-crop a region of this ratio centered on the tapped text. */
    val ASPECT_RATIO_PRESETS = listOf(
        AspectRatioPreset("1:1", "1:1", 1, 1),
        AspectRatioPreset("4:3", "4:3", 4, 3),
        AspectRatioPreset("3:2", "3:2", 3, 2),
        AspectRatioPreset("16:9", "16:9", 16, 9),
        AspectRatioPreset("9:16", "9:16", 9, 16),
        AspectRatioPreset("4:5", "4:5", 4, 5),
        AspectRatioPreset("3:4", "3:4", 3, 4),
    )

    private val aspectByKey = ASPECT_RATIO_PRESETS.associateBy { it.key }

    fun aspectByKey(key: String): AspectRatioPreset? = aspectByKey[key]
}
