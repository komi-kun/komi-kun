# 04 — Reader integration: `OcrViewport`, Compose overlay, WebGPU fork

Status: **implementation plan (planning only)** · Branch: `feat/introduce-ocr-and-yomitan` · Owner: reader-integration workstream.
Reads from: `docs/design/yomitan-manga-ocr-port.md` §7 (§7.1–§7.3), `01-architecture-build.md`, `03-ocr.md`, `AGENTS.md`.
Consumes: the Apache `ReaderOcrHost`/`OcrViewport` boundary defined by `01` §6.1 and owned here per `03` §3.5/§6.
No code is written by this doc; every path is a plan.

**Fixed decisions reflected here:** the overlay lives in komi-kun's existing Compose overlay
(`reader_activity.xml` `compose_overlay` above `viewer_container`) for **all four** backends; a
viewer-agnostic `OcrViewport` with one adapter per backend; long-press routes through
`ReaderActivity.onPageLongTap` with the touch point retained; **fork `komi-kun/webgpuviewer` first**,
upstream a minimal accessor PR.

---

## 0. Backends in scope (verified)

| # | Backend | View stack | Tap/long-press owner | Image source for mapping |
|---|---|---|---|---|
| 1 | Pager SSIV | `PagerViewer` → `Pager` (`DirectionalViewPager`) → `PagerPageHolder` → `ReaderPageImageView` → `SubsamplingScaleImageView` | `Pager.tapListener`/`longTapListener` (`PagerViewer.kt:121/138`) | SSIV `sourceToViewCoord` (merged/cropped bitmap may differ from the page) |
| 2 | Webtoon | `WebtoonViewer` → `WebtoonRecyclerView` → `WebtoonPageHolder` → `ReaderPageImageView(isWebtoon=true)` → `WebtoonStripImageView` (or `WebtoonSubsamplingImageView` escape hatch) | `recycler.tapListener`/`longTapListener` (`WebtoonViewer.kt:133/148`) | strip view scale (`WebtoonStripImageView.kt:87`) + scroll position |
| 3 | WebGPU paged | `WebGpuViewer` → `pager: ImageView` (`AbstractComposeView`) → `ImageViewer(state)` | Compose `ImageViewer`'s `pointerInput` → `state.onTap`/`onLongTap` (`WebGpuViewer.kt:958/968`) | `state.getPage(0)` + fork accessor |
| 4 | WebGPU continuous | `WebGpuViewerContinuous` → `pager: ImageViewContinuous` (`ImageViewerContinuousState`) | same as #3 | `state.visiblePages` + scroll offset (fork accessor) |

`getView()` returns `pager` for all WebGPU viewers (`WebGpuViewer.kt:1086`); `pager.state` is public
(`webgpuviewer/ImageView.kt:26`).

---

## 1. `OcrViewport` — the viewer-agnostic boundary

### 1.1 Where it lives (⚠ IFACE — a real conflict to resolve)

`01` §6.1 places `OcrViewport` in `:core:ocr` (`mihon.core.ocr.overlay`, **Apache**) but `03` builds
its OCR track as a **GPL `:ocr`** module (`tachiyomi.ocr.*`, `03` §7.1). The interface itself is **pure
geometry with no GPL code** and must be importable by reader files that stay Apache (`03` §7.1:
"reader files … touch only that interface"). **Recommendation:** put the interface (and the value
types below) in the **Apache** module — either `:core:ocr`'s `overlay/` (01) or a tiny
`domain/src/main/java/tachiyomi/domain/ocr/` (03's own file-ownership table) — and keep only the
**adapters + drawing** GPL where the ported Chimahon code actually lands. **Decision needed from the
maintainer; this doc assumes the interface is GPL-free and the adapters are app-side.**

### 1.2 Exact interface + value types

```kotlin
package mihon.core.ocr.overlay           // Apache; package per 01 §1.3

import android.graphics.PointF
import android.graphics.RectF
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage   // adapters live in :app

/**
 * Maps a page's image-normalized space (0..1, origin top-left, *before* any reader transform)
 * to/from the Compose overlay's screen space (px in compose_overlay's coordinate system, which
 * fills the reader root above viewer_container).
 *
 * One adapter per backend, created lazily by the viewer and recreated when it is (re)loaded.
 * Every method is main-thread only (reads View/Compose transform state).
 */
interface OcrViewport {
    /** Pages currently drawn by this viewer, with their per-page display transform. */
    fun visiblePages(): List<ReaderPage>

    /** Forward map: image-normalized rect -> overlay px, or null if [page] is not shown. */
    fun imageNormalizedToScreen(page: ReaderPage, rect: RectF): RectF?

    /** Inverse map: overlay px -> page + image-normalized point, or null if no page is under it. */
    fun screenToImageNormalized(x: Float, y: Float): ImagePoint?

    /**
     * Monotonic token that changes when the transform moves (pan/zoom/scroll/page turn).
     * The overlay samples it once per frame to decide redraws cheaply.
     */
    fun transformVersion(): Long
}

/** A hit under an overlay-space point. */
data class ImagePoint(val page: ReaderPage, val image: PointF)   // image is normalized 0..1

/**
 * How a page's *source image* was transformed before display. Drives the page-normalized ->
 * displayed-bitmap-normalized step, reusing `OcrCoordinateMapper` (03 §1.4). Identity for a raw
 * page that the viewer shows unmodified (WebGPU paged/continuous: the fort's placement IS the map).
 */
sealed interface OcrDisplayTransform {
    data object None : OcrDisplayTransform
    data class Crop(val cropRect: RectF) : OcrDisplayTransform
    data class Split(val keepLeft: Boolean) : OcrDisplayTransform
    data class SplitAndMerge(val upperIsRight: Boolean) : OcrDisplayTransform
    data class Merge(
        val partner: ReaderPage, val partnerW: Int, val partnerH: Int,
        val isLTR: Boolean, val centerMarginPx: Int,
    ) : OcrDisplayTransform
}
```

**Why this shape (vs `03`'s sketch):** `03` §3.5/§6 sketched `imageNormalizedToScreen(block)` and
`screenToImageNormalized(pt)`, which cannot express a **dual spread** (two pages on screen) or the
per-page crop/split/merge transform. Adding `page` + `OcrDisplayTransform` covers pager dual-page,
webtoon `splitAndMerge` and WebGPU spreads without leaking viewer types into the interface. The
`transformVersion()` token replaces naive frame polling for redraw decisions.

### 1.3 Shared adapter helpers (GPL-free candidates)

- `OcrCoordinateMapper` (ported per `03` §1.4, in the OCR module) provides the **pure** transforms
  `mapToSplit`/`mapToMerged`/`mapToSplitAndMerge`/`mapToCropped`/`mapToRotated`. The adapters call it
  to apply `OcrDisplayTransform`; keep it behind the same module boundary as `03` decides.
- `SsivScreen` tiny helper: converts a point in `SubsamplingScaleImageView` view coords to
  `compose_overlay` coords by adding the SSIV view's location relative to `binding.composeOverlay`
  (`getLocationInWindow` of both, difference). Shared by adapters 1 and 2.

### 1.4 Adapter 1 — pager SSIV

`app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/SsivOcrViewport.kt` (`// KUN`).

- Owned by `PagerViewer`; rebuilt in `onPageSelected` (`PagerViewer.kt:252`) so it always binds the
  current holder.
- `visiblePages()` → the `PagerPageHolder`'s first/second `ReaderPage` from
  `adapter.joinedItems.getOrNull(pager.currentItem)` (same lookup as `PagerViewer.kt:140-142`).
- `imageNormalizedToScreen(page, rect)`:
  1. apply `OcrDisplayTransform` (from the holder) to `rect` → **displayed-bitmap-normalized**;
  2. `ssiv.sourceToViewCoord(nx * sWidth, ny * sHeight)` for the two opposite corners;
  3. `SsivScreen.toOverlay(...)`; return the axis-aligned bbox.
- `screenToImageNormalized(x, y)` → `ssiv.viewToSourceCoord(overlayToSsivPoint)`, normalize by
  `sWidth`/`sHeight`, invert the transform.
- `transformVersion()` → `SsivScreen.viewVersion(ssiv)`: a counter bumped from
  `setOnStateChangedListener.onScaleChanged`/`onCenterChanged` (already wired at
  `ReaderPageImageView.kt:303-313`) plus the holder's page identity.
- **Transform plumbing:** `PagerPageHolder` must expose
  `fun ocrDisplayTransform(): OcrDisplayTransform` describing whether it split a wide page
  (`viewer.splitDoublePages`), merged two pages (`ImageUtil.mergeBitmaps`,
  `PagerPageHolder.kt:336`), or cropped (`config.cropBorders`, `:220`). This is the same information
  `03` §1.4 attributes to `PagerPageHolder.loadOcrWithTransform` (`:729-745`) — reuse that path.
- Edge: `dualPageRotateToFit` rotates the bitmap by ±90 before SSIV; handle by mapping corners and
  returning the bbox (documented limitation for the label rect; the box is still correct because
  SSIV's transform carries the rotation).

### 1.5 Adapter 2 — webtoon

`app/.../ui/reader/viewer/webtoon/WebtoonOcrViewport.kt` (`// KUN`). Owned by `WebtoonViewer`,
rebuilt on viewport change (`WebtoonViewer.kt:327`).

- `visiblePages()` → the `ReaderPage`s whose holder views intersect the recycler viewport
  (`recycler.findFirstVisibleItemPosition()`..`findLastVisibleItemPosition()`, `layoutManager`;
  `WebtoonLayoutManager`).
- Strip path (`WebtoonStripImageView`): the view is drawn at `width = recycler.width - 2*sidePadding`
  and `height = width * imageHeight / imageWidth` (`WebtoonStripImageView.kt:77-80`, `:87`). So
  `screen = stripView.positionInOverlay + normalized * view.width/height`; `OcrDisplayTransform` is
  `None` (or `Crop`, if `config.cropBorders`).
- Escape-hatch path (`WebtoonSubsamplingImageView`, KMK `alwaysDecodeLongStripWithSSIV` at
  `ReaderPageImageView.kt:66-70`, `:294`): same math as adapter 1.
- `transformVersion()` → strip view's on-screen offset (recomputed on scroll) + view identity.

### 1.6 Adapter 3 — WebGPU paged

`app/.../ui/reader/viewer/webgpu/WebGpuOcrViewport.kt` (`// KUN`). Owned by `WebGpuViewer`; uses
`pager.state` (an `ImageViewerState`).

- `visiblePages()` → `state.visiblePages` (fork accessor, §3) mapped to `ReaderPage` via
  `viewerPageFor(imagePage)` (exists, `WebGpuViewer.kt:184`).
- `imageNormalizedToScreen(page, rect)`:
  - `state.getPage(0)` (or the matching visible `ImagePage`); `val r = state.rectOf(page)` →
    `[x1,y1,x2,y2]` normalized to the surface (fork accessor, §3);
  - `screenPx = (r[0] + rect.left*(r[2]-r[0])) * surfaceW`, etc. `OcrDisplayTransform = None`
    (a raw page; `Image.placement` already folds `trim` + `WebGpuRenderer.offsetX/Y`).
- `screenToImageNormalized(x, y)`: invert per page (find the visible page whose rect contains the
  normalized point, then `u = (x/surfaceW - r[0])/(r[2]-r[0])`).
- `transformVersion()` → `state.transformVersion` (fork accessor) **or** a frame-sampled cache keyed
  on `(page.x, page.y, page.scale, state.pageOffset)` if we choose not to add the token.
- Long-press point: `state.onLongTap` already delivers `(x/state.width, y/state.height)`
  (`ImageViewer.kt:130-135`) — multiply back by `surfaceW/H` for overlay px.

### 1.7 Adapter 4 — WebGPU continuous

`app/.../ui/reader/viewer/webgpu/WebGpuOcrViewport.kt` (same file, `isContinuous` branch). **Phase 2
of this doc (after paged).** Needs one more fork accessor: the vertical scroll offset in the same
space `getPageSlotHeight` (`ImageViewerContinuousState.kt:208`) uses, so a page's document-space slot
maps to viewport px. `state.visiblePages` already returns every on-screen page.

- `visiblePages()` → `state.visiblePages` → `readerPageFor` (`WebGpuViewerContinuous.kt:66`).
- `imageNormalizedToScreen` → page's document slot top (`Σ getPageSlotHeight`) − `scrollOffsetY`,
  then linear within `getPageHeight` (`:145`).
- Ordered after paged because it needs the extra `scrollOffsetY` accessor and multi-page math; the
  interface does not change.

---

## 2. Exact hook points (`path:line`)

### 2.1 Overlay mount

| What | Where | Edit |
|---|---|---|
| Overlay host | `app/src/main/res/layout/reader_activity.xml:26-29` | none (already above `viewer_container`, `:10-14`) |
| Compose overlay | `ReaderActivity.kt:227` (`binding.setComposeOverlay()`) | none |
| Compose content | `ReaderActivity.kt:309-511` (`setComposeOverlay`) | insert `OcrOverlay(...)` into the `Box` at `:330`, beside `ContentOverlay` (`:343`) / `AppBars` (`:345`) |
| Popup host | `ReaderActivity.kt:361-509` (`when (state.dialog)`) | add OCR/dictionary popup + selection panel; keep the popup **non-modal** — it adds pointer input only over its own bounds and the reader observes outside taps passively |

The `compose_overlay` `ComposeView` is a *non-clickable* sibling above `viewer_container`; touches
pass through to the viewer unless a composable adds `pointerInput`. **The dictionary popup adds
pointer input only over its own bounds** — box outlines are draw-only (`Canvas`), and the reader
observes outside taps for dismissal with a passive, non-consuming detector (§4.5).

### 2.2 Tap / long-press capture

| Backend | Where | Change |
|---|---|---|
| Pager | `PagerViewer.kt:121-137` (`tapListener`), `:138-149` (`longTapListener`) | keep navigation on tap; but first try `activity.onPageSingleTap(firstPage, secondPage, point)` — on a hit the tap is consumed, on a miss navigation runs as before. In `longTapListener`, convert `MotionEvent` → overlay px and call `activity.onPageLongTap(firstPage, secondPage, point)` |
| Webtoon | `WebtoonViewer.kt:133-147` (`tapListener`), `:148-161` (`longTapListener`) | same as pager (`:155`) |
| WebGPU paged | `WebGpuViewer.kt:968-972` (`onLongTap = { _ -> … }`) | **retain the offset**: `onLongTap = { offset -> … onPageLongTap(it.page, point = offset.toOverlayPx()) }`; `onTap` first tries `onPageSingleTap` |
| WebGPU continuous | inherits `WebGpuViewer` `onLongTap`/`onTap` | same |
| Signature | `ReaderActivity.kt:1165` | `fun onPageLongTap(page: ReaderPage, extraPage: ReaderPage? = null, point: PointF? = null)` (append arg; `// KUN`). Keep the existing `openPageDialog` fallback at `:1166`. |

`onPageLongTap` becomes the **single long-press entry point**: if OCR is enabled and `point` hits a
block → dictionary lookup (§4); otherwise → `viewModel.openPageDialog(page, extraPage)` (unchanged
behavior). Its single-tap sibling `onPageSingleTap` returns `true` when the tap hit a block (so the
viewer consumes it) and `false` otherwise, and routes through the *same* hit-test + lookup path in
`ReaderOcrHostImpl`.

### 2.3 Reader menu / actions

| What | Where | Edit |
|---|---|---|
| Top/bottom bar | `ReaderActivity.kt:635` (`AppBars`), call at `:665-754` | pass `ocrEnabled`, `onToggleOcr`, `onOpenOcrSource`; wire to new `ReaderOcrHost` methods |
| Bar composable | `eu.kanade.presentation.reader.appbars.ReaderAppBars` (`ReaderAppBars.kt:46-99`) | add params + an OCR icon button (next to crop/rotate) |
| Bottom button enum | `setting/ReaderBottomButton.kt:7-18` | add `Ocr("ocr", KMR.strings.pref_category_ocr)`; add to defaults at `:23-29` |
| Per-manga source | `setting/ReaderPreferences.kt` | add `ocrOverlayEnabled`, `ocrOutlineVisible`, `ocrSource(mangaId)` (per `03` §5.3) |
| Page actions sheet | `ReaderActivity.kt:415-442`, `ReaderViewModel.kt:1334` | optional "OCR this page" entry when a source page is un-OCRed (reuse `Dialog.PageActions`) |

### 2.4 ReaderViewModel / state

| What | Where | Edit |
|---|---|---|
| State | `ReaderViewModel.kt:1658-1696` | add OCR-relevant state **or** keep it in a dedicated `ReaderOcrViewModel` (recommended, `AGENTS.md` §8) and read both in `setComposeOverlay` |
| Dialog | `ReaderViewModel.kt:1698-1720` | add `Dialog.OcrSourceSelect` if the source is user-picked |
| Events | `ReaderViewModel.kt:1722-1737` | add an event only if orchestration (not the popup) owns progress |
| Viewer access | `ReaderViewModel.kt:1670` (`State.viewer`) | `OcrViewport` is obtained via a new `Viewer.ocrViewport(): OcrViewport?` (add to `viewer/Viewer.kt`) |

### 2.5 Viewer base

Add to `viewer/Viewer.kt` (interface, `// KUN`):

```kotlin
/** Viewer-specific image<->screen mapping for the OCR overlay; null when not ready. */
fun ocrViewport(): OcrViewport? = null
```

Implement in `PagerViewer`, `WebtoonViewer`, `WebGpuViewer`. `ReaderActivity` reads it from
`viewModel.state.value.viewer` when a long-press or an overlay redraw needs mapping.

---

## 3. `komi-kun/webgpuviewer` fork patch

### 3.1 What the app needs (nothing more)

1. **`ImageViewerState.visiblePages: List<ImagePage>`** — the current `onScreenPages` is
   `protected` (`ImageViewerState.kt:221`).
2. **A `GPUTexture`-free rect** for a page, normalized to the surface — today only
   `pageRect(dst: GPUTexture)` exists and its base returns `null` (`ImagePage.kt:1331`); the real
   geometry lives in `Images.pageRect` (`:705` → `Image.placement(dst,…)`) and
   `ImageSpread.leafRect`/`wholeRect` (`:1079`/`:1110`). `Image.placement` uses **only**
   `dst.width`/`dst.height` (`renderer/Image.kt:568-576`), so the texture is incidental.
3. **(continuous only)** `ImageViewerContinuousState` scroll offset for viewport mapping.

### 3.2 Minimal API (additive, non-behavioural)

Make the surface **size** the primitive and keep the texture overloads delegating:

```diff
 // renderer/Image.kt
-fun placement(dst: GPUTexture, x: Float, y: Float, scale: Float): FloatArray {
+fun placement(surfaceWidth: Int, surfaceHeight: Int, x: Float, y: Float, scale: Float): FloatArray {
     val adjustedX = x + this.x / surfaceWidth + WebGpuRenderer.offsetX
     val adjustedY = y + this.y / surfaceHeight + WebGpuRenderer.offsetY
     ...
 }
+fun placement(dst: GPUTexture, x: Float, y: Float, scale: Float): FloatArray =
+    placement(dst.width, dst.height, x, y, scale)

 // viewer/ImagePage.kt  (base)
-open fun pageRect(dst: GPUTexture): FloatArray? = null
+open fun pageRect(surfaceWidth: Int, surfaceHeight: Int): FloatArray? = null
+open fun pageRect(dst: GPUTexture): FloatArray? = pageRect(dst.width, dst.height)

 // Images / ImageSpread: change the override to the Int overload; keep the GPUTexture one delegating.
+override fun wholeRect(surfaceWidth: Int, surfaceHeight: Int): FloatArray? = ...
+override fun leafRect(surfaceWidth: Int, surfaceHeight: Int, left: Boolean): FloatArray? = ...

 // viewer/ImageViewerState.kt
+/** Pages drawn on the last frame, in draw order. */
+val visiblePages: List<ImagePage> get() = onScreenPages

+/** A page's rect in viewport-normalized (x1,y1,x2,y2) coordinates, or null if not drawable. */
+fun rectOf(page: ImagePage): FloatArray? = page.wholeRect(width, height)

+/** Bumped on pan/zoom/scroll/page change; cheap redraw signal for app overlays. */
+val transformVersion: Long get() = /* ++ on invalidate()/pageOffset/scale/x/y writes */
```

Diff size: ~1 helper in `Image`, ~4 overload delegations in `ImagePage`, 3 small members in
`ImageViewerState`, plus a continuous scroll getter. **No draw path changes** → trivially upstreamable.

### 3.3 Why it is upstreamable

- **Additive and documented** — no signature removed, no existing caller changes behavior; the
  texture→size refactor proves the texture was never read beyond `.width/.height`.
- **Generally useful** — any app embedding the viewer that needs an overlay, accessibility hit-test,
  share-crop, or annotation wants exactly these accessors.
- **Small PR surface** — one file for `Image.placement`, one for `ImagePage`, one for
  `ImageViewerState` (+ continuous).

### 3.4 Consuming the fork

Current pin: `gradle/libs.versions.toml:19` `webgpuviewer = "52"`, library at `:78-79`,
used at `app/build.gradle.kts:336` (`implementation(libs.webgpuviewer)`, note the Compose-1.12
comment at `:221`).

| Option | How | When |
|---|---|---|
| **JitPack** (bring-up) | `settings.gradle.kts` → `dependencyResolutionManagement.repositories` add `maven("https://jitpack.io")`; catalog `webgpuviewer = { module = "com.github.komi-kun.webgpuviewer:library", version = "<commit-or-tag>" }` | fastest; commit-pinned is reproducible; avoid `master-SNAPSHOT` |
| **GitHub Packages AAR** (stable CI) | publish `ca.mpreg:webgpuviewer` under our coordinates to `https://maven.pkg.github.com/komi-kun/webgpuviewer`; add the repo + `GITHUB_ACTOR`/`GITHUB_TOKEN` (read) in CI | before this lands on `master`, if CI flakiness/cold JitPack builds hurt |

Keep the swap **isolated to one catalog line**; no app code names the fork.

### 3.5 Swap back after upstream merges

1. Upstream the §3.2 PR; wait for a release containing it.
2. Change only `libs.versions.toml:19` back to the upstream version; remove the `jitpack.io` repo line
   if it was added solely for this.
3. Nothing else changes because every consumer goes through `OcrViewport`
   (`WebGpuOcrViewport` is the only fork-API consumer).

---

## 4. State / flow: OCR tap → dictionary popup

### 4.1 Sequence

```
long-press (any backend)
  └─ viewer maps MotionEvent / WebGPU normalized offset → overlay px
  └─ ReaderActivity.onPageLongTap(page, extraPage, point)          [ReaderActivity.kt:1165]
       ├─ ocr overlay enabled && point hits a block?
       │    └─ viewport.screenToImageNormalized(point) → ImagePoint(page, image)
       │         └─ OcrHitTester.findBlock + hitCharacter             (03 §3.5)
       │              └─ OcrTextBlock.extractLookupString(...)        (03 §6)
       │                   └─ DictionaryHost.lookup(OcrLookupRequest) ─→ popup visible
       └─ else → viewModel.openPageDialog(page, extraPage)           [ReaderViewModel.kt:1334]

single tap (any backend, OCR overlay on)
  └─ viewer tap callback (tapListener / state.onTap)
  └─ ReaderActivity.onPageSingleTap(page, extraPage, point): Boolean
       ├─ true  (point hits a block) → same lookup path as above; viewer consumes the tap
       └─ false (miss / overlay off) → viewer's normal navigation runs unchanged
```

`OcrLookupRequest(query, sentence, sentenceOffset, anchorRect, vertical, language)` is the DTO both
this doc and the dictionary track (from `03` §6) must agree on; `anchorRect` is an overlay-px rect
from `OcrViewport.imageNormalizedToScreen`.

### 4.2 Where OCR blocks live

- **Source:** per `03` §1.4/§5.2, run OCR off the **page byte stream / `.mokuro`**, never the view.
  The reader asks `ReaderOcrHost.blocksFor(page)` (Apache interface, `03` §7.1) and receives
  `List<OcrTextBlock>` (the model shared with `03`).
- **Cache:** keyed by `(chapterId, pageIndex)`; a small `LinkedHashMap` + in-flight `Deferred` map
  mirroring Chimahon's `ReaderViewModel` (`03` §1.4) but in a dedicated `ReaderOcrViewModel`.
- **Exposure:** `StateFlow<Map<PageKey, List<OcrTextBlock>>>` (or a per-current-page flow). The
  Compose overlay `collectAsState()`s it; no View reads in the data path.

### 4.3 Overlay composable

`eu.kanade.presentation.reader.OcrOverlay` (`// KUN`), inserted at `ReaderActivity.kt:330`:

```kotlin
@Composable
fun OcrOverlay(
    viewport: OcrViewport?,          // null when the viewer has no mapping yet
    blocks: List<OcrTextBlock>,      // for the current page(s)
    activeBlock: OcrTextBlock?,      // highlighted
    outlineVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    // Draw-only: Canvas rectangles (+ optional reused TextMeasurer). No pointerInput here.
}
```

- **No text is drawn** in v1 (outline boxes only) — cuts the font-fit/vertical-layout work
  (`03` §3.4, `OcrTextOverlayPainter`) to a later polish pass and keeps this overlay Apache-clean.
- **Redraw driver:** `LaunchedEffect(viewport) { while (isActive) { withFrameNanos {}; } }` bumping a
  state read by the `Canvas`, but **only while the overlay is visible**; map only the visible pages'
  blocks. (Chimahon redraws inside SSIV `onDraw`; we cannot, so gate cost explicitly.)
- **Popup:** dictionary-owned `OcrLookupPopup`-equivalent rendered at `ReaderActivity.kt:361-509`
  when `popupVisible`. It is **non-modal**: there is **no scrim**, so the page keeps
  scrolling/zooming/paging underneath while the popup tracks its OCR block live (the popup's anchor
  is refreshed every frame). It is dismissed by an outside tap (a passive, non-consuming
  `OutsideTapDismissDetector` fed from `ReaderActivity.dispatchTouchEvent`), Back (`BackHandler`,
  first), a page change, or when the anchored block leaves the screen. A single tap on a *different*
  block moves the popup to that block (the lookup path above) rather than dismissing and reopening,
  and the dismiss detector skips such a tap so the popup does not flicker.

### 4.4 Threading & lifecycle

- **OCR work:** `Dispatchers.IO` via the OCR services; never on the main thread (`03` §5.2).
- **Mapping/drawing:** main thread only (reads SSIV/Compose transform state).
- **Cancellation:** cancel in-flight OCR/all:page work with the ViewModel scope; clear the page
  cache on chapter change (`setChapters`, `ReaderActivity.kt:1045`), viewer destroy
  (`ReaderActivity.kt:516-522`) and reading-mode change (`updateViewer`, `:932`).
- **Viewer swap:** `updateViewer` destroys the old viewer and removes its views (`:952-958`);
  recreate the `OcrViewport` from the new viewer (`viewModel.onViewerLoaded(newViewer)`, `:956`).
- **Backgrounding:** popup + overlay state survive `onPause` (`:524`); no OCR work is tied to
  window focus.

### 4.5 Gesture-conflict avoidance

| Gesture | Owner | Rule |
|---|---|---|
| Page turn / drag / pinch | viewer (SSIV / ViewPager / recycler / Compose `pointerInput`) | overlay adds **no** pointer input except over the popup's own bounds → never intercepts; outside-tap dismissal is observed, not consumed |
| Single tap | viewer navigation (`tapListener`, `state.onTap`) | normal navigation, **except** while the OCR overlay is on and the tap hits a block: then it opens/moves the dictionary popup and the tap is consumed. A miss falls through unchanged |
| Long-press | viewer → `onPageLongTap` | OCR hook; long-press on empty space falls back to page actions |
| Zoom/pan | SSIV / WebGPU page transform | box mapping reads the transform each frame via `transformVersion()`; no gesture filtering needed |
| Back | overlay popup first, then activity | `BackHandler(enabled = popupVisible)` |

Single-tap lookup is gated purely by the existing `ReaderPreferences.ocrOverlayEnabled` flag (it is on
exactly while the boxes are drawn, i.e. while the two-finger gesture has it on) — no new preference.
Because it is wired into the *existing* single-tap owner (the viewer's own tap callback, which already
distinguishes tap / drag / double-tap / long-press), no second raw-touch consumer is added and the
tap is only consumed on an actual hit. The passive `OutsideTapDismissDetector` is unchanged in what
it sees; it only learns the tap point so a tap on **another block** does not dismiss-then-reopen the
popup, and the single-tap lookup moves the popup straight to that block (fast word-by-word scanning).

---

## 5. Maintainability, ownership, risks, ordered tasks

### 5.1 File-ownership map

| File | Class | Owner | Marker |
|---|---|---|---|
| `app/.../ui/reader/viewer/OcrViewport.kt` (+ value types) | interface (Apache) | this doc / `01`+`03` reconcile | `// KUN` |
| `viewer/pager/SsivOcrViewport.kt` | adapter | this doc | `// KUN` |
| `viewer/webtoon/WebtoonOcrViewport.kt` | adapter | this doc | `// KUN` |
| `viewer/webgpu/WebGpuOcrViewport.kt` | adapter | this doc | `// KUN` |
| `presentation/reader/OcrOverlay.kt` | Compose overlay | this doc | `// KUN` |
| `viewer/Viewer.kt` | `ocrViewport()` | this doc | `// KUN` |
| `ReaderActivity.kt`, `ReaderViewModel.kt`, `ReaderPreferences.kt`, `ReaderBottomButton.kt`, `ReaderAppBars.kt`, `PagerViewer.kt`, `WebtoonViewer.kt`, `WebGpuViewer.kt`, `WebGpuViewerContinuous.kt`, `ReaderPageImageView.kt`, `PagerPageHolder.kt`, `WebtoonPageHolder.kt` | existing upstream files | this doc | tiny `// KUN` hunks + `ReaderOcrHost` interface only |
| `komi-kun/webgpuviewer` | fork | this doc | upstream library, no KUN markers |
| OCR model / `ReaderOcrHost` / `OcrCoordinateMapper` | OCR module | `03` | per `03` §7.1 |

### 5.2 Maintainability

1. **One entry point.** All backends funnel into `ReaderActivity.onPageLongTap`; adapters only ever
   convert coordinates. No viewer-specific OCR logic in the activity.
2. **Interface-first.** App code depends on `OcrViewport` + `ReaderOcrHost`, never on the fork API
   except inside `WebGpuOcrViewport`; swapping the fork (§3.5) touches one catalog line.
3. **Feature-flag** the whole overlay behind `ReaderPreferences.ocrOverlayEnabled`; when off,
   `Viewer.ocrViewport()` may return `null` and `onPageLongTap` behaves exactly as today.
4. **No text rendering in v1** keeps the overlay small and the font-fit port optional (`03` §3.4).
5. **Tests without Android** for the pure math: `OcrCoordinateMapper` round-trips, `OcrViewport`
   adapters' map/invert (inject fake dimensions), and a `transformVersion` token test.
6. **Update `AGENTS.md`** (reader hooks, fork dependency, module ownership) in the same PR.

### 5.3 Risks / unknowns

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| R1 | `OcrViewport` module placement conflict (`01` Apache `:core:ocr` vs `03` GPL `:ocr`) | High | decide before coding; keep the interface GPL-free (§1.1) |
| R2 | WebGPU fork drift / upstream not merged in time | Med | JitPack commit pin; `OcrViewport` isolates the API; minimal PR (§3.3) |
| R3 | Continuous-mode scroll offset accessor | Med | defer to phase 2; §1.7 |
| R4 | SSIV merged/split/cropped transform correctness | Med | reuse `OcrCoordinateMapper` + `PagerPageHolder.loadOcrWithTransform` (`03` §1.4) |
| R5 | Overlay redraw cost at frame rate | Med | `transformVersion()` gate + draw only visible pages' blocks; outline-only v1 |
| R6 | Webtoon strip view recycled/reloaded mid-display (`WebtoonStripImageView.kt:54-68`) | Low | rebuild the adapter on holder rebind; drop stale mappings |
| R7 | `dualPageRotateToFit` rotation vs bbox label rects | Low | corner-map + bbox; document the label-rect limitation |

### 5.4 Ordered task breakdown

| # | Task | Depends on | Output |
|---|---|---|---|
| 1 | Resolve `OcrViewport` module placement (§1.1) with the `01`/`03` owners | — | one interface location |
| 2 | Add `OcrViewport` + `OcrDisplayTransform` + `ImagePoint`; `Viewer.ocrViewport()` stub | 1 | interface compiles |
| 3 | Fork `webgpuviewer`; implement §3.2; branch `feat/ocr-transform-accessors`; unit test geometry | — | fork commit/tag |
| 4 | Consume the fork (JitPack pin) in the catalog; `WebGpuOcrViewport` (paged) | 3 | webgpu mapping |
| 5 | `SsivOcrViewport` + `PagerPageHolder.ocrDisplayTransform()` | 2 | pager mapping |
| 6 | `WebtoonOcrViewport` (+ strip escape hatch) | 2 | webtoon mapping |
| 7 | `OcrOverlay` composable (outlines, frame-driven redraw) mounted at `ReaderActivity.kt:330` | 2 | boxes render |
| 8 | Retain long-press point: `onPageLongTap(..., point)`; wire pager/webtoon/webgpu | 4,5,6 | hit → point |
| 9 | `ReaderOcrHost` integration + `ReaderOcrViewModel` (blocks by page, cache, in-flight) | `03` §5–6 | blocks available |
| 10 | Dictionary seam: `OcrLookupRequest` → popup in the Compose overlay; `BackHandler` | `03` §6, dict track | tap → lookup |
| 11 | Reader menu: `ReaderBottomButton.Ocr`, `ReaderAppBars` params, `ReaderPreferences` prefs | 2 | user toggle |
| 12 | `WebGpuOcrViewport` continuous (fork `scrollOffsetY`) | 4 | continuous mapping |
| 13 | Tests (§5.2) + `spotlessApply`/`spotlessCheck`/`assembleDebug` | all | green |

---

## 6. Cross-doc interface needs (⚠)

**To `03-ocr.md`:**
- ⚠ **IFACE-04a** — confirm `OcrViewport`'s package/module (§1.1). `04` owns the interface; `03` §3.5
  delegates it here.
- ⚠ **IFACE-04b** — the shared DTO `OcrLookupRequest(query, sentence, sentenceOffset, anchorRect,
  vertical, language)` and the `OcrTextBlock` model type both tracks import (no duplication).
- ⚠ **IFACE-04c** — `ReaderOcrHost.blocksFor(page): List<OcrTextBlock>` must be callable from the
  reader without importing GPL code, and must key off page bytes/`.mokuro`, not a `Bitmap` (`03` §5.2).

**To `01-architecture-build.md`:**
- ⚠ **IFACE-01a** — §1.1 conflict: `01` §6.1 lists `OcrViewport` under Apache `:core:ocr`, `03`
  builds a GPL `:ocr`. `04` needs the interface GPL-free on the reader side.
- ⚠ **IFACE-01b** — `01` §5.1 already notes "fork pin handled by the reader workstream" for
  `webgpuviewer`; this doc supplies the pin mechanics (§3.4) and the JitPack/GitHub-Packages repo
  entry that `settings.gradle.kts` needs.
- ⚠ **IFACE-01c** — if the interface lives in `:core:ocr`, `:app` must already depend on
  `projects.core.ocr` (`01` §5.6); if it lives in `:domain`, add the `tachiyomi.domain.ocr` package
  to `01` §6.3.
