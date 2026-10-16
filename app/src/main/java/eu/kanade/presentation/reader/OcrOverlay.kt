// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Reader OCR outline overlay (contract §1.3/§2.3: GPL, composed only by the GPL
// `ReaderOcrHostImpl`). v1 draws outline-only boxes - no text - and adds no pointer input, so it
// never steals gestures from the viewer (plan 04 §4.3/§4.5).
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.ocr.OcrViewport
import kotlinx.coroutines.isActive
import mihon.core.ocr.model.OcrTextBlock

/**
 * Draws the OCR block outlines for the current page in the reader's Compose overlay.
 *
 * Outline-only (no text rendering) keeps this small and gesture-free: [Canvas] draws and adds no
 * `pointerInput`, so touches fall through to the viewer. Redraws are driven by sampling
 * [OcrViewport.transformVersion] once per frame while it is composed, rather than a naive poll
 * (plan 04 §4.3, risk R5).
 *
 * [blocks] are the OCR blocks of the page currently being read; they are mapped against the first
 * visible page of [viewport]. Dual-page spreads map only that page in v1.
 *
 * [boxScalePercent] is the `ReaderPreferences.ocrBoxScale` display setting (100 = tight around the
 * detected block); the drawn box is grown/shrunk about its own centre by that factor.
 */
@Composable
fun OcrOverlay(
    viewport: OcrViewport?,
    blocks: List<OcrTextBlock>,
    activeBlock: OcrTextBlock?,
    outlineVisible: Boolean,
    boxScalePercent: Int = 100,
    modifier: Modifier = Modifier,
) {
    if (viewport == null || !outlineVisible) return

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
    val strokeWidth = with(LocalDensity.current) { 1.5.dp.toPx() }
    // `ocrBoxScale` is a percent; guard against a degenerate zero/negative value.
    val boxScale = (boxScalePercent / 100f).coerceAtLeast(MIN_BOX_SCALE)

    Canvas(modifier = modifier.fillMaxSize()) {
        // Read so a transform change redraws the boxes.
        transformVersion
        val page = viewport.visiblePages().firstOrNull() ?: return@Canvas
        for (block in blocks) {
            val mapped = viewport.imageNormalizedToScreen(
                page,
                RectF(block.xmin, block.ymin, block.xmax, block.ymax),
            ) ?: continue
            val rect = mapped.scaledAboutCenter(boxScale)
            val topLeft = Offset(rect.left, rect.top)
            val size = Size(rect.width(), rect.height())
            if (activeBlock != null && activeBlock === block) {
                drawRect(color = activeColor, topLeft = topLeft, size = size)
            }
            drawRect(
                color = outlineColor,
                topLeft = topLeft,
                size = size,
                style = Stroke(width = strokeWidth),
            )
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
