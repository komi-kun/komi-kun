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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

const val MAX_TOTAL_PIXELS = 3_000_000
const val CHUNK_HEIGHT_LIMIT = 3000
const val CHUNK_OVERLAP_PX = 96
const val TALL_IMAGE_CHUNK_THRESHOLD = 2500
const val WEBTOON_ASPECT_RATIO = 3.0

data class ImageChunk(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    val globalY: Int,
    val fullWidth: Int,
    val fullHeight: Int,
)

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
    return OcrBitmapDecoder.decode(data)
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

fun prepareForOcr(bitmap: Bitmap): List<ImageChunk> {
    val w = bitmap.width
    val h = bitmap.height
    val pixelCount = w * h
    val aspectRatio = h.toDouble() / w
    val shouldChunkByHeight = h > TALL_IMAGE_CHUNK_THRESHOLD
    val shouldChunkByAspect = aspectRatio > WEBTOON_ASPECT_RATIO && pixelCount > MAX_TOTAL_PIXELS

    return if (shouldChunkByHeight || shouldChunkByAspect) {
        chunkImage(bitmap)
    } else {
        val resizedBitmap = resizeToMaxPixels(bitmap, MAX_TOTAL_PIXELS)
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
    }
}

fun resizeToMaxPixels(bitmap: Bitmap, maxTotalPixels: Int): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val pixelCount = w * h

    if (pixelCount <= maxTotalPixels) return bitmap

    val aspectRatio = w.toDouble() / h
    val newW = sqrt(maxTotalPixels * aspectRatio).toInt().coerceAtLeast(1)
    val newH = (newW / aspectRatio).toInt().coerceAtLeast(1)

    return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
}

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
    if (chunk.globalY == 0 && chunk.fullHeight == chunk.height) return this
    val pixelTop = bbox.top * chunk.height + chunk.globalY
    val pixelBottom = bbox.bottom * chunk.height + chunk.globalY
    return copy(
        bbox = NormalizedBBox(
            left = bbox.left,
            top = pixelTop / chunk.fullHeight,
            right = bbox.right,
            bottom = pixelBottom / chunk.fullHeight,
            rotation = bbox.rotation,
        ),
    )
}

/** Drops blocks that both chunks of an overlapping split detected at the seam. */
fun dedupeChunkBoundaryResults(results: List<OcrTextBlock>): List<OcrTextBlock> {
    if (results.size < 2) return results

    val sorted = results.sortedBy { it.ymin }
    val kept = mutableListOf<OcrTextBlock>()

    for (candidate in sorted) {
        var isDuplicate = false
        for (i in kept.size - 1 downTo 0) {
            val existing = kept[i]

            if (candidate.ymin - existing.ymin > 0.005) break

            if (existing.vertical == candidate.vertical &&
                sameText(existing.lines.joinToString(""), candidate.lines.joinToString("")) &&
                (iou(existing, candidate) >= 0.55 || closeCenters(existing, candidate))
            ) {
                isDuplicate = true
                break
            }
        }

        if (!isDuplicate) {
            kept.add(candidate)
        }
    }
    return kept
}

private fun sameText(a: String, b: String): Boolean = a.trim() == b.trim()

private fun closeCenters(a: OcrTextBlock, b: OcrTextBlock): Boolean {
    val ax = (a.xmin + a.xmax) / 2.0
    val ay = (a.ymin + a.ymax) / 2.0
    val bx = (b.xmin + b.xmax) / 2.0
    val by = (b.ymin + b.ymax) / 2.0
    return hypot(ax - bx, ay - by) <= 0.01
}

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
    val chunks = prepareForOcr(bytes)
    return processChunks(chunks, language, addSpaceOnMerge, chunkProcessor)
}

suspend fun processImageWithChunks(
    bitmap: Bitmap,
    language: OcrLanguage,
    addSpaceOnMerge: Boolean? = null,
    chunkProcessor: suspend (ImageChunk) -> List<EngineLine>,
): List<OcrTextBlock> {
    val chunks = prepareForOcr(bitmap)
    return processChunks(chunks, language, addSpaceOnMerge, chunkProcessor)
}

private suspend fun processChunks(
    chunks: List<ImageChunk>,
    language: OcrLanguage,
    addSpaceOnMerge: Boolean?,
    chunkProcessor: suspend (ImageChunk) -> List<EngineLine>,
): List<OcrTextBlock> {
    val config = MergeConfig(
        language = language,
        addSpaceOnMerge = addSpaceOnMerge,
        imageWidth = chunks.firstOrNull()?.fullWidth?.toDouble(),
        imageHeight = chunks.firstOrNull()?.fullHeight?.toDouble(),
    )
    val merger = OwOCRMerger(config)

    val allEngineLines = chunks.flatMap { chunk ->
        val lines = chunkProcessor(chunk)
        lines.map { it.normalizeFromChunk(chunk) }
    }.distinctBy { it.text + it.bbox.toString() }

    val fullWidth = chunks.firstOrNull()?.fullWidth ?: 0
    val fullHeight = chunks.firstOrNull()?.fullHeight ?: 0
    val mergedResults = merger.merge(allEngineLines, fullWidth, fullHeight)
    return if (chunks.size > 1) {
        dedupeChunkBoundaryResults(mergedResults)
    } else {
        mergedResults
    }
}
