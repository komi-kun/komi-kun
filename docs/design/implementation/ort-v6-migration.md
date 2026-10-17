# ONNX Runtime + PP-OCRv6 migration

**Status doc / session handoff.** This file is the durable record for the ORT + PP-OCRv6 work. If
the agent session is compacted or restarted, **read this file first** — it records the objective,
every decision, what is done, what the review loop found, and how to resume.

---

## 1. Objective

Replace the `:core:ocr` inference backend (**Paddle-Lite**.nb`) with **ONNX Runtime** and move the
model set from PP-OCRv4/v5 to **PP-OCRv6 `small`** (det + rec + dict). Motivation, in order:

1. **Quality.** PP-OCRv6 `small` is a large, robust win on real Japanese manga (see §3).
2. **x86_64.** Paddle-Lite ships no Android x86_64 build; ONNX Runtime does. This removes the
   "OCR unavailable on x86_64" limitation.
3. PP-OCRv6 has **no Paddle-Lite path** (Paddle 3.x PIR is rejected by `opt` 2.14-rc), so a backend
   change is required regardless.

## 2. Branch / PR

| | |
|---|---|
| Branch | `feat/ocr-onnxruntime-v6` |
| Based on | `feat/introduce-ocr-and-yomitan` (= PR #7, → `master`) |
| PR | opened **into `feat/introduce-ocr-and-yomitan`** (not `master`) |
| Remote | `origin` = `git@github.com:komi-kun/komi-kun.git` |

Rule: never push to `master`; the feature branch is the integration target for this work.

## 3. Evidence (why v6 small)

Benchmark on 5 real manga × 6 pages (original vs AI-upscaled vs classical Lanczos), 752 common crops
from one detector, host CPU. Artifacts + raw data: `/tmp/opencode/ocr-bench` (`results.json`,
`failures/`).

Gold-relative on **original** pages — **EM / CER**:

| model | EM | CER | ms/crop |
|---|---|---|---|
| PP-OCRv5 mobile (current) | 47.3% | 19.7% | 7.8 |
| v6 tiny | 3.6% | 78.0% | 2.0 |
| **v6 small** | **78.2%** | **10.0%** | 6.8 |
| v6 medium | 85.5% | 7.6% | 23.7 |
| manga-ocr (oracle) | 87.3% | 5.6% | 197 |

**Audit caveats (independently verified, must be repeated when citing):**
- "EM" is *agreement with a manga-ocr-derived gold* (48/55 labels byte-identical to manga-ocr), not
  absolute accuracy. The **v5→v6_small gap** is robust (cluster-bootstrap by title [+21.1, +41.5] pp;
  positive in all 5 titles; survives punctuation stripping at +29.1pp; same ordering on a
  gold-independent 752-crop metric).
- **v6 tiny is unusable for Japanese**: its dict has **zero kana** (6904 entries, 6174 kanji).
- **AI upscaling does not help OCR**; it degrades the stronger models (v6_medium ~−9pp after
  punctuation normalisation; v6_small −5.5pp, not significant) and inflates detector boxes
  25→48/page (up-scaler texture read as text). Classical Lanczos ≈ original. **Do not add upscaling.**
- Recognizer input height is **fixed at 48px** — pre-upscaling is largely resampled away.
- Latency figures are a 4-thread **desktop** run (Ryzen 9 5950X); phone numbers are estimates.

## 4. Design decisions

- **Single engine, not dual.** ORT replaces Paddle-Lite. Reverting = revert commit + manifest
  rollback. (No dual-runtime APK: that would double native size.)
- **Keep the JNI surface and the `TIMING|` / `LINE|` output protocol unchanged** so the Kotlin layer
  and the `OcrViewport` seam need no change.
- **Keep the class/package names** (`mihon.core.ocr.paddle.*`, `PaddleOcrEngine`) to minimise churn;
  they still describe PaddleOCR *models*. Add comments noting the ORT backend.
- **ABI set is unchanged** (`arm64-v8a`, `armeabi-v7a`, `x86_64`); ORT simply fills the x86_64 OCR
  gap that Paddle-Lite left. `:core:dictionary` already builds x86_64.
- **Artifact** published as `ocr-models-v3` in `komi-kun/komi-kun-ocr-models` (checksummed
  manifest); app fetches it via `:core:ocr:fetchPaddleNativeLibs`.
- Prefer the **ORT C API** (`onnxruntime_c_api.h`) for its ABI stability; link against or `dlopen`
  the AAR's `libonnxruntime.so`.

## 5. Phases / status

| # | Phase | Status |
|---|---|---|
| 0 | Branch + this doc | done |
| 1 | New native engine (ORT) + `ocr-models-v3` release | done |
| 2 | App integration (`paddle-manifest.json`, engine constants, ABI) | done |
| 3 | Verification (gates + APK contents) | done |
| 4 | Push branch, open PR into the feature branch | done — PR #8 (→ `feat/introduce-ocr-and-yomitan`) |
| 5 | Review loop (≤5 iterations, fresh context each time) | **iteration 1 fixed** (see §6); more pending |

### Release

`ocr-models-v3` (tag `ocr-models-v3`, commit `2471502`) is published at
`https://github.com/komi-kun/komi-kun-ocr-models/releases/tag/ocr-models-v3`;
the app pins `baseUrl = .../releases/download/ocr-models-v3`. Its `INTEGRATION.md` is the
drop-in diff applied by phase 2; `app-paddle-manifest.json` is copied verbatim to
`core/ocr/paddle-manifest.json`.

## 6. Review-loop log

Each iteration = a **fresh-context** review agent on the diff, then fixes for findings I agree with.

| iter | reviewer findings | fixed | remaining |
|---|---|---|---|
| 1 | 1. **Docs presented Paddle-Lite `.nb` as the current backend** (contract §1.7; `03-ocr` §2; this doc's own framing). 2. **`NOTICE`** lacked the statically-linked `libpaddle_ocr.so` attributions (**opencv-mobile**, **Clipper**). 3. **`ModelDownloader`**: `importFromUri` extracted into `filesDir/<name>` but verified/loaded from `filesDir/paddle_ocr/<name>`, so flat archives always failed; `isPaddleDownloaded` was existence-only, so a corrupt model counted as present and was never re-fetched; failed downloads left `<name>.part` behind. 4. **ORT init failure unsurfaced** — the shim captures `ort_error_` but nothing logs/reads it (`nativeInit` only logs paths), so a missing/mismatched `libonnxruntime.so` was undiagnosable. | Docs → **ONNX Runtime + PP-OCRv6 small** everywhere authoritative (`00` §1.7; `03` §2.1–§2.3, §7); `01-architecture-build.md` (§1.1, §5.4) and `yomitan-manga-ocr-port.md` (§4, §6.1) kept as history with a **“superseded by `ort-v6-migration.md`”** note. `NOTICE` → added **opencv-mobile 4.13.0** (Apache-2.0) and **Clipper 6.4.2** (BSL-1.0) from the artifact's own `NOTICE`. `ModelDownloader` → extraction/verification/loading all use `modelsDir`; `isPaddleDownloaded` re-verifies size **and** SHA-256 against the cached manifest; `<name>.part` files are cleaned on failure and on the next run (owned names only); unit tests added. `PaddleOcrEngine` → `nativeGetOrtError()` accessor surfaced in the init-failure log. | See “Still open” below. |
| — | (next iteration) | — | — |

### Still open (carried from iteration 1)

- **On-device latency is unmeasured**, and the `dlopen` path has **never executed on a device or
  emulator** — only the host parity harness. See §8.
- **Native ORT-error accessor not yet shipped:** the app declares `nativeGetOrtError()`, but the
  pinned `ocr-models-v3` shim exports only `nativeInit`/`nativeProcess`/`nativeRelease`. Until a shim
  release adds the accessor, `PaddleOcrEngine` logs `"unavailable"` and the shim's `nativeInit`
  logcat line (`... runtime=<ort load error>`) remains the source of truth. The artifact-side change
  is: expose `Engine::ort_error()`, log it in `nativeInit`, and add the JNI getter.
- **APK size** growth (+103 MB universal, ≈ +34/24/50 MB per ABI split) is **accepted** — it is the
  full `onnxruntime-android` build, not `onnxruntime-mobile`; see §8.

## 7. Key paths & commands

- Engine: `core/ocr/src/main/java/mihon/core/ocr/paddle/PaddleOcrEngine.kt`
- Manifest: `core/ocr/paddle-manifest.json`; downloader `.../paddle/ModelDownloader.kt`
- Native build: artifact repo `komi-kun/komi-kun-ocr-models` (`build.sh`, `CMakeLists.txt`, `deps.lock.json`)
- Design/contract: `docs/design/implementation/00-implementation-contract.md`, `03-ocr.md`
- Benchmark data: `/tmp/opencode/ocr-bench`

Gates (serialise Gradle: `flock /tmp/opencode/gradle.lock ./gradlew …`):

```bash
./gradlew spotlessCheck
./gradlew :core:ocr:compileDebugKotlin
./gradlew :core:ocr:testDebugUnitTest
./gradlew :core:ocr:fetchPaddleNativeLibs      # verify new hashes
./gradlew :app:assembleDebug                    # confirm libs in APK
./gradlew verifyGplFree                         # ORT is MIT; seam stays Apache — keep green
./gradlew :data:verifyDebugDatabaseMigration
```

## 8. Open questions / risks

- On-device latency unmeasured (ORT vs Paddle-Lite threading differs) — must be checked before merge.
- Absolute Japanese accuracy of v6_small is unknown (only agreement with a manga-ocr reference).
- APK size (**measured**, debug ABI splits; run-to-run packaging jitter ≈ ±0.8 MB): the shim +
  runtime grow each split by ≈ +34.2 MB (`arm64-v8a`), +24.2 MB (`armeabi-v7a`), +49.8 MB
  (`x86_64`, includes the shim's first x86_64 copy + `libomp.so`); the universal APK ≈ +103.0 MB
  (baseline → after: `arm64-v8a` 105.8 → 140.0 MB, `armeabi-v7a` 93.8 → 118.0 MB,
  `x86_64` 104.3 → 154.1 MB, universal 212.1 → 315.1 MB). The **compressed payload** grows by
  exactly the added (stripped) libs: +34,028,152 / +24,053,288 / +45,520,952 B per split,
  +103,602,392 B universal; the residual file-size change is zip alignment/padding. The runtime is
  the **full** `onnxruntime-android:1.31.0` build (`libonnxruntime.so` ≈ 34/24/41 MB uncompressed
  per ABI), not the smaller `--minimal_build`/`onnxruntime-mobile` variant.
- Licensing: ONNX Runtime is MIT; v6 models Apache-2.0 — compatible with the GPL-3.0 combined work.
  Iteration 1 added the statically-linked **opencv-mobile** (Apache-2.0) and **Clipper** (BSL-1.0)
  attributions to `NOTICE`; `scripts/verify-gpl-free.sh` stays green (the seam is unchanged).
- **Artifact-side ORT-error accessor** (iteration 1, finding 4) is not yet in the pinned shim — see
  §6 “Still open”. Declaring `nativeGetOrtError()` in the app is forward-compatible but currently
  reports `"unavailable"`.
