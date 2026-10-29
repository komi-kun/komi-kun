---
name: ocr-model-release
description: Build, publish and wire the PaddleOCR native artifact that :core:ocr fetches. Use when asked to rebuild or update the OCR `.so`/models, add/adjust OCR models, cut a new OCR model release, fix `core/ocr/paddle-manifest.json`, or when the OCR engine is unavailable/out of date.
---

# Releasing the OCR native artifact

`:core:ocr` uses **our own** Apache-2.0 PaddleOCR artifact — not the opaque zip Chimahon ships. It lives
in the GitHub repo **`komi-kun/komi-kun-ocr-models`** (releases tagged like `ocr-models-v5`), and the
app fetches it at build time and verifies every SHA-256.

## What the artifact is

Two independent things live in `komi-kun/komi-kun-ocr-models`:

| what | pinned in | tags |
|---|---|---|
| the **native artifact** (`libpaddle_ocr.so` + `libonnxruntime.so` + `libomp.so` and a copy of the models) | `core/ocr/paddle-manifest.json` | `ocr-models-vN` so far (legacy naming) → **`ocr-native-vN`** for new cuts |
| the **runtime model releases** the app's selection resolves to | `ModelRelease.kt` | one tag per detector × recogniser pair (`ocr-manga-vN` / `ocr-models-vN`) |

**Naming rule — two axes, never mixed.** The native artifact and the model releases are separate
version axes. A future **native** artifact release is tagged **`ocr-native-vN`**; do **not** keep
numbering it `ocr-models-vN`, because `ocr-models-v3` / `ocr-models-v4` already name two of the four
*model* releases (the `DIALOGUE`/`SFX` × `STOCK` pair). Model releases keep their names
(`ocr-manga-vN` for a fine-tune, `ocr-models-vN` for a stock pair). **Existing tags are never
renamed** (see "never re-tag a release with different bytes" below) — `ocr-models-v5` stays the
native pin it is today.

Current native artifact: **`ocr-models-v5`** (ONNX Runtime + PP-OCRv6 small). `v5` is a **shim-only**
rebuild over `ocr-models-v4`'s model bytes — the same ONNX models and runtime, with `libpaddle_ocr.so`
taking the detector long side as a 7th `nativeProcess` argument (`maxSideLen`).

The runtime releases are a 2×2 matrix. Every release publishes the **same file names** — the detector
`ppocrv6_small_det.onnx` is shared by all four — so only the tag identifies the bytes:

| detector \ recogniser | `FINE_TUNED` | `STOCK` |
|---|---|---|
| `DIALOGUE` (default) | `ocr-manga-v4` | `ocr-models-v3` |
| `SFX` | `ocr-manga-v3` | `ocr-models-v4` |

`DetModelVariant.DEFAULT` is `DIALOGUE`, so a fresh install resolves to `ocr-manga-v4`. `ModelDownloader`
records the installed tag in `paddle_ocr/installed-release.txt`, and that marker — never "the file
exists" — is what says which weights are on disk (the engine keys its reload on it too).

- **`libpaddle_ocr.so`** — our JNI shim over **ONNX Runtime** (via the ABI-stable
  `onnxruntime_c_api.h` C API) + opencv-mobile + Clipper. It `dlopen()`s `libonnxruntime.so` and
  resolves `OrtGetApiBase()`, so a missing runtime degrades to `Engine::Init` returning false rather
  than a link failure. Exports exactly
  `Java_mihon_core_ocr_paddle_PaddleOcrEngine_{nativeInit,nativeProcess,nativeRelease}` and emits the
  `TIMING|`/`LINE|` stdout protocol that `PaddleOcrEngine` parses — **unchanged** from the
  Paddle-Lite era.
- **`libonnxruntime.so`** per ABI — republished byte-for-byte from Microsoft's
  `com.microsoft.onnxruntime:onnxruntime-android:1.31.0` AAR (MIT). This is the **full** Android
  build (~34 MB arm64-v8a, ~24 MB armeabi-v7a, ~41 MB x86_64 uncompressed), not the smaller
  `--minimal_build`/`onnxruntime-mobile` variant; it ships as a checksummed build-time fetch, not a
  Gradle dependency.
- **`libomp.so`** per ABI (required by opencv-mobile's `parallel_for_`, not by ORT, which owns its own
  thread pool). **`libc++_shared.so` is deliberately NOT listed in the app manifest** (the release
  does ship it; the app simply does not fetch it) — AGP already packages the NDK copy (via
  `:core:dictionary`), and a second copy fails the app merge.
- **Models** — Apache-2.0 ONNX exports of PaddlePaddle PP-OCRv6 **small**, published in each of the
  four runtime releases. `ppocrv6_small_det.onnx` is the **SFX** detector in `ocr-manga-v3` /
  `ocr-models-v4` (a Manga109-s/COO fine-tune — SFX H 32.78 % → 65.03 %, curved 9+-point recall
  27.5 % → 74.0 %, art FP 5.35 → 1.35 /page on held-out books, dialogue block coverage 92.10 % →
  94.94 %) and the **stock dialogue** detector in `ocr-manga-v4` / `ocr-models-v3`;
  `ppocrv6_small_rec.onnx` (unified ja/zh/en CTC, `h=48`, 18 710 output classes, 18 708-entry dict with
  kana) / `ppocrv6_small_rec_manga_real.onnx` (the manga fine-tune) and `ppocrv6_small_dict.txt`
  (byte-identical in all four). These are **not** in the APK; `ModelDownloader` fetches them opt-in at
  runtime (SHA-256 verified) and prunes superseded `ppocrv5_*`/`ppocrv4_*` files on upgrade. v6
  **tiny** has no kana and must not be used.
- **ABIs**: `arm64-v8a`, `armeabi-v7a`, **`x86_64`** — ORT publishes all three and the ONNX graphs
  are architecture-independent, so x86_64 emulators are supported (no more graceful degradation).
- The repo holds `build.sh`, `CMakeLists.txt`, `deps.lock.json`, a CI workflow, `LICENSE`/`NOTICE`,
  `INTEGRATION.md` and a `tools/parity_check.py`.

## 1. Build / publish

```bash
# in a clone of komi-kun/komi-kun-ocr-models
./build.sh                       # builds libpaddle_ocr.so per ABI (+ libomp + libonnxruntime) and stages assets
./build.sh --manifest-only       # regenerate manifest.json / app-paddle-manifest.json
gh release create ocr-models-v<n> <staged-assets> --title "OCR models v<n>"
```

Assets are published **flat** (`arm64-v8a_libpaddle_ocr.so`, `arm64-v8a_libonnxruntime.so`,
`arm64-v8a_libomp.so`, `ppocrv6_small_det.onnx`, …), not under per-ABI subdirectories — the app's
fetch task resolves by `path`.

- **Never re-tag a release with different bytes** — the app manifest pins hashes; cut a new tag.
- `libonnxruntime.so` and the models are the fixed upstream bytes (pinned by SHA-256); our
  `libpaddle_ocr.so` is rebuilt from source and is not byte-identical across runs.

## 2. Wire it into the app

Apply the release's `INTEGRATION.md`. In short: replace `core/ocr/paddle-manifest.json` with the new
`app-paddle-manifest.json` content (`release`, `baseUrl`, `abi[]`, and one entry per file
(`abi?`, `name`, `path`, `sha256`, `size`, `spdx`)); keep `libc++_shared.so` out. Then update the
model file names in the selection enums (`DetModelVariant.detModel`, `RecModelVariant.recModel`,
`DICT_TXT` in `PaddleOcrEngine`) — `ModelDownloader` derives its required set from those, and adds any
superseded *new* names to `LEGACY_MODELS`. The other three releases of a pair must then be re-cut with
the same new names, or the matrix ships mismatched file sets.

`./gradlew :core:ocr:fetchPaddleNativeLibs` reads that manifest, verifies each SHA-256, and writes the
files into the git-ignored `core/ocr/src/main/jniLibs/<abi>/`. It is wired to `:core:ocr`'s per-variant
`merge*JniLibFolders`/`NativeLibs` tasks, so **every `assemble*` (incl. CI `assembleNightly`) packages
the libs**, while `compile`/`test` never hit the network and the task is `UP-TO-DATE` once fetched.

## 3. Verify

```bash
flock /tmp/opencode/gradle.lock ./gradlew :core:ocr:fetchPaddleNativeLibs   # hashes verified
flock /tmp/opencode/gradle.lock ./gradlew :app:assembleDebug
unzip -l app/build/outputs/apk/debug/app-arm64-v8a-debug.apk | grep -E 'libpaddle_ocr|libonnxruntime|libomp'
# and check x86_64: libonnxruntime.so must be packaged there too
```

## Known constraints

- **APK size.** `libonnxruntime.so` is ~34/24/41 MB uncompressed per ABI; with ABI splits each per-ABI
  APK grows by its own copy, and the universal APK carries all three. Moving it to a runtime download
  (like the models) would shrink the APK but lose the "shipped in the APK" property and reintroduce a
  download step in `PaddleOcrEngine.loadNativeLibraries`.
- The manifest `abi` list and the app's `splits.abi` should agree (`arm64-v8a`, `armeabi-v7a`, `x86_64`).
- See `core/ocr/README.md` for the module-local layout notes.
