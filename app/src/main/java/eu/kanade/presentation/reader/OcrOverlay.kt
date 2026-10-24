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
import androidx.compose.ui.text.TextLayoutResult
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
 *
 * [textScalePercent] is the `ReaderPreferences.ocrTextScale` display setting (100 = the calibrated
 * size that matches the printed glyphs; see [layoutVerticalText]). It is an escape hatch for the
 * many different lettering fonts a manga can use.
 */
@Composable
fun OcrOverlay(
    viewport: OcrViewport?,
    pages: List<OcrOverlayPage>,
    activeBlock: OcrTextBlock?,
    outlineVisible: Boolean,
    showText: Boolean,
    boxScalePercent: Int = 100,
    textScalePercent: Int = 100,
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
            val nextVersion = viewport.transformVersion()
            if (transformVersion != nextVersion) {
                transformVersion = nextVersion
            }
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
    // `ocrBoxScale` / `ocrTextScale` are percents; the conversion (and its clamps) is the shared
    // `OcrDisplayScale` policy, so the sliders and the drawing cannot disagree.
    val boxScale = OcrDisplayScale.boxScaleFactor(boxScalePercent)
    val textScale = OcrDisplayScale.textScaleFactor(textScalePercent)

    val layoutCache = remember(baseTextStyle) { OcrTextLayoutCache() }

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
                        cache = layoutCache,
                        block = block,
                        rect = mapped,
                        textMeasurer = textMeasurer,
                        baseStyle = baseTextStyle,
                        baseTextPx = baseTextPx,
                        minTextPx = minTextPx,
                        plateCornerPx = plateCornerPx,
                        textScale = textScale,
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
 * text itself, sized against the printed text underneath (see [layoutVerticalText]) and scaled by
 * [textScale] (the user's overlay text size preference).
 *
 * Vertical (tategaki) blocks are laid out one upright glyph per cell, stacked top-to-bottom in
 * right-to-left columns (see [layoutVerticalText]) — never by rotating the whole run. A vertical
 * block whose single line is clearly horizontal on screen stays horizontal, and the horizontal path
 * is otherwise unchanged. A legibility floor refuses to shrink past [minTextPx]; anything that no
 * longer fits is clipped to the block rather than drawn as unreadable micro-text.
 */
private fun DrawScope.drawOcrBlockText(
    cache: OcrTextLayoutCache,
    block: OcrTextBlock,
    rect: RectF,
    textMeasurer: TextMeasurer,
    baseStyle: TextStyle,
    baseTextPx: Float,
    minTextPx: Float,
    plateCornerPx: Float,
    textScale: Float,
) {
    val rectW = rect.width()
    val rectH = rect.height()
    if (rectW < MIN_BLOCK_PX || rectH < MIN_BLOCK_PX || baseTextPx <= 0f) return

    val lineCount = block.lines.count { it.isNotBlank() }
    if (lineCount == 0) return

    // Backing plate: legible over busy artwork without hiding the page, since it only covers the
    // recognised block.
    drawRoundRect(
        color = PLATE_COLOR,
        topLeft = Offset(rect.left, rect.top),
        size = Size(rectW, rectH),
        cornerRadius = CornerRadius(plateCornerPx, plateCornerPx),
    )

    val minScale = minTextPx / baseTextPx
    if (block.vertical && !isClearlyHorizontal(block.vertical, lineCount, rectW, rectH)) {
        drawTategakiText(cache, block, rect, textMeasurer, baseStyle, minScale, textScale)
    } else {
        drawHorizontalOcrText(cache, block, rect, textMeasurer, baseStyle, minScale, textScale)
    }
}

/** Horizontal path, unchanged positioning: measure the joined lines once, scale to fit, then clip. */
private fun DrawScope.drawHorizontalOcrText(
    cache: OcrTextLayoutCache,
    block: OcrTextBlock,
    rect: RectF,
    textMeasurer: TextMeasurer,
    baseStyle: TextStyle,
    minScale: Float,
    textScale: Float,
) {
    val cached = cache.getOrMeasureHorizontal(block, textMeasurer, baseStyle) ?: return
    val textW = cached.textW
    val textH = cached.textH
    if (textW <= 0f || textH <= 0f) return

    val scale = horizontalTextScale(rect.width(), rect.height(), textW, textH, minScale)

    clipRect(rect.left, rect.top, rect.right, rect.bottom) {
        withTransform({
            translate(rect.centerX(), rect.centerY())
            scale(scale * textScale, scale * textScale, pivot = Offset.Zero)
            translate(-textW / 2f, -textH / 2f)
        }) {
            drawText(cached.layout, color = Color.White)
        }
    }
}

/**
 * Tategaki path: every glyph is measured at [baseStyle]'s reference size, positioned upright by
 * [layoutVerticalText] and drawn scaled by the layout's uniform [PlacedGlyph.scale]. Latin/digit
 * glyphs are turned 90° inside their own cell; kana, kanji and punctuation stay upright.
 */
private fun DrawScope.drawTategakiText(
    cache: OcrTextLayoutCache,
    block: OcrTextBlock,
    rect: RectF,
    textMeasurer: TextMeasurer,
    baseStyle: TextStyle,
    minScale: Float,
    textScale: Float,
) {
    val cached = cache.getOrMeasureVertical(block, textMeasurer, baseStyle) ?: return
    val placed = layoutVerticalText(
        cached.metrics,
        rect.left,
        rect.top,
        rect.right,
        rect.bottom,
        minScale,
        glyphScale = textScale,
    )
    if (placed.isEmpty()) return
    val layouts = cached.flattenedLayouts
    if (placed.size != layouts.size) return

    clipRect(rect.left, rect.top, rect.right, rect.bottom) {
        placed.forEachIndexed { index, glyph ->
            if (glyph.text.isBlank()) return@forEachIndexed
            val layout = layouts[index]
            val baseW = cached.flattenedWidths[index]
            val baseH = cached.flattenedHeights[index]
            if (baseW <= 0f || baseH <= 0f) return@forEachIndexed
            withTransform({
                translate(glyph.left, glyph.top)
                scale(glyph.scale, glyph.scale, pivot = Offset.Zero)
                // Rotate about the glyph's own centre (scaled space), keeping it centred in the cell.
                if (glyph.rotated) rotate(90f, pivot = Offset(baseW / 2f, baseH / 2f))
            }) {
                drawText(layout, color = Color.White)
            }
        }
    }
}

/** A glyph and its reference-size measurement, kept paired for the draw pass. */
private data class MeasuredGlyph(val text: String, val layout: TextLayoutResult)

/**
 * Cached horizontal text layout for an [OcrTextBlock].
 * Pre-measured at reference [baseTextStyle] so the draw pass only scales and translates via [withTransform].
 */
private class CachedHorizontalOcrLayout(
    val layout: TextLayoutResult,
    val textW: Float,
    val textH: Float,
)

/**
 * Cached vertical (tategaki) text layout for an [OcrTextBlock].
 * Holds the pre-computed column [metrics] for [layoutVerticalText] and pre-measured glyph layouts
 * in column-first flattened order matching [PlacedGlyph] outputs.
 */
private class CachedVerticalOcrLayout(
    val metrics: List<List<GlyphMetric>>,
    val flattenedLayouts: List<TextLayoutResult>,
    val flattenedWidths: FloatArray,
    val flattenedHeights: FloatArray,
)

/**
 * In-memory cache for OCR text layouts and glyph measurements.
 *
 * During pan/zoom in the reader, the transform version invalidates the canvas draw pass every frame.
 * Without caching, measuring every glyph in every block on every frame creates thousands of HarfBuzz/Minikin
 * font-shaping calls and layout allocations per frame on the main thread, dropping FPS drastically.
 *
 * This cache preserves:
 * 1. Individual glyph layouts at reference [baseTextStyle] (reused across blocks and frames).
 * 2. Block-level horizontal layouts and vertical column metrics/flattened layouts.
 */
private class OcrTextLayoutCache(
    private val maxBlocks: Int = 500,
    private val maxGlyphs: Int = 1000,
) {
    private val glyphCache = object : LinkedHashMap<String, TextLayoutResult>(maxGlyphs, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TextLayoutResult>?): Boolean {
            return size > maxGlyphs
        }
    }

    private val horizontalCache = object : LinkedHashMap<OcrTextBlock, CachedHorizontalOcrLayout>(maxBlocks, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<OcrTextBlock, CachedHorizontalOcrLayout>?): Boolean {
            return size > maxBlocks
        }
    }

    private val verticalCache = object : LinkedHashMap<OcrTextBlock, CachedVerticalOcrLayout>(maxBlocks, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<OcrTextBlock, CachedVerticalOcrLayout>?): Boolean {
            return size > maxBlocks
        }
    }

    fun getOrMeasureHorizontal(
        block: OcrTextBlock,
        textMeasurer: TextMeasurer,
        baseStyle: TextStyle,
    ): CachedHorizontalOcrLayout? {
        val cached = horizontalCache[block]
        if (cached != null) return cached

        val lines = block.lines.filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        val text = lines.joinToString("\n")
        val layout = textMeasurer.measure(AnnotatedString(text), style = baseStyle)
        val textW = layout.size.width.toFloat()
        val textH = layout.size.height.toFloat()
        if (textW <= 0f || textH <= 0f) return null

        val result = CachedHorizontalOcrLayout(layout, textW, textH)
        horizontalCache[block] = result
        return result
    }

    fun getOrMeasureVertical(
        block: OcrTextBlock,
        textMeasurer: TextMeasurer,
        baseStyle: TextStyle,
    ): CachedVerticalOcrLayout? {
        val cached = verticalCache[block]
        if (cached != null) return cached

        val lines = block.lines.filter { it.isNotBlank() }
        if (lines.isEmpty()) return null

        val measuredColumns = lines.map { line ->
            splitGlyphs(line).map { glyph ->
                val layout = glyphCache.getOrPut(glyph) {
                    textMeasurer.measure(AnnotatedString(glyph), style = baseStyle)
                }
                MeasuredGlyph(glyph, layout)
            }
        }
        val metrics = measuredColumns.map { column ->
            column.map { GlyphMetric(it.text, it.layout.size.width.toFloat(), it.layout.size.height.toFloat()) }
        }
        val flattened = measuredColumns.flatten()
        if (flattened.isEmpty()) return null

        val flattenedLayouts = ArrayList<TextLayoutResult>(flattened.size)
        val flattenedWidths = FloatArray(flattened.size)
        val flattenedHeights = FloatArray(flattened.size)

        for (i in flattened.indices) {
            val mg = flattened[i]
            flattenedLayouts.add(mg.layout)
            flattenedWidths[i] = mg.layout.size.width.toFloat()
            flattenedHeights[i] = mg.layout.size.height.toFloat()
        }

        val result = CachedVerticalOcrLayout(
            metrics = metrics,
            flattenedLayouts = flattenedLayouts,
            flattenedWidths = flattenedWidths,
            flattenedHeights = flattenedHeights,
        )
        verticalCache[block] = result
        return result
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

/** Fixed size the recognised text is measured at, before being scaled to each block. */
private const val BASE_TEXT_SP = 20f

/** Smallest on-screen text size; below this the text is clipped rather than shrunk further. */
private const val MIN_TEXT_SP = 9f

/** Blocks smaller than this (px) are too small to show readable text in. */
private const val MIN_BLOCK_PX = 6f

private val PLATE_COLOR = Color.Black.copy(alpha = 0.62f)
