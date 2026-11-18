package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil

/**
 * The image of [page] as a pager shows it: a wide page rotated to fit or split in two, the half on the reading
 * side first, so [isLeftToRight] tells which. [onPageSplit] is told of the [InsertPage] for the second half.
 *
 * KUN --> [sideMargin] is the centre margin the fork adds to each half of a double page, and [onSide] is told
 * which half a split kept: the fork's OCR overlay maps block coordinates back through that side, and the stream
 * it comes back with no longer says which half it holds.
 */
fun DualPageSettings.processPagedImage(
    page: ReaderPage,
    imageSource: BufferedSource,
    isLeftToRight: Boolean,
    // KUN -->
    sideMargin: Int = 0,
    onSide: (ImageUtil.Side) -> Unit = {},
    // KUN <--
    onPageSplit: (InsertPage) -> Unit,
): BufferedSource {
    if (dualPageRotateToFit) {
        return rotateDualPage(imageSource)
    }

    if (!dualPageSplit) {
        return imageSource
    }

    if (page is InsertPage) {
        // KUN --> the split half is known here, see the KDoc
        return splitInHalf(imageSource, page, isLeftToRight, sideMargin, onSide)
        // KUN <--
    }

    val isDoublePage = ImageUtil.isWideImage(imageSource)
    if (!isDoublePage) {
        return imageSource
    }

    onPageSplit(InsertPage(page))

    // KUN --> the split half is known here, see the KDoc
    return splitInHalf(imageSource, page, isLeftToRight, sideMargin, onSide)
    // KUN <--
}

/**
 * The image of a page as a strip shows it: a wide page rotated to fit, or split with its halves one above the other.
 *
 * KUN --> [onSplit] is told the side a split put on top, which the fork's OCR overlay maps through.
 */
fun DualPageSettings.processStripImage(
    imageSource: BufferedSource,
    // KUN -->
    onSplit: (ImageUtil.Side) -> Unit = {},
    // KUN <--
): BufferedSource {
    if (dualPageRotateToFit) {
        return rotateDualPage(imageSource)
    }

    if (dualPageSplit) {
        val isDoublePage = ImageUtil.isWideImage(imageSource)
        if (isDoublePage) {
            val upperSide = if (dualPageInvert) ImageUtil.Side.LEFT else ImageUtil.Side.RIGHT
            // KUN -->
            onSplit(upperSide)
            // KUN <--
            return ImageUtil.splitAndMerge(imageSource, upperSide)
        }
    }

    return imageSource
}

private fun DualPageSettings.rotateDualPage(imageSource: BufferedSource): BufferedSource {
    val isDoublePage = ImageUtil.isWideImage(imageSource)
    return if (isDoublePage) {
        val rotation = if (dualPageRotateToFitInvert) -90f else 90f
        ImageUtil.rotateImage(imageSource, rotation)
    } else {
        imageSource
    }
}

// KUN -->
// The fork's version of upstream's helper: it takes the centre margin a double page's halves get,
// and reports the side it kept so the OCR overlay can map through it.
private fun DualPageSettings.splitInHalf(
    imageSource: BufferedSource,
    page: ReaderPage,
    isLeftToRight: Boolean,
    sideMargin: Int,
    onSide: (ImageUtil.Side) -> Unit,
): BufferedSource {
    var side = when {
        isLeftToRight && page is InsertPage -> ImageUtil.Side.RIGHT
        !isLeftToRight && page is InsertPage -> ImageUtil.Side.LEFT
        isLeftToRight && page !is InsertPage -> ImageUtil.Side.LEFT
        else -> ImageUtil.Side.RIGHT
    }

    if (dualPageInvert) {
        side = when (side) {
            ImageUtil.Side.RIGHT -> ImageUtil.Side.LEFT
            ImageUtil.Side.LEFT -> ImageUtil.Side.RIGHT
        }
    }

    onSide(side)
    return ImageUtil.splitInHalf(imageSource, side, sideMargin)
}
// KUN <--
