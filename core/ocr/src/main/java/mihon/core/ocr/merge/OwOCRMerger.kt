// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Clean reimplementation of Chimahon's `chimahon/src/main/java/chimahon/ocr/OwOCRMerger.kt` @ f85c063
// (https://github.com/Chimahon/Chimahon, GPL-3.0-or-later). It keeps the same staged
// paragraph-reconstruction pipeline and heuristics — line dictionaries, a global furigana filter,
// per-orientation paragraph grouping, close-paragraph merging, row grouping and reading order —
// while being restructured behind [OcrLineMerger] and emitting the unified
// `mihon.core.ocr.model.OcrTextBlock` directly (03 §3.3 "unify to one").
// KUN <--
package mihon.core.ocr.merge

import mihon.core.ocr.EngineLine
import mihon.core.ocr.MergeConfig
import mihon.core.ocr.NormalizedBBox
import mihon.core.ocr.OcrLanguage
import mihon.core.ocr.OcrLineGeometry
import mihon.core.ocr.OcrPreprocessor
import mihon.core.ocr.WritingDirection
import mihon.core.ocr.model.OcrTextBlock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Port of owocr's paragraph-reconstruction pipeline (connected components + BFS).
 *
 * The merger consumes the engine-agnostic [EngineLine] IR and produces the viewer-facing
 * [OcrTextBlock]s, applying:
 *  1. a global furigana (ruby) filter for Japanese,
 *  2. paragraph grouping (Japanese and legacy paths),
 *  3. close-paragraph merging,
 *  4. row grouping and reading order (right-to-left for vertical/RTL, top-to-bottom otherwise).
 */
class OwOCRMerger(
    private val config: MergeConfig = MergeConfig(),
) : OcrLineMerger {

    override fun merge(lines: List<EngineLine>, imageW: Int, imageH: Int): List<OcrTextBlock> {
        if (!config.enabled || lines.isEmpty()) return emptyList()

        val cfg = config.copy(imageWidth = imageW.toDouble(), imageHeight = imageH.toDouble())
        val parsed = lines.mapNotNull { it.toLine(cfg) }
        if (parsed.isEmpty()) return emptyList()

        val japanese = cfg.language.isJapanese
        val filtered = if (cfg.furiganaFilter && japanese) globalFuriganaFilter(parsed, cfg) else parsed
        if (filtered.isEmpty()) return emptyList()

        val paragraphs = if (japanese) japaneseParagraphs(filtered, cfg) else legacyParagraphs(filtered, cfg)
        val merged = if (cfg.mergeCloseParagraphs) mergeCloseParagraphs(paragraphs, cfg) else paragraphs

        val rows = groupIntoRows(merged, japanese)
        val ordered = rows
            .sortedBy { row -> row.paragraphs.minOf { it.bbox.top } }
            .flatMap { row -> reorderRow(row, japanese) }

        return ordered.map { it.toTextBlock(cfg.language) }
    }

    // ================================================================
    // STAGE 1: engine lines -> line dictionaries
    // ================================================================

    private class Line(
        val text: String,
        val bbox: NormalizedBBox,
        val vertical: Boolean,
        val rtl: Boolean,
        val characterSize: Double,
        val hasJpText: Boolean,
        val hasKanji: Boolean,
        val rotation: Double,
        var isFurigana: Boolean = false,
    ) {
        val area: Double get() = bbox.width * bbox.height
    }

    private fun EngineLine.toLine(cfg: MergeConfig): Line? {
        if (text.isBlank()) return null
        if (cfg.language == OcrLanguage.CHINESE && !hasKanji) return null

        val vertical = writingDirection == WritingDirection.TTB
        val rtl = writingDirection == WritingDirection.RTL || (!vertical && cfg.language.isRtl)
        return Line(
            text = text,
            bbox = bbox,
            vertical = vertical,
            rtl = rtl,
            characterSize = characterSize,
            hasJpText = hasJpText,
            hasKanji = hasKanji,
            rotation = rotation,
        )
    }

    // ================================================================
    // STAGE 2: paragraph records
    // ================================================================

    private class Paragraph(
        val bbox: NormalizedBBox,
        val vertical: Boolean,
        val rtl: Boolean,
        val characterSize: Double,
        val rotation: Double,
        val lines: List<Line>,
    )

    private fun Paragraph.toTextBlock(language: OcrLanguage): OcrTextBlock = OcrTextBlock(
        xmin = bbox.left.toFloat(),
        ymin = bbox.top.toFloat(),
        xmax = bbox.right.toFloat(),
        ymax = bbox.bottom.toFloat(),
        lines = lines.map { it.text },
        vertical = vertical,
        lineGeometries = lines.map { it.toGeometry() },
        language = language.bcp47,
    )

    private fun Line.toGeometry(): OcrLineGeometry = OcrLineGeometry(
        xmin = bbox.left.toFloat(),
        ymin = bbox.top.toFloat(),
        xmax = bbox.right.toFloat(),
        ymax = bbox.bottom.toFloat(),
        rotation = Math.toDegrees(rotation).toFloat(),
    )

    /**
     * Builds one paragraph from its source lines.
     *
     * The legacy (non-Japanese) path additionally merges broken line fragments and applies the
     * local furigana filter before the horizontal reading order (top-to-bottom, then
     * left-to-right) is applied — matching the viewer's `orderedLineIndices`.
     */
    private fun buildParagraph(
        lines: List<Line>,
        vertical: Boolean,
        rtl: Boolean,
        cfg: MergeConfig,
        legacy: Boolean,
    ): Paragraph {
        val sorted = if (vertical) {
            lines.sortedByDescending { it.bbox.right }
        } else {
            lines.sortedBy { it.bbox.top }
        }

        val merged = if (legacy) mergeOverlappingLines(sorted, vertical) else sorted
        val filtered = if (legacy && sorted.none { it.isFurigana } && cfg.furiganaFilter) {
            localFuriganaFilter(merged, vertical)
        } else {
            merged
        }
        val effective = filtered.ifEmpty { sorted.take(1) }

        val ordered = if (legacy && !vertical && !rtl) {
            effective.sortedWith(compareBy<Line> { it.bbox.centerY }.thenBy { it.bbox.left })
        } else {
            effective
        }

        val left = ordered.minOf { it.bbox.left }
        val right = ordered.maxOf { it.bbox.right }
        val top = ordered.minOf { it.bbox.top }
        val bottom = ordered.maxOf { it.bbox.bottom }
        val characterSize = if (vertical) {
            ordered.maxByOrNull { it.bbox.width }?.characterSize ?: 0.0
        } else {
            ordered.maxByOrNull { it.bbox.height }?.characterSize ?: 0.0
        }
        val avgRotation = ordered.map { it.bbox.rotation }.average()

        return Paragraph(
            bbox = NormalizedBBox(left, top, right, bottom),
            vertical = vertical,
            rtl = rtl,
            characterSize = characterSize,
            rotation = avgRotation,
            lines = ordered,
        )
    }

    // ================================================================
    // STAGE 2a: paragraph grouping — Japanese path (pixel-proximity clustering)
    // ================================================================

    private fun japaneseParagraphs(lines: List<Line>, cfg: MergeConfig): List<Paragraph> {
        val used = BooleanArray(lines.size)
        val paragraphs = mutableListOf<Paragraph>()

        for (i in lines.indices) {
            if (used[i]) continue
            val cluster = mutableListOf(lines[i])
            used[i] = true

            val queue = ArrayDeque<Line>()
            queue.add(lines[i])
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                for (j in lines.indices) {
                    if (used[j]) continue
                    val candidate = lines[j]
                    val vertical = if (current.area > candidate.area) current.vertical else candidate.vertical
                    if (shouldGroupInSameParagraph(current, candidate, vertical, cfg)) {
                        cluster.add(candidate)
                        queue.add(candidate)
                        used[j] = true
                    }
                }
            }

            val vertical = cluster.maxByOrNull { it.area }?.vertical ?: true
            paragraphs.add(buildParagraph(cluster, vertical, cluster.first().rtl, cfg, legacy = false))
        }

        return paragraphs
    }

    /**
     * Pixel-relative paragraph grouping used for Japanese. Lines join when their thickness, area
     * density, rotation and nearest-edge distance are compatible; furigana (small, tight kana
     * beside a kanji line) is explicitly allowed to join.
     */
    private fun shouldGroupInSameParagraph(
        line1: Line,
        line2: Line,
        vertical: Boolean,
        cfg: MergeConfig,
    ): Boolean {
        val imgW = cfg.imageWidth ?: 1000.0
        val imgH = cfg.imageHeight ?: 1000.0

        val w1 = line1.bbox.width * imgW
        val h1 = line1.bbox.height * imgH
        val w2 = line2.bbox.width * imgW
        val h2 = line2.bbox.height * imgH

        val charSize1 = if (line1.vertical) w1 else h1
        val charSize2 = if (line2.vertical) w2 else h2
        val characterSize = maxOf(charSize1, charSize2)

        val t1 = minOf(w1, h1)
        val t2 = minOf(w2, h2)
        val thicknessRatio = if (t1 > 0 && t2 > 0) maxOf(t1, t2) / minOf(t1, t2) else 1.0

        var rotDiff = abs(line1.bbox.rotation - line2.bbox.rotation)
        while (rotDiff > PI) rotDiff -= PI
        if (rotDiff > PI / 2) rotDiff = PI - rotDiff
        val distToMultipleOfPiHalf = minOf(rotDiff, abs(PI / 2 - rotDiff))
        if (distToMultipleOfPiHalf >= 0.1) return false

        val area1 = w1 * h1
        val area2 = w2 * h2
        val areaRatio = if (maxOf(area1, area2) > 0) minOf(area1, area2) / maxOf(area1, area2) else 1.0
        val isPunctuation = areaRatio < 0.10

        val maxHDistMult: Double
        val minDensityReq: Double
        if (isPunctuation) {
            maxHDistMult = 1.0
            minDensityReq = 0.40
        } else if (thicknessRatio > 1.8) {
            maxHDistMult = 0.50
            minDensityReq = 0.55
        } else {
            maxHDistMult = 0.75
            minDensityReq = 0.50
        }
        val furiganaHDistMult = if (thicknessRatio < 1.35 && !isPunctuation) 1.25 else 0.0

        val mergedLeft = minOf(line1.bbox.left, line2.bbox.left) * imgW
        val mergedTop = minOf(line1.bbox.top, line2.bbox.top) * imgH
        val mergedRight = maxOf(line1.bbox.right, line2.bbox.right) * imgW
        val mergedBottom = maxOf(line1.bbox.bottom, line2.bbox.bottom) * imgH
        val mergedArea = maxOf(0.0, mergedRight - mergedLeft) * maxOf(0.0, mergedBottom - mergedTop)
        if (mergedArea > 0 && (area1 + area2) / mergedArea < minDensityReq) return false

        if (vertical) {
            val hDist = horizontalDistance(line1.bbox, line2.bbox) * imgW
            val lineWidth = (w1 + w2) / 2
            val yMinDiff = abs(line1.bbox.top - line2.bbox.top) * imgH
            val yOverlap = maxOf(0.0, minOf(line1.bbox.bottom, line2.bbox.bottom) * imgH - maxOf(line1.bbox.top, line2.bbox.top) * imgH)
            val minH = minOf(h1, h2)
            val yMaxDiff = abs(line1.bbox.bottom - line2.bbox.bottom) * imgH
            if (yMinDiff > characterSize * 2.0 && yMaxDiff > characterSize * 2.0) return false

            if (hDist < lineWidth * maxHDistMult && yOverlap > minH * 0.5) return true
            if (furiganaHDistMult > 0 && hDist < lineWidth * furiganaHDistMult &&
                yMinDiff < characterSize * 0.3 && yOverlap > minH * 0.5
            ) {
                return true
            }
            if (thicknessRatio < 2.5 && hDist < lineWidth * 1.0 &&
                yMinDiff < characterSize * 0.5 && yOverlap > minH * 0.5
            ) {
                return true
            }
            return false
        }

        val vDist = verticalDistance(line1.bbox, line2.bbox) * imgH
        val lineHeight = maxOf(h1, h2)
        if (vDist >= lineHeight * 1.5) return false

        val coord1 = line2.bbox.right * imgW
        val coord2 = line1.bbox.right * imgW
        if (abs(coord1 - coord2) < 1.5 * characterSize) return true
        if (cfg.supportCenterAlignedText && horizontalOverlap(line1.bbox, line2.bbox) > 0.9) return true

        return false
    }

    // ================================================================
    // STAGE 2b: paragraph grouping — legacy path (per-orientation component sweep)
    // ================================================================

    private fun legacyParagraphs(lines: List<Line>, cfg: MergeConfig): List<Paragraph> {
        val grouped = mutableSetOf<Int>()
        val paragraphs = mutableListOf<Paragraph>()

        fun groupLines(vertical: Boolean, rtl: Boolean) {
            val indices = lines.indices.filter { i ->
                lines[i].vertical == vertical && lines[i].rtl == rtl && i !in grouped
            }
            if (indices.size < 2) return

            val components = connectedComponents(
                items = indices.map { lines[it] },
                shouldConnect = { a, b -> shouldGroupInSameParagraphLegacy(a, b, vertical, cfg) },
                startCoord = when {
                    vertical -> { line: Line -> line.bbox.top }
                    rtl -> { line: Line -> line.bbox.right }
                    else -> { line: Line -> line.bbox.left }
                },
                endCoord = when {
                    vertical -> { line: Line -> line.bbox.bottom }
                    rtl -> { line: Line -> line.bbox.left }
                    else -> { line: Line -> line.bbox.right }
                },
                sweepPadding = 0.0,
            )

            for (component in components) {
                if (component.size > 1) {
                    val originalIndices = component.map { indices[it] }
                    val paragraphLines = originalIndices.map { lines[it] }
                    paragraphs.add(buildParagraph(paragraphLines, vertical, rtl, cfg, legacy = true))
                    grouped.addAll(originalIndices)
                }
            }
        }

        groupLines(vertical = true, rtl = false)
        groupLines(vertical = false, rtl = true)
        groupLines(vertical = false, rtl = false)

        lines.indices.filter { it !in grouped }.forEach { i ->
            paragraphs.add(buildParagraph(listOf(lines[i]), lines[i].vertical, lines[i].rtl, cfg, legacy = true))
        }

        return paragraphs
    }

    private fun shouldGroupInSameParagraphLegacy(
        line1: Line,
        line2: Line,
        vertical: Boolean,
        cfg: MergeConfig,
    ): Boolean {
        val characterSize = maxOf(line1.characterSize, line2.characterSize)

        if (vertical) {
            val hDist = horizontalDistance(line1.bbox, line2.bbox)
            val lineWidth = maxOf(line1.bbox.width, line2.bbox.width)
            if (hDist >= lineWidth) return false
            if (abs(line1.bbox.top - line2.bbox.top) < characterSize) return true
        } else {
            val vDist = verticalDistance(line1.bbox, line2.bbox)
            val lineHeight = maxOf(line1.bbox.height, line2.bbox.height)
            if (vDist >= lineHeight * 2) return false

            val coord1 = if (line1.rtl) line2.bbox.right else line1.bbox.left
            val coord2 = if (line1.rtl) line1.bbox.right else line2.bbox.left
            if (coord1 - coord2 < 2 * characterSize) return true
            if (cfg.supportCenterAlignedText && horizontalOverlap(line1.bbox, line2.bbox) > 0.9) return true
        }

        val filtered = localFuriganaFilter(listOf(line1, line2), vertical)
        if (filtered.size == 1) {
            line1.isFurigana = true
            return true
        }

        return false
    }

    // ================================================================
    // STAGE 2c: broken-fragment merge + furigana filters
    // ================================================================

    private fun mergeOverlappingLines(lines: List<Line>, vertical: Boolean): List<Line> {
        if (lines.size < 2) return lines

        val sorted = if (vertical) {
            lines.sortedBy { it.bbox.top }
        } else {
            lines.sortedBy { it.bbox.left }
        }

        val merged = mutableListOf<Line>()
        val usedIndices = mutableSetOf<Int>()

        for (i in sorted.indices) {
            if (i in usedIndices) continue
            val mergeGroup = mutableListOf(sorted[i])
            usedIndices.add(i)
            var lastLine = sorted[i]

            for (j in (i + 1) until sorted.size) {
                if (j in usedIndices) continue
                val nextLine = sorted[j]

                if (vertical) {
                    if (nextLine.bbox.top - lastLine.bbox.bottom > 0.5 * lastLine.characterSize) break
                } else {
                    if (nextLine.bbox.left - lastLine.bbox.right > 0.5 * lastLine.characterSize) break
                }

                if (shouldMergeLines(lastLine, nextLine, vertical)) {
                    mergeGroup.add(nextLine)
                    usedIndices.add(j)
                    lastLine = nextLine
                }
            }

            merged.add(if (mergeGroup.size > 1) mergeMultipleLines(mergeGroup, vertical) else sorted[i])
        }
        return merged
    }

    private fun shouldMergeLines(a: Line, b: Line, vertical: Boolean): Boolean = if (vertical) {
        horizontalOverlap(a.bbox, b.bbox) > 0.8 && verticalOverlap(a.bbox, b.bbox) < 0.4
    } else {
        verticalOverlap(a.bbox, b.bbox) > 0.8 && horizontalOverlap(a.bbox, b.bbox) < 0.4
    }

    private fun mergeMultipleLines(lines: List<Line>, vertical: Boolean): Line {
        val sorted = if (vertical) {
            lines.sortedBy { it.bbox.centerY }
        } else {
            lines.sortedBy { it.bbox.centerX }
        }

        val mergedText = OcrPreprocessor.clean(sorted.joinToString("") { it.text })
        val left = sorted.minOf { it.bbox.left }
        val right = sorted.maxOf { it.bbox.right }
        val top = sorted.minOf { it.bbox.top }
        val bottom = sorted.maxOf { it.bbox.bottom }
        val totalDim = sorted.sumOf { if (vertical) it.bbox.height else it.bbox.width }
        val charSize = totalDim / mergedText.length.coerceAtLeast(1)

        return Line(
            text = mergedText,
            bbox = NormalizedBBox(left, top, right, bottom),
            vertical = vertical,
            rtl = lines[0].rtl,
            characterSize = charSize,
            hasJpText = sorted.any { it.hasJpText },
            hasKanji = sorted.any { it.hasKanji },
            rotation = sorted[0].rotation,
        )
    }

    private val katakanaRegex = Regex("[\u30A0-\u30FF]")
    private val kanjiRegex = Regex("[\u4E00-\u9FFF\u3400-\u4DBF]")

    /** Drops a kana fragment that sits immediately below/right of a kanji line (ruby). */
    private fun localFuriganaFilter(lines: List<Line>, vertical: Boolean): List<Line> {
        val filtered = mutableListOf<Line>()
        for (i in lines.indices) {
            if (lines[i].isFurigana) continue
            if (i >= lines.size - 1) {
                filtered.add(lines[i])
                continue
            }
            val next = lines[i + 1]

            if (!(lines[i].hasJpText && next.hasJpText)) {
                filtered.add(lines[i])
                continue
            }
            if (lines[i].hasKanji) {
                filtered.add(lines[i])
                continue
            }
            if (!next.hasKanji) {
                filtered.add(lines[i])
                continue
            }
            if (next.isFurigana) {
                filtered.add(lines[i])
                continue
            }

            val curBbox = lines[i].bbox
            val nextBbox = next.bbox

            val passedPosition = if (vertical) {
                val minHDist = abs(nextBbox.width - curBbox.width) / 2
                val maxHDist = nextBbox.width + curBbox.width / 2
                val hDist = curBbox.centerX - nextBbox.centerX
                val vOverlap = verticalOverlap(curBbox, nextBbox)
                hDist > minHDist && hDist < maxHDist && vOverlap > 0.4
            } else {
                val minVDist = abs(nextBbox.height - curBbox.height) / 2
                val maxVDist = nextBbox.height + curBbox.height / 2
                val vDist = nextBbox.centerY - curBbox.centerY
                val hOverlap = horizontalOverlap(curBbox, nextBbox)
                vDist > minVDist && vDist < maxVDist && hOverlap > 0.4
            }
            if (!passedPosition) {
                filtered.add(lines[i])
                continue
            }

            val passedSize = if (vertical) {
                lines[i].characterSize < next.characterSize * 0.85
            } else {
                curBbox.height < nextBbox.height * 0.85
            }
            if (!passedSize) filtered.add(lines[i])
        }
        return filtered
    }

    /** Drops any non-kanji line that is a thin ruby annotation for a larger kanji line anywhere. */
    private fun globalFuriganaFilter(lines: List<Line>, cfg: MergeConfig): List<Line> {
        val n = lines.size
        val keep = BooleanArray(n) { true }

        val alphaRegex = Regex("[A-Za-z]")
        val digitRegex = Regex("[0-9]")
        val imgW = cfg.imageWidth ?: 1000.0
        val imgH = cfg.imageHeight ?: 1000.0

        for (j in 0 until n) {
            if (!keep[j]) continue
            val sub = lines[j]
            val subText = sub.text

            if (kanjiRegex.containsMatchIn(subText)) continue
            val hasAlpha = alphaRegex.containsMatchIn(subText)
            val hasKatakana = katakanaRegex.containsMatchIn(subText)
            if (hasAlpha && !hasKatakana) continue
            val hasDigit = digitRegex.containsMatchIn(subText)
            if (hasDigit && !hasKatakana && !hasAlpha) continue

            val subBbox = sub.bbox
            val subThickness = minOf(subBbox.width * imgW, subBbox.height * imgH)

            for (i in 0 until n) {
                if (i == j || !keep[i]) continue
                val main = lines[i]
                if (!kanjiRegex.containsMatchIn(main.text)) continue

                val mainBbox = main.bbox
                val mainThickness = minOf(mainBbox.width * imgW, mainBbox.height * imgH)
                if (subThickness > mainThickness * 0.75) continue

                val proximityLimit = mainThickness * 0.30
                val overlapRatio = 0.05

                val xOverlap = maxOf(0.0, minOf(subBbox.right, mainBbox.right) - maxOf(subBbox.left, mainBbox.left)) * imgW
                val xGapV = if (xOverlap > 0.0) {
                    0.0
                } else {
                    minOf(abs(subBbox.left - mainBbox.right), abs(subBbox.right - mainBbox.left)) * imgW
                }
                val oy = maxOf(0.0, minOf(subBbox.bottom, mainBbox.bottom) - maxOf(subBbox.top, mainBbox.top)) * imgH
                val yOverlapV = oy / minOf(maxOf(0.001, subBbox.height * imgH), maxOf(0.001, mainBbox.height * imgH))
                val isVerticalFurigana = xGapV < proximityLimit && yOverlapV > overlapRatio

                val yOverlap = maxOf(0.0, minOf(subBbox.bottom, mainBbox.bottom) - maxOf(subBbox.top, mainBbox.top)) * imgH
                val yGapH = if (yOverlap > 0.0) {
                    0.0
                } else {
                    minOf(abs(subBbox.top - mainBbox.bottom), abs(subBbox.bottom - mainBbox.top)) * imgH
                }
                val ox = maxOf(0.0, minOf(subBbox.right, mainBbox.right) - maxOf(subBbox.left, mainBbox.left)) * imgW
                val xOverlapH = ox / minOf(maxOf(0.001, subBbox.width * imgW), maxOf(0.001, mainBbox.width * imgW))
                val isHorizontalFurigana = yGapH < proximityLimit && xOverlapH > overlapRatio

                val isFuri = when {
                    mainBbox.height * imgH > mainBbox.width * imgW * 1.2 ->
                        isVerticalFurigana && mainBbox.centerX < subBbox.centerX
                    mainBbox.width * imgW > mainBbox.height * imgH * 1.2 ->
                        isHorizontalFurigana && mainBbox.centerY > subBbox.centerY
                    else ->
                        (isVerticalFurigana && mainBbox.centerX < subBbox.centerX) ||
                            (isHorizontalFurigana && mainBbox.centerY > subBbox.centerY)
                }

                if (isFuri) {
                    keep[j] = false
                    break
                }
            }
        }

        return lines.filterIndexed { i, _ -> keep[i] }
    }

    // ================================================================
    // STAGE 3: merge close paragraphs
    // ================================================================

    private fun mergeCloseParagraphs(paragraphs: List<Paragraph>, cfg: MergeConfig): List<Paragraph> {
        if (paragraphs.size < 2) return paragraphs

        val merged = mutableListOf<Paragraph>()

        fun mergeOrientation(vertical: Boolean) {
            val indices = paragraphs.indices.filter { paragraphs[it].vertical == vertical }
            if (indices.size <= 1) {
                indices.forEach { merged.add(paragraphs[it]) }
                return
            }

            val components = connectedComponents(
                items = indices.map { paragraphs[it] },
                shouldConnect = { a, b -> closeEnough(a, b, vertical) },
                startCoord = { if (vertical) it.bbox.left else it.bbox.top },
                endCoord = { if (vertical) it.bbox.right else it.bbox.bottom },
            )

            for (component in components) {
                val originalIndices = component.map { indices[it] }
                if (component.size == 1) {
                    merged.add(paragraphs[originalIndices[0]])
                } else {
                    val componentParagraphs = originalIndices.map { paragraphs[it] }
                    val allLines = componentParagraphs.flatMap { it.lines }
                    merged.add(
                        buildParagraph(
                            lines = allLines,
                            vertical = vertical,
                            rtl = componentParagraphs.first().rtl,
                            cfg = cfg.copy(furiganaFilter = false),
                            legacy = false,
                        ),
                    )
                }
            }
        }

        mergeOrientation(vertical = true)
        mergeOrientation(vertical = false)
        return merged
    }

    private fun closeEnough(a: Paragraph, b: Paragraph, vertical: Boolean): Boolean {
        val charSize = maxOf(a.characterSize, b.characterSize)

        var rotDiff = abs(a.rotation - b.rotation)
        while (rotDiff > PI) rotDiff -= PI
        if (rotDiff > PI / 2) rotDiff = PI - rotDiff
        val distToMultipleOfPiHalf = minOf(rotDiff, abs(PI / 2 - rotDiff))
        if (distToMultipleOfPiHalf >= 0.1) return false

        return if (vertical) {
            val widthRatio = minOf(a.bbox.width, b.bbox.width) / max(0.001, maxOf(a.bbox.width, b.bbox.width))
            verticalDistance(a.bbox, b.bbox) <= 0.5 * charSize &&
                widthRatio > 0.95 &&
                horizontalOverlap(a.bbox, b.bbox) > 0.95
        } else {
            val heightRatio = minOf(a.bbox.height, b.bbox.height) / max(0.001, maxOf(a.bbox.height, b.bbox.height))
            a.rtl == b.rtl &&
                horizontalDistance(a.bbox, b.bbox) <= 2 * charSize &&
                heightRatio > 0.6 &&
                verticalOverlap(a.bbox, b.bbox) > 0.7
        }
    }

    // ================================================================
    // STAGE 4/5: rows and reading order
    // ================================================================

    private class Row(
        val paragraphs: List<Paragraph>,
        val verticalOrRtl: Boolean,
    )

    private fun groupIntoRows(paragraphs: List<Paragraph>, japanese: Boolean): List<Row> {
        if (paragraphs.isEmpty()) return emptyList()
        if (paragraphs.size == 1) {
            return listOf(Row(paragraphs, paragraphs[0].vertical || paragraphs[0].rtl))
        }

        val components = connectedComponents(
            items = paragraphs,
            shouldConnect = { a, b ->
                val overlapTop = maxOf(a.bbox.top, b.bbox.top)
                val overlapBottom = minOf(a.bbox.bottom, b.bbox.bottom)
                if (overlapBottom <= overlapTop) {
                    false
                } else {
                    (overlapBottom - overlapTop) / minOf(a.bbox.height, b.bbox.height).coerceAtLeast(0.001) > 0.2
                }
            },
            startCoord = { it.bbox.top },
            endCoord = { it.bbox.bottom },
            sweepPadding = if (japanese) 0.2 else 0.0,
        )

        return components.map { component ->
            val rowParagraphs = component.map { paragraphs[it] }
            val verticalOrRtl = rowParagraphs.count { it.vertical || it.rtl } * 2 >= rowParagraphs.size
            Row(rowParagraphs, verticalOrRtl)
        }
    }

    private fun reorderRow(row: Row, japanese: Boolean): List<Paragraph> {
        if (row.paragraphs.size < 2) return row.paragraphs
        val sorted = row.paragraphs.sortedBy { it.bbox.left }
        val reordered = if (row.verticalOrRtl) sorted.reversed() else sorted
        return if (japanese) {
            reorderMixedOrientation(reordered)
        } else {
            reorderMixedOrientationLegacy(reordered, row.verticalOrRtl)
        }
    }

    private fun reorderMixedOrientation(paragraphs: List<Paragraph>): List<Paragraph> {
        if (paragraphs.size < 2) return paragraphs

        var result = paragraphs
        var madeProgress = true
        while (madeProgress) {
            madeProgress = false
            for (i in 0 until result.size - 1) {
                val p1 = result[i]
                val p2 = result[i + 1]
                val p1Center = if (p1.vertical) p1.bbox.centerY else p1.bbox.centerX
                val p2Center = if (p2.vertical) p2.bbox.centerY else p2.bbox.centerX
                val p1End = if (p1.vertical) p1.bbox.bottom else p1.bbox.right
                val p2End = if (p2.vertical) p2.bbox.bottom else p2.bbox.right

                if (p1.vertical != p2.vertical) {
                    if (p2.vertical && p2Center < p1Center + (p1End - p1Center) / 2) {
                        result = result.toMutableList().apply {
                            removeAt(i + 1)
                            add(i, p2)
                        }
                        madeProgress = true
                    } else if (!p2.vertical && p1Center > p2Center + (p2End - p2Center) / 2) {
                        result = result.toMutableList().apply {
                            removeAt(i + 1)
                            add(i, p2)
                        }
                        madeProgress = true
                    }
                }
            }
        }
        return result
    }

    private fun reorderMixedOrientationLegacy(paragraphs: List<Paragraph>, verticalOrRtl: Boolean): List<Paragraph> {
        if (paragraphs.size < 2) return paragraphs

        val result = mutableListOf<Paragraph>()
        val currentBlock = mutableListOf(paragraphs[0])
        var currentVertical = paragraphs[0].vertical

        for (paragraph in paragraphs.drop(1)) {
            if (paragraph.vertical == currentVertical) {
                currentBlock.add(paragraph)
            } else {
                if (currentVertical != verticalOrRtl) currentBlock.reverse()
                result.addAll(currentBlock)
                currentBlock.clear()
                currentBlock.add(paragraph)
                currentVertical = paragraph.vertical
            }
        }

        if (currentVertical != verticalOrRtl) currentBlock.reverse()
        result.addAll(currentBlock)
        return result
    }

    // ================================================================
    // UTILITY: connected components (sweep-line + BFS)
    // ================================================================

    internal fun <T> connectedComponents(
        items: List<T>,
        shouldConnect: (T, T) -> Boolean,
        startCoord: (T) -> Double,
        endCoord: (T) -> Double,
        sweepPadding: Double = 0.2,
    ): List<List<Int>> {
        if (items.isEmpty()) return emptyList()
        if (items.size == 1) return listOf(listOf(0))

        val graph = Array(items.size) { mutableListOf<Int>() }
        val isReverseSweep = startCoord(items[0]) > endCoord(items[0])

        val sortedItems = items.indices
            .map { i -> i to items[i] }
            .sortedBy { (_, item) -> startCoord(item) }
            .let { if (isReverseSweep) it.reversed() else it }

        class ActiveItem(val index: Int, val item: T, val end: Double)

        val activeItems = mutableListOf<ActiveItem>()
        for ((originalIdx, item) in sortedItems) {
            val currentStart = startCoord(item)
            val lineEnd = endCoord(item)

            activeItems.retainAll { active ->
                if (isReverseSweep) {
                    active.end < currentStart - sweepPadding
                } else {
                    active.end > currentStart - sweepPadding
                }
            }

            for (active in activeItems) {
                if (shouldConnect(item, active.item)) {
                    graph[originalIdx].add(active.index)
                    graph[active.index].add(originalIdx)
                }
            }

            activeItems.add(ActiveItem(originalIdx, item, lineEnd))
        }

        val visited = BooleanArray(items.size)
        val components = mutableListOf<List<Int>>()
        for (i in items.indices) {
            if (visited[i]) continue
            val component = mutableListOf<Int>()
            val queue = ArrayDeque<Int>()
            queue.add(i)
            visited[i] = true
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                component.add(node)
                for (neighbor in graph[node]) {
                    if (!visited[neighbor]) {
                        visited[neighbor] = true
                        queue.add(neighbor)
                    }
                }
            }
            components.add(component)
        }

        return components
    }

    // ================================================================
    // UTILITY: overlap / distance on normalized boxes
    // ================================================================

    private fun horizontalOverlap(a: NormalizedBBox, b: NormalizedBBox): Double {
        val overlapLeft = maxOf(a.left, b.left)
        val overlapRight = minOf(a.right, b.right)
        if (overlapRight <= overlapLeft) return 0.0
        val overlapWidth = overlapRight - overlapLeft
        val smallerWidth = minOf(a.width, b.width)
        return if (smallerWidth > 0) overlapWidth / smallerWidth else 0.0
    }

    private fun verticalOverlap(a: NormalizedBBox, b: NormalizedBBox): Double {
        val overlapTop = maxOf(a.top, b.top)
        val overlapBottom = minOf(a.bottom, b.bottom)
        if (overlapBottom <= overlapTop) return 0.0
        val overlapHeight = overlapBottom - overlapTop
        val smallerHeight = minOf(a.height, b.height)
        return if (smallerHeight > 0) overlapHeight / smallerHeight else 0.0
    }

    private fun horizontalDistance(a: NormalizedBBox, b: NormalizedBBox): Double = when {
        a.right < b.left -> b.left - a.right
        b.right < a.left -> a.left - b.right
        else -> 0.0
    }

    private fun verticalDistance(a: NormalizedBBox, b: NormalizedBBox): Double = when {
        a.bottom < b.top -> b.top - a.bottom
        b.bottom < a.top -> a.top - b.bottom
        else -> 0.0
    }
}
