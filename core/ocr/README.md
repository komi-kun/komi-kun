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
| `paddle/ModelDownloader.kt` | checksum-verified, opt-in runtime download/import of the det/rec models + dict (prunes superseded models) |
| `overlay/OcrCoordinateMapper.kt` | normalized-space block remapping for reader transforms (split/merge/crop/rotate) |
| `cache/OcrStore.kt` | persisted OCR queue (`SharedPreferences` JSON) |
| `cache/OcrCacheManager.kt` | per-page/per-chapter OCR result persistence (dir/CBZ sidecar/internal JSON) |
| `cache/OcrManager.kt` | source policy + engine orchestration off page bytes, queue driving |
| `cache/OcrJob.kt` | background queue runner (plain coroutine job; app wraps it for WorkManager) |
| `OcrPreferences.kt` | OCR source/overlay/queue preferences (`PreferenceStore`) |
| `di/OcrBindings.kt` | Metro `@BindingContainer` registered in `AppGraph` |

## PaddleOCR artifact (integration point)

`libpaddle_ocr.so` is **not built here** — it is a separate, checksummed artifact (contract §1.7):
our own JNI over **ONNX Runtime** (MIT) over PaddleOCR's Apache-2.0 deploy post-processing,
published to `komi-kun/komi-kun-ocr-models` (release **`ocr-models-v3`**) together with
`libonnxruntime.so` and `libomp.so`, described by `paddle-manifest.json`.

### Fetching + packaging

```bash
./gradlew :core:ocr:fetchPaddleNativeLibs   # -> src/main/jniLibs/<abi>/ (git-ignored)
```

- `fetchPaddleNativeLibs` reads `core/ocr/paddle-manifest.json`, downloads each **native** entry by
  its flat release-asset name (`entry["path"]`, e.g. `arm64-v8a_libpaddle_ocr.so`), verifies the
  SHA-256 and **hard-fails on mismatch**. Entries without an `abi` are the runtime-downloaded models
  and are skipped here.
- **Wiring choice.** The task is wired as a dependency of `:core:ocr`'s per-variant JNI / native-lib
  merge tasks (`merge*JniLibFolders`, `merge*NativeLibs`), so every `assemble*` (local and CI)
  packages the fetched `.so`s with no workflow change. It is deliberately **not** hooked into
  `preBuild`, so `compileDebugKotlin` / `testDebugUnitTest` stay offline. It declares its expected
  outputs, so after the first successful fetch it is **UP-TO-DATE** and makes no network request; a
  clean checkout downloads once, on the first `assemble*`. If you only ever compile/test, nothing is
  fetched and OCR degrades gracefully at runtime until you run `fetchPaddleNativeLibs`.
- `libc++_shared.so` is **not** fetched (and not listed in the manifest): AGP already packages the
  NDK copy for `-DANDROID_STL=c++_shared` modules (`:core:dictionary`), and a second copy in
  `jniLibs` fails the app merge with a duplicate-path error. `libomp.so` **is** required — not by
  ONNX Runtime (which owns its own thread pool) but by opencv-mobile's `parallel_for_`
  (`omp_get_thread_num` / `omp_get_max_threads`), statically linked into `libpaddle_ocr.so`.
  `libonnxruntime.so` **is** fetched and packaged: it is republished byte-for-byte from Microsoft's
  `onnxruntime-android` 1.31.0 AAR, and our shim `dlopen`s it (see the manifest's `MIT` entries).

### Engine & ABI caveat

- The engine emits the `TIMING|` / `LINE|` text protocol consumed by `PaddleOcrEngine.parseOutput`;
  the JNI signatures are `nativeInit` / `nativeProcess` / `nativeRelease` (unchanged by the backend
  swap). The loader prefers the app-storage layout (downloaded at runtime) and falls back to the
  APK-packaged `jniLibs` layout; in the latter, the shim's own `dlopen` of `libonnxruntime.so`
  resolves it from the same APK lib directory.
- ABIs shipped: **`arm64-v8a`**, **`armeabi-v7a`**, **`x86_64`**. ONNX Runtime publishes all three
  and the ONNX graphs are architecture-independent, so **`x86_64` emulators are now supported**
  instead of merely degrading. `PaddleOcrEngine.supportedAbi()` still catches
  `UnsatisfiedLinkError` as a safety net for any ABI with no published artifact.
- Models (`ppocrv6_small_det.onnx`, `ppocrv6_small_rec.onnx`, `ppocrv6_small_dict.txt`) download at
  runtime, opt-in, through `paddle/ModelDownloader`, which verifies each file's SHA-256 against the
  release `manifest.json` before keeping it (contract §1.7). The engine loads one ONNX graph per
  stage, so `nativeInit`'s `detBin`/`recBin` arguments are passed empty. PP-OCRv6 **small**'s
  recogniser is a single unified multi-language model (v6 tiny has no kana and is not used) and is
  still CTC at `rec_image_height = 48` with the same `['#' (blank)] + dict + [' ']` convention, so
  no input-shape or dictionary-format change was needed. `ModelDownloader.cleanupStaleModels()`
  deletes the superseded `ppocrv5_*` and `ppocrv4_*` files on download/import so an upgrade does not
  keep dead weights (unknown files are never touched).

## Tests

Pure logic is unit-tested under `src/test/` (box math, merger ordering incl. vertical text,
`.mokuro` parse/resolve/convert, hit-testing, lookup-text extraction, model-download checks;
97 tests). Run:

```bash
flock /tmp/opencode/gradle.lock ./gradlew :core:ocr:testDebugUnitTest
```
