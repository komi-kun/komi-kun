// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Clean reimplementation of Chimahon's `chimahon/src/main/java/chimahon/ocr/OwOCRMerger.kt` @ f85c063
// (https://github.com/Chimahon/Chimahon, GPL-3.0-or-later). It keeps the same staged
// paragraph-reconstruction pipeline and heuristics — line dictionaries, a global furigana filter,
// per-orientation paragraph grouping, close-paragraph merging, row grouping and reading order —
// while being restructured behind [OcrLineMerger] and emitting the unified
// `mihon.core.ocr.model.OcrTextBlock` directly (03 §3.3 "unify to one").
//
// Tuning of the three rules below (furigana character-size gate, single-character SFX isolation,
// reading-order band guard) was measured against the offline replay in the `ppocrv6-manga-ft` repo,
// `scripts/exp_page16_merger_filter.py`, which ports `toLine` / `filterSingleCharSfxNoise` /
// `globalFuriganaFilter`. That script has NOT been updated for the three rules below, so its
// "dropped by singleChar/furigana" lists now over-report drops; this file is the source of truth.
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
        // KUN -->
        val filteredNoise = if (japanese) filterSingleCharSfxNoise(parsed) else parsed
        if (filteredNoise.isEmpty()) return emptyList()
        val filteredFuri = if (cfg.furiganaFilter && japanese) globalFuriganaFilter(filteredNoise, cfg) else filteredNoise
        if (filteredFuri.isEmpty()) return emptyList()
        val filtered = if (japanese) trimFuriganaBloatedVerticalColumns(filteredFuri) else filteredFuri
        // KUN <--

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
        // KUN -->
        if (cfg.language == OcrLanguage.JAPANESE && (!hasJpText || OcrPreprocessor.isImplausibleJapaneseNoise(text))) {
            return null
        }
        // KUN <--

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

    // KUN -->
    private fun filterSingleCharSfxNoise(lines: List<Line>): List<Line> {
        if (lines.isEmpty()) return emptyList()

        val thicknesses = lines.map { minOf(it.bbox.width, it.bbox.height) }.sorted()
        val medianThickness = if (thicknesses.size % 2 == 1) {
            thicknesses[thicknesses.size / 2]
        } else {
            (thicknesses[thicknesses.size / 2 - 1] + thicknesses[thicknesses.size / 2]) / 2.0
        }

        return lines.filterNot { line ->
            val stripped = line.text.trim { it.isWhitespace() || !it.isLetterOrDigit() }
            if (stripped.isEmpty()) return@filterNot false
            val cpCount = stripped.codePointCount(0, stripped.length)
            if (cpCount != 1) return@filterNot false

            val lineThickness = minOf(line.bbox.width, line.bbox.height)
            val isTilted = abs(line.bbox.rotation) > 0.03
            val isOversized = lines.size >= 3 && lineThickness > medianThickness * 1.55
            // KUN -->
            // An oversized single-character box is noise only when it stands alone. A column of a
            // dialogue paragraph always has a same-orientation successive column/line beside it,
            // while a graphic SFX does not — so a box that does have one is a real column the
            // recogniser under-read to a single character, not noise: on a real vertical-text page
            // a 281 px two-character column (1.82x the page's median column width) was returned as
            // its first character alone, and the whole column disappeared from the overlay while
            // the column beside it in the same bubble survived.
            isTilted || (isOversized && !line.hasSuccessiveLineNeighbour(lines))
            // KUN <--
        }
    }

    // KUN -->
    /**
     * True when another line is a successive column/line of this one ([OcrMergeGeometry.nextLine]),
     * i.e. the box sits inside a text run rather than standing on its own like a drawn SFX.
     *
     * [OcrMergeGeometry.nextLine] also requires a comparable thickness, which is what keeps a
     * standalone oversized glyph apart: its own size already breaks the comparison against the
     * surrounding dialogue.
     */
    private fun Line.hasSuccessiveLineNeighbour(lines: List<Line>): Boolean = lines.any { other ->
        other !== this &&
            other.vertical == vertical &&
            OcrMergeGeometry.nextLine(bbox, other.bbox, vertical)
    }
    // KUN <--

    private fun Line.canAdoptVerticalFromNeighbor(): Boolean {
        val cpCount = text.trim().codePointCount(0, text.trim().length)
        if (cpCount != 1) return false
        val minDim = minOf(bbox.width, bbox.height)
        val maxDim = maxOf(bbox.width, bbox.height).coerceAtLeast(1e-6)
        return minDim / maxDim >= 0.65
    }

    /**
     * When the detector merges vertical ruby furigana (printed on the right of a vertical column)
     * into the main kanji column's bounding box, the box's right edge balloons to ~1.5-2.0x the
     * normal column width and overlaps the neighbour column to its right. Trimming the right edge
     * back to the page's normal vertical column width restores clean column alignment and merging.
     */
    private fun trimFuriganaBloatedVerticalColumns(lines: List<Line>): List<Line> {
        val verticalWidths = lines.filter { it.vertical }.map { it.bbox.width }.sorted()
        if (verticalWidths.size < 3) return lines
        val medianWidth = if (verticalWidths.size % 2 == 1) {
            verticalWidths[verticalWidths.size / 2]
        } else {
            (verticalWidths[verticalWidths.size / 2 - 1] + verticalWidths[verticalWidths.size / 2]) / 2.0
        }
        if (medianWidth <= 0.0) return lines

        return lines.map { line ->
            val trimmed = line.text.trim()
            val cpCount = trimmed.codePointCount(0, trimmed.length)
            if (
                line.vertical &&
                line.hasKanji &&
                cpCount >= 3 &&
                line.bbox.height > line.bbox.width * 1.4 &&
                line.bbox.width > medianWidth * 1.45 &&
                line.bbox.width < medianWidth * 2.2
            ) {
                val newRight = minOf(line.bbox.right, line.bbox.left + medianWidth * 1.10)
                Line(
                    text = line.text,
                    bbox = line.bbox.copy(right = newRight),
                    vertical = line.vertical,
                    rtl = line.rtl,
                    characterSize = line.characterSize,
                    hasJpText = line.hasJpText,
                    hasKanji = line.hasKanji,
                    rotation = line.rotation,
                    isFurigana = line.isFurigana,
                )
            } else {
                line
            }
        }
    }
    // KUN <--

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

    // KUN -->
    private fun dedupeParagraphLines(lines: List<Line>, vertical: Boolean): List<Line> {
        if (lines.size < 2) return lines
        val kept = mutableListOf<Line>()
        for (candidate in lines) {
            val candidateText = candidate.text.trim()
            val duplicate = kept.any { existing ->
                val existingText = existing.text.trim()
                if (existingText != candidateText &&
                    !existingText.contains(candidateText) &&
                    !candidateText.contains(existingText)
                ) {
                    return@any false
                }
                val interW = maxOf(0.0, minOf(existing.bbox.right, candidate.bbox.right) - maxOf(existing.bbox.left, candidate.bbox.left))
                val interH = maxOf(0.0, minOf(existing.bbox.bottom, candidate.bbox.bottom) - maxOf(existing.bbox.top, candidate.bbox.top))
                val interArea = interW * interH
                val minArea = minOf(existing.bbox.width * existing.bbox.height, candidate.bbox.width * candidate.bbox.height)
                if (minArea <= 0.0) return@any false
                val iom = interArea / minArea
                if (vertical) {
                    val minW = minOf(existing.bbox.width, candidate.bbox.width)
                    val xOverlap = if (minW > 0.0) interW / minW else 0.0
                    xOverlap >= 0.50 || iom >= 0.40
                } else {
                    val minH = minOf(existing.bbox.height, candidate.bbox.height)
                    val yOverlap = if (minH > 0.0) interH / minH else 0.0
                    yOverlap >= 0.50 || iom >= 0.40
                }
            }
            if (!duplicate) {
                kept.add(candidate)
            }
        }
        return kept
    }
    // KUN <--

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
        // KUN -->
        val sorted = sortLinesInParagraph(lines, vertical, rtl)
        val deduplicated = dedupeParagraphLines(sorted, vertical)
        // KUN <--

        val merged = if (legacy) mergeOverlappingLines(deduplicated, vertical) else deduplicated
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

    // KUN -->
    /**
     * Orders lines within a paragraph in correct reading order.
     * For vertical (tategaki): columns are ordered right-to-left, and fragments within the same
     * column (or with high advance overlap) are ordered top-to-bottom.
     * For horizontal: rows are ordered top-to-bottom, and fragments within the same row (or with
     * high advance overlap) are ordered left-to-right (or right-to-left for RTL).
     */
    private fun sortLinesInParagraph(
        lines: List<Line>,
        vertical: Boolean,
        rtl: Boolean,
    ): List<Line> {
        if (lines.size <= 1) return lines

        val n = lines.size
        val parent = IntArray(n) { it }
        fun find(i: Int): Int {
            var root = i
            while (root != parent[root]) root = parent[root]
            var curr = i
            while (curr != root) {
                val next = parent[curr]
                parent[curr] = root
                curr = next
            }
            return root
        }
        fun union(i: Int, j: Int) {
            val rootI = find(i)
            val rootJ = find(j)
            if (rootI != rootJ) parent[rootI] = rootJ
        }

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val a = lines[i]
                val b = lines[j]
                val onSameRun = OcrMergeGeometry.sameLineFragment(a.bbox, b.bbox, vertical) ||
                    OcrMergeGeometry.advanceOverlap(a.bbox, b.bbox, vertical) > 0.45
                if (onSameRun) {
                    union(i, j)
                }
            }
        }

        val groups = lines.indices.groupBy { find(it) }.values.map { indices ->
            indices.map { lines[it] }
        }

        val sortedGroups = groups.map { group ->
            if (vertical) {
                group.sortedBy { it.bbox.top }
            } else if (rtl) {
                group.sortedByDescending { it.bbox.right }
            } else {
                group.sortedBy { it.bbox.left }
            }
        }

        return if (vertical) {
            sortedGroups.sortedByDescending { group -> group.maxOf { it.bbox.right } }.flatten()
        } else {
            sortedGroups.sortedBy { group -> group.minOf { it.bbox.top } }.flatten()
        }
    }
    // KUN <--

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
                    // KUN -->
                    val vertical = when {
                        current.vertical == candidate.vertical -> current.vertical
                        current.vertical && candidate.canAdoptVerticalFromNeighbor() -> true
                        candidate.vertical && current.canAdoptVerticalFromNeighbor() -> true
                        else -> null
                    } ?: continue
                    // KUN <--
                    if (shouldGroupInSameParagraph(current, candidate, vertical, cfg)) {
                        cluster.add(candidate)
                        queue.add(candidate)
                        used[j] = true
                    }
                }
            }

            // KUN -->
            val vertical = cluster.any { it.vertical }
            // A cluster that bridged two stacked bubbles comes back as one band per bubble.
            splitInterleavedBands(cluster, vertical).forEach { band ->
                paragraphs.add(buildParagraph(band, band.any { it.vertical }, band.first().rtl, cfg, legacy = false))
            }
            // KUN <--
        }

        return paragraphs
    }

    // KUN -->
    /**
     * Reading-order guard: a paragraph's columns must stay contiguous in reading order.
     *
     * Two text bubbles that are stacked with no dividing border can be bridged by a single
     * [OcrMergeGeometry.sameLineFragment] edge — the upper bubble's leftmost column and the lower
     * bubble's next column are then x-aligned and only a small vertical gap apart, so the fragment
     * rule reads the gap as a detector-split line. The cluster is still two bubbles, and sorting it
     * right-to-left interleaves their columns:
     *
     * `bubble A col 1 | bubble B col 1 | bubble A col 2 | bubble B col 2 | …`
     *
     * The defect is invisible in the edge itself (each edge is legal when taken alone) and only
     * shows up in the finished cluster, so this runs once per cluster instead of per edge: lines
     * that overlap on the run axis form a *band* (one bubble's stack of columns), and if the bands
     * alternate along the reading order the cluster is more than one bubble. Splitting it restores
     * both the paragraph count and the order, because the row/reading-order stage then emits the
     * upper bubble's row before the lower one.
     *
     * A single column split by the detector into fragments is not affected: those bands each appear
     * once, in order, so the sequence stays contiguous (see the "detector-split fragment inside a
     * multi-column … paragraph" tests).
     */
    private fun splitInterleavedBands(lines: List<Line>, vertical: Boolean): List<List<Line>> {
        val n = lines.size
        if (n < 3) return listOf(lines)

        val parent = IntArray(n) { it }
        fun find(i: Int): Int {
            var root = i
            while (root != parent[root]) root = parent[root]
            var curr = i
            while (curr != root) {
                val next = parent[curr]
                parent[curr] = root
                curr = next
            }
            return root
        }

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val a = lines[i]
                val b = lines[j]
                if (a.vertical == b.vertical &&
                    OcrMergeGeometry.runOverlap(a.bbox, b.bbox, vertical) >= BAND_RUN_OVERLAP
                ) {
                    val rootI = find(i)
                    val rootJ = find(j)
                    if (rootI != rootJ) parent[rootI] = rootJ
                }
            }
        }

        val bandOf = IntArray(n) { find(it) }
        if (bandOf.distinct().size <= 1) return listOf(lines)

        val ordered = sortLinesInParagraph(lines, vertical, lines.first().rtl)
        fun bandOfLine(line: Line): Int = bandOf[lines.indexOfFirst { it === line }]

        val seen = HashSet<Int>()
        var previous = -1
        var contiguous = true
        for (line in ordered) {
            val band = bandOfLine(line)
            if (band == previous) continue
            if (!seen.add(band)) {
                contiguous = false
                break
            }
            previous = band
        }
        if (contiguous) return listOf(lines)

        val groups = LinkedHashMap<Int, MutableList<Line>>()
        for (line in ordered) {
            groups.getOrPut(bandOfLine(line)) { mutableListOf() }.add(line)
        }
        return groups.values.toList()
    }
    // KUN <--

    /**
     * Paragraph grouping used for Japanese.
     *
     * The pair must have the same orientation, a comparable thickness and compatible rotation,
     * and it must be a genuine "next line" along the text's advance axis ([OcrMergeGeometry.nextLine]).
     * Requiring overlap on the run axis and rejecting same-row boxes is what stops two separate
     * text patches whose boxes touch at a border from being glued into one paragraph.
     *
     * A line kept next to its main line by the (disabled) furigana filter is the one deliberate
     * exception: ruby is much thinner than the kanji it annotates.
     */
    private fun shouldGroupInSameParagraph(
        line1: Line,
        line2: Line,
        vertical: Boolean,
        cfg: MergeConfig,
    ): Boolean {
        val t1 = OcrMergeGeometry.thickness(line1.bbox, vertical)
        val t2 = OcrMergeGeometry.thickness(line2.bbox, vertical)
        val larger = maxOf(t1, t2)
        val thicknessRatio = if (larger > 0) minOf(t1, t2) / larger else 1.0

        var rotDiff = abs(line1.bbox.rotation - line2.bbox.rotation)
        while (rotDiff > PI) rotDiff -= PI
        if (rotDiff > PI / 2) rotDiff = PI - rotDiff
        val distToMultipleOfPiHalf = minOf(rotDiff, abs(PI / 2 - rotDiff))
        if (distToMultipleOfPiHalf >= 0.1) return false

        if (thicknessRatio < OcrMergeGeometry.MIN_THICKNESS_RATIO) {
            // Only when the user explicitly turned the furigana filter off: the kana is the
            // same run as the kanji line it sits beside, so keep it in the block.
            return !cfg.furiganaFilter && cfg.language.isJapanese &&
                line1.hasJpText && line2.hasJpText &&
                OcrMergeGeometry.runOverlap(line1.bbox, line2.bbox, vertical) > 0.8
        }

        // KUN -->
        return OcrMergeGeometry.nextLine(line1.bbox, line2.bbox, vertical, cfg.supportCenterAlignedText) ||
            OcrMergeGeometry.sameLineFragment(line1.bbox, line2.bbox, vertical)
        // KUN <--
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

    /**
     * Legacy-path version of the same rule. The old implementation only compared a signed edge
     * distance (`coord1 - coord2`) after a generous distance check, so any two boxes on the same
     * row could join no matter how far apart they were. Delegating to the shared geometry keeps
     * both paths honouring the same "successive line" definition.
     */
    private fun shouldGroupInSameParagraphLegacy(
        line1: Line,
        line2: Line,
        vertical: Boolean,
        cfg: MergeConfig,
    ): Boolean {
        if (line1.vertical != line2.vertical) return false

        val t1 = OcrMergeGeometry.thickness(line1.bbox, vertical)
        val t2 = OcrMergeGeometry.thickness(line2.bbox, vertical)
        val larger = maxOf(t1, t2)
        val thicknessRatio = if (larger > 0) minOf(t1, t2) / larger else 1.0
        if (thicknessRatio < OcrMergeGeometry.MIN_THICKNESS_RATIO) {
            val filtered = localFuriganaFilter(listOf(line1, line2), vertical)
            if (filtered.size == 1) {
                line1.isFurigana = true
                return true
            }
            return false
        }

        // KUN -->
        return OcrMergeGeometry.nextLine(line1.bbox, line2.bbox, vertical, cfg.supportCenterAlignedText) ||
            OcrMergeGeometry.sameLineFragment(line1.bbox, line2.bbox, vertical)
        // KUN <--
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

    // KUN -->
    private companion object {
        /**
         * Largest sub-line to annotated-line *character size* ratio that can still be furigana.
         *
         * Ruby glyphs are set smaller than the characters they annotate; measured 0.50 and 0.67 on
         * the furigana fixtures and 0.90-1.07 on the genuine columns of a real vertical-text page
         * (see [globalFuriganaFilter]).
         */
        const val FURIGANA_MAX_CHAR_SIZE_RATIO = 0.80

        /**
         * Run-axis overlap above which two lines belong to the same stacked column band.
         *
         * Half the smaller box: inside one bubble every column overlaps its neighbours along the run
         * axis by at least this much on a real vertical-text page (the two bubbles' stacks overlap
         * 0.92-0.95 of their shorter column), while the two stacked bubbles overlap 0.
         */
        const val BAND_RUN_OVERLAP = 0.5
    }
    // KUN <--

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

            // KUN -->
            val hasKanji = kanjiRegex.containsMatchIn(subText)
            val cpCount = subText.codePointCount(0, subText.length)
            if (hasKanji && cpCount > 2) continue
            // KUN <--
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
                // KUN -->
                val maxThicknessRatio = if (hasKanji) 0.55 else 0.75
                if (subThickness > mainThickness * maxThicknessRatio) continue

                // Ruby repeats a reading in smaller type, so the decisive signal is the *character*
                // size, not the box thickness (which the detector inflates by its 1.40 unclip ratio
                // and by any ruby column it swallows into the kanji box). Measured on a real
                // vertical-text page, against the kanji column each one sits beside: the three
                // genuine dialogue columns there read 1.07, 0.90 and 0.91 of the main character
                // size, while the furigana fixtures read 0.50 and 0.67, so 0.80 sits in the middle
                // of that gap.
                // A "ruby never fills the column height" rule was considered and rejected: it only
                // explains the first of those three (the other two are 0.30x and 0.33x of the main
                // column's height and would still be dropped), while character size separates all
                // three from real ruby.
                if (sub.characterSize > 0.0 && main.characterSize > 0.0 &&
                    sub.characterSize > main.characterSize * FURIGANA_MAX_CHAR_SIZE_RATIO
                ) {
                    continue
                }
                // KUN <--

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

    /**
     * Close-paragraph merging is deliberately narrow: it only rejoins **single-line** paragraphs
     * that the detector split into fragments of one line ([OcrMergeGeometry.sameLineFragment]).
     *
     * It no longer merges multi-line blocks that sit side by side. That was the over-aggressive
     * rule behind "two text patches whose boxes are joined at a border become one patch": a
     * horizontal distance within `2 * characterSize` plus vertical overlap merged any two nearby
     * blocks, including two touching columns/bubbles of unrelated text. Stacking lines inside a
     * paragraph is handled earlier, by [shouldGroupInSameParagraph].
     */
    private fun closeEnough(a: Paragraph, b: Paragraph, vertical: Boolean): Boolean {
        var rotDiff = abs(a.rotation - b.rotation)
        while (rotDiff > PI) rotDiff -= PI
        if (rotDiff > PI / 2) rotDiff = PI - rotDiff
        val distToMultipleOfPiHalf = minOf(rotDiff, abs(PI / 2 - rotDiff))
        if (distToMultipleOfPiHalf >= 0.1) return false

        if (a.vertical != b.vertical) return false
        if (!vertical && a.rtl != b.rtl) return false

        return a.lines.size == 1 && b.lines.size == 1 &&
            OcrMergeGeometry.sameLineFragment(a.bbox, b.bbox, vertical)
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
}
