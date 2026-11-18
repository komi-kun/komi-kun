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
