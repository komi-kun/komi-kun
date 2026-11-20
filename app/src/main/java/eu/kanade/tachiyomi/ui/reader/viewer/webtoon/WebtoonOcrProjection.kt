// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Projection arithmetic for the webtoon OCR adapter, kept beside the other OCR coordinate helpers
// and deliberately free of Android types (as `OcrPopupFit` is): the adapter reads the live view
// state - the strip's origin in overlay space and the zoom its ancestors apply - and hands it here,
// so the forward and the inverse mapping can be checked against each other on the JVM.
//
// Why the zoom needs an explicit multiplier: in the webtoon reader the zoom is a layer transform
// (`WebtoonRecyclerView.setScaleRate` sets `scaleX`/`scaleY`/`x`/`y` on the recycler), so the strip
// view's *layout* size never changes - `width`/`height` stay the unzoomed numbers. A window-space
// *point* (`View.getLocationInWindow`, i.e. `OcrOverlayCoords.offsetOf`) is matrix-mapped through the
// zoomed ancestors and therefore carries their translation, but an *extent* re-derived from a layout
// property cannot carry their scale; it has to be multiplied in.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import kotlin.math.roundToInt

/** A rectangle in one of the reader's OCR spaces: image-normalized (`0..1`), or overlay pixels. */
internal data class OcrRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** A point in one of the reader's OCR spaces. */
internal data class OcrPoint(
    val x: Float,
    val y: Float,
)

/**
 * Image-normalized [block] (`0..1` of the strip image) projected to overlay pixels.
 *
 * [originX]/[originY] is the strip's top-left corner in overlay coordinates and [scaleX]/[scaleY] is
 * the zoom its ancestors apply (1 with no zoom). Both must be read live, from the same view state
 * the overlay is drawn against: together they are `offset + scale ⊙ (normalized ⊙ sheetSize)`, the
 * affine map from the strip's layout space to the overlay's.
 */
internal fun projectImageNormalizedToScreen(
    block: OcrRect,
    sheetWidth: Float,
    sheetHeight: Float,
    originX: Float,
    originY: Float,
    scaleX: Float,
    scaleY: Float,
): OcrRect = OcrRect(
    originX + block.left * sheetWidth * scaleX,
    originY + block.top * sheetHeight * scaleY,
    originX + block.right * sheetWidth * scaleX,
    originY + block.bottom * sheetHeight * scaleY,
)

/**
 * An overlay point in the local coordinates of a view whose top-left sits at [originX]/[originY] in
 * overlay space and which is drawn under [scaleX]/[scaleY] of ancestor zoom.
 *
 * That is the space `RecyclerView.findChildViewUnder` and the strip's own `width`/`height` are
 * measured in: both are layout spaces, i.e. *before* the ancestors' scale.
 */
internal fun overlayToViewLocal(
    x: Float,
    y: Float,
    originX: Float,
    originY: Float,
    scaleX: Float,
    scaleY: Float,
): OcrPoint = OcrPoint(
    (x - originX) / scaleX,
    (y - originY) / scaleY,
)

/**
 * The exact forward inverse of [overlayToViewLocal]: a point in a view's own (unzoomed) local space →
 * overlay pixels, under the same [originX]/[originY] and [scaleX]/[scaleY].
 *
 * This is the conversion a *touch* needs. Android delivers a MotionEvent to a view in that view's
 * local space - `ViewGroup` maps the window point through the inverse of the child's transform before
 * dispatching - so `MotionEvent.getX()` is a layout coordinate, not an overlay one, and the ancestor
 * zoom has to be multiplied back in. Using the origin alone (the pre-fix `toOverlayPoint`) leaves the
 * tap path in a space that is neither: it draws at `origin + scale·local` but resolves at
 * `origin + local`, and the two agree only at scale 1.
 */
internal fun overlayPointOfViewLocal(
    x: Float,
    y: Float,
    originX: Float,
    originY: Float,
    scaleX: Float,
    scaleY: Float,
): OcrPoint = OcrPoint(
    originX + x * scaleX,
    originY + y * scaleY,
)

/**
 * The token [WebtoonOcrViewport.transformVersion] builds: every input that changes what the overlay
 * must project, folded into one value.
 *
 * - The **scroll offset** and the visible holders' identities: a page turn or a reload re-lays the
 *   strips out.
 * - Each holder's **transform version**: the sheet's own decode finished, so the border crop it went
 *   through is now known (`WebtoonPageHolder.onImageDecoded` wraps the transform). Blocks can resolve
 *   *before* their page's image does - the loader polls every frame and the strip's layout size is
 *   already non-zero - so without this term the overlay would keep drawing them at the pre-crop
 *   positions until something else moved. The paged backend is immune because `SsivOcrViewport`
 *   already folds its holders' versions in.
 * - The **zoom and pan**: a pinch moves neither the scroll offset nor the holder set - the zoom is a
 *   layer transform (`WebtoonRecyclerView.setScaleRate`) and the pan is the view's `x`/`y`
 *   translation - so without these the boxes keep the pre-zoom projection for the whole gesture.
 *
 * Pure so the composition can be checked on the JVM: dropping any term makes two different geometries
 * share a token, which is exactly the "boxes stay where they were" defect this is here to prevent.
 */
internal fun webtoonOcrTransformToken(
    scrollOffset: Int,
    holderIds: List<Int>,
    holderTransformVersions: List<Long>,
    scaleX: Float,
    scaleY: Float,
    translationX: Float,
    translationY: Float,
): Long {
    var token = scrollOffset.toLong() * 31L + holderIds.sumOf { it.toLong() }
    token = token * 31L + holderTransformVersions.sum()
    token = token * 31L + scaleX.toRawBits().toLong()
    token = token * 31L + scaleY.toRawBits().toLong()
    token = token * 31L + translationX.toRawBits().toLong()
    token = token * 31L + translationY.toRawBits().toLong()
    return token
}

/**
 * A point in the recycler's own layout space → overlay pixels: the conversion the webtoon reader's
 * tap path hands [WebtoonOcrViewport.overlayPointOfRecyclerLocal].
 *
 * [originX]/[originY] is the recycler's top-left in overlay space and [scaleX]/[scaleY] the zoom its
 * ancestors apply. Both are read live from the same views the draw path projects with, which is what
 * keeps a tap and the box drawn under the finger in one space - dropping the scale here (the
 * `ea8552cb10` defect) leaves the tap in a third space that is `origin + local`.
 */
internal fun recyclerLocalToOverlay(
    x: Float,
    y: Float,
    originX: Float,
    originY: Float,
    scaleX: Float,
    scaleY: Float,
): OcrPoint = overlayPointOfViewLocal(x, y, originX, originY, scaleX, scaleY)

/**
 * The overlay-space bounding box of a rect in a view's own (layout) space, through [originX]/
 * [originY] and the ancestor zoom [scaleX]/[scaleY] - the SSIV escape hatch's draw path, whose
 * `sourceToViewCoord` corners are layout coordinates like the strip's are.
 *
 * The corners are mapped and re-ordered rather than mapped as `origin + min * scale`, so the box is
 * still a box if the scale is negative (it never is: the webtoon zoom is clamped to `0.5..3`).
 */
internal fun overlayRectOfViewRect(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    originX: Float,
    originY: Float,
    scaleX: Float,
    scaleY: Float,
): OcrRect {
    val topLeft = overlayPointOfViewLocal(left, top, originX, originY, scaleX, scaleY)
    val bottomRight = overlayPointOfViewLocal(right, bottom, originX, originY, scaleX, scaleY)
    return OcrRect(
        left = minOf(topLeft.x, bottomRight.x),
        top = minOf(topLeft.y, bottomRight.y),
        right = maxOf(topLeft.x, bottomRight.x),
        bottom = maxOf(topLeft.y, bottomRight.y),
    )
}

/**
 * An overlay-pixel distance as a `RecyclerView.scrollBy` / `computeVerticalScrollOffset` distance.
 *
 * Scrolling moves the view's *layout*, which the zoom does not change, so a distance measured in
 * overlay pixels is that many layout pixels only at scale 1; at zoom `s` the view has to move
 * `overlay / s` layout pixels to cover the same on-screen distance.
 *
 * A non-positive scale cannot happen (the webtoon zoom is clamped to `0.5..3`); it returns 0 - no
 * move - rather than an unbounded or infinite delta.
 */
internal fun overlayDistanceToScroll(overlayPixels: Float, scrollScale: Float): Int {
    if (scrollScale <= 0f) return 0
    return (overlayPixels / scrollScale).roundToInt()
}

/**
 * The exact inverse of [projectImageNormalizedToScreen]: an overlay point → image-normalized
 * coordinates, or null when it lands outside the strip (`0..1` on either axis).
 *
 * Draw and hit-test share these multipliers by construction, so a tap can never drift off the block
 * that was drawn under the finger.
 */
internal fun projectScreenToImageNormalized(
    x: Float,
    y: Float,
    sheetWidth: Float,
    sheetHeight: Float,
    originX: Float,
    originY: Float,
    scaleX: Float,
    scaleY: Float,
): OcrPoint? {
    if (sheetWidth * scaleX <= 0f || sheetHeight * scaleY <= 0f) return null
    val local = overlayToViewLocal(x, y, originX, originY, scaleX, scaleY)
    val u = local.x / sheetWidth
    val v = local.y / sheetHeight
    if (u < 0f || u > 1f || v < 0f || v > 1f) return null
    return OcrPoint(u, v)
}
