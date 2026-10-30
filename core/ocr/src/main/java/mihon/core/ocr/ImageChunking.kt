// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Ported from Chimahon (https://github.com/Chimahon/Chimahon, GPL-3.0-or-later),
// `chimahon/src/main/java/chimahon/ocr/ImageChunking.kt` @ f85c063.
// The chunk pipeline now emits the unified `mihon.core.ocr.model.OcrTextBlock` (03 §3.3) and runs
// the merger through the `OcrLineMerger` seam instead of referencing `OwOCRMerger` directly.
// KUN <--
package mihon.core.ocr

import android.graphics.Bitmap
import mihon.core.ocr.merge.OwOCRMerger
import mihon.core.ocr.model.OcrTextBlock
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

const val MAX_TOTAL_PIXELS = 3_000_000
const val CHUNK_HEIGHT_LIMIT = 3000
const val CHUNK_OVERLAP_PX = 96
const val TALL_IMAGE_CHUNK_THRESHOLD = 2500
const val WEBTOON_ASPECT_RATIO = 3.0

// KUN -->
const val TILE_MAX_DIMENSION = 2400
const val TILE_OVERLAP_PX = 360
const val TILE_TRIGGER_DIMENSION = 2500

/**
 * Pixel budget for a single page before any chunking.
 *
 * AI-upscaled pages are commonly 4x (a ~2 MP scan becomes 30-40 MP). The engine
 * downsizes to ~960 px for detection and 48 px line height for recognition, so
 * the extra pixels carry no information - but they do force the 2D tiler, which
 * measured worse than plain downscaling on paired real pages (21.0 % vs 11.5 %
 * line CER) and is ~10x slower. Ordinary pages are already under this budget.
 */
const val MAX_OCR_PIXELS = 4_000_000
// KUN <--

data class ImageChunk(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    val globalX: Int,
    val globalY: Int,
    val fullWidth: Int,
    val fullHeight: Int,
    // KUN -->
    /**
     * Detector long side this chunk must be recognised at, resolved by [prepareForOcr] **once from
     * the source page's** long side and carried unchanged through the 4 MP pre-shrink and the
     * tiling — never recomputed from a possibly-resized bitmap downstream. Defaults to
     * [DETECTOR_LIMIT_SIDE] for chunks built outside [prepareForOcr] (the plain tiler, tests).
     */
    val detectorLimitSide: Int = DETECTOR_LIMIT_SIDE,
    // KUN <--
) {
    // KUN -->
    constructor(
        bitmap: Bitmap,
        width: Int,
        height: Int,
        globalY: Int,
        fullWidth: Int,
        fullHeight: Int,
    ) : this(
        bitmap = bitmap,
        width = width,
        height = height,
        globalX = 0,
        globalY = globalY,
        fullWidth = fullWidth,
        fullHeight = fullHeight,
    )
    // KUN <--
}

fun splitImageIntoChunks(
    data: ByteArray,
    chunkHeightLimit: Int = 3000,
): List<ImageChunk> {
    val bitmap = decodeBitmap(data)
    val fullWidth = bitmap.width
    val fullHeight = bitmap.height
    val chunks = mutableListOf<ImageChunk>()

    try {
        var currentY = 0
        while (currentY < fullHeight) {
            val currentChunkHeight = min(chunkHeightLimit, fullHeight - currentY)
            if (currentChunkHeight == 0) break

            val chunkBitmap = Bitmap.createBitmap(bitmap, 0, currentY, fullWidth, currentChunkHeight)
            chunks += ImageChunk(
                bitmap = chunkBitmap,
                width = fullWidth,
                height = currentChunkHeight,
                globalY = currentY,
                fullWidth = fullWidth,
                fullHeight = fullHeight,
            )

            currentY += chunkHeightLimit
        }
    } finally {
        bitmap.recycle()
    }

    return chunks
}

private fun decodeBitmap(data: ByteArray): Bitmap {
    // KUN --> Sampled decode: the chunkers below must never be fed a full-size page.
    return OcrBitmapDecoder.decode(data)
    // KUN <--
}

fun prepareForOcr(data: ByteArray): List<ImageChunk> {
    val bitmap = decodeBitmap(data)
    val w = bitmap.width
    val h = bitmap.height
    val pixelCount = w * h
    val aspectRatio = h.toDouble() / w
    val shouldChunkByHeight = h > TALL_IMAGE_CHUNK_THRESHOLD
    val shouldChunkByAspect = aspectRatio > WEBTOON_ASPECT_RATIO && pixelCount > MAX_TOTAL_PIXELS

    return if (shouldChunkByHeight || shouldChunkByAspect) {
        chunkImage(bitmap).also { bitmap.recycle() }
    } else {
        var resized: Bitmap? = null
        try {
            val resizedBitmap = resizeToMaxPixels(bitmap, MAX_TOTAL_PIXELS).also { resized = it }
            listOf(
                ImageChunk(
                    bitmap = resizedBitmap,
                    width = resizedBitmap.width,
                    height = resizedBitmap.height,
                    globalY = 0,
                    fullWidth = resizedBitmap.width,
                    fullHeight = resizedBitmap.height,
                ),
            )
        } finally {
            if (resized != null && resized !== bitmap) {
                bitmap.recycle()
            }
        }
    }
}

/**
 * Splits a page into the chunks the detector will see.
 *
 * [detResolution] is the user's detector-resolution mode, resolved **once** against the page's own
 * long side and then applied to every chunk ([ImageChunk.detectorLimitSide]). That single
 * resolution is what keeps the frame independent of the budget: the 4 MP pre-shrink reproduces the
 * frame of the page at that limit, and a chunk carries the same limit down to the engine. Resolving
 * per chunk instead let the shrink decide the geometry — a 2134x1894 page is the 1600 rung, but its
 * pre-shrunk 2122x1883 chunk resolves to 1280, so the same page read at 1600x1408 unbudgeted and
 * 1280x1120 after the shrink.
 */
fun prepareForOcr(
    bitmap: Bitmap,
    detResolution: OcrDetResolution = OcrDetResolution.DEFAULT,
): List<ImageChunk> {
    // KUN -->
    // The limit the pre-shrink must preserve the frame of: resolved from the source page's long
    // side, because that is the size the page reaches the detector at if the shrink does not run.
    // The same value is applied to every chunk below, so the budget cannot move the frame.
    val limitSide = detResolution.limitFor(max(bitmap.width, bitmap.height))
    // Normalise oversized single pages *before* deciding to tile. Webtoon strips
    // (very tall) keep the old path so their width is not squeezed away.
    val source = if (bitmap.height.toDouble() / bitmap.width <= WEBTOON_ASPECT_RATIO) {
        resizeToMaxPixels(bitmap, MAX_OCR_PIXELS, limitSide)
    } else {
        bitmap
    }
    // KUN <--
    val w = source.width
    val h = source.height
    // KUN -->
    val chunks = if (w > TILE_TRIGGER_DIMENSION || h > TILE_TRIGGER_DIMENSION) {
        chunkImage2D(source)
    } else {
        val pixelCount = w * h
        val aspectRatio = h.toDouble() / w
        val shouldChunkByHeight = h > TALL_IMAGE_CHUNK_THRESHOLD
        val shouldChunkByAspect = aspectRatio > WEBTOON_ASPECT_RATIO && pixelCount > MAX_TOTAL_PIXELS

        if (shouldChunkByHeight || shouldChunkByAspect) {
            chunkImage(source)
        } else {
            listOf(
                ImageChunk(
                    bitmap = source,
                    width = w,
                    height = h,
                    globalX = 0,
                    globalY = 0,
                    fullWidth = w,
                    fullHeight = h,
                ),
            )
        }
    }
    // KUN -->
    // The page-resolved limit, applied to every chunk: the pre-shrink preserves the page's frame at
    // exactly this limit, so a chunk must never re-resolve it from a possibly-shrunk own long side
    // (that is what made the frame depend on whether the 4 MP budget ran). A fixed mode resolves to
    // the same value for every chunk regardless.
    val limitedChunks = chunks.map { chunk ->
        chunk.copy(detectorLimitSide = limitSide)
    }
    // Tiled chunks are independent copies; the single-chunk case owns `source`
    // itself, so only recycle when no chunk references it.
    if (source !== bitmap && limitedChunks.none { it.bitmap === source }) {
        source.recycle()
    }
    // KUN <--
    return limitedChunks
}

// KUN -->
fun computeIntervals(
    totalSize: Int,
    maxTileSize: Int = TILE_MAX_DIMENSION,
    overlapPx: Int = TILE_OVERLAP_PX,
): List<Pair<Int, Int>> {
    if (totalSize <= maxTileSize) {
        return listOf(0 to totalSize)
    }
    val intervals = mutableListOf<Pair<Int, Int>>()
    val step = (maxTileSize - overlapPx).coerceAtLeast(1)
    var pos = 0
    while (pos < totalSize) {
        val size = min(maxTileSize, totalSize - pos)
        if (size <= 0) break
        if (size < maxTileSize && pos > 0) {
            val adjustedPos = (totalSize - maxTileSize).coerceAtLeast(0)
            if (adjustedPos != intervals.lastOrNull()?.first) {
                intervals.add(adjustedPos to min(maxTileSize, totalSize - adjustedPos))
            }
            break
        }
        intervals.add(pos to size)
        pos += step
    }
    return intervals
}

fun chunkImage2D(
    bitmap: Bitmap,
    tileMaxDimension: Int = TILE_MAX_DIMENSION,
    overlapPx: Int = TILE_OVERLAP_PX,
): List<ImageChunk> {
    val fullWidth = bitmap.width
    val fullHeight = bitmap.height
    val chunks = mutableListOf<ImageChunk>()

    val xIntervals = computeIntervals(fullWidth, tileMaxDimension, overlapPx)
    val yIntervals = computeIntervals(fullHeight, tileMaxDimension, overlapPx)

    for ((y, curH) in yIntervals) {
        for ((x, curW) in xIntervals) {
            val chunkBitmap = Bitmap.createBitmap(bitmap, x, y, curW, curH)
            chunks += ImageChunk(
                bitmap = chunkBitmap,
                width = curW,
                height = curH,
                globalX = x,
                globalY = y,
                fullWidth = fullWidth,
                fullHeight = fullHeight,
            )
        }
    }
    return chunks
}
// KUN <--

fun resizeToMaxPixels(
    bitmap: Bitmap,
    maxTotalPixels: Int,
    limitSide: Int = DETECTOR_LIMIT_SIDE,
): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val pixelCount = w * h

    if (pixelCount <= maxTotalPixels) return bitmap

    // KUN -->
    // Frame-preserving budget clamp: a page must reach the detector with the same input size
    // whether or not this shrink ran (see budgetedResizeDims).
    val (newW, newH) = budgetedResizeDims(w, h, maxTotalPixels, limitSide)
    // KUN <--

    return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
}

// KUN -->
/**
 * Longest side `libpaddle_ocr.so` downsizes a chunk to (PaddleOCR's `limit_side_len`), and the
 * pixel multiple it aligns the result to. Per side the native DB preprocess computes
 * `max(lround(side * ratio / 32) * 32, 32)` with `ratio = (float) (limit / longestSide)` (or `1.0`
 * when the side already fits the limit).
 *
 * This is the **default** of the user-selectable detector resolution
 * ([OcrDetResolution.DEFAULT]); every limit-consuming helper below takes it as a parameter, and
 * [mihon.core.ocr.paddle.PaddleOcrEngine] passes the resolved 960/1280/1600 through to the shim's
 * `maxSideLen` argument.
 */
internal const val DETECTOR_LIMIT_SIDE = 960
internal const val DETECTOR_SIDE_ALIGN = 32

/**
 * Distance kept, in detector pixels, from a 32-px bucket edge when a side is snapped into it. Only
 * has to dwarf the float noise of the native's `side * ratio` (~1e-4 px).
 */
private const val FRAME_EDGE_MARGIN_PX = 0.05

/** Size, in pixels, that the detector consumes for a chunk. */
internal data class DetectorFrame(val width: Int, val height: Int)

/**
 * Detector input for a [width] x [height] chunk at [limitSide] (default: [DETECTOR_LIMIT_SIDE]),
 * replicated from the native preprocess.
 */
internal fun detectorFrame(
    width: Int,
    height: Int,
    limitSide: Int = DETECTOR_LIMIT_SIDE,
): DetectorFrame {
    val longest = max(width, height)
    // The native divides in float32 and widens to double before scaling; keep that order so the
    // replica resolves 32-px ties exactly like the shipped engine (round half away from zero).
    val ratio = if (longest > limitSide) {
        (limitSide.toFloat() / longest.toFloat()).toDouble()
    } else {
        1.0
    }
    return DetectorFrame(
        width = alignedDetectorSide(width, ratio),
        height = alignedDetectorSide(height, ratio),
    )
}

private fun alignedDetectorSide(side: Int, ratio: Double): Int =
    (round(side * ratio / DETECTOR_SIDE_ALIGN) * DETECTOR_SIDE_ALIGN)
        .toInt()
        .coerceAtLeast(DETECTOR_SIDE_ALIGN)

/**
 * Resize dimensions for a page that must fit [maxTotalPixels], chosen so the detector frame of the
 * shrunk page is identical to the frame of the page as it arrives.
 *
 * Plain aspect math truncates both sides independently (`int(sqrt(budget * aspect))`, then
 * `int(width / aspect)`), so the working aspect drifts by up to a pixel - and one pixel is enough
 * for the detector's 32-px quantisation to move the shorter side a whole bucket over: a 5616x5712
 * page reads at 928x960 unbudgeted but 960x960 after a naive clamp. Two views of one page (before
 * and after a 4x upscale) then read from different detector geometries, which costs real accuracy.
 *
 * The naive result is kept whenever it already reproduces the page's frame - the common case, and
 * the reason this changes nothing for ordinary pages. Otherwise the shorter side moves to the
 * nearest pixel inside the frame's bucket and the longer side (the budget knob) is trimmed to stay
 * within [maxTotalPixels]. Below [limitSide] there is no shared frame to preserve: the page is not
 * downscaled at all, so its detector input is just its own size rounded to 32 px.
 *
 * [limitSide] must be the limit the detector **really** applies (see
 * [OcrPreferences.effectiveDetectorLimit] and [ImageChunk.detectorLimitSide]); every comparison
 * below is against the frame that limit produces.
 */
internal fun budgetedResizeDims(
    width: Int,
    height: Int,
    maxTotalPixels: Int,
    limitSide: Int = DETECTOR_LIMIT_SIDE,
): Pair<Int, Int> {
    if (width.toLong() * height <= maxTotalPixels) return width to height

    val aspectRatio = width.toDouble() / height
    val budgetWidth = sqrt(maxTotalPixels * aspectRatio).toInt().coerceAtLeast(1)
    val budgetHeight = (budgetWidth / aspectRatio).toInt().coerceAtLeast(1)

    if (max(width, height) <= limitSide) return budgetWidth to budgetHeight
    if (max(budgetWidth, budgetHeight) <= limitSide) return budgetWidth to budgetHeight

    val target = detectorFrame(width, height, limitSide)
    if (detectorFrame(budgetWidth, budgetHeight, limitSide) == target) return budgetWidth to budgetHeight

    val widthIsLongest = budgetWidth >= budgetHeight
    val shortest = if (widthIsLongest) budgetHeight else budgetWidth
    // Exact shorter-side value, in detector pixels, of the frame to reproduce (a multiple of 32).
    val scaledShortest = if (widthIsLongest) target.height else target.width

    // Longest side at the target aspect that still fits the budget; trimmed further only if the
    // snapped shorter side pushes the pixel count over.
    var longest = min(
        max(budgetWidth, budgetHeight),
        sqrt(maxTotalPixels.toDouble() * limitSide / scaledShortest).toInt(),
    )
    while (longest > limitSide) {
        // The frame is unchanged while shorterSide * limit / longest stays inside the bucket:
        // round(x / 32) == scaledShortest / 32  <=>  x in [scaledShortest - 16, scaledShortest + 16).
        val low = ceil(
            (scaledShortest - DETECTOR_SIDE_ALIGN / 2.0 + FRAME_EDGE_MARGIN_PX) *
                longest / limitSide,
        ).toInt()
        val high = floor(
            (scaledShortest + DETECTOR_SIDE_ALIGN / 2.0 - FRAME_EDGE_MARGIN_PX) *
                longest / limitSide,
        ).toInt()
        if (low <= high) {
            val snapped = shortest.coerceIn(low, high)
            val dims = if (widthIsLongest) longest to snapped else snapped to longest
            if (snapped >= 1 && longest >= snapped &&
                dims.first.toLong() * dims.second <= maxTotalPixels
            ) {
                return dims
            }
        }
        longest--
    }
    return budgetWidth to budgetHeight
}
// KUN <--

fun chunkImage(
    bitmap: Bitmap,
    chunkHeightLimit: Int = CHUNK_HEIGHT_LIMIT,
    chunkOverlapPx: Int = CHUNK_OVERLAP_PX,
): List<ImageChunk> {
    val fullWidth = bitmap.width
    val fullHeight = bitmap.height
    val chunks = mutableListOf<ImageChunk>()

    val step = (chunkHeightLimit - chunkOverlapPx).coerceAtLeast(1)
    var currentY = 0
    while (currentY < fullHeight) {
        val currentChunkHeight = min(chunkHeightLimit, fullHeight - currentY)
        if (currentChunkHeight == 0) break

        val chunkBitmap = Bitmap.createBitmap(bitmap, 0, currentY, fullWidth, currentChunkHeight)
        chunks += ImageChunk(
            bitmap = chunkBitmap,
            width = fullWidth,
            height = currentChunkHeight,
            globalY = currentY,
            fullWidth = fullWidth,
            fullHeight = fullHeight,
        )
        currentY += step
    }

    return chunks
}

/** Maps a chunk-local [EngineLine] back into full-image normalized coordinates. */
fun EngineLine.normalizeFromChunk(chunk: ImageChunk): EngineLine {
    // KUN -->
    if (chunk.globalX == 0 && chunk.globalY == 0 &&
        chunk.fullWidth == chunk.width && chunk.fullHeight == chunk.height
    ) {
        return this
    }
    val pixelLeft = bbox.left * chunk.width + chunk.globalX
    val pixelRight = bbox.right * chunk.width + chunk.globalX
    val pixelTop = bbox.top * chunk.height + chunk.globalY
    val pixelBottom = bbox.bottom * chunk.height + chunk.globalY
    val newBbox = NormalizedBBox(
        left = pixelLeft / chunk.fullWidth,
        top = pixelTop / chunk.fullHeight,
        right = pixelRight / chunk.fullWidth,
        bottom = pixelBottom / chunk.fullHeight,
        rotation = bbox.rotation,
    )
    val charCount = text.length.coerceAtLeast(1)
    val dimension = if (writingDirection == WritingDirection.TTB) newBbox.height else newBbox.width
    val newCharSize = dimension / charCount

    return copy(
        bbox = newBbox,
        characterSize = newCharSize,
    )
    // KUN <--
}

// KUN -->
/**
 * Drops duplicate or partially overlapping [EngineLine]s emitted across multiple chunks/tiles.
 * When a line is detected in multiple overlapping tiles, keeps the most complete (longest text,
 * largest bounding box) instance and drops the duplicate.
 */
fun dedupeEngineLines(lines: List<EngineLine>): List<EngineLine> {
    if (lines.size < 2) return lines

    val sorted = lines.sortedWith(
        compareByDescending<EngineLine> { it.text.trim().length }
            .thenByDescending { it.bbox.width * it.bbox.height },
    )
    val kept = mutableListOf<EngineLine>()

    for (candidate in sorted) {
        val isDup = kept.any { existing -> isDuplicateEngineLine(existing, candidate) }
        if (!isDup) {
            kept.add(candidate)
        }
    }
    return kept
}

private fun isDuplicateEngineLine(a: EngineLine, b: EngineLine): Boolean {
    val interW = max(0.0, min(a.bbox.right, b.bbox.right) - max(a.bbox.left, b.bbox.left))
    val interH = max(0.0, min(a.bbox.bottom, b.bbox.bottom) - max(a.bbox.top, b.bbox.top))
    val interArea = interW * interH
    if (interArea <= 0.0) return false

    val aArea = a.bbox.width * a.bbox.height
    val bArea = b.bbox.width * b.bbox.height
    val minArea = min(aArea, bArea)
    if (minArea <= 0.0) return false

    val iom = interArea / minArea
    val union = aArea + bArea - interArea
    val iou = if (union > 0.0) interArea / union else 0.0

    // Spatial overlap check: either IoU is >= 0.35 or one box is largely inside the other (IoM >= 0.50)
    if (iou < 0.35 && iom < 0.50) return false

    val textA = a.text.trim()
    val textB = b.text.trim()
    if (textA.isEmpty() || textB.isEmpty()) return iou >= 0.70

    // Text matching: exact match, substring containment, or high similarity
    return textA == textB ||
        textA.contains(textB) ||
        textB.contains(textA) ||
        (textSimilarity(textA, textB) >= 0.65 && iom >= 0.55)
}

private fun textSimilarity(s1: String, s2: String): Double {
    if (s1 == s2) return 1.0
    val maxLen = max(s1.length, s2.length)
    if (maxLen == 0) return 1.0
    val dist = levenshteinDistance(s1, s2)
    return (maxLen - dist).toDouble() / maxLen
}

private fun levenshteinDistance(s1: String, s2: String): Int {
    val dp = IntArray(s2.length + 1) { it }
    for (i in 1..s1.length) {
        var prev = dp[0]
        dp[0] = i
        for (j in 1..s2.length) {
            val temp = dp[j]
            dp[j] = if (s1[i - 1] == s2[j - 1]) {
                prev
            } else {
                min(prev, min(dp[j - 1], dp[j])) + 1
            }
            prev = temp
        }
    }
    return dp[s2.length]
}

/** Drops blocks that multiple chunks of an overlapping split detected at the seam. */
fun dedupeChunkBoundaryResults(results: List<OcrTextBlock>): List<OcrTextBlock> {
    if (results.size < 2) return results.map { stripDuplicateLines(it) }

    val sorted = results.sortedWith(compareBy({ it.ymin }, { it.xmin }))
    val kept = mutableListOf<OcrTextBlock>()

    for (candidate in sorted) {
        var duplicateIdx = -1
        for (i in kept.indices) {
            val existing = kept[i]
            if (existing.vertical == candidate.vertical && isDuplicate(existing, candidate)) {
                duplicateIdx = i
                break
            }
        }

        if (duplicateIdx == -1) {
            kept.add(candidate)
        } else {
            val existing = kept[duplicateIdx]
            val existingText = existing.lines.joinToString("").trim()
            val candidateText = candidate.lines.joinToString("").trim()
            if (candidateText.length > existingText.length) {
                kept[duplicateIdx] = candidate
            }
        }
    }
    return kept.map { stripDuplicateLines(it) }
}

private fun stripDuplicateLines(block: OcrTextBlock): OcrTextBlock {
    if (block.lines.size <= 1) return block
    val geoms = block.lineGeometries
    val deduped = mutableListOf<String>()
    val dedupedGeoms = if (geoms != null && geoms.size == block.lines.size) {
        mutableListOf<OcrLineGeometry>()
    } else {
        null
    }
    for (i in block.lines.indices) {
        val line = block.lines[i]
        val trimmed = line.trim()
        if (deduped.isEmpty() || deduped.last().trim() != trimmed) {
            deduped.add(line)
            if (geoms != null && dedupedGeoms != null) {
                dedupedGeoms.add(geoms[i])
            }
        }
    }
    return if (deduped.size != block.lines.size) {
        block.copy(
            lines = deduped,
            lineGeometries = dedupedGeoms ?: geoms,
        )
    } else {
        block
    }
}

private fun isDuplicate(a: OcrTextBlock, b: OcrTextBlock): Boolean {
    val textA = a.lines.joinToString("").trim()
    val textB = b.lines.joinToString("").trim()
    val textMatch = textA == textB ||
        (textA.isNotEmpty() && textB.isNotEmpty() && (textA.contains(textB) || textB.contains(textA) || textSimilarity(textA, textB) >= 0.70))

    val interW = max(0.0, min(a.xmax, b.xmax).toDouble() - max(a.xmin, b.xmin).toDouble())
    val interH = max(0.0, min(a.ymax, b.ymax).toDouble() - max(a.ymin, b.ymin).toDouble())
    val interArea = interW * interH
    if (interArea <= 0.0) return false

    val aArea = (a.xmax - a.xmin).toDouble() * (a.ymax - a.ymin)
    val bArea = (b.xmax - b.xmin).toDouble() * (b.ymax - b.ymin)
    val minArea = min(aArea, bArea)
    if (minArea <= 0.0) return false

    val iom = interArea / minArea
    val union = aArea + bArea - interArea
    val iouVal = if (union > 0.0) interArea / union else 0.0
    val centerClose = closeCenters(a, b)

    return textMatch && (iouVal >= 0.35 || iom >= 0.50 || centerClose)
}

private fun closeCenters(a: OcrTextBlock, b: OcrTextBlock): Boolean {
    val ax = (a.xmin + a.xmax) / 2.0
    val ay = (a.ymin + a.ymax) / 2.0
    val bx = (b.xmin + b.xmax) / 2.0
    val by = (b.ymin + b.ymax) / 2.0
    return hypot(ax - bx, ay - by) <= 0.02
}
// KUN <--

private fun iou(a: OcrTextBlock, b: OcrTextBlock): Double {
    val interW = max(0.0, min(a.xmax, b.xmax).toDouble() - max(a.xmin, b.xmin).toDouble())
    val interH = max(0.0, min(a.ymax, b.ymax).toDouble() - max(a.ymin, b.ymin).toDouble())
    val interArea = interW * interH
    if (interArea <= 0.0) return 0.0

    val aArea = (a.xmax - a.xmin).toDouble() * (a.ymax - a.ymin)
    val bArea = (b.xmax - b.xmin).toDouble() * (b.ymax - b.ymin)
    val union = aArea + bArea - interArea
    return if (union <= 0.0) 0.0 else interArea / union
}

suspend fun processImageWithChunks(
    bytes: ByteArray,
    language: OcrLanguage,
    addSpaceOnMerge: Boolean? = null,
    chunkProcessor: suspend (ImageChunk) -> List<EngineLine>,
): List<OcrTextBlock> {
    val bitmap = decodeBitmap(bytes)
    return try {
        val chunks = prepareForOcr(bitmap)
        processChunks(chunks, language, addSpaceOnMerge, sourceBitmapToPreserve = null, chunkProcessor)
    } finally {
        if (!bitmap.isRecycled) {
            bitmap.recycle()
        }
    }
}

suspend fun processImageWithChunks(
    bitmap: Bitmap,
    language: OcrLanguage,
    addSpaceOnMerge: Boolean? = null,
    detResolution: OcrDetResolution = OcrDetResolution.DEFAULT,
    chunkProcessor: suspend (ImageChunk) -> List<EngineLine>,
): List<OcrTextBlock> {
    val chunks = prepareForOcr(bitmap, detResolution)
    return processChunks(chunks, language, addSpaceOnMerge, sourceBitmapToPreserve = bitmap, chunkProcessor)
}

private suspend fun processChunks(
    chunks: List<ImageChunk>,
    language: OcrLanguage,
    addSpaceOnMerge: Boolean?,
    sourceBitmapToPreserve: Bitmap? = null,
    chunkProcessor: suspend (ImageChunk) -> List<EngineLine>,
): List<OcrTextBlock> {
    try {
        val fullWidth = chunks.firstOrNull()?.fullWidth ?: 0
        val fullHeight = chunks.firstOrNull()?.fullHeight ?: 0
        val config = MergeConfig(
            language = language,
            addSpaceOnMerge = addSpaceOnMerge,
            imageWidth = fullWidth.toDouble(),
            imageHeight = fullHeight.toDouble(),
        )
        val merger = OwOCRMerger(config)

        val allEngineLines = chunks.flatMap { chunk ->
            val lines = chunkProcessor(chunk)
            lines.map { it.normalizeFromChunk(chunk) }
        }
        // KUN -->
        val dedupedLines = dedupeEngineLines(allEngineLines)
        // KUN <--

        val mergedResults = merger.merge(dedupedLines, fullWidth, fullHeight)
        return if (chunks.size > 1) {
            dedupeChunkBoundaryResults(mergedResults)
        } else {
            mergedResults
        }
    } finally {
        for (chunk in chunks) {
            if (chunk.bitmap !== sourceBitmapToPreserve && !chunk.bitmap.isRecycled) {
                chunk.bitmap.recycle()
            }
        }
    }
}
