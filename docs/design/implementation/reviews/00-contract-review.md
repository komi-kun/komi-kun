# 00 — Contract review (fresh-eyes, pre-implementation)

Status: **review** · Reviewer: fresh-eyes subagent · Date: 2026-10-16
Scope: `00-implementation-contract.md` (frozen) + supporting plans `01`–`05` +
`../yomitan-manga-ocr-port.md` + `../yomitan-spec/README.md`.
Method: read the contract and plans, then verified concrete claims against
`/home/luca/development/komi-kun`, `/tmp/opencode/hoshidicts`, `/tmp/opencode/chimahon`,
`/tmp/opencode/webgpuviewer`. Findings are ranked. No code or plan was edited.

---

## Verdict summary

The contract is well-organised and resolves the module-license drift correctly in §6 (GPL `:core:ocr`).
However it is not yet safe to freeze: the **license-boundary enforcement is undefined/unsatisfiable**,
the **webgpuviewer fork artifact it pins does not exist**, several reader files that it declares
GPL-free **will be forced to import GPL types**, and two workstreams **own the same build file in the
same parallel wave**. Several `01`/`03` "hard facts" are also stale versus the actual repos.

---

## Blockers

### B1 — `verifyGplFree` gate is undefined and its import pattern is self-contradictory
- `00 §5` lists `:ci:verifyGplFree` as a gate for **every wave**, and `00 §1.3` says it "fails if a file
  … imports `mihon.core.ocr`/`mihon.core.dictionary` or a `*.ocr.impl`/`mihon.dictionary.*` GPL package".
- There is **no `:ci` module**: `settings.gradle.kts` has no `include(":ci")` (verified `settings.gradle.kts:51-74`),
  the root has no `ci/` directory, and `00 §3.1`/`§3.2` assign no one to create it. The gate as written
  cannot run, yet it is a required exit criterion for W1–W5.
- The pattern `mihon.dictionary.*` collides with the **Apache seam package itself**. `00 §1.2` puts
  `ReaderDictionaryHost` in package `mihon.dictionary.host`, and `ReaderActivity.kt` (in the GPL-free
  list, `00 §1.3`) must `import mihon.dictionary.host.ReaderDictionaryHost` to call it. A grep for
  `mihon.dictionary.*` in a GPL-free file therefore **flags the seam it is supposed to allow**. The
  GPL impl `ReaderDictionaryHostImpl` also lives in that package per `02 §1.2/§3.4`, so the check cannot
  distinguish seam from impl by package.
- **Fix required in §1.3/§5 before W1:** define the check precisely (explicit allow-list of seam types,
  distinct GPL package roots, or a Gradle `verifyGplFree` task), name its owner, and add it to §3.1/§3.2.

### B2 — the pinned `webgpuviewer` fork does not exist as a commit/tag
- `00 §1.6`/`§2.6` pin "fork tag of `feat/ocr-transform-accessors`"; W0's exit artifact is "webgpu tag".
- In `/tmp/opencode/webgpuviewer`, `feat/ocr-transform-accessors` and `master` are the **same commit**
  (`7b877c9`); the only tag is `52`. The accessors (`Image.placement(surfaceWidth,…)`,
  `ImagePage.pageRect(Int,Int)`, `ImageViewerState.visiblePages`/`rectOf`/`transformVersion`,
  `ImageViewerContinuousState.scrollOffsetY`) exist **only as uncommitted working-tree changes**
  (`git status` shows 3 modified files; `git diff master..HEAD` is empty). No commit, no tag, not pushed.
- The JitPack line in `00 §2.6` (`com.github.komi-kun.webgpuviewer:library:<fork-tag>`) therefore has
  nothing to resolve. W0 must **commit + tag + push** before W1's catalog edit and W4's adapter; the
  contract should state this explicitly instead of implying the artifact already exists.
- Also verify the JitPack coordinate: the fork's `namespace = "ca.mpreg.webgpuviewer"` and submodule is
  `library`, so `…webgpuviewer:library` is plausible but unvalidated against a real build.

### B3 — the GPL-free reader files will import GPL types (seam is not actually closed)
- `00 §1.3` lists `…/ui/reader/setting/ReaderPreferences.kt` as **GPL-free**, but `03 §5.3` requires it to
  expose `ocrSource(mangaId): ReaderOcrSource`, and `03 §7.1`/`§1.1` place the OCR model layer in the
  **GPL `:core:ocr`**. `ReaderOcrSource` is not assigned a location anywhere in the contract. If it lands
  in `:core:ocr` (as 03 implies) the "GPL-free" file imports a GPL package and the whole boundary claim
  fails; if it stays in `:app` it must be constrained to import nothing GPL. Decide and record.
- `04 §4.2`/`§4.3` still specify `ReaderOcrHost.blocksFor(page): List<OcrTextBlock>` and a standalone
  `OcrOverlay(blocks: List<OcrTextBlock>)` in `app/…/presentation/reader/OcrOverlay.kt` (04 calls it
  "Apache-clean"). This **directly contradicts** `00 §2.3`/`§6.4` ("`ReaderOcrHost` exposes **no**
  `OcrTextBlock`") and `00 §2.2` ("GPL and never crosses the Apache seam"). As frozen, 04 cannot be
  built against §2. The `OcrOverlay.kt` file's license is also unstated in §1.3 (04 says Apache, 03
  §7.1 says GPL).
- Consequence: W3 (impl) and W4 (reader) will implement two incompatible designs. Plan 04 §4.2–4.3 and
  §5.1 must be superseded by §2.3 (hit-test + block rendering live inside the GPL impl; seam returns
  only `Boolean`/draws `Overlay(viewport, modifier)`).

### B4 — `AppBindings.kt` is owned by two workstreams in the same parallel wave
- `02 §5.3` wires `DictionaryRepository`/profile store with `@Provides` in
  `app/src/main/java/mihon/app/di/AppBindings.kt`; `03 §5.1` says OCR services
  (`OcrManager`/`OcrStore`/`OcrCacheManager`/`OcrJob`/`ModelDownloader`) also go in `AppBindings.kt`.
- `00 §4` runs **W2 (dictionary UI) and W3 (OCR services) in parallel during wave W3**, so both edit
  the same file concurrently. The contract's ownership map (`§3.1`/`§3.4`) never lists `AppBindings.kt`
  at all, and `01 §8.7` explicitly says to avoid touching it — three documents disagree.
- Same wave also has a create/consume ordering hazard: `SettingsOcrScreen.kt` is **created by W3** but
  **registered by W2** in `SettingsMainScreen.kt`/`SettingsSearchScreen.kt` (`00 §3.4`) — a parallel
  registration of a class the other stream may not have created yet.
- **Fix:** assign `AppBindings.kt` (and `SettingsMainScreen`/`SettingsSearchScreen` registration points)
  a single editor per wave, or move all new bindings into the modules' `@BindingContainer`s and have
  W2 register OCR settings after W3's screen exists (serialize that one dependency).

---

## Should-fix

### S1 — `hoshidicts` "two required fork commits" are already done (and one flag is misnamed)
- `00 §1.6` and `01 §2.2` require fork commit (a) `external/utf8proc` **SSH→HTTPS** and (b) guarding
  `hoshidicts-cli`/`benchmark-*`. Both **already exist at the pinned `156f586`**:
  `/tmp/opencode/hoshidicts/.gitmodules` already uses `https://github.com/JuliaStrings/utf8proc.git`,
  and `/tmp/opencode/hoshidicts/CMakeLists.txt:22-23,58-81` already defines
  `option(HOSHIDICTS_BUILD_CLI OFF)` / `option(HOSHIDICTS_BUILD_BENCHMARKS OFF)` with guarded
  `add_executable`. W0's "post-patch SHA" work is a near no-op; only the submodule add remains.
- `01 §2.3/§2.4` pass `-DHOSHIDICTS_BUILD_TESTS=OFF`; the real option is **`HOSHIDICTS_BUILD_BENCHMARKS`**.
  As written the flag is ignored (harmless only because it defaults OFF).

### S2 — `probeEntryTypes` already returns 4 longs; the contract's extension task is fictional
- `00 §2.1`/`§3.2` and `01 §3.2` claim Chimahon returns 3 longs and W1 must "extend the blob-walk …
  to count `type==2`/kanji". The verified JNI (`chimahon/…/hoshidicts_jni.cpp:449-544`) **already**
  allocates `NewLongArray(4)`, counts `kanjiCount` (`:490,:531`) and returns `{term,freq,pitch,kanji}`.
  `ImportResult` already has `kanjiCount` (`chimahon/HoshiDicts.kt:6-14`). Remove the task; keep only the
  genuinely new fields (`errors`, `score`, `transcriptions`) that do require JNI `FindClass` changes.

### S3 — chapter files are in `:domain`, not `data/`
- `00 §1.3` lists `data/…/Chapter.kt`, `data/…/ChapterUpdate.kt`. Actual paths:
  `domain/src/main/java/tachiyomi/domain/chapter/model/Chapter.kt`,
  `domain/.../ChapterUpdate.kt` (and a second `app/.../domain/chapter/model/Chapter.kt`). The mapper is
  `data/src/main/java/tachiyomi/data/chapter/ChapterMapper.kt`.
- Because the `verifyGplFree` file list uses these paths, the check would silently not cover the files
  it was meant to protect. Correct the paths and state which `Chapter.kt` gets `isOcrReady`.

### S4 — missing build wiring
- `:core:ocr`'s `OcrBitmapDecoder` uses `tachiyomi.decoder.ImageDecoder`, which comes from the external
  `ca.mpreg:imagedecoder` (`gradle/libs.versions.toml:18,78`). `00 §1.1` allows only `core:common`,
  `core:archive`, `core:metro` — the decoder artifact is missing from the allowed-deps list.
- `00 §1.5` includes `x86_64` for Paddle, but `PaddleOcrEngine.supportedAbi()` (chimahon) rejects
  anything but `arm64-v8a`/`armeabi-v7a` — W3 must patch the Kotlin gate too; the contract doesn't say so.
- `00 §1.7` ships only `libpaddle_ocr.so`; `03 §2.1` also lists `libomp.so` and `libc++_shared.so`.
  Omitting OpenMP/STL from the shipped set reproduces the `UnsatisfiedLinkError` the plan is trying to
  avoid (and risks a second `libc++_shared.so` colliding with `:core:dictionary`'s `-DANDROID_STL=c++_shared`).
- CoroutineWorkers (`OcrJob`, `DictionaryUpdateJob`) built for Metro need a `WorkerFactory`;
  `App.kt` currently initialises WorkManager with a bare `Configuration.Builder().build()`
  (`App.kt:279-280`). `App.kt` and the notification channels (`Notifications.kt`, `02 §6.4`) are not in
  the ownership map.
- `00 §3.1` lists only 3 workflows for `submodules: recursive`; `.github/workflows/build_benchmark.yml`
  also checks out (`:32,:60`) and builds, so it needs the same treatment (or an explicit "benchmark is
  out of scope" note).

### S5 — `Mokuro*` ported into `core/common` would erode the boundary and evade the check
- `00 §3.4` puts `core/common/.../Mokuro*` under `// KUN` (W3); `03 §4/§7.1` says the ported sidecar
  hook + network cache are **derived from Chimahon** yet must "stay Apache-2.0". That is contradictory:
  copied GPL-derived code cannot be Apache, and `core/common` is a shared upstream module. Neither file
  is in `00 §1.3`'s GPL-free list, so `verifyGplFree` (even if fixed) would not catch GPL code sitting
  inside `core/common`. Move these into `:core:ocr`, or define a clean-room generic interceptor.

### S6 — manifest/Anki wave assignment contradicts the "Anki is last" decision
- `00 §3.1` assigns `app/src/main/AndroidManifest.xml` (AnkiDroid `<queries>` + permission) to **W4**,
  while `00 §7 q4` keeps Anki as the **last phase (W5)** and `§3.2`/`§3.5` place `anki/**` in W5.
  Align the manifest to W5 (or state that only the `<queries>` entry is early).

---

## Nits

- **N1** `reader_activity.xml` overlay is line **27** (`:27`), matching `03 §3.4` but not the design doc's
  `:26` (`yomitan-manga-ocr-port.md §7.1`). Trivial, but the plans cite both.
- **N2** `settings.gradle.kts` does not yet include `:core:dictionary`/`:core:ocr` (expected in W1) —
  worth calling out that W1 must land includes before any module compiles.
- **N3** JitPack **is** already configured (`settings.gradle.kts:14,:44`), so `00 §6.8` ("no repository
  line") is correct — good. But `00 §2.6` shows a `version.ref = "<fork-tag>"` while the current entry
  (`libs.versions.toml:79`) uses `version.ref = "webgpuviewer"`; keep the ref name consistent.
- **N4** GPL-free files already import `KMR`/`SYMR` (e.g. `ReaderPreferences.kt:18`,
  `ReaderBottomButton.kt:5`), and `04 §2.3` adds another `KMR` string. A file importing `KMR` cannot
  compile in Mihon (only Komikku has `i18n-kmk`), so the "contributable to Mihon/Komikku" claim in the
  design should be narrowed to Komikku for these files.
- **N5** Plan `01` still describes retired layouts: `§1.3` places `ui/DictionaryPopup.kt` in the module
  (contract `§3.4` puts it in `:app`) and `§1.1`/`02 §1.3` name `:engine`/`:dictionary` modules the
  contract dropped. Mark these superseded so implementers code against §2.
- **N6** `mihon.library` applies only `configureAndroid`/`configureTest`
  (`buildSrc/.../mihon.library.gradle.kts`); confirm it (or the module) sets `ndkVersion`/
  `externalNativeBuild` support before `:core:dictionary` relies on it.

---

## What is correct / low-risk (verified)

- GPL-3.0 for both modules and the one-way `:core:ocr ∌ :core:dictionary` rule (`§6.1`).
- Seams are in `:app` (cannot be a library without a cycle) — matches the `@Composable`/`ReaderPage` facts.
- JitPack already present; root `.gitmodules` genuinely absent; `webgpuviewer` is ABI-neutral.
- `:data:verifyDebugDatabaseMigration` exists and is already a CI step; latest migration is `53.sqm`, so
  `54.sqm` is correct.
- `chapter.sq` + `ChapterMapper.kt` exist and are the right edit points.
- FileProvider `${applicationId}.provider` and `provider_paths.xml` already exist for Anki media.
- WebGPU long-press offset is genuinely discarded today (`WebGpuViewer.kt:968`) — retaining it is right.

---

## Recommended gate before W1

1. Fix B1 (define + own `verifyGplFree`, disambiguate the seam package).
2. Commit/tag/push the `webgpuviewer` fork; record the real tag (B2).
3. Freeze the `ReaderOcrSource` location and delete 04's `OcrTextBlock` seam (B3).
4. Assign single editors for `AppBindings.kt` and the settings-registration files (B4).
5. Correct the stale repo facts (S1–S3) and add the missing allowed-deps/worker/native-lib wiring (S4).
