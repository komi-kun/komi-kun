// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Ported from Chimahon (https://github.com/Chimahon/Chimahon, GPL-3.0-or-later),
// `chimahon/src/main/java/chimahon/ocr/MokuroPageResolver.kt` @ f85c063.
// KUN <--
package mihon.core.ocr.mokuro

data class ImageFileInfo(
    val name: String,
    val relativePath: String,
    val basename: String,
)

/**
 * Matches a chapter page to its `.mokuro` page: first by `img_path` suffix, then by basename, and
 * finally by position. Returns null when there is no chapter image at [pageIndex].
 */
fun resolveMokuroPage(
    mokuro: MokuroVolume,
    chapterImageFiles: List<ImageFileInfo>,
    pageIndex: Int,
): MokuroPage? {
    if (pageIndex !in chapterImageFiles.indices) return null
    val imageFile = chapterImageFiles[pageIndex]

    mokuro.pages.forEach { mokuroPage ->
        val mokuroPath = mokuroPage.imgPath
        val mokuroBasename = mokuroPath.substringAfterLast('/').substringBeforeLast('.')

        if (mokuroPath.endsWith(imageFile.name, ignoreCase = true)) {
            return mokuroPage
        }

        if (mokuroBasename.equals(imageFile.basename, ignoreCase = true)) {
            return mokuroPage
        }
    }

    if (pageIndex < mokuro.pages.size) {
        return mokuro.pages[pageIndex]
    }

    return null
}
