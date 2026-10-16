// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Ported from Chimahon (https://github.com/Chimahon/Chimahon, GPL-3.0-or-later),
// `chimahon/src/main/java/chimahon/ocr/MokuroOps.kt` @ f85c063.
// A thin facade so callers need only one import; the top-level functions remain available.
// KUN <--
package mihon.core.ocr.mokuro

import mihon.core.ocr.model.OcrTextBlock

object Mokuro {
    fun parseMokuro(content: String): MokuroVolume? = mihon.core.ocr.mokuro.parseMokuro(content)

    fun resolveMokuroPage(
        mokuro: MokuroVolume,
        chapterImageFiles: List<ImageFileInfo>,
        pageIndex: Int,
    ): MokuroPage? = mihon.core.ocr.mokuro.resolveMokuroPage(mokuro, chapterImageFiles, pageIndex)

    fun convertMokuroBlocks(page: MokuroPage): List<OcrTextBlock> =
        mihon.core.ocr.mokuro.convertMokuroBlocks(page)
}
