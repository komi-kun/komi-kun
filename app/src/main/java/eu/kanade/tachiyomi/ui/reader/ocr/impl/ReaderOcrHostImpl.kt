// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// GPL implementation of the Apache `eu.kanade.tachiyomi.ui.reader.ocr.ReaderOcrHost` seam
// (contract §1.2/§2.3). It lives in the distinct `.impl` package so `verifyGplFree` can separate the
// Apache seam files from the GPL implementation. The reader only ever sees the Apache interface.
//
// `onPageLongPress` maps the overlay tap to a page-normalized point through the current
// [OcrViewport], hit-tests the page's OCR blocks (`OcrHitTester`) and hands a
// [DictionaryLookupRequest] to the GPL `ReaderDictionaryHost`. `Overlay` delegates the drawing to
// the GPL `OcrOverlay` (W4), which composes no pointer input of its own.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.ocr.impl

import android.content.Context
import android.graphics.RectF
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.presentation.reader.OcrOverlay
import eu.kanade.presentation.reader.OcrOverlayPage
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPopupSizing
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.ocr.ImagePoint
import eu.kanade.tachiyomi.ui.reader.ocr.OcrViewport
import eu.kanade.tachiyomi.ui.reader.ocr.ReaderOcrHost
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.popup.PopupSide
import eu.kanade.tachiyomi.ui.reader.viewer.popup.popupSide
import mihon.core.ocr.OcrHitTester
import mihon.core.ocr.OcrNormalizedBounds
import mihon.core.ocr.model.OcrTextBlock
import mihon.core.ocr.model.uniformCharOffset
import mihon.core.ocr.shouldScanWholeWord
import mihon.dictionary.host.DictionaryLookupRequest
import mihon.dictionary.host.ReaderDictionaryHost

/**
 * GPL implementation of [ReaderOcrHost] (contract §3.6b: `@Inject` + `@ContributesBinding`, never
 * wired through `AppBindings.kt` or `OcrHostBindings`).
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ReaderOcrHostImpl(
    private val blockLoader: ReaderOcrBlockLoader,
    private val readerPreferences: ReaderPreferences,
    private val dictionaryPreferences: DictionaryPreferences,
    private val dictionaryHost: ReaderDictionaryHost,
    private val popupAnchor: ReaderOcrPopupAnchor,
    private val context: Context,
) : ReaderOcrHost {

    /** The viewport the overlay was last composed with; used to map the long-press point. */
    @Volatile
    private var viewport: OcrViewport? = null

    /** The overlay's last measured size, so a lookup knows how much room the popup will take. */
    @Volatile
    private var overlaySize = IntSize.Zero

    // KUN -->
    /**
     * Uptime until which an off-screen block rect must **not** dismiss the popup. A just-opened
     * popup schedules its own "keep the block visible" move (and the popup composes) over a few
     * frames; a rect sampled mid-move can momentarily read as off-screen, and dismissing on that
     * frame was what closed the popup right after it opened. After the grace, the real guard (the
     * block left the overlay / scrolled away) applies as before.
     */
    private var popupGraceUntil = 0L
    // KUN <--

    /** The block highlighted by the last successful hit, for the overlay. */
    private val activeBlock = mutableStateOf<Pair<OcrPageKey, OcrTextBlock>?>(null)

    @Composable
    override fun Overlay(viewport: OcrViewport?, modifier: Modifier) {
        // Recording the viewport is a side effect, not composition: keep it out of the compose
        // phase so recomposition stays pure (the hit-test only reads it after a long-press).
        SideEffect { this.viewport = viewport }
        val loaded by blockLoader.blocks.collectAsState()
        val active by activeBlock
        val outlineVisible = readerPreferences.ocrOutlineVisible.get()
        val showText = readerPreferences.ocrTextOverlayVisible.get()
        val activeBlockHighlight = readerPreferences.ocrActiveBlockHighlight.get()
        val boxScalePercent = readerPreferences.ocrBoxScale.get()
        val textScalePercent = readerPreferences.ocrTextScale.get()

        // The compose overlay's size, captured from layout. Used to dismiss the popup once its block
        // has been scrolled fully off-screen - a page can still be partially visible in a continuous
        // viewer while the block is not.
        val measuredOverlaySize = remember { mutableStateOf(IntSize.Zero) }

        // `OcrViewport.visiblePages()` is viewer-owned state, not a Compose snapshot state, so the
        // page the viewer is showing (including the very first page after mount) can appear without
        // recomposing this composable. Poll it once per frame into snapshot state, so the overlay
        // updates as soon as the viewer reports its pages - no manual off/on toggle. `produceState`
        // uses structural equality, so a stable page set does not recompose. The same loop primes
        // the block cache: the visible pages **and their neighbours**, so the incoming page's
        // blocks are already resolved while it is still sliding in rather than only once settled.
        val visiblePages by produceState(initialValue = emptyList<ReaderPage>(), viewport) {
            if (viewport == null) return@produceState
            while (true) {
                val pages = viewport.visiblePages()
                value = pages
                blockLoader.ensureLoaded(pages.withNeighbours())
                updatePopupAnchor(viewport, pages, measuredOverlaySize.value)
                // KUN --> A popup closed from outside the OCR host (outside tap, system back) must
                // still undo the "keep the block visible" move. Our own off-screen dismissal above
                // has already cleared the active block, so this only fires for external closes.
                if (activeBlock.value != null && !dictionaryHost.isOpen()) {
                    viewport.restoreFit()
                    activeBlock.value = null
                    popupAnchor.clear()
                }
                // KUN <--
                withFrameNanos { }
            }
        }

        val currentPage = visiblePages.firstOrNull()
        // Drawn every visible page, not just the current one, so a page turn shows the incoming
        // page's boxes/text as it slides in.
        val overlayPages = visiblePages.map { page ->
            OcrOverlayPage(page, loaded[page.ocrKey()].orEmpty())
        }
        OcrOverlay(
            viewport = viewport,
            pages = overlayPages,
            activeBlock = active?.takeIf { activeBlockHighlight && it.first == currentPage?.ocrKey() }?.second,
            outlineVisible = outlineVisible,
            showText = showText,
            boxScalePercent = boxScalePercent,
            textScalePercent = textScalePercent,
            modifier = modifier.onGloballyPositioned {
                measuredOverlaySize.value = it.size
                overlaySize = it.size
            },
        )
    }

    /**
     * The visible pages plus their immediate chapter neighbours, so the block cache is primed for
     * the page a turn is about to reveal. [ReaderOcrBlockLoader.ensureLoaded] deduplicates by page
     * key and in-flight job, so preloading never repeats work.
     */
    private fun List<ReaderPage>.withNeighbours(): List<ReaderPage> {
        if (isEmpty()) return this
        val result = LinkedHashSet(this)
        for (page in this) {
            val chapterPages = page.chapter.pages ?: continue
            chapterPages.getOrNull(page.index - 1)?.let(result::add)
            chapterPages.getOrNull(page.index + 1)?.let(result::add)
        }
        return result.toList()
    }

    // KUN -->
    // Long-press and single-tap share the exact same hit-test + lookup path; only the gesture that
    // reaches it differs (plan 04 §2.2/§4.5).
    override fun onPageLongPress(page: ReaderPage, extraPage: ReaderPage?, overlayPoint: Offset): Boolean =
        lookupAt(overlayPoint)

    override fun onPageSingleTap(page: ReaderPage, extraPage: ReaderPage?, overlayPoint: Offset): Boolean =
        lookupAt(overlayPoint)

    override fun hitsBlock(overlayPoint: Offset): Boolean = findBlockAt(overlayPoint) != null
    // KUN <--

    private fun lookupAt(overlayPoint: Offset): Boolean {
        val currentViewport = viewport ?: return false
        val hit = currentViewport.screenToImageNormalized(overlayPoint.x, overlayPoint.y) ?: return false

        val key = hit.page.ocrKey()
        val block = findBlock(hit) ?: return false
        val screen = currentViewport.imageNormalizedToScreen(hit.page, block.toRectF()) ?: return false

        val offset = uniformCharOffset(
            block = block,
            localX = overlayPoint.x - screen.left,
            localY = overlayPoint.y - screen.top,
            screenW = screen.width(),
            screenH = screen.height(),
        )

        val profile = dictionaryPreferences.profileStore.getActiveProfile()
        val language = block.language.ifBlank { profile.languageCode.ifBlank { DEFAULT_LANGUAGE } }
        val wholeWord = shouldScanWholeWord(profile.scanResolution, language)
        val query = block.extractLookupString(offset, wholeWord)
        if (query.isBlank()) return false

        activeBlock.value = key to block
        popupAnchor.rect.value = screen
        // KUN --> A new lookup must survive its own placement: ignore off-screen anchor samples
        // while the popup settles (see popupGraceUntil). Refreshed per lookup.
        popupGraceUntil = SystemClock.uptimeMillis() + POPUP_GRACE_MS
        // KUN <--
        dictionaryHost.showLookup(
            DictionaryLookupRequest(
                query = query,
                sentence = block.fullText,
                sentenceOffset = offset,
                anchorRect = screen,
                vertical = block.vertical,
                language = language,
            ),
        )
        // KUN --> Keep the looked-up block visible above the popup: paged backends zoom and pan,
        // continuous ones scroll the minimum. A comfortable block is left untouched.
        //
        // Only the "popup below the block" case needs the page moved (and only a block that already
        // reaches the lower band does). A block low on the screen leaves no room below, so the popup
        // goes **above** it instead - and the block is already where it needs to be, so no zoom or
        // scroll happens. That is exactly the bottom-edge case that used to zoom to a degenerate
        // target and dismiss itself.
        val fraction = popupLowerFraction()
        val overlayHeight = overlaySize.height.toFloat()
        if (overlayHeight > 0f &&
            popupSide(
                screen.top,
                screen.bottom,
                overlayHeight,
                overlayHeight * fraction,
            ) == PopupSide.Below
        ) {
            if (currentViewport.fitBlockAbove(screen, fraction)) {
                popupGraceUntil = SystemClock.uptimeMillis() + POPUP_GRACE_MS
            }
        }
        // KUN <--
        return true
    }

    // KUN -->
    /**
     * The fraction of the viewport height the dictionary popup will cover, from the same sizing
     * (screen-relative baseline scaled by the size preference) `DictionaryPopup` uses.
     */
    private fun popupLowerFraction(): Float {
        val overlayHeightPx = overlaySize.height
        if (overlayHeightPx <= 0) return 0f
        val popupHeightPx = dictionaryPopupHeightPx()
        if (popupHeightPx <= 0f) return 0f
        // Relative to the overlay the fit itself measures in, so the band the block is moved to
        // matches the region the popup actually occupies.
        return (popupHeightPx / overlayHeightPx).coerceIn(0f, 1f)
    }

    /** The dictionary popup's height in px, from the same sizing `DictionaryPopup` uses. */
    private fun dictionaryPopupHeightPx(): Float {
        val config = context.resources.configuration
        val size = DictionaryPopupSizing.resolve(
            screenWidthDp = config.screenWidthDp,
            screenHeightDp = config.screenHeightDp,
            percent = dictionaryPreferences.popupSizePercent().get(),
            overrideWidthDp = dictionaryPreferences.popupWidth().get(),
            overrideHeightDp = dictionaryPreferences.popupHeight().get(),
        )
        return size.heightDp * context.resources.displayMetrics.density
    }
    // KUN <--

    // KUN -->
    /** The OCR block under [overlayPoint], or null when the viewport has none there. */
    private fun findBlockAt(overlayPoint: Offset): OcrTextBlock? {
        val currentViewport = viewport ?: return null
        val hit = currentViewport.screenToImageNormalized(overlayPoint.x, overlayPoint.y) ?: return null
        return findBlock(hit)
    }

    private fun findBlock(hit: ImagePoint): OcrTextBlock? {
        val pageBlocks = blockLoader.blocksFor(hit.page)
        if (pageBlocks.isEmpty()) return null
        return OcrHitTester.findBlock(pageBlocks, hit.image.x, hit.image.y) { it.toBounds() }
    }
    // KUN <--

    /**
     * Re-maps the active lookup's block to the current viewport every frame so the popup follows
     * page turns, zoom and scroll instead of staying at the position it opened at.
     *
     * When the block's page is no longer on screen - or the block itself has been scrolled fully
     * off the overlay - the lookup is dismissed (the popup must never be left stuck over a different
     * page or pinned to an edge for an off-screen block).
     */
    private fun updatePopupAnchor(viewport: OcrViewport, pages: List<ReaderPage>, overlaySize: IntSize) {
        val active = activeBlock.value ?: return
        val page = pages.firstOrNull { it.ocrKey() == active.first }
        val rect = page?.let { viewport.imageNormalizedToScreen(it, active.second.toRectF()) }
        if (rect != null && rect.isOnScreen(overlaySize)) {
            popupAnchor.rect.value = rect
        } else if (rect == null || SystemClock.uptimeMillis() >= popupGraceUntil) {
            dictionaryHost.dismiss()
            // KUN --> Undo the "keep the block visible" fit; the adapter keeps the user's own
            // position when they moved the view manually.
            viewport.restoreFit()
            // KUN <--
            activeBlock.value = null
            popupAnchor.clear()
        }
        // KUN --> The `else` (no branch taken) is the settle grace: a momentarily off-screen rect is
        // ignored so the popup we just placed cannot dismiss itself mid-move (see popupGraceUntil).
        // KUN <--
    }

    /** True while any part of [rect] is inside the overlay. A not-yet-measured overlay keeps it. */
    private fun RectF.isOnScreen(overlaySize: IntSize): Boolean {
        if (overlaySize.width <= 0 || overlaySize.height <= 0) return true
        return right > 0f && bottom > 0f &&
            left < overlaySize.width && top < overlaySize.height
    }

    private fun OcrTextBlock.toRectF(): RectF = RectF(xmin, ymin, xmax, ymax)

    private fun OcrTextBlock.toBounds(): OcrNormalizedBounds = OcrNormalizedBounds(
        left = xmin,
        top = ymin,
        right = xmax,
        bottom = ymax,
    )

    private companion object {
        private const val DEFAULT_LANGUAGE = "ja"

        // KUN --> Long enough to cover the paged fit animation (220 ms) plus a few frames of the
        // popup settling, short enough that a genuinely scrolled-away block still dismisses promptly.
        private const val POPUP_GRACE_MS = 600L
        // KUN <--
    }
}
