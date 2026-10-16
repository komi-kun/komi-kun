// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Runtime `.mokuro` path (fix B1): turn a chapter's `.mokuro` sidecar into the reader's OCR blocks.
//
// The file discovery (sibling file / downloaded chapter dir / CBZ sidecar) happens app-side over
// `UniFile`; this object keeps the pure, unit-testable half: sidecar-name selection and the
// parse → resolve → convert composition over the existing `mihon.core.ocr.mokuro` primitives.
// KUN <--
package mihon.core.ocr.cache

import mihon.core.ocr.model.OcrTextBlock
import mihon.core.ocr.mokuro.ImageFileInfo
import mihon.core.ocr.mokuro.Mokuro

/**
 * Reads `.mokuro` sidecars into viewer blocks. See the module `mihon.core.ocr.mokuro` parser for
 * the JSON shape; this only glues [Mokuro] together with the cache's page selection.
 */
object MokuroChapterLoader {

    /** The `.mokuro` sidecar extension (case-insensitive match). */
    const val MOKURO_EXTENSION: String = ".mokuro"

    /**
     * Parses [content], resolves it against the chapter's [imageFiles] and returns the blocks of
     * [pageIndex], or null when the JSON is malformed, the page cannot be resolved, or it carries
     * no usable boxes. An empty result means "no `.mokuro` data for this page" so the caller can
     * fall back to the engine.
     */
    fun blocksForPage(
        content: String,
        imageFiles: List<ImageFileInfo>,
        pageIndex: Int,
    ): List<OcrTextBlock>? {
        val volume = Mokuro.parseMokuro(content) ?: return null
        val page = Mokuro.resolveMokuroPage(volume, imageFiles, pageIndex) ?: return null
        return Mokuro.convertMokuroBlocks(page).takeIf { it.isNotEmpty() }
    }

    /**
     * Picks the `.mokuro` name to use from the [presentNames] available around a chapter location.
     *
     * Priority (fix B1 discovery): the exact chapter name (`<name>.mokuro`), then the stem for an
     * archive (`<stem>.mokuro`, e.g. `Chapter 1.cbz` → `Chapter 1.mokuro`), then any `.mokuro`
     * (sorted, so a directory holding a single sidecar resolves deterministically).
     */
    fun selectSidecar(
        chapterName: String,
        presentNames: Collection<String>,
    ): String? {
        val exact = "$chapterName$MOKURO_EXTENSION"
        presentNames.firstOrNull { it.equals(exact, ignoreCase = true) }?.let { return it }

        val stem = chapterName.substringBeforeLast('.', missingDelimiterValue = chapterName)
        if (stem.isNotEmpty() && !stem.equals(chapterName, ignoreCase = true)) {
            val byStem = "$stem$MOKURO_EXTENSION"
            presentNames.firstOrNull { it.equals(byStem, ignoreCase = true) }?.let { return it }
        }

        return presentNames
            .filter { it.endsWith(MOKURO_EXTENSION, ignoreCase = true) }
            .minOrNull()
    }
}
