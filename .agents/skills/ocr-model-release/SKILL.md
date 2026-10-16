---
name: ocr-model-release
description: Build, publish and wire the PaddleOCR native artifact that :core:ocr fetches. Use when asked to rebuild or update the Paddle `.so`, add/adjust OCR models, cut a new OCR model release, fix `core/ocr/paddle-manifest.json`, or when the OCR engine is unavailable/out of date.
---

# Releasing the PaddleOCR artifact

`:core:ocr` uses **our own** Apache-2.0 PaddleOCR artifact — not the opaque zip Chimahon ships. It lives
in the GitHub repo **`komi-kun/komi-kun-ocr-models`** (releases tagged like `ocr-models-v1`), and the
app fetches it at build time and verifies every SHA-256.

## What the artifact is

- **`libpaddle_ocr.so`** — our JNI shim over the official **Paddle-Lite** prebuilt static lib
  (`libpaddle_api_light_bundled.a`) + opencv-mobile. Exports exactly
  `Java_mihon_core_ocr_paddle_PaddleOcrEngine_{nativeInit,nativeProcess,nativeRelease}` and emits the
  `TIMING|`/`LINE|` stdout protocol that `PaddleOcrEngine` parses.
- **`libomp.so`** per ABI (Paddle-Lite runtime dep). **`libc++_shared.so` is deliberately NOT shipped**
  — AGP already packages the NDK copy (via `:core:dictionary`), and a second copy fails the app merge.
- **Models** — official PaddleOCR Apache-2.0: `ppocrv4_det.nb`, `ppocrv4_japan_rec.nb`,
  `ppocrv4_japan_dict.txt`. These are **not** in the APK; `ModelDownloader` fetches them opt-in at
  runtime (SHA-256 verified).
- The repo holds `build.sh`, `CMakeLists.txt`, `deps.lock.json`, a CI workflow, `LICENSE`/`NOTICE`, and
  `INTEGRATION.md`.

## 1. Build / publish

```bash
# in a clone of komi-kun/komi-kun-ocr-models
./build.sh                       # builds libpaddle_ocr.so per ABI (+ libomp) and stages assets
./build.sh --manifest-only       # regenerate manifest.json / app-paddle-manifest.json
gh release create ocr-models-v<n> <staged-assets> --title "OCR models v<n>"
```

Assets are published **flat** (`arm64-v8a_libpaddle_ocr.so`, `arm64-v8a_libomp.so`,
`ppocrv4_det.nb`, …), not under per-ABI subdirectories — the app's fetch task resolves by `path`.

- **Never re-tag a release with different bytes** — the app manifest pins hashes; cut a new tag.
- The build is **not** byte-reproducible (`opt` writes a non-deterministic header); pin the exact
  published bytes.

## 2. Wire it into the app

Replace `core/ocr/paddle-manifest.json` with the new `app-paddle-manifest.json` content: `release`,
`baseUrl`, `abi[]`, and one entry per file (`abi?`, `name`, `path`, `sha256`, `size`, `spdx`). Keep
`libc++_shared.so` out.

`./gradlew :core:ocr:fetchPaddleNativeLibs` reads that manifest, verifies each SHA-256, and writes the
files into the git-ignored `core/ocr/src/main/jniLibs/<abi>/`. It is wired to `:core:ocr`'s per-variant
`merge*JniLibFolders`/`NativeLibs` tasks, so **every `assemble*` (incl. CI `assembleNightly`) packages
the libs**, while `compile`/`test` never hit the network and the task is `UP-TO-DATE` once fetched.

Also update `ModelDownloader.REQUIRED_MODELS` (and `PaddleOcrEngine`'s `DET_MODEL`/`REC_MODEL`/
`DICT_TXT`) if the model file names changed.

## 3. Verify

```bash
flock /tmp/opencode/gradle.lock ./gradlew :core:ocr:fetchPaddleNativeLibs   # hashes verified
flock /tmp/opencode/gradle.lock ./gradlew :app:assembleDebug
unzip -l app/build/outputs/apk/debug/app-arm64-v8a-debug.apk | grep -E 'libpaddle_ocr|libomp'
```

## Known constraints

- **x86_64 has no Paddle engine.** Paddle-Lite ships no Android x86_64 prebuilt, and `.nb` models are
  target-specific, so an ARM `.so` on x86_64 aborts. `supportedAbi()` accepts `x86_64` on purpose so
  `System.loadLibrary` throws and `init()` returns `false` → **OCR is unavailable on x86_64 emulators,
  gracefully**. Enabling it needs a from-source Paddle-Lite build **and** an x86 model set with
  ABI-aware model selection.
- The manifest `abi` list and the app's `splits.abi` should agree (`arm64-v8a`, `armeabi-v7a`, `x86_64`).
- See `core/ocr/README.md` for the module-local layout notes.
