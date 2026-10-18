// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Reader OCR outline/text overlay (contract §1.3/§2.3: GPL, composed only by the GPL
// `ReaderOcrHostImpl`). Draws outline-only boxes and, when enabled, the recognised text layered
// over the image text it came from. Adds no pointer input, so it never steals gestures from the
// viewer (plan 04 §4.3/§4.5).
// KUN <--
package eu.kanade.presentation.reader

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.ocr.OcrViewport
import kotlinx.coroutines.isActive
import mihon.core.ocr.model.OcrTextBlock
import kotlin.math.max
import kotlin.math.min

/** One visible page and the OCR blocks already loaded for it. */
data class OcrOverlayPage(val page: ReaderPage, val blocks: List<OcrTextBlock>)

/**
 * Draws the OCR overlay for every visible page: block outlines, and (when [showText]) each block's
 * recognised text positioned over the image text it came from, scaled to the block's own region.
 *
 * Drawing no text region for none (no `pointerInput`) keeps this gesture-free: touches fall through
 * to the viewer. Redraws are driven by sampling [OcrViewport.transformVersion] once per frame while
 * it is composed, rather than a naive poll (plan 04 §4.3, risk R5); [pages] changing (a new page
 * becoming visible, blocks arriving) recomposes and redraws too.
 *
 * Every page the viewer reports as visible is mapped with that page's own geometry
 * ([OcrViewport.imageNormalizedToScreen]), so during a page turn the incoming page's boxes and text
 * are drawn on it as it slides in rather than only once the turn settles.
 *
 * [boxScalePercent] is the `ReaderPreferences.ocrBoxScale` display setting (100 = tight around the
 * detected block); the drawn outline is grown/shrunk about its own centre by that factor. The text
 * layer always uses the true block rect, so it stays over the glyphs it came from.
 */
@Composable
fun OcrOverlay(
    viewport: OcrViewport?,
    pages: List<OcrOverlayPage>,
    activeBlock: OcrTextBlock?,
    outlineVisible: Boolean,
    showText: Boolean,
    boxScalePercent: Int = 100,
    modifier: Modifier = Modifier,
) {
    if (viewport == null) return
    if (!outlineVisible && !showText) return

    // Bumped once per frame from the viewport's transform token; read inside the Canvas so it only
    // invalidates the draw phase, not the composition.
    var transformVersion by remember(viewport) { mutableLongStateOf(viewport.transformVersion()) }
    LaunchedEffect(viewport) {
        while (isActive) {
            withFrameNanos { }
            transformVersion = viewport.transformVersion()
        }
    }

    val outlineColor = MaterialTheme.colorScheme.primary
    val activeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val strokeWidth = with(density) { 1.5.dp.toPx() }
    // Text is measured once at this fixed size and scaled to fit each block; the plate/corner and
    // the legibility floor are in px so they are stable across the reader's font scale.
    val baseTextStyle = remember(density) { TextStyle(fontSize = BASE_TEXT_SP.sp, color = Color.White) }
    val baseTextPx = with(density) { BASE_TEXT_SP.sp.toPx() }
    val minTextPx = with(density) { MIN_TEXT_SP.sp.toPx() }
    val plateCornerPx = with(density) { 3.dp.toPx() }
    // `ocrBoxScale` is a percent; guard against a degenerate zero/negative value.
    val boxScale = (boxScalePercent / 100f).coerceAtLeast(MIN_BOX_SCALE)

    Canvas(modifier = modifier.fillMaxSize()) {
        // Read so a transform change redraws the boxes.
        transformVersion
        for (overlayPage in pages) {
            val page = overlayPage.page
            for (block in overlayPage.blocks) {
                val mapped = viewport.imageNormalizedToScreen(
                    page,
                    RectF(block.xmin, block.ymin, block.xmax, block.ymax),
                ) ?: continue
                if (mapped.right < 0f || mapped.bottom < 0f || mapped.left > size.width || mapped.top > size.height) {
                    continue
                }

                if (activeBlock != null && activeBlock === block) {
                    val rect = mapped.scaledAboutCenter(boxScale)
                    drawRect(
                        color = activeColor,
                        topLeft = Offset(rect.left, rect.top),
                        size = Size(rect.width(), rect.height()),
                    )
                }

                if (showText) {
                    drawOcrBlockText(
                        block = block,
                        rect = mapped,
                        textMeasurer = textMeasurer,
                        baseStyle = baseTextStyle,
                        baseTextPx = baseTextPx,
                        minTextPx = minTextPx,
                        plateCornerPx = plateCornerPx,
                    )
                }

                if (outlineVisible) {
                    val rect = mapped.scaledAboutCenter(boxScale)
                    drawRect(
                        color = outlineColor,
                        topLeft = Offset(rect.left, rect.top),
                        size = Size(rect.width(), rect.height()),
                        style = Stroke(width = strokeWidth),
                    )
                }
            }
        }
    }
}

/**
 * Layers [block]'s recognised text over its own [rect]: a translucent plate for contrast, then the
 * text scaled to fill the block. Vertical (tategaki) blocks are rotated so the string runs down the
 * column instead of across it. A legibility floor refuses to shrink past [minTextPx]; anything that
 * no longer fits is clipped to the block rather than drawn as unreadable micro-text.
 */
private fun DrawScope.drawOcrBlockText(
    block: OcrTextBlock,
    rect: RectF,
    textMeasurer: TextMeasurer,
    baseStyle: TextStyle,
    baseTextPx: Float,
    minTextPx: Float,
    plateCornerPx: Float,
) {
    val rectW = rect.width()
    val rectH = rect.height()
    if (rectW < MIN_BLOCK_PX || rectH < MIN_BLOCK_PX || baseTextPx <= 0f) return

    val text = block.lines.filter { it.isNotBlank() }.joinToString("\n").ifBlank { return }
    val layout = textMeasurer.measure(AnnotatedString(text), style = baseStyle)
    val textW = layout.size.width.toFloat()
    val textH = layout.size.height.toFloat()
    if (textW <= 0f || textH <= 0f) return

    // Backing plate: legible over busy artwork without hiding the page, since it only covers the
    // recognised block.
    drawRoundRect(
        color = PLATE_COLOR,
        topLeft = Offset(rect.left, rect.top),
        size = Size(rectW, rectH),
        cornerRadius = CornerRadius(plateCornerPx, plateCornerPx),
    )

    val vertical = block.vertical
    // A rotated (vertical) string's width spans the block's height and vice versa.
    val fit = if (vertical) {
        min(rectH / textW, rectW / textH)
    } else {
        min(rectW / textW, rectH / textH)
    }
    // Do not shrink below the legibility floor; the clip below trims whatever then overflows.
    val scale = max(fit, minTextPx / baseTextPx)
    val cx = rect.centerX()
    val cy = rect.centerY()

    clipRect(rect.left, rect.top, rect.right, rect.bottom) {
        withTransform({
            translate(cx, cy)
            if (vertical) rotate(90f, pivot = Offset.Zero)
            scale(scale, scale, pivot = Offset.Zero)
            translate(-textW / 2f, -textH / 2f)
        }) {
            drawText(layout, color = Color.White, topLeft = Offset.Zero)
        }
    }
}

/**
 * Grows/shrinks [this] about its own centre by [scale], implementing `ReaderPreferences.ocrBoxScale`
 * (100% = tight around the detected block, >100% = larger).
 */
private fun RectF.scaledAboutCenter(scale: Float): RectF {
    if (scale == 1f) return this
    val cx = (left + right) / 2f
    val cy = (top + bottom) / 2f
    val halfWidth = width() / 2f * scale
    val halfHeight = height() / 2f * scale
    return RectF(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight)
}

private const val MIN_BOX_SCALE = 0.05f

/** Fixed size the recognised text is measured at, before being scaled to each block. */
private const val BASE_TEXT_SP = 20f

/** Smallest on-screen text size; below this the text is clipped rather than shrunk further. */
private const val MIN_TEXT_SP = 9f

/** Blocks smaller than this (px) are too small to show readable text in. */
private const val MIN_BLOCK_PX = 6f

private val PLATE_COLOR = Color.Black.copy(alpha = 0.58f)
