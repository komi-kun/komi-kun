# 03 — OCR (PaddleOCR + `.mokuro`) implementation plan

Status: **plan** · Branch: `feat/introduce-ocr-and-yomitan` · Owner: this doc
Scope: the **OCR track** of `docs/design/yomitan-manga-ocr-port.md` (§6, §7.1–§7.2, phases 3–5).
Depends on: `01-architecture-build.md` (engine module + build flags) and `04-reader-integration.md`
(reader overlay + `OcrViewport` host) — interface needs are flagged inline with `⚠ IFACE-01` /
`⚠ IFACE-04`. `01` now exists: this plan **adopts its module/package layout** (`:core:ocr`,
`core/ocr/`, `mihon.core.ocr`, `di/OcrBindings.kt`, `overlay/OcrViewport.kt`, `merge/LineMerger.kt`)
and records two **conflicts** with it in §1.5 (licensing) and §2.5 (ABI/provenance).

**Fixed decisions (from the design + task):** PaddleOCR directly (Apache-2.0) + `.mokuro`; **drop**
cloud Google Lens and on-device Google Lens; OCR runs off the **page byte stream / cached file**, not
the view bitmap; overlay drawn in komi-kun's existing Compose overlay layer (`compose_overlay`), not by
subclassing SSIV; combined work is GPL-3.0 with `// KUN` markers and `i18n-kmk` strings.

**Reference revision.** `Chimahon/chimahon` @ `f85c063` ("chore: release v2.4.6",
2026-09-28), local clone at `/tmp/opencode/chimahon`. All `path:line` refs below are that revision.
Note: the installed Chimahon is **more integrated** than the design doc assumed — it already has
persistent OCR cache, `ReaderOcrSource`, a DB `ocr_ready` column, `.mokuro` download, and a parallel
OCR queue. Pin this revision in the port PR and re-diff if it moves.

---

## 0. One-paragraph summary

Vendor Chimahon's **engine-agnostic OCR layer** (`chimahon/ocr/**`, minus the Lens pieces) as-is into a
new GPL `:core:ocr` module; port the **Paddle engine** (`PaddleOcrEngine.kt` + a JNI shim we build) and
replace Chimahon's opaque GPL binary release with **our own reproducible, checksummed, Apache-2.0
artifact**; port the **app orchestration** (`OcrManager`/`OcrStore`/`OcrCacheManager`/`OcrJob`/
`ModelDownloader`) to **Metro**; expose OCR results to the dictionary popup through a thin
`ReaderOcrHost`/`OcrViewport` boundary so the reader files that must stay Apache-2.0 never import the
GPL module.

---

## 1. Component inventory — copy as-is vs adapt

### 1.1 Engine-agnostic — **copy as-is** (pure Kotlin, no Lens)

Source root `chimahon/src/main/java/chimahon/ocr/`. These are the reusable IR + algorithms.

| File | Key symbols (`path:line`) | Action |
|---|---|---|
| `OcrEngine.kt` | `OcrEngine` interface `:3-6` (`suspend recognize(bytes, language): List<EngineLine>`) | copy |
| `EngineLine.kt` | `NormalizedBBox` `:5-16`, `WritingDirection` `:18`, `EngineLine` `:24-32`, factory `:37-59` | copy |
| `OcrLanguage.kt` | `OcrLanguage` enum `:3-52`, `prefersVertical`/`prefersNoSpace` `:51-55` | copy |
| `OcrResult.kt` | `BoundingBox` `:3-9`, `OcrResult` `:11-17`, `RawChunk` `:19-26`, `MergeConfig` `:33-45` | copy |
| `OcrBlockData.kt` | `OcrLineGeometry`/`OcrBlockData`/`OcrPageData` (serializable cache DTO) `:6-42` | copy |
| `OcrPreprocessor.kt` | `OcrPreprocessor.clean` `:16-39` (punctuation collapse) | copy |
| `OcrBitmapDecoder.kt` | `decode` `:10-24` (uses `tachiyomi.decoder.ImageDecoder`) | copy |
| `ImageChunking.kt` | constants `:9-13`, `ImageChunk` `:15-22`, `splitImageIntoChunks` `:24-56`, `prepareForOcr` `:62/:95`, `resizeToMaxPixels` `:120`, `normalizeFromChunk` `:164`, `dedupeChunkBoundaryResults` `:179`, `processImageWithChunks` `:241/:251` | copy |
| `OcrHitTester.kt` | `findBlock` `:41-57`, `contains` `:59-74`, `hitCharacter` `:80-120`, `hitLines` `:123-146`, `isLineVertical` `:149-159` | copy |
| `OcrTextOverlayPainter.kt` | font-fit algorithms `drawHorizontal` `:41-78`, `drawVertical` `:80-124` | **adapt** — Android `Canvas`→Compose `DrawScope` (see §3.4) |
| `OcrLookupText.kt` | `isOcrLookupStartChar` `:6-20`, `extractOcrLookupText` `:23-31`, whole-word/scan-resolution helpers `:46-104`, `isOcrAllowedForLanguage` `:111-119`, `extractWholeWord` `:127-137` | copy |
| `OwOCRMerger.kt` | `object OwOCRMerger` `:19` (1261 LOC — line merge, furigana filter, reading order) | **port via seam** (§3.3) |
| `CropPresets.kt` | `CropPresets` `:3-26` | **defer** (Anki crop only; not OCR) |
| `Mokuro*.kt` | `MokuroModels` `:6-30`, `parseMokuro` `:10-14`, `resolveMokuroPage` `:9-35`, `convertMokuroBlocks` `:3-45`, `Mokuro` facade `:3-11` | copy as-is (§4) |
| `Lens*.kt` | `LensClient` `:1-417`, `LensProto`, `LensMerger` `:1-416`, `LensImageProcessor`, `LensConstants` | **DROP** |

### 1.2 Paddle engine — **adapt** (native + ABI)

`chimahon-local-ocr/src/main/java/chimahon/local/ocr/PaddleOcrEngine.kt`:

- `PaddleOcrOptions` `:25-31`, `PaddleOcrResult` `:33-46`.
- `PaddleOcrEngine` `:48-393`: `init` `:101-141` (loads libs by **absolute path** from app storage
  `:112-121`), `recognize` `:145-171` (bounded by a **companion-wide** semaphore + `lock`;
  `INFER_PERMITS` `:83-84`), `parseOutput` `:213-294` (parses `TIMING|`/`LINE|` protocol `:229-281`),
  `quadRotation` `:304-317`, `detectPerformanceCores` `:372-392`, JNI `nativeInit/nativeProcess/
  nativeRelease` `:347-365`.
- Model constants `:69-74` (`manga_det_fp16.{param,bin}`, `manga_rec_fp16.{param,bin}`,
  `ppocrv6_dict.txt`); ABI gate arm64-v8a/armeabi-v7a `:93-97`.
- `chimahon-local-ocr/build.gradle.kts` `:1-35` builds **only** `lens_asset_init` (`:8`) — i.e. the
  Paddle `.so`'s **source is not in the repo**; it is downloaded prebuilt (§2).

### 1.3 App orchestration — **adapt** (Injekt → Metro)

`app/src/main/java/eu/kanade/tachiyomi/data/ocr/`:

| File | Key symbols | Action |
|---|---|---|
| `OcrManager.kt` | class `:43-52`, `queueState` `:80-81`, `queueChapters` `:110-146`, `markChapterReadyForOcr` `:148`, `runPendingQueue` `:291-373`, `processTask` `:462-777`, per-page loop `:538-678`, page-bytes→OCR `:571-633`, retry pass `:680-751`, image providers `:794-919`, `OcrQueueItem` `:920-927`, `OcrQueueStatus` `:929-936` | port + Metro |
| `OcrStore.kt` | `OcrStore` `:11-153` (SharedPreferences JSON), `OcrTask` `:161-170`, status helpers `:172-185` | port + Metro |
| `OcrCacheManager.kt` | package `chimahon.ocr`(!) `:1`, `saveOcrBlocks` `:92`, `loadOcrBlocks` `:126`, `hasOcrData` `:157`, delete `:183/:210`, `getReadableSize` `:237`, `clear` `:253`, `ChapterLocation` `:272-273`, dir/CBZ/internal persistence `:298-493`, `OcrChapterData` `:555-557` | port + Metro |
| `OcrJob.kt` | `CoroutineWorker` `:23-81` (WorkManager, foreground) | port + Metro |
| `ModelDownloader.kt` | class `:22-25`, release base `:27-28`, `isPaddleDownloaded` `:41-44`, `requiredPaddleFiles` `:65-77`, `triggerPaddleDownload` `:89-97`, `importFromUri` `:168-177`, `extractZip` `:179-197` | **rewrite** (§2) |
| `OcrEngineSelector.kt` | `OcrEngineType` `:16-31`, `recognizePage` overloads `:33-181`, paddle branch `:72-101` | **simplify** to Paddle-only |
| `PaddleOcrBridge.kt` | reflection bridge `:11-60` | see §2.4 |
| `LocalOcrBridge.kt` | Lens bridge `:11-60` | **DROP** |

### 1.4 Reader overlay — **adapt** (SSIV subclass → Compose layer)

`app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/`:

- `OcrTextBlock.kt` `:1-241` — viewer model + `fullText`/`orderedFullText`/`displayText`
  `:47/:57/:66`, `orderedLineIndices` (reading order) `:123-140`, `uniformCharOffset` `:180-219`,
  `trimLineOverlap` `:227-240`. **Copy** — pure logic, unit-testable.
- `OcrCoordinateMapper.kt` `:19-353` — `mapToSplit` `:36`, `mapToMerged` `:88`, `mapToCropped` `:142`,
  `remapSeamAugmented` `:168`, `mapToSplitAndMerge` `:210`, `mapToRotated` `:263`,
  `mapWithCenterMargin` `:302`, `remapToCanvas` `:340`. **Copy** — these are the per-page
  normalized-space transforms; they are **viewer-agnostic** and slot under `OcrViewport` (§3.5).
- `OcrSubsamplingImageView.kt` `:49-545` — `extends SubsamplingScaleImageView`; `onDraw` `:101`,
  `drawOcrBlock` `:132`, `drawOcrTextBox` `:205`, `getLineRect` `:302`, `getLineSubstringRect` `:319`,
  `getMatchedWordRect` `:347`, `drawVerticalOcrText` `:399`. **DROP the SSIV subclass**; **reuse the
  drawing math** (`sourceToViewCoord` → replace with `OcrViewport`; vertical/horizontal layout +
  highlight rects are reusable).
- `OcrLookupPopup.kt` `:129` (`@Composable OcrLookupPopup`, 62 KB). **Do not port into OCR** — it is a
  dictionary UI concern (handled by the dictionary track); OCR supplies the request (§6).
- Callers: `ReaderPageImageView.kt` OCR state `:91-171` (`onShowOcrPopup` `:156`, `setOcrBlocks` `:769`,
  SSIV creation `:569`), `PagerPageHolder.kt` callbacks `:128-136` + `loadOcrWithTransform` `:729-745`,
  `WebtoonPageHolder.kt` (same shape).

### 1.5 ⚠ Conflict with `01-architecture-build.md` — module licence

`01` places OCR in **`:core:ocr` (`core/ocr/`, `mihon.core.ocr`) and marks it Apache-2.0 /
"GPL-free → upstream-contributable"** (`01` §1.1, §1.3). This plan's fixed decision is different:
the combined work is **GPL-3.0** and the task explicitly allows **copying `chimahon/ocr/**` as-is**
("licensing now permits reuse").

These cannot both hold:

- **`OwOCRMerger.kt`, `OcrTextOverlayPainter.kt`, `OcrHitTester.kt`, `OcrTextBlock` ordering,
  `ImageChunking`, `OcrLookupText`, `Mokuro*`** are **derived from GPL-3.0 Chimahon**. Copying any of
  them makes `:core:ocr` a **GPL-derived** module — it cannot be Apache-2.0, and files in it cannot be
  contributed back to Mihon/Komikku.
- Only **our own** additions (Metro bindings, the JNI shim over ONNX Runtime, the Compose overlay glue)
  are cleanly Apache-2.0.

**Resolution (recommend to `01`):** keep the module split and the **one-way dependency rule**
(`:core:ocr ∌ :core:dictionary`) and the path/package names, but **change `:core:ocr`'s licence to
GPL-3.0** (derived) and move the "upstream-contributable" claim to the **reader-side interface glue
only** (§7.1). Alternative if `01` insists on an Apache `:core:ocr`: clean-room reimplement the
Chimahon-derived files (merger especially, §3.3) — more work for no licensing benefit under the
adopted GPL decision. **Settle this before scaffolding**; if `01` keeps it Apache, every "copy as-is"
in §1.1 becomes a reimplementation.

---

## 2. Paddle `.so` + det/rec models — reproducible, permissive source/build/host

**Problem.** Chimahon downloads `https://github.com/Chimahon/chimahon-local-models/releases/download/
v2.5/paddle-ocr.zip` (`ModelDownloader.kt:27-31`) containing `libpaddle_ocr.so` (+ `libomp.so`,
`libc++_shared.so`) and `manga_*` models. The JNI `.so` **source is not in Chimahon's repo** (its CMake
only builds `lens_asset_init`, `build.gradle.kts:8`). So "copy their release" is not reproducible, and
hot-linking a third-party GPL release is fragile. We need **our own** artifact.

### 2.1 Decision

Ship a **`komi-kun/komi-kun-ocr-models`** release (or GitHub Packages) built by **our CI**, containing:

1. `libpaddle_ocr.so` — **we write** a small JNI shim (`ocr-jni/`) that reproduces
   `nativeInit`/`nativeProcess`/`nativeRelease` (`PaddleOcrEngine.kt:347-365`) against
   **ONNX Runtime** (MIT, loaded dynamically through its ABI-stable C API) + **PaddleOCR** deploy/lite
   post-processing (Apache-2.0), emitting the same `TIMING|`/`LINE|` text protocol (`:229-281`) so
   `PaddleOcrEngine.kt` stays near-unchanged.
2. `libonnxruntime.so`, `libomp.so` — ONNX Runtime's republished per-ABI shared library (MIT) and the
   Android NDK OpenMP runtime (`libomp.so`, Apache-2.0 WITH LLVM-exception; required by opencv-mobile's
   `parallel_for_`, not by ONNX Runtime). `libc++_shared.so` is **not** shipped: AGP already packages the
   NDK copy via `:core:dictionary`'s `-DANDROID_STL=c++_shared`.
3. Models: **official PaddlePaddle PP-OCRv6 `small`** ONNX exports (Apache-2.0) —
   `ppocrv6_small_det.onnx`, `ppocrv6_small_rec.onnx` (unified ja/zh/en CTC, `h=48`) and
   `ppocrv6_small_dict.txt`. The `manga_det_fp16`/`manga_rec_fp16` Paddle-Lite fine-tune is **no longer
   used**; see `ort-v6-migration.md`.

### 2.2 Alternatives considered

| Option | Verdict |
|---|---|
| Hot-link/re-host Chimahon's `v2.5` zip | ❌ not reproducible, opaque provenance, third-party hosting |
| Reuse `ocrtest` (Chimahon's reference project) | ❌ source not public |
| Official PaddleOCR Android demo `.so` | ⚠️ usable but API/output mismatch; still needs adaptation |
| **Build our JNI over ONNX Runtime + official models (chosen)** | ✅ reproducible, MIT/Apache-2.0, own hosting |

### 2.3 Concrete requirements

- **ABI:** `arm64-v8a`, `armeabi-v7a`, `x86_64` (matches the manifest and `PaddleOcrEngine`); ONNX
  Runtime publishes all three and the ONNX graphs are architecture-independent, so x86_64 is supported.
- **Hosting:** pinned release tag (e.g. `ocr-models-v3`); a `manifest.json` with per-file
  **SHA-256**, byte size, ABI, and SPDX id (`MIT` for ONNX Runtime, `Apache-2.0` for Paddle + our `.so`;
  NDK runtime libs under their terms). **`ModelDownloader` must verify checksums** (Chimahon does not —
  §7 R2).
- **Delivery:** **runtime download, user-opt-in** (Chimahon's model) + an **import-from-file** path
  (`ModelDownloader.importFromUri:168`, kept, but checksum-gated). Do **not** bundle in the APK
  (size + ABI splits); expose a "Download OCR models" CTA in `SettingsOcrScreen` and post-onboarding.
- **Size budget:** record actual sizes in the manifest; expect low-tens-of-MB per ABI `.so` + a few
  MB of ONNX models + small dict. Cap the model download UX accordingly.
- **Load path:** keep Chimahon's **absolute-path** `System.load` (`:112-121`) so the `.so` can live in
  app storage; keep the STL → OpenMP → ONNX Runtime → engine ordering (`:109-116`).
- **License paperwork:** ship `NOTICE`/`LICENSE` in the OSS-licences screen (PaddleOCR, ONNX Runtime,
  opencv-mobile, Clipper, NDK runtime, our JNI). The engine `.so` we build is Apache-2.0; combined APK
  is GPL-3.0.
- **CI:** build the `.so` with `cargo`-free CMake + NDK (AGP 9.4 default r28.2.x) in the models repo,
  publish on tag; the app build consumes the manifest, not the toolchain. ⚠ IFACE-01: `01` §5.4 keeps
  the `.so` in `core/ocr/src/main/jniLibs/<abi>/`; agree whether `:core:ocr` builds it or only
  consumes a fetched AAR/artifact (see §2.5).

### 2.4 Kotlin side in komi-kun

Keep `PaddleOcrEngine.kt` and its JNI signatures; **drop** `PaddleOcrBridge`'s reflection
(`PaddleOcrBridge.kt:24-31`) — with a compile-time module dependency we can call
`PaddleOcrEngine(context)` directly. Keep the `OcrEngine` interface (`OcrEngine.kt:3-6`) and the
`PaddleOcrOptions` defaults (`:25-31`).

### 2.5 ⚠ Reconciliation with `01` §5.4 / §5.5

- `01` §5.4 recommends **option A** (pinned, checksummed `libpaddle_ocr.so` in
  `core/ocr/src/main/jniLibs/<abi>/` fetched by a Gradle task; models at runtime) or **option B**
  (build + publish an AAR). This plan's §2.1 (own release + `manifest.json`) is **compatible with A**:
  use A now, keep B open. Whichever is chosen, the artifact must be **ours/checksummed**, not
  Chimahon's `v2.5` zip.
- **ABI mismatch:** `01` §1.2/§5.5 uses `arm64-v8a, x86_64` (engine-module parity), but Chimahon's
  Paddle build supports **`arm64-v8a`, `armeabi-v7a`** only (`PaddleOcrEngine.kt:93-97`). Decide:
  (a) extend our Paddle build to `x86_64` (emulator/CI convenience) or (b) align OCR to
  `arm64-v8a` + `armeabi-v7a` and let 32-bit/x86 gracefully degrade. **Recommend (a) for CI** plus
  keep `armeabi-v7a` for devices; record the final ABI set in the `manifest.json`.
- **Models:** `01` puts only the `.so` in `jniLibs`; models stay **runtime-downloaded** — matches §2.3.
  `01` §5.6 lists Paddle's `.so` in `keepDebugSymbols`; do the same for `libpaddle_ocr.so`.

---

## 3. OCR data model, merging, reading order, hit-testing, seams

### 3.1 Normalized boxes

Keep `NormalizedBBox` 0–1 (`EngineLine.kt:5-16`) and `EngineLine` (`:24-32`) as the **only** engine
output contract. All coordinates are axis-aligned normalized; `rotation` is folded to `[-π/4, π/4]`
with a `0.05 rad` deadband (`PaddleOcrEngine.quadRotation:304-317`). This decouples engine from
renderer/cache and lets `.mokuro` (pixels) share the same IR by dividing by `img_width/height`
(`MokuroConverter.kt:24-27`).

### 3.2 Cache DTO

Persist `OcrBlockData`/`OcrPageData`/`OcrLineGeometry` (`OcrBlockData.kt:6-42`) via kotlinx
serialization; mapper between DTO and the viewer's `OcrTextBlock`
(`OcrCacheManager.kt:495-521`). Keep `language` on the page/block (needed for scan-resolution and
language gating, §6).

### 3.3 Merging (`OwOCRMerger`) — port behind a seam

OwOCRMerger (`OwOCRMerger.kt:19`, 1261 LOC) does line grouping, furigana filter, paragraph joining,
center-aligned support, reading order. **Port it** (GPL accepted) but wrap it:

```kotlin
// KUN -->
interface OcrLineMerger { fun merge(lines: List<EngineLine>, imageW: Int, imageH: Int): List<OcrTextBlock> }
// KUN <--
```

Reasons: it is the highest-risk file, and the seam lets us unit-test against fixtures and swap later.
`MergeConfig` (`OcrResult.kt:33-45`) already parameterises it. Prefer feeding merger output straight to
the cache DTO to avoid Chimahon's double `OcrTextBlock` types (`OcrManager.kt:834-852` vs
`chimahon.ocr.OcrTextBlock` — unify to one).

### 3.4 Overlay painting

Chimahon paints with `Canvas` inside an SSIV subclass. We paint in the **Compose overlay**
(`compose_overlay`, `reader_activity.xml:27`, host `ReaderActivity.kt:309`). Reuse the font-fit
**algorithms** from `OcrTextOverlayPainter.kt:41-124` (binary-search font size, per-char layout,
highlight ranges) rewritten against `DrawScope`/`TextMeasurer`, plus the vertical-char rotation set
(`OcrSubsamplingImageView.kt:461-490`). ⚠ IFACE-04: the Compose overlay slot + `DrawScope` →
image-normalized transform must come from `04-reader-integration.md`.

### 3.5 Hit-testing + coordinate mapping (the `OcrViewport` boundary)

- Hit math is pure: `OcrHitTester.findBlock/contains/hitCharacter/hitLines/isLineVertical`
  (`OcrHitTester.kt:35-159`). Tap point and boxes must be in the **same normalized space**.
- `OcrCoordinateMapper` (`:19-353`) converts between page-relative normalized spaces (split/merged/
  rotated/cropped). Copy as-is.
- The **view↔image** transform is the only viewer-specific part. ⚠ IFACE-04: `04` owns
  `OcrViewport { fun imageNormalizedToScreen(block): RectF; fun screenToImageNormalized(pt): Offset }`,
  declared at `mihon.core.ocr` `overlay/OcrViewport.kt` (`01` §1.3); the **implementations** live in
  the viewer modules and are injected. Our plan: implement `OcrViewport` for (a) SSIV pager
  (`sourceToViewCoord`, as in `OcrSubsamplingImageView.kt:146-150`), (b) webtoon strip, (c) WebGPU
  paged/continuous via the fork's accessors (`WebGpuViewer.kt:958-970` today discards the normalized
  `onLongTap` offset — it must be **retained**; komi-kun `WebGpuViewer.kt:968`).
- Vertical detection heuristic: `isLineVertical` (`:149-159`) = block-vertical OR aspect > 1.2.
- Reading order: `OcrTextBlock.orderedLineIndices` (`OcrTextBlock.kt:123-140`) — vertical: sort by
  `centerX` **descending** then `ymin`; horizontal: `centerY` then `xmin`.

### 3.6 Unit-test seams (no Android)

| Seam | Source | What to test |
|---|---|---|
| Text cleaning | `OcrPreprocessor.clean` `:16-39` | punctuation collapse, katakana ellipsis |
| IR construction | `EngineLine(...)` `:37-59` | char size, `hasJpText`/`hasKanji` |
| Chunking | `splitImageIntoChunks`/`dedupeChunkBoundaryResults` `ImageChunking.kt:24/:179` | tall-page split, overlap dedupe |
| Merger | `OcrLineMerger` (§3.3) | fixtures per direction/furigana |
| Hit-test | `OcrHitTester` `:35-159` | contain/closest/char offset/rotation |
| Ordering/text | `OcrTextBlock` `:47-240` | reading order, whole-word extraction, overlap trim |
| Map | `OcrCoordinateMapper` `:19-353` | split/merge/rotate round-trips |
| Mokuro | `Mokuro*` | parse/resolve/convert, degenerate boxes |
| Gating | `isOcrAllowedForLanguage` `:111-119`, `shouldScanWholeWord` `:60-65` | language/profile matrix |

Chimahon keeps `app/src/test/kotlin/.../ReaderOcrSourceTest.kt` and `.../CameraOcrTest.kt` — port the
OCRed-away parts (ignore Lens-only cases).

---

## 4. `.mokuro` — parsing, resolution, rendering, discovery, download

- **Parse:** `parseMokuro` (`MokuroParser.kt:10-14`) with lenient JSON; models
  `MokuroVolume/MokuroPage/MokuroBlock` (`MokuroModels.kt:6-30`). Copy as-is.
- **Resolve page→block:** `resolveMokuroPage` (`MokuroPageResolver.kt:9-35`) matches by
  `img_path` suffix, then basename, then positional fallback.
- **Convert to IR:** `convertMokuroBlocks` → `OcrTextBlock` normalized
  (`MokuroConverter.kt:3-45`, polygon bbox at `:47-73`). Copy.
- **Discovery** (adapt, de-hack): Chimahon's reader looks inside the chapter folder, for a sibling
  `{base}.mokuro`, and inside the archive, stripping a `_[A-Za-z0-9]{6}$` hash
  (`ReaderViewModel.kt:2217` read, `:2275` base names, `:2280` exists-check). Keep the generic
  discovery; **extract the hash/`mokuro.moe` specifics into the extension**, not core (today it is a
  hardcoded `source.name == "Mokuro"` check at `MokuroSidecarCopier.kt:69` and
  `ReaderViewModel.kt:2070`, and a `mokuro.moe` URL builder — that is extension policy).
- **Sidecar download:** `MokuroSidecarCopier` (`:18-105`) runs after download
  (`Downloader.kt:465`) and `MokuroOcrInterceptor` (`core/common/.../MokuroOcrInterceptor.kt:19-73`)
  caches any `.mokuro` HTTP response by URL hash. Port the **generic** sidecar-copy hook (source/
  extension supplies the URL via a source capability) + the network cache; keep them Apache-clean if
  they stay upstream-facing (⚠ they are shared `core/common` — see §7 GPL boundary).
- **Rendering:** identical to engine OCR — `.mokuro` blocks feed the same `OcrTextBlock` overlay
  path; no engine call. `ReaderOcrSource.MOKURO` disables the persistent cache
  (`ReaderOcrSource.kt:16-20`) because the sidecar *is* the cache.

---

## 5. App orchestration (Metro-first)

### 5.1 Services → Metro

Port with `@Inject` constructors and `@Provides`/`@Binds` in `AppBindings.kt`
(`app/src/main/java/mihon/app/di/AppBindings.kt`), replacing Chimahon's Injekt registrations
(`AppModule.kt:335-341`). ⚠ IFACE-01: `01` §4/§1.3 puts the Metro bindings in
`mihon.core.ocr.di.OcrBindings` (a `@BindingContainer` registered on `AppGraph.kt`) and binds
`OcrEngine`; app-level services (`OcrManager`/`OcrStore`/`OcrCacheManager`/`OcrJob`/`ModelDownloader`)
go in `AppBindings.kt` (they need `Context`/`DownloadManager`). Confirm which side owns each binding
when `:core:ocr` is scaffolded.

| Service | Source | Notes |
|---|---|---|
| `OcrManager` | `OcrManager.kt:43` | drops Lens; keeps queue + `processTask` |
| `OcrStore` | `OcrStore.kt:11` | SharedPreferences JSON queue |
| `OcrCacheManager` | `OcrCacheManager.kt:37` | dir/CBZ/internal persistence |
| `OcrJob` | `OcrJob.kt:23` | WorkManager foreground worker |
| `ModelDownloader` | `ModelDownloader.kt:22` | rewritten (§2) |
| `PaddleOcrEngine` | §2.4 | bound as `OcrEngine` |

`OcrEngineSelector.recognizePage` (`OcrEngineSelector.kt:33/:108`) collapses to **Paddle-only**:
delete the CLOUD/LOCAL branches and `LocalOcrBridge`.

### 5.2 Input = page bytes, not the view

Chimahon already reads page bytes through `ChapterImageProvider` (`OcrManager.kt:854-919`,
`DirectoryImageProvider:858`, `CbzImageProvider:878`) and feeds `recognizePage(bytes, lang)`
(`:571-601`). Keep exactly this — it is the required decoupling from rendering (WebGPU exposes no
bitmap). Reader-side OCR likewise decodes page bytes (`ReaderViewModel.kt:2329 fetchOcrBlocks`,
`:1442 OcrBitmapDecoder.decode`). Do **not** introduce any `View.draw()`/bitmap-capture path.

### 5.3 Per-manga OCR source preference

Port `ReaderOcrSource` (`ReaderOcrSource.kt:5-53`) but **shrink** to `AUTOMATIC`, `MOKURO`,
`PADDLE` (drop `GOOGLE_LENS`, `LOCAL`). Keep `usesMokuro`/`usesPersistentCache`/
`persistentCacheVariant`. Per-manga key: `readerOcrSourcePreferenceKey(mangaId)`
(`ReaderPreferences.kt:370`, app-state scoped). Add prefs to komi-kun `ReaderPreferences.kt`:
`ocrOverlayEnabled` / `ocrSource(mangaId)` / `ocrOutlineVisible` / `ocrAutoOnDownload`
(`ReaderPreferences.kt:117-127`).

### 5.4 Chapter OCR actions + progress

- Actions: `ChapterDownloadAction.OCR` / `DELETE_OCR` (`ReaderViewModel.kt:269-270`), reader top-bar
  `OcrSourceAction` (`ReaderTopBar.kt:124-201`), chapter 3-dot menu, and the chapter-list badge from
  the new `ocr_ready` column.
- Progress: `OcrManager.queueState` (`:80-81`) → `OcrProgressHud` (`ReaderActivity.kt:525`) and the
  OCR queue screen; `OcrJob` foreground notification (`OcrJob.kt:27-41`). Parallelism from
  `dictionaryPreferences.parallelOcrLimit` (`OcrManager.kt:304`) → move to an OCR-owned preference.
- Download integration: `Downloader` calls `markChapterReadyForOcr`/`queueChapters`
  (`Downloader.kt:465-474`); port into komi-kun's `Downloader` with `// KUN` markers.

### 5.5 DB schema: `ocr_ready`

Mirror Chimahon: `ALTER TABLE chapters ADD COLUMN ocr_ready INTEGER NOT NULL DEFAULT 0`
(`data/src/main/sqldelight/tachiyomi/migrations/11.sqm:10-11`), plus `Chapter.isOcrReady`
(`Chapter.kt:22`) and `ChapterUpdate.isOcrReady` (`ChapterUpdate.kt:20`). **komi-kun's latest
migration is `53.sqm` → add `54.sqm`**, update `chapter.sq` + the `*RepositoryImpl` mapper, then run
`./gradlew :data:generateSqlDelightInterface`.

### 5.6 Settings + i18n

- `SettingsOcrScreen` (Chimahon `SettingsOcrScreen.kt:64`: engine, parallel limit, box scale/opacity,
  active text/bg opacity, outline, cache size/clear) — **trim** Lens/video/paddle-vs-cloud bits.
- Register in `SettingsMainScreen.kt` (entries around `:189-259`) and `SettingsSearchScreen.kt`.
- **All new strings → `i18n-kmk/src/commonMain/moko-resources/base/strings.xml` via `KMR`** (never
  `i18n/` `MR` or `i18n-sy/` `SYMR`; never non-base locales). Replace Chimahon's
  `MR.strings.pref_category_ocr` etc.

---

## 6. How OCR results feed the dictionary lookup popup

**Pipeline (per tap):**

1. `OcrViewport.screenToImageNormalized(tap)` (⚠ IFACE-04) → normalized point; retain the WebGPU
   `onLongTap` offset (`WebGpuViewer.kt:968-970`, currently `{ _ -> }`).
2. `OcrHitTester.findBlock` (`:41-57`) then `hitCharacter` (`:80-120`) → `(block, charOffset)`.
3. `OcrTextBlock.extractLookupString(global, wholeWord)` (`OcrTextBlock.kt:162-166`) with
   `wholeWord = shouldScanWholeWord(resolution, language)` (`OcrLookupText.kt:60-65`); the sentence
   context = `orderedDisplayText`/`displayText` (`:57/:66`); clamp via `lineBoundariesFor` (`:169`).
4. Build a request `{ query=lookupString, sentence, sentenceOffset, anchorRect, vertical, language }`
   and hand it to the **dictionary track** — this is the OCR↔dictionary seam.

**Dict-side interface needed** (owned by the dictionary plan; Chimahon shape for reference):
`DictionaryRepository.lookup(query, paths, languageCode): LookupResult2`
(`DictionaryRepository.kt:57`, result `:266-271` = `results/styles/mediaDataUris/error`;
`LookupResult{matched,deinflected,process,term,preprocessorSteps}`). OCR must **not** own
`OcrLookupPopup` (`OcrLookupPopup.kt:129`); the dictionary track renders it from the request.
Anchor rect comes from `OcrSubsamplingImageView.getMatchedWordRect` (`:347-393`) — port as a pure
rect computation under `OcrViewport`.

⚠ IFACE: agree a single DTO (e.g. `OcrLookupRequest(query, sentence, offset, anchor, vertical,
language)`) between this plan and the dictionary plan so the popup never imports OCR internals.

---

## 7. Maintainability, ownership, risks, ordered tasks

### 7.1 GPL boundary / file-ownership map

| Layer | Location | Licence intent | Rule |
|---|---|---|---|
| Engine-agnostic OCR (`chimahon/ocr` port) | **`:core:ocr`** (`core/ocr/`, `mihon.core.ocr`) | **GPL-3.0** (derived from Chimahon) — **see §1.5 conflict** | self-contained; never imported by upstream-facing files |
| Paddle engine + JNI | `:core:ocr` `paddle/` | GPL-3.0 Kotlin + **Apache-2.0** `.so`/libs we build | `.so` in `jniLibs/` or fetched AAR (`01` §5.4) |
| App orchestration | `:core:ocr` + `:app` bindings | GPL-3.0 (services) | Metro-bound (`OcrBindings` + `AppBindings`) |
| Reader overlay composables | `app/.../ui/reader/overlay/…` | GPL-3.0 | behind `ReaderOcrHost` / `OcrViewport` |
| `ReaderActivity`/`ReaderViewModel`/`ReaderPreferences`/`Downloader` | existing upstream files | **stay Apache-2.0** | only tiny `// KUN` hunks importing an **Apache `ReaderOcrHost` interface**; no GPL imports |
| `MokuroOcrInterceptor` (`core/common`) | shared upstream-ish | **Apache-2.0** | must not depend on the GPL module; keep generic |
| DB migration `54.sqm`, `Chapter`, `chapter.sq` | `data/` | Apache-2.0 (schema only) | no GPL imports |
| Strings | `i18n-kmk` | — | `KMR` only |

Define the Apache `ReaderOcrHost` interface (in `app`, no GPL imports) and implement it in `:core:ocr`;
`ReaderActivity`/`ReaderViewModel` touch only that interface — this is what keeps the reader file
contributable upstream. **Settled:** `:core:ocr` is **GPL-3.0-or-later** (`00` §1.1/§1.3, §6.1), so
the GPL-derived OCR files stay there; the former “if `01` keeps it Apache” branch is closed.

### 7.2 Risks / unknowns

- **R1 — Paddle model provenance (resolved).** Only the **official** PaddleOCR PP-OCRv6 `small`
  models (Apache-2.0, §2.1) ship; the `manga_det_fp16`/`manga_rec_fp16` Paddle-Lite fine-tune was
  never adopted.
- **R2 — Download integrity.** Chimahon's `ModelDownloader` does no checksum (`ModelDownloader.kt:145-166`).
  Add SHA-256 verification + `manifest.json`.
- **R3 — Merger port.** `OwOCRMerger` (1261 LOC) is the riskiest correctness surface → seam + fixtures.
- **R4 — WebGPU coordinates.** Hit-testing depends on fork accessors (design §7.3); `04` scope.
- **R5 — Overlay perf.** Redrawing boxes + text in Compose at frame rate; cache layouts, reuse
  `TextMeasurer`, cap active-block redraw (Chimahon caches layouts: `ReaderPageImageView.kt:150`).
- **R6 — Memory.** Full-page bitmaps/chunks on low-RAM devices; keep `resizeToMaxPixels` +
  chunking + `runtime.recycle()` (`PaddleOcrEngine.kt:166`, `:208-210`).
- **R7 — DB migration.** `54.sqm` must be appended and the interface regenerated; verify upgrade path.
- **R8 — Reference drift.** Chimahon may move; pin `f85c063` and re-diff before merge.

### 7.3 Ordered task breakdown

0. **Settle the `:core:ocr` licence** with `01` (§1.5) and the Paddle ABI/artifact option (§2.5) —
   both block scaffolding.
1. **Scaffold** `:core:ocr` (`core/ocr/`, `mihon.core.ocr`, `01` §1.3); copy engine-agnostic IR +
   `OcrTextBlock`; drop Lens. Add `di/OcrBindings.kt` (`01` §4). **Merger seam** `OcrLineMerger`
   (`merge/LineMerger.kt`) + port `OwOCRMerger` + fixtures (T-§3.6).
2. **OCR artifact**: write the JNI shim over ONNX Runtime, build `.so` per ABI, export the PP-OCRv6
   small ONNX models, publish `komi-kun-ocr-models` + `manifest.json`; rewrite `ModelDownloader` with
   checksums.
3. **Port `PaddleOcrEngine`** + Metro binding of `OcrEngine`; smoke-test on a downloaded chapter.
4. **DB** `54.sqm` + `Chapter`/`ChapterUpdate`/`chapter.sq` + mapper; regenerate SQLDelight.
5. **App services** `OcrStore`/`OcrCacheManager`/`OcrManager`/`OcrJob` → Metro.
6. **`.mokuro`** parse/resolve/convert + generic sidecar discovery + download hook.
7. **Reader source pref + actions + progress** (`ReaderOcrSource` trimmed, `ReaderPreferences`,
   top-bar action, chapter menu, `Downloader` hooks, `OcrProgressHud`).
8. **Overlay + `OcrViewport` (pager)** in the Compose layer (⚠ IFACE-04); wire hit-test → request.
9. **`ReaderOcrHost` interface** + `ReaderActivity`/`ReaderViewModel` `// KUN` hunks.
10. **Dictionary seam** (§6 DTO) with the dictionary track; popup owned by that track.
11. **Settings + i18n** (`SettingsOcrScreen` trimmed, `KMR`, register in main/search screens).
12. **Webtoon + WebGPU viewports** (fork accessors), then vertical/continuous.
13. **Tests** for §3.6 seams; `spotlessApply`/`spotlessCheck`/`assembleDebug`.
