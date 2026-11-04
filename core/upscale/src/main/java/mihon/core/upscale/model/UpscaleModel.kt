// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The upscaling model descriptions — pure data, no Android, no inference.
//
// A model is identified by a stable snake_case [UpscaleModel.id] (what the preferences store and what
// the downloader/cache key on) and by [UpscaleModel.fileName], the ONNX file the model release
// manifest carries. The manifest owns the bytes and their SHA-256; nothing here pins a hash, so a
// re-exported model file is a manifest change, not a code change.
// KUN <--
package mihon.core.upscale.model

/** Which family a model belongs to; the routing policy never mixes them (see `UpscaleRouter`). */
enum class ModelFamily {
    /** MangaJaNai — line art, trained per target height ("bucket"). */
    MANGA_JANAI,

    /** IllustrationJaNai — colour pages. */
    ILLUSTRATION_JANAI,
}

/** The network architecture, as the file name states it. */
enum class ModelArch {
    ESRGAN,
    FDAT_M,
    SPAN_S,
}

/**
 * One selectable upscaling model.
 *
 * [bucketHeight] is the page height the model was trained for — present for the MangaJaNai family
 * only, `null` for IllustrationJaNai. [approxBytes] is the published file size from the release
 * manifest, for the settings screen's size label; the manifest is still the authority on the bytes.
 */
data class UpscaleModel(
    val id: String,
    val fileName: String,
    val family: ModelFamily,
    val scale: Int,
    val bucketHeight: Int?,
    val arch: ModelArch,
    val displayName: String,
    val approxBytes: Long,
) {
    init {
        require(id.isNotEmpty()) { "id must not be empty" }
        require(fileName.endsWith(".onnx")) { "fileName must be an ONNX file: $fileName" }
        require(scale > 0) { "scale must be positive: $scale" }
        require((family == ModelFamily.MANGA_JANAI) == (bucketHeight != null)) {
            "$id: bucketHeight is manga-only (family=$family, bucketHeight=$bucketHeight)"
        }
        require(approxBytes > 0) { "$id: approxBytes must be positive: $approxBytes" }
    }
}
