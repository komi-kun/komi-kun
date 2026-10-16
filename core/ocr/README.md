# `:core:ocr`

Engine-agnostic OCR pipeline for komi-kun: the normalized OCR IR, the line merger/
reading-order algorithm, `.mokuro` sidecar support and the PaddleOCR engine bridge.

- **Namespace:** `mihon.core.ocr` · **License:** GPL-3.0-or-later
- **Hard rule:** must never depend on `:core:dictionary` (they are independent GPL modules).
- **Seam:** the `OcrLineMerger` interface (`merge/OcrLineMerger.kt`) isolates the highest-risk
  step (paragraph grouping / furigana filtering / reading order) from the rest of the pipeline.

## Provenance

Most of this module is ported from [Chimahon](https://github.com/Chimahon/Chimahon)
(GPL-3.0-or-later), reference revision **`f85c063`** ("chore: release v2.4.6"). Every ported file
carries an SPDX header plus a `// KUN -->` provenance block naming the upstream file. Lens-specific
pieces (`LensClient`/`LensProto`/`LensMerger`/`LensImageProcessor`/`LensConstants`, `LocalOcrBridge`)
are intentionally **not** ported.

`merge/OwOCRMerger.kt` is a clean reimplementation of Chimahon's 1261-LOC `OwOCRMerger.kt` behind
the seam, keeping the same staged heuristics (`03-ocr.md` §3.3). `model/OcrTextBlock.kt` unifies
Chimahon's two parallel block types into one.

## Layout

| Path | Contents |
|---|---|
| `EngineLine.kt` | `NormalizedBBox` (0–1, radians), `WritingDirection`, `EngineLine` + factory |
| `OcrEngine.kt` / `OcrLanguage.kt` / `OcrResult.kt` | engine seam, language enum, paragraph + merge config |
| `OcrBlockData.kt` | serializable cache DTOs (`OcrLineGeometry`/`OcrBlockData`/`OcrPageData`) + mappers |
| `OcrPreprocessor.kt` / `OcrLookupText.kt` / `OcrHitTester.kt` / `CropPresets.kt` | pure helpers |
| `ImageChunking.kt` / `OcrBitmapDecoder.kt` / `OcrTextOverlayPainter.kt` | Android-side pipeline/font-fit |
| `model/OcrTextBlock.kt` | unified viewer block (reading order, display text, lookup extraction) |
| `merge/` | `OcrLineMerger` seam + `OwOCRMerger` implementation |
| `mokuro/` | `.mokuro` models, lenient parser, page resolver, converter, facade |
| `paddle/PaddleOcrEngine.kt` | Paddle OCR JNI bridge/loader |
| `paddle/ModelDownloader.kt` | checksum-verified, opt-in runtime download/import of the det/rec models + dict |
| `overlay/OcrCoordinateMapper.kt` | normalized-space block remapping for reader transforms (split/merge/crop/rotate) |
| `cache/OcrStore.kt` | persisted OCR queue (`SharedPreferences` JSON) |
| `cache/OcrCacheManager.kt` | per-page/per-chapter OCR result persistence (dir/CBZ sidecar/internal JSON) |
| `cache/OcrManager.kt` | source policy + engine orchestration off page bytes, queue driving |
| `cache/OcrJob.kt` | background queue runner (plain coroutine job; app wraps it for WorkManager) |
| `OcrPreferences.kt` | OCR source/overlay/queue preferences (`PreferenceStore`) |
| `di/OcrBindings.kt` | Metro `@BindingContainer` registered in `AppGraph` |

## PaddleOCR artifact (integration point)

`libpaddle_ocr.so` is **not built here** — it is a separate, checksummed artifact (contract §1.7):
our own JNI over Paddle-Lite (Apache-2.0) published to `komi-kun/komi-kun-ocr-models`, together
with `libomp.so` / `libc++_shared.so`, described by `paddle-manifest.json`.

- Fetch into `src/main/jniLibs/<abi>/`: `./gradlew :core:ocr:fetchPaddleNativeLibs`
  (not wired into `assemble`; the manifest's empty `sha256` placeholders make it a no-op until the
  artifact is published).
- The engine emits the `TIMING|` / `LINE|` text protocol consumed by `PaddleOcrEngine.parseOutput`;
  the JNI signatures are `nativeInit` / `nativeProcess` / `nativeRelease`.
- ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64` (contract §1.5). The Kotlin `supportedAbi()` gate
  accepts `x86_64`.
- The loader prefers the app-storage layout (downloaded at runtime) and falls back to the
  APK-packaged `jniLibs` layout.
- Models (`manga_det_fp16.*`, `manga_rec_fp16.*`, `ppocrv6_dict.txt`) download at runtime, opt-in,
  through `paddle/ModelDownloader`, which verifies each file's SHA-256 against the release
  `manifest.json` before keeping it (contract §1.7).

## Tests

Pure logic is unit-tested under `src/test/` (box math, merger ordering incl. vertical text,
`.mokuro` parse/resolve/convert, hit-testing, lookup-text extraction; 79 tests). Run:

```bash
flock /tmp/opencode/gradle.lock ./gradlew :core:ocr:testDebugUnitTest
```
