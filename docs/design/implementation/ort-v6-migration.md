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
| 5 | Review loop (≤5 iterations, fresh context each time) | **done — converged at iteration 5** (see §6); no Blocker/Major |
| 6 | Final pass: emulator validation + reader-crash fix | **done** — x86_64 emulator (API 37) validation of the engine + models; a pre-existing reader crash (F1) found and fixed here; see “Final pass” below |

### Final pass (emulator validation + F1)

Run on an **x86_64** emulator (`Pixel_9_Pro_XL`, API 37), the platform the migration exists to enable:

- The engine **loads** and `nativeInit` reports **`ok=true`**; the `dlopen`/JNI path that §8 left
  "never executed on a device" now has a real on-device run.
- The models **download and verify** (size + SHA-256 against the cached manifest) on x86_64.
- The recognition **probe produced correct Japanese**, confirming the ORT + PP-OCRv6 `small` stack
  end-to-end on this ABI.
- **The app's own reader path could not be exercised**: opening any chapter crashed in
  `ReaderActivity`'s constructor (`ViewConfiguration.get(this)` in a property initializer, i.e.
  `Context.getResources()` before attach). **F1 originates on the base branch**
  `feat/introduce-ocr-and-yomitan` (commit `46496637e6`), not in this PR; it is fixed **here** so
  that **merging #8 propagates the fix** to the integration target. Fix: the
  `ocrTwoFingerTapDetector` is now `by lazy { … }`.

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
| 1 | 1. **Docs presented Paddle-Lite `.nb` as the current backend** (contract §1.7; `03-ocr` §2; this doc's own framing). 2. **`NOTICE`** lacked the statically-linked `libpaddle_ocr.so` attributions (**opencv-mobile**, **Clipper**). 3. **`ModelDownloader`**: `importFromUri` extracted into `filesDir/<name>` but verified/loaded from `filesDir/paddle_ocr/<name>`, so flat archives always failed; `isPaddleDownloaded` was existence-only, so a corrupt model counted as present and was never re-fetched; failed downloads left `<name>.part` behind. 4. **ORT init failure had no app-visible reason** — the shim captures `ort_error_` and its `nativeInit` does log the runtime reason to logcat (`runtime=…`, e.g. `libonnxruntime.so not found`), but the app read nothing, so a missing/mismatched `libonnxruntime.so` was only diagnosable from logcat. | Docs → **ONNX Runtime + PP-OCRv6 small** everywhere authoritative (`00` §1.7; `03` §2.1–§2.3, §7); `01-architecture-build.md` (§1.1, §5.4) and `yomitan-manga-ocr-port.md` (§4, §6.1) kept as history with a **“superseded by `ort-v6-migration.md`”** note. `NOTICE` → added **opencv-mobile 4.13.0** (Apache-2.0) and **Clipper 6.4.2** (BSL-1.0) from the artifact's own `NOTICE`. `ModelDownloader` → extraction/verification/loading all use `modelsDir`; `isPaddleDownloaded` re-verifies size **and** SHA-256 against the cached manifest; `<name>.part` files are cleaned on failure and on the next run (owned names only); unit tests added. `PaddleOcrEngine` → `nativeGetOrtError()` accessor added as a **forward-compatible stub** (not yet exported by the pinned shim, so it logs a fallback and the shim's `nativeInit` logcat line stays the source of truth). | See “Still open” below. |
| 2 | 1. **`nativeGetOrtError` is inert** — the pinned `ocr-models-v3` shim exports only `nativeInit`/`nativeProcess`/`nativeRelease`, so the accessor always throws `UnsatisfiedLinkError`; iteration 1's *“surfaced the native ORT error”* claim was overstated (the shim already logs the real reason from `nativeInit`). 2. **Main-thread regression** — `isPaddleDownloaded` SHA-256s ~31 MB while `triggerPaddleDownload()` is non-suspend and called from Compose `onValueChanged`, freezing the UI on the first tap. 3. **Import hardening** — `importFromUri`/`extractZipInto` extracted into `modelsDir`, which also holds `lib/<abi>/`, so a malicious archive could plant a `.so` that `loadNativeLibraries` would `System.load`. 4. **Doc inaccuracies** — `libc++_shared.so` “not shipped” (false: it is), the `:core:ocr` **Apache-2.0** licence claims, `01` §3.3's native-set list missing `libonnxruntime.so`, a stale OCR test count, and `nativeInit` “only logs paths”. | 1. Accessor kept as an explicitly-commented **forward-compatible stub**; Kotlin fallback message made honest (`unavailable; see the nativeInit logcat line`); the overstated commit/PR claim and the §6 iteration-1 wording corrected. 2. `isPaddleDownloaded` is now size/existence-only (`modelFileHasExpectedSize`; it reads only the small cached `manifest.json`, never the model bytes); `triggerPaddleDownload()` always schedules the full size+SHA-256 verification off the main thread (`downloadModels` skips files that verify, re-fetches mismatches; still verifies offline against the cached manifest). 3. `importFromUri` extracts into a scratch dir and `installRequiredModels` copies **only** `REQUIRED_MODELS` (matched by name) into `modelsDir`; zip-slip is still rejected. 4. Docs corrected (`00` §1.7 + §3.3; `01` §1.1 + §5.4 licence rows; `core/ocr/README.md`; `ocr-model-release` SKILL). Tests added for the cheap gate and the selective install. | See “Still open”. |
| 3 | 1. **No single-flight guard in `triggerPaddleDownload`** — every call launched a run; the settings toggle could overlap two runs, and the second run's `cleanupPartFiles()` deleted the first run's in-flight `<name>.part`, so `sha256Hex(temp)` threw `FileNotFoundException` (spurious `Failed` + a wasted up-to-31 MB download) and both raced on `manifest.json`/`_state`. 2. **Cached-manifest fast path could pin a model set forever** — once verified, `downloadModels()` returned without re-checking the cached manifest's release/baseUrl, so a future release reusing the same file names would never be fetched. 3. **`importFromUri` verified too late and was a no-op offline** — it copied into `modelsDir` *before* hashing and only verified against a **network** manifest, so a bad archive clobbered good files with no rollback and offline it returned success having verified nothing. 4. **Licensing docs** still presented the unverified `manga_*_fp16` fine-tune as live (`NOTICE:48-50`, `03-ocr.md:442-444`, `00:527-528`). 5. **Dead code + silent failure** — `isPaddleDownloaded`/`requiredModelFiles()`/the `ModelDownloadState` flow had no production caller, a failed first-time download was silent, and the KDoc said the gate “only `stat`s” when it actually reads + JSON-parses `manifest.json`. 6. **Round-2 tests missed the decision logic** (no `Context`), so pending/fast-path decisions were untested. 7. **Doc nits** — `01` §1.3's native list lacked `libonnxruntime.so`/`libomp.so`; `03` §7.1's “if `01` keeps it Apache” note was stale. | 1. A `SingleFlightGate` (AtomicBoolean) coalesces redundant triggers; `importFromUri` and `downloadModels` also share a `Mutex` so an import can never race a download, and `importFromUri` no longer purges parts. A single trigger still always schedules a run. 2. The fast path now requires `cachedManifestIsCurrent` (release tag/baseUrl == `DEFAULT_RELEASE`/`DEFAULT_BASE_URL`); a manifest from another release is re-fetched even if the names match. 3. `importFromUri` fetches the live manifest but **falls back to the cached one** offline, verifies the staged files in the scratch dir (`verifyStagedModels`, checksum required) **before** copying, and fails loudly when no manifest is available. 4. All three now read “official PP-OCRv6 `small` models only” (the fine-tune is historical). 5. The settings screen observes `state` and `isPaddleDownloaded` and shows ready/running/error via existing `KMR` strings (new `pref_ocr_model_status`), and `triggerPaddleDownload` logs failures at error level (`xLogE`); the KDoc no longer claims a pure `stat`. 6. Extracted pure `SingleFlightGate`, `pendingModelEntries`, `requiredModelsVerified`, `cachedManifestIsCurrent`/`cachedManifestIsUsable` + unit tests. 7. `01` §1.3 lists all three native libs; the `03` note is marked settled (GPL). | See “Still open”. |
| 4 | 1. **Two inconsistent `ModelDownloader` instances + an unreachable import path** — `SettingsOcrScreen` built its own instance via `remember { … }` while `OcrBindings` provided an `@SingleIn(AppScope)` one **nothing injected**, so the single-flight gate was not shared and the round-2/3 `importFromUri` hardening had no caller. 2. **Doc drift** — `03` §2 still said `libc++_shared.so` was “not shipped” (it is, for all three ABIs), its §2 heading still said “Paddle `.so`”, and `00`’s v1.1 changelog line omitted `libonnxruntime.so`. 3. **Stale `:core:ocr` test count** in `core/ocr/README.md`. 4. **`cachedManifestIsCurrent` bug** — `manifest.release == release \|\| manifest.baseUrl == baseUrl` with `OcrModelManifest.baseUrl` defaulting to `DEFAULT_BASE_URL`, so a cached manifest that **omitted** `baseUrl` always looked current regardless of `release` (the exact stale-pin case it exists to prevent). 5. **`importFromUri` leaked its `InputStream`** on the offline “no cached manifest” path (opened at `:244`, `use`d only at `:259`). 6. **`installRequiredModels` copied onto good files with `overwrite = true`**, so a mid-copy `IOException` left a truncated model. 7. **Round-3 tests missed the real risks** — the gate test was sequential, and the staged-archive test never asserted `modelsDir` was untouched. 8. **A failed download could not be retried** (`ListPreferenceWidget` ignores re-selecting the same value) and `Failed(message)` rendered the constant `ocr_status_error` (“Error”), dropping the reason. 9. **`PaddleOcrEngine.loadNativeLibraries`’s app-storage branch returned `false` without falling back to `jniLibs`**, contradicting its KDoc/README. | 1. `AppGraph` now exposes `val modelDownloader: ModelDownloader` and `SettingsOcrScreen` reads `context.appGraph.modelDownloader`, so **exactly one** graph-scoped instance drives state/downloads and the gate is shared by the CTA and the new retry. **F1 decision:** `importFromUri` is kept as **intentionally-unwired API** with an explicit KDoc note (contract §3.4 schedules **no import UI**); its tests are kept and it stays serialised against downloads by `runLock`. 2. `03` §2 reworded (“**is** shipped … deliberately **not listed in `paddle-manifest.json` / not fetched into `jniLibs`**”, matching `00` §1.7 + `core/ocr/README.md`), §2 heading → “Native `.so`s (ONNX Runtime) + det/rec models”; `00` changelog adds `libonnxruntime.so`. 3. README → **108** tests (105 at the reviewed tip + the 3 new iteration-4 tests). 4. `OcrModelManifest.baseUrl` now defaults to `""` and `cachedManifestIsCurrent` **requires the release identifier** (baseUrl only as an explicit, non-blank fallback when `release` is absent); test added for “release differs, baseUrl matches” → stale. 5. The whole resolution + staging block is wrapped in `input.use`. 6. Copies stage through `<name>.part` then rename (same scratch name `cleanupPartFiles` prunes). 7. Added a **2-thread** race test (exactly one acquires), a failure→retry test over the extracted `SingleFlightGate.runExclusive` (the `finally` releases), and strengthened the staged-archive test to assert the existing install is untouched; `installRequiredModels` is asserted to leave no `.part`. 8. The status row is **clickable to retry**, and `Failed` now renders `KMR.ocr_status_error_reason` (“Error: %s”) with the real reason. 9. `loadNativeLibraries` now tries the app-storage layout and **falls back to `jniLibs`** when it is absent or fails to load. | See “Still open”. |
| 5 | 1. **Vacuous test assertion** — `verifyStagedModels rejects a staged archive before it is installed` ended with `existing.readText() shouldBe "good"`, but the test only calls `verifyStagedModels`, which never touches `modelsDir`, so the line could never fail. 2. **KDoc/README overstated the app-storage path** — `PaddleOcrEngine.loadNativeLibraries` and `core/ocr/README.md` said the loader “prefers the app-storage layout (downloaded at runtime)”, but nothing writes `.so`s into `filesDir` today (only models are). 3. **`ocr_status_error`** (base `i18n-kmk` string) is unused now that errors render `ocr_status_error_reason`. No Blocker/Major findings. | 1. Test now mirrors `importFromUri`'s verify-then-install ordering (`verifyStagedModels` then `installRequiredModels`) for both the checksum and missing-manifest rejections, so the untouched-install assertion is non-vacuous; a comment records that the real `Context`/`Uri` entry point is not exercised. 2. KDoc + README reworded: the APK `jniLibs` path is **live**, the app-storage layout is a **forward-compatible path, currently not populated**; the fallback itself is unchanged. 3. Left as-is (harmless; removing a base string is not worth the churn). | — (none) |

### Still open (carried from iteration 1)

- **On-device latency is unmeasured**, and the `dlopen` path has **never executed on a device or
  emulator** — only the host parity harness. See §8.
- **Native ORT-error accessor not yet shipped:** the app declares `nativeGetOrtError()` as a
  deliberately-commented **forward-compatible stub**, but the pinned `ocr-models-v3` shim exports only
  `nativeInit`/`nativeProcess`/`nativeRelease`. Until a shim release adds the accessor,
  `PaddleOcrEngine` logs the honest fallback (`unavailable; see the nativeInit logcat line`) and the
  shim's `nativeInit` logcat line (`... runtime=<ort load error>`) remains the source of truth. The
  artifact-side change is: expose `Engine::ort_error()`, log it in `nativeInit`, and add the JNI
  getter.
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

- On-device latency: the ORT path now runs on an **x86_64 emulator** (§5 “Final pass”) — engine load,
  model verify and recognition all confirmed — but latency and threading are **still unmeasured** (the
  emulator is not representative hardware, and the other ABIs are untested); must be checked on a
  real device before the parent PR merges.
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
- **Artifact-side ORT-error accessor** (iteration 1 finding 4, re-checked in iteration 2) is not yet in
  the pinned shim — see §6 “Still open”. Declaring `nativeGetOrtError()` in the app is
  forward-compatible but currently inert: it throws `UnsatisfiedLinkError`, so `PaddleOcrEngine` logs
  the fallback `unavailable; see the nativeInit logcat line`.

## 9. Where things stand

The ORT + PP-OCRv6 work is **converged**: the review loop (≤5 iterations, fresh context each) stopped
at **iteration 5 (bound)** with **no Blocker/Major** findings, and iteration 5's three nits are
handled (§6). A **final pass** then validated the stack on an **x86_64 emulator** and fixed the
reader crash it exposed (§5 “Final pass”). All gates are green.

- **PR #8.** `feat/ocr-onnxruntime-v6` is open **into `feat/introduce-ocr-and-yomitan`** (PR #7), not
  `master`; phases 0–5 are done (§5). The branch is the integration target for this work.
- **On-x86_64-emulator validation (done).** `Pixel_9_Pro_XL`, API 37: the engine loads
  (`nativeInit ok=true`), the models download and verify, and the recognition probe produced correct
  Japanese. The `dlopen`/JNI path now has a real on-device run, so the §8 “never executed on a device”
  risk is closed **for x86_64**; latency numbers on other ABIs are still unmeasured.
- **Reader crash found and fixed (F1).** The emulator could not open a chapter because
  `ReaderActivity`'s constructor dereferenced `Context.getResources()` (a `ViewConfiguration.get(this)`
  property initializer) before attach. The bug **originates on the base branch**
  `feat/introduce-ocr-and-yomitan` (commit `46496637e6`) — it predates this PR — and is fixed **here**
  (`ocrTwoFingerTapDetector` is now `by lazy { … }`) so that **merging #8 propagates the fix** into the
  integration target.
- **APK-size tradeoff (accepted).** Shipping the full `onnxruntime-android:1.31.0` runtime (not
  `onnxruntime-mobile`) grows each ABI split by ≈ +34/24/50 MB and the universal APK by ≈ +103 MB
  (§8). Accepted as the cost of real `x86_64` support and PP-OCRv6.
- **How to resume.** Read this file first, then the artifact release
  [`ocr-models-v3`](https://github.com/komi-kun/komi-kun-ocr-models/releases/tag/ocr-models-v3)
  (tag `ocr-models-v3`, commit `2471502`) — its `INTEGRATION.md` is the drop-in diff and
  `app-paddle-manifest.json` is copied verbatim to `core/ocr/paddle-manifest.json`. Gates and key
  paths are in §7; open risks in §8.
