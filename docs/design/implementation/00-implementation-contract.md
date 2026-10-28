# 00 — Implementation Contract (FROZEN, v1.1)

Status: **frozen** · Branch: `feat/introduce-ocr-and-yomitan` · Reconciles `01`–`05`.
This file is the single source of truth. Where a plan below contradicts a plan above, **this file wins**.
Implementation waves code against §2 only; do not re-open §1–§2 without the maintainer.
See **§7 Plan overrides** for every place this contract now diverges from plans 01–05.

Legend: **W1**=architecture/build (01) · **W2**=dictionary UI (02) · **W3**=OCR (03) ·
**W4**=reader (04) · **W5**=tests+Anki (05).

### Changelog v1.1

Fixes for the fresh-eyes review (`reviews/00-contract-review.md`):

- **B1** — undefined `:ci:verifyGplFree` → concrete, owned `scripts/verify-gpl-free.sh`
  (`./gradlew verifyGplFree`) with explicit allow/deny lists (§1.9); the pattern that flagged the Apache
  seam is gone.
- **B2** — seam vs impl packages are now distinct; the shared enum `ReaderOcrSource` moves to Apache
  `:core:common` (§1.3); `OcrTextBlock` still never crosses the seam (§2.3); `OcrOverlay.kt` is **GPL**.
- **B3** — `AppBindings.kt` is **W1-only, frozen after W1**; each workstream owns a `@BindingContainer`/
  uses `@ContributesBinding` (§3.1, §3.6).
- **B4** — `webgpuviewer` fork pin resolved: tag `ocr-accessors-1` (`792aef3b6886343ee38a9140eefdc98114869dfd`), §1.6/§2.6.
- **Should-fixes** — `hoshidicts` pin → `62fc9fa1…` with `HOSHIDICTS_BUILD_CLI`/`_BENCHMARKS`;
  `probeEntryTypes` already returns 4 longs (no work); chapter files are in `:domain`; added
  `ca.mpreg:imagedecoder`, `libonnxruntime.so`/`libomp.so`/`libc++_shared.so`, Paddle x86_64 gate, `WorkerFactory`/`App.kt`,
  `Notifications.kt`; `Mokuro*` → `:core:ocr`; Anki manifest → W5.

---

## 1. Frozen decisions

### 1.1 Modules (exactly two new modules)

| Module | Path | Namespace | License | Owner | May depend on |
|---|---|---|---|---|---|
| `:core:dictionary` | `core/dictionary/` | `mihon.core.dictionary` | **GPL-3.0-or-later** | W1 (engine) / W2 (repo, ui, assets, anki) | `core:common`, `core:metro`, `i18n-kmk` |
| `:core:ocr` | `core/ocr/` | `mihon.core.ocr` | **GPL-3.0-or-later** | W3 | `core:common`, `core:archive`, `core:metro`, `ca.mpreg:imagedecoder` (`libs.image-decoder`) |

**Hard rules (CI-enforced):** `:core:ocr` must not depend on `:core:dictionary`; neither may be imported by
any GPL-free file (§1.3); `:app` depends on both. There is **no** separate `:dictionary` module (02 §1.3)
and **no** `:engine` module — both collapse into `:core:dictionary`. `:core:ocr` needs `ca.mpreg:imagedecoder`
for `OcrBitmapDecoder` (`tachiyomi.decoder.ImageDecoder`).

**Shared Apache value type.** The one enum both the GPL modules and GPL-free `:app` files need is
`ReaderOcrSource`; it lives in **`:core:common`** (§1.3), which both new modules and `:app` already
depend on — no cycle, no third module.

### 1.2 Seam interfaces (GPL-free, Apache-2.0)

The seam interfaces live in **`:app`**, not in a library module. Rationale: they reference app types
(`ReaderPage`, `@Composable`) and app-facing interfaces (`SearchableSettings`/`Tab`), so a library module
would invert the dependency into a cycle (02 §1.2, 04 §1.1). GPL-free = the *file* imports no GPL package;
that is what makes it upstream-contributable even though `:app` links GPL modules.

| Interface | File / package | License | Owner |
|---|---|---|---|
| `OcrViewport`, `ImagePoint`, `OcrDisplayTransform` | `app/…/ui/reader/ocr/OcrViewport.kt` (`eu.kanade.tachiyomi.ui.reader.ocr`) | Apache-2.0 | W4 |
| `ReaderOcrHost` | `app/…/ui/reader/ocr/ReaderOcrHost.kt` (same pkg) | Apache-2.0 | W4 |
| `ReaderDictionaryHost`, `DictionaryLookupRequest` | `app/…/mihon/dictionary/host/ReaderDictionaryHost.kt` (`mihon.dictionary.host`) | Apache-2.0 | W2 |

**GPL implementations sit in *different* packages** so the GPL-free check can separate seam from impl (B1):
`ReaderOcrHostImpl` → `eu.kanade.tachiyomi.ui.reader.ocr.impl` (W3); `ReaderDictionaryHostImpl` →
`mihon.dictionary.impl` (W2); viewport adapters `SsivOcrViewport`/`WebtoonOcrViewport`/`WebGpuOcrViewport`
(W4) live under their viewer sub-packages. All are `// KUN`, GPL, bound with
`@ContributesBinding(AppScope::class)` **only** — never by editing `AppBindings.kt` (§3.6).
Reader files obtain them **only** as the Apache interface types.

### 1.3 GPL-free file set (may be contributed to **Komikku**)

Exactly these source files, and nothing else (all import no GPL package):
- the three seam files in §1.2 (Apache, in `:app`);
- `core/common/src/main/java/tachiyomi/core/common/ocr/ReaderOcrSource.kt` — the shrunk
  `AUTOMATIC/MOKURO/PADDLE` enum (`usesMokuro`/`usesPersistentCache`/`persistentCacheVariant`; drop
  `recognitionEngine`/`OcrEngineType`). Owned by W1;
- `app/…/ui/reader/ReaderActivity.kt`, `ReaderViewModel.kt`, `setting/ReaderPreferences.kt`,
  `setting/ReaderBottomButton.kt`, `eu.kanade.presentation.reader.appbars.ReaderAppBars.kt`;
  `app/…/ui/reader/ReaderOcrTogglePolicy.kt` (the reader overlay toggle decision `ReaderViewModel`
  calls from the same package — invisible to an import scan, so it is listed explicitly) and
  `app/…/ui/reader/ReaderOcrOverlayPolicy.kt` (the reader overlay *layering* decision
  `ReaderActivity` calls from the same package — listed for the same reason);
- `core/common/…/preference/DictionaryOcrPreferences.kt` — the combined dictionary/OCR opt-in in
  Apache `:core:common`; the GPL-free `ReaderActivity`/`ReaderViewModel` gate on it;
- `domain/…/chapter/model/Chapter.kt`, `domain/…/chapter/model/ChapterUpdate.kt` (add `isOcrReady`);
  `data/…/migrations/54.sqm`, `data/…/chapter/ChapterMapper.kt`, `data/…/chapter.sq` (schema only).

Everything else new or changed is **GPL-3.0-or-later** with an SPDX header — including app-level
helpers for the port (`OcrDisplayScale`, `OcrRuntimePolicy`, `DictionarySessionOwner`,
`DictionaryOcrSetupPolicy`, `DictionaryTabNavigationPolicy`, `AnkiScreenshotPresetOptions`, …) and
their tests: an Apache header on a new file is a claim of GPL-freedom that only the files above may
make. Viewer files
(`viewer/**`, `PagerViewer`, `WebtoonViewer`, `WebGpuViewer`, `*PageHolder`, `ReaderPageImageView`) and
`app/…/presentation/reader/OcrOverlay.kt` are **GPL** because they construct the adapters/render blocks
(04 §1.4–1.7); they are *not* in the GPL-free set. `OcrTextBlock` is GPL and never appears in a GPL-free
file (§2.3); `OcrOverlay` (GPL) is composed only by `ReaderOcrHostImpl` (GPL).

**Contributability caveat:** these files import `KMR`/`SYMR` strings (§2, N4), which exist only in Komikku;
the "upstream-contributable" claim is therefore **Komikku**, not Mihon. Boundary enforcement: §1.9.

### 1.4 Toolchain

| Item | Pin |
|---|---|
| NDK | `29.0.14206865` (r29) — add `NDK_VERSION` to `AndroidConfig.kt` |
| CMake | `3.22.1` — add `CMAKE_VERSION` to `AndroidConfig.kt` |
| C++ | C++23, `-DANDROID_STL=c++_shared`, `-Oz`, LTO off first bring-up |
| 16 KB pages | `-Wl,-z,max-page-size=16384`, `useLegacyPackaging=false` |
| JDK | 21 (`.github/.java-version`), bytecode 17 |

### 1.5 ABI set (ONE set for all native artifacts)

**`arm64-v8a`, `armeabi-v7a`, `x86_64`. Drop `x86` (32-bit i686) — obsolete.**

- Both native artifacts (`hoshidicts` engine + Paddle) build for all three. `hoshidicts` must be built for
  armv7 too (W1); Paddle for x86_64 too (W3, per 03 §2.5 recommendation (a)).
- `webgpuviewer`'s AAR bundles native libs (`jni/<abi>/{libwebgpu_c_bundled.so,libresize.so}` for
  `arm64-v8a, armeabi-v7a, x86, x86_64`). Vendored in-repo at `maven/` (§1.6); `x86` is unused by our
  `splits.abi` but still packed into the universal APK (pre-existing artifact behaviour).
- `app/build.gradle.kts` `splits.abi`: `include("armeabi-v7a", "arm64-v8a", "x86_64")`; keep
  `isUniversalApk = true` (universal APK carries all three).
- Native modules set `ndk.abiFilters += listOf("arm64-v8a","armeabi-v7a","x86_64")` identically.
- Models remain **runtime-downloaded** (not in the APK); only `.so`s ship per-ABI APK.
- Fallback if the armv7 engine build proves painful: drop `armeabi-v7a` from both artifacts **and** the
  splits (maintainer call). Do not ship an ABI that has one native lib but not the other.

### 1.6 Submodules, forks, pins

| Repo | Pin | Notes |
|---|---|---|
| `komi-kun/hoshidicts` → `core/dictionary/src/main/cpp/hoshidicts` | **`62fc9fa16c3c4aac4b7ad06302af86cca09ada9a`** (branch `komi-kun/ndk29-build-guards`) | **CONFIRMED, host build green (GCC + Clang).** Already contains the UTF8proc SSH→HTTPS fix, the CLI/benchmark CMake guards, and host-portability includes — **no W0 fork commit is needed**; the submodule add is done (see `.gitmodules`). Guarded options are `HOSHIDICTS_BUILD_CLI` / `HOSHIDICTS_BUILD_BENCHMARKS` (**not** `HOSHIDICTS_BUILD_TESTS`). Record the pin in `.gitmodules` + `core/dictionary/README.md`. Never track a branch. |
| nested externals (8) | `git submodule update --init --recursive` | `submodules: recursive` in CI checkout of all build workflows (incl. `build_benchmark.yml`) |
| `komi-kun/webgpuviewer` | **tag `ocr-accessors-1`** = `792aef3b6886343ee38a9140eefdc98114869dfd` (branch `feat/ocr-transform-accessors`, base `7b877c9`) | Coordinate `com.github.komi-kun.webgpuviewer:library:ocr-accessors-1`, consumed from an **in-repo Maven repository**: `maven/` in the repo root, declared as `maven { url = uri("$rootDir/maven") }` in `settings.gradle.kts`. **JitPack cannot build the fork** — the `com.android.application`/`com.android.library` plugin markers (AGP `9.4.1`) fail to resolve on JitPack even though they exist on Google Maven (see `ocr-accessors-1/build.log`), so JitPack is not a viable fetch path for this AGP 9.x project. The release AAR published by the fork (`:library:assembleRelease` + `publishToMavenLocal` verified green) is vendored verbatim as `maven/.../library-ocr-accessors-1.{aar,pom,module,sources.jar,javadoc.jar}` (AAR ≈ 15.6 MB, self-contained: bundles `androidx.webgpu` classes + `jni/<abi>/libwebgpu_c_bundled.so`). Swap back to JitPack by deleting `maven/` and the repository line once the accessors land upstream. |

Root `.gitmodules` now exists with the single `core/dictionary/src/main/cpp/hoshidicts` entry, using the
HTTPS URL `https://github.com/komi-kun/hoshidicts.git` (the fork builds over HTTPS, not SSH); the pin
above is the tracked commit.

### 1.7 OCR artifact strategy

`komi-kun/komi-kun-ocr-models` GitHub release (built by our CI, **not** Chimahon's `v2.5` zip):
`libpaddle_ocr.so` (our JNI over **ONNX Runtime**, emits the `TIMING|`/`LINE|` protocol 03 §2.1) **plus
its runtime deps — `libonnxruntime.so` and `libomp.so`** (per ABI) + `manifest.json` with per-file
**SHA-256**, size, ABI, SPDX. Ship via `core/ocr/src/main/jniLibs/<abi>/` (Gradle fetch task, plan 01
§5.4 option A); models + `ppocrv6_small_dict.txt` download at user opt-in at runtime, **checksum-verified**
in `ModelDownloader`. Official PaddlePaddle **PP-OCRv6 `small`** ONNX exports (Apache-2.0) — the current
backend is ONNX Runtime, **not** Paddle-Lite (see `ort-v6-migration.md`); the `manga_*_fp16` Paddle-Lite
fine-tune is no longer used. Add `libpaddle_ocr.so` to `keepDebugSymbols`. `libc++_shared.so` **is**
shipped in the release but is deliberately **not listed in `paddle-manifest.json` / not fetched into
`jniLibs`**: AGP already packages the NDK copy via `:core:dictionary`'s `-DANDROID_STL=c++_shared`,
and a second copy collides at app merge (mirrors the manifest's `_comment`; re-add the release's entry
only if no other module provides it). Keep the STL → OpenMP → ONNX Runtime → engine `System.load`
order. `PaddleOcrEngine.supportedAbi()` accepts
`arm64-v8a`, `armeabi-v7a` and `x86_64` (§1.5).

### 1.8 Licensing artifacts

Combined distribution is **GPL-3.0-or-later** while Apache files keep Apache headers. Add
`LICENSES/GPL-3.0-or-later.txt`, `NOTICE` (combined work + GPL components), per-file SPDX headers, and OSS
licenses screen entries. Do **not** use the `main-mit` `hoshidicts` branch to claim Apache-clean.

### 1.9 GPL-free boundary check — `verifyGplFree` (owner W1)

Mechanism (replaces the undefined `:ci:verifyGplFree`): **`scripts/verify-gpl-free.sh`** (ripgrep), wrapped by
a Gradle `Exec` task `verifyGplFree` in `:app`, so `./gradlew verifyGplFree` and CI share one code path. W1
creates both in W1 (§3.1); it is a gate every wave (§5). It greps **only the §1.3 file set** for `import`
lines and fails when an import FQN starts with a **deny prefix** and is not exactly an **allowed seam FQN**:

- **Deny prefixes**: `mihon.core.dictionary`, `mihon.core.ocr`, `mihon.dictionary`,
  `eu.kanade.tachiyomi.ui.reader.ocr`, `eu.kanade.tachiyomi.ui.reader.overlay`.
- **Allowed FQNs** (Apache seam, §1.2): `mihon.dictionary.host.ReaderDictionaryHost`,
  `mihon.dictionary.host.DictionaryLookupRequest`, `eu.kanade.tachiyomi.ui.reader.ocr.OcrViewport`,
  `…ImagePoint`, `…OcrDisplayTransform`, `…ReaderOcrHost`.
- `tachiyomi.core.common.ocr.ReaderOcrSource` is Apache → never denied. Distinct seam/impl packages (§1.2)
  make the rule exact; the script asserts every §1.3 path exists, so path drift can't silently disable
  coverage (fixes S3).

---

## 2. Authoritative interfaces

### 2.1 Dictionary engine facade — `:core:dictionary` `mihon.core.dictionary.engine` (GPL)

```kotlin
interface DictionaryEngine {
    val isAvailable: Boolean                                  // false on ABI without lib; degrade
    suspend fun importDictionary(
        zip: java.io.File,
        outputDir: java.io.File,
        onProgress: (ImportProgress) -> Unit = {},            // coarse phases only (§0 flag)
    ): ImportResult
    fun probeEntryTypes(dictDir: java.io.File): EntryTypeCounts
    fun openSession(paths: DictionaryPaths): DictionarySession
}

sealed interface ImportProgress {
    data object Started : ImportProgress
    data class Finished(val result: ImportResult) : ImportProgress
}

/** NOT thread-safe: one native handle. Engine serialises; repo owns one session. */
interface DictionarySession : AutoCloseable {
    fun lookup(text: String, maxResults: Int = 20, scanLength: Int = 25): List<LookupResult>
    fun query(text: String): List<TermResult>
    fun queryKanji(character: String): KanjiResult
    fun styles(): List<DictionaryStyle>
    fun mediaBytes(dictName: String, path: String): ByteArray?
    fun rebuild(paths: DictionaryPaths)
}
```

Models (`mihon.core.dictionary.model`, per 01 §3.1, verbatim): `ImportResult(success,title,termCount,
metaCount,freqCount,pitchCount,kanjiCount,mediaCount,errors)`, `DictionaryStyle(dictName,styles)`,
`Frequency(value,displayValue)`, `GlossaryEntry(dictName,glossary,definitionTags,termTags)`,
`FrequencyEntry(dictName,frequencies)`, `PitchEntry(dictName,pitchPositions:IntArray,transcriptions)`,
`TransformGroup(name,description)`, `TermResult(expression,reading,rules,score,glossaries,frequencies,pitches)`,
`LookupResult(matched,deinflected,process,term,preprocessorSteps)`, `KanjiEntry(...)`, `KanjiResult(character,entries)`,
`DictionaryPaths(termPaths,freqPaths,pitchPaths,kanjiPaths)`, `EntryTypeCounts(term,freq,pitch,kanji)`.
JNI (`internal object HoshiDictsNative`) and C++ `FindClass` strings must match these exactly; a JVM test
asserts the constructor signature strings (01 §3.5).
**`probeEntryTypes` needs no extension** (S2): the vendored JNI already returns `{term,freq,pitch,kanji}` (4
longs). Only the new fields (`errors`, `score`, `transcriptions`) need `FindClass` work; drop 01's blob-walk task.

### 2.2 OCR DTOs & seams — `:core:ocr` `mihon.core.ocr` (GPL)

```kotlin
interface OcrEngine {
    suspend fun recognize(bytes: ByteArray, language: OcrLanguage): List<EngineLine>
}
data class NormalizedBBox(val xmin: Float, val ymin: Float, val xmax: Float, val ymax: Float)  // 0..1
enum class WritingDirection { LTR, TTB }
data class EngineLine(
    val text: String, val box: NormalizedBBox,
    val rotation: Float = 0f, val direction: WritingDirection = WritingDirection.LTR,
)
enum class OcrLanguage { /* per 03 §1.1 OcrLanguage.kt */ }
data class MergeConfig(/* 03 §1.1 OcrResult.kt:33-45 */)
data class OcrLineGeometry(/* 03 §3.2 */)
data class OcrBlockData(/* 03 §3.2, includes language */)
data class OcrPageData(/* 03 §3.2, includes language + blocks */)

/** Ported OwOCRMerger behind a seam (03 §3.3). */
interface OcrLineMerger {
    fun merge(lines: List<EngineLine>, imageW: Int, imageH: Int): List<OcrTextBlock>
}

/** Single viewer-facing IR. Lives in `mihon.core.ocr.model`; GPL. */
class OcrTextBlock { /* 03 §1.4 OcrTextBlock.kt: fullText/orderedFullText/displayText/orderedLineIndices */
    fun extractLookupString(global: Boolean, wholeWord: Boolean): String
}
```

`OcrTextBlock` is **GPL and never crosses the Apache seam** (see §2.3). `Mokuro*` (`mihon.core.ocr.mokuro`),
`OcrHitTester`, `OcrCoordinateMapper`, `OcrPreprocessor`, `ImageChunking`, `PaddleOcrEngine` are internal
to `:core:ocr`.

### 2.3 Apache seam interfaces — `:app` (GPL-free)

```kotlin
// eu.kanade.tachiyomi.ui.reader.ocr  — Apache-2.0
interface OcrViewport {
    fun visiblePages(): List<ReaderPage>
    fun imageNormalizedToScreen(page: ReaderPage, rect: RectF): RectF?
    fun screenToImageNormalized(x: Float, y: Float): ImagePoint?
    fun transformVersion(): Long
}
data class ImagePoint(val page: ReaderPage, val image: PointF)          // image normalized 0..1
sealed interface OcrDisplayTransform {
    data object None : OcrDisplayTransform
    data class Crop(val cropRect: RectF) : OcrDisplayTransform
    data class Split(val keepLeft: Boolean) : OcrDisplayTransform
    data class SplitAndMerge(val upperIsRight: Boolean) : OcrDisplayTransform
    data class Merge(val partner: ReaderPage, val partnerW: Int, val partnerH: Int,
                     val isLTR: Boolean, val centerMarginPx: Int) : OcrDisplayTransform
}

/** GPL-free: exposes no OcrTextBlock. Hit-test + lookup-text happen inside the impl. */
interface ReaderOcrHost {
    @Composable fun Overlay(viewport: OcrViewport?, modifier: Modifier)
    /** true iff the point hit an OCR block and a lookup was started; else false (→ page dialog). */
    fun onPageLongPress(page: ReaderPage, extraPage: ReaderPage?, overlayPoint: Offset): Boolean
}

// mihon.dictionary.host — Apache-2.0
interface ReaderDictionaryHost {
    @Composable fun Popup()
    fun warm(context: Context, languageCode: String)
    fun showLookup(request: DictionaryLookupRequest)
    fun dismiss()
}
data class DictionaryLookupRequest(
    val query: String, val sentence: String, val sentenceOffset: Int,
    val anchorRect: RectF?, val vertical: Boolean, val language: String,
)
```

`DictionaryLookupRequest` is the **single OCR→dictionary DTO** (was `OcrLookupRequest` in 03 §6 / 04 §4.1).
`ReaderOcrHostImpl` maps `onPageLongPress` → hit-test → `DictionaryLookupRequest` → `ReaderDictionaryHost`.

The only shared value type outside these files is the Apache enum `tachiyomi.core.common.ocr.ReaderOcrSource`
(`:core:common`, §1.3), importable by GPL-free `:app` files and GPL `:core:ocr` services; `OcrTextBlock` is
GPL (§2.2) and must not appear in any GPL-free file.

### 2.4 Long-press routing (frozen signature)

```kotlin
// ReaderActivity.kt
fun onPageLongTap(page: ReaderPage, extraPage: ReaderPage? = null, point: PointF? = null)
```
Single entry point for all four backends. If `ocrOverlayEnabled` and `point != null` and
`readerOcrHost.onPageLongPress(...)` returns true → consumed; else → `viewModel.openPageDialog(page, extraPage)`.
WebGPU **must retain** the normalized offset currently discarded (`WebGpuViewer.kt:968-970`).

### 2.5 Viewer base hook (frozen)

```kotlin
// viewer/Viewer.kt  (interface, // KUN)
fun ocrViewport(): OcrViewport? = null
```
Implemented in `PagerViewer`, `WebtoonViewer`, `WebGpuViewer` (continuous shares the file).

### 2.6 `komi-kun/webgpuviewer` fork API (minimal, additive)

`Image.placement(surfaceWidth: Int, surfaceHeight: Int, …)` + texture overload delegating;
`ImagePage.pageRect(surfaceWidth, surfaceHeight)`;/`Images`/`ImageSpread` Int overloads;
`ImageViewerState.visiblePages`, `rectOf(page)`, `transformVersion`; and (continuous)
`ImageViewerContinuousState` scroll-offset getter. No `GPUTexture` required, no draw-path change
(04 §3.2). Consume via one catalog line, reusing the existing ref (no new ref name): `webgpuviewer = { module =
"com.github.komi-kun.webgpuviewer:library", version.ref = "webgpuviewer" }` with `[versions] webgpuviewer =
"ocr-accessors-1"` (tag = `792aef3b6886343ee38a9140eefdc98114869dfd`). The catalog line is unchanged, but the
artifact is served by the **in-repo Maven repository `maven/`** declared in `settings.gradle.kts` (§1.6) rather
than JitPack, which cannot build the fork's AGP 9.4.1 project. Swapping back to upstream/JitPack is one
repository-line + `maven/`-directory removal.

---

## 3. File-ownership map

No two owners touch the same file in the same wave. `A:Wn` = action in wave *n*.

### 3.1 Build / shared (owner W1, every wave)

| File | Action | Wave |
|---|---|---|
| `settings.gradle.kts` | include `:core:dictionary`, `:core:ocr` (**must land before any module compiles**) | W1 |
| `buildSrc/…/AndroidConfig.kt` | `NDK_VERSION`, `CMAKE_VERSION` | W1 |
| `buildSrc/…/mihon.library.gradle.kts` | verify it supports `ndkVersion`/`externalNativeBuild` (N6); add if missing | W1 |
| `gradle/libs.versions.toml` | anki-api, `image-decoder`; `webgpuviewer` pin (§2.6) | W1 |
| `app/build.gradle.kts` | deps on both modules; `splits.abi` (§1.5); `keepDebugSymbols` | W1 |
| `.gitmodules` (+ submodule add) | hoshidicts pin (§1.6) | W0/W1 |
| `.github/workflows/{build_pull_request,build_push,build_nightly,build_benchmark}.yml` | `submodules: recursive`, NDK/CMake provision | W1 |
| `scripts/verify-gpl-free.sh` + `verifyGplFree` task (`:app`) | create (§1.9) | W1 |
| `app/src/main/java/mihon/app/di/AppGraph.kt` | register **all four** `bindingContainers` (§3.6) | W1 |
| `app/src/main/java/mihon/app/di/AppBindings.kt` | **W1-only; frozen after W1 — no other wave edits it** | W1 |
| `core/common/src/main/java/tachiyomi/core/common/ocr/ReaderOcrSource.kt` | create (Apache, §1.3) | W1 |
| `app/…/data/notification/Notifications.kt` | dictionary-update + OCR channels/IDs (sole editor; W3 supplies spec) | W2 |
| `app/src/main/java/eu/kanade/tachiyomi/App.kt` | `WorkerFactory` for OCR/dictionary workers (`:279-280`) | W3 |
| `app/src/main/AndroidManifest.xml` | Phase 6 AnkiDroid `<queries>`+permission (**Anki is last** — align to W5) | W5 |
| `LICENSES/GPL-3.0-or-later.txt`, `NOTICE` | create | W1 |
| `AGENTS.md` | module table, DI, ABI, licensing | W1 + W5 |
| README pins (`core/dictionary/README.md`, `core/ocr/README.md`) | pins recorded | W1 |

### 3.2 `:core:dictionary` (W1 scaffolds build files; then W1 engine / W2 the rest)

| Path | Action | Owner |
|---|---|---|
| `core/dictionary/build.gradle.kts`, `consumer-rules.pro`, `src/main/AndroidManifest.xml`, `src/main/cpp/CMakeLists.txt`, `di/DictionaryBindings.kt` | create (W1; build file frozen for all waves) | W1 |
| `src/main/cpp/{hoshidicts_jni.cpp,hoshidicts/}` | port/JNI + submodule | W1 |
| `src/main/java/.../engine/{HoshiDictsNative,DictionaryEngine,HoshiDictionaryEngine,DictionarySession}.kt`, `model/*` | create | W1 |
| `src/main/assets/dictionary/{renderer.js,kanji-renderer.js,base.css,index.html}` | copy | W2 |
| `src/main/java/.../repository/*`, `model/DictionaryPaths.kt` (paths/store), `ui/{DictionaryWebView,DictionaryWebViewBridges,DictionaryBootstrapHtml,DictionaryPayloadBuilder,KanaConverter,DictionaryProfile*}.kt` | adapt | W2 |
| `src/main/java/.../anki/**` | port | W5 |
| `src/test/java/.../{jni/Corpus*}` and `src/androidTest/...` | create | W1 (sigs) / W5 (corpus) |

### 3.3 `:core:ocr` (W1 scaffold, then W3)

| Path | Action | Owner |
|---|---|---|
| `core/ocr/build.gradle.kts`, `src/main/AndroidManifest.xml`, `di/OcrBindings.kt` | create (W1; build file frozen) | W1 |
| `src/main/java/mihon/core/ocr/{OcrEngine,EngineLine,OcrLanguage,OcrResult,OcrBlockData,OcrPreprocessor,OcrBitmapDecoder,ImageChunking,OcrHitTester,OcrLookupText,OcrTextBlock}.kt` | copy | W3 |
| `.../merge/{LineMerger.kt,OwOCRMerger.kt}`, `.../mokuro/*`, `.../overlay/OcrCoordinateMapper.kt`, `.../cache/{OcrStore,OcrCacheManager,OcrManager,OcrJob}.kt` | port | W3 |
| `.../paddle/{PaddleOcrEngine,ModelDownloader}.kt` | port (incl. `supportedAbi()` **x86_64 gate patch**, §1.7) | W3 |
| `src/main/jniLibs/<abi>/{libpaddle_ocr.so,libonnxruntime.so,libomp.so}` + fetch task | create (§1.7) | W3 |
| `src/test/java/...` (A2 seams) | create | W5 |

`:core:ocr`'s `cache/*` services consume `tachiyomi.core.common.ocr.ReaderOcrSource` (Apache, `:core:common`)
— no `:app` dependency, no cycle.

### 3.4 `:app` (W2 dictionary UI / W3 OCR wiring / W4 reader)

| Path | Action | Owner |
|---|---|---|
| `…/mihon/dictionary/host/ReaderDictionaryHost.kt` (seam) | create | W2 |
| `…/mihon/dictionary/host/DictionaryHostBindings.kt` (app `@BindingContainer`) | **W1 stub → W2 fills** | W2 |
| `…/mihon/dictionary/impl/ReaderDictionaryHostImpl.kt` (GPL; **not** in the seam pkg) | create | W2 |
| `…/ui/dictionary/{DictionaryPopup,DictionaryTab,DictionaryEntryWebView,DictionaryPopupWebViewWarmup,DictionaryPreferences}.kt` | create/adapt | W2 |
| `…/presentation/more/settings/screen/Settings{Dictionary,DictionaryPopup,Anki}Screen.kt` | create/adapt | W2 |
| `…/presentation/more/settings/screen/{SettingsMainScreen,SettingsSearchScreen}.kt` | register all dict+OCR+Anki entries (**sole editor**; OCR entry lands in W3 after W3 created the screen) | W2 |
| `i18n-kmk/.../base/strings.xml` | all new `KMR` keys (**sole editor**, key lists from W3/W4/W5) | W2 |
| `…/ui/home/HomeScreen.kt`, `…/ui/more/MoreTab.kt`, `…/ui/manga/MangaScreen*.kt`, `…/ui/manga/MangaScreenModel.kt` | Tab/profiles | W2 |
| `…/presentation/more/settings/screen/SettingsOcrScreen.kt` | create **early, in the W2 wave** (W3 owns; registered by W2 in W3) | W3 |
| `…/ui/reader/ocr/{OcrViewport,ReaderOcrHost}.kt` (seams) | create | W4 |
| `…/ui/reader/ocr/OcrHostBindings.kt` (app `@BindingContainer`) | **W1 stub → W3 fills** | W3 |
| `…/ui/reader/ocr/impl/ReaderOcrHostImpl.kt` (GPL) | create | W3 |
| `…/ui/reader/viewer/{pager/SsivOcrViewport,webtoon/WebtoonOcrViewport,webgpu/WebGpuOcrViewport}.kt`, `…/presentation/reader/OcrOverlay.kt` (**GPL**) | create | W4 |
| `…/ui/reader/viewer/{Viewer,reader/ReaderActivity,reader/ReaderViewModel,reader/setting/ReaderPreferences,reader/setting/ReaderBottomButton,presentation/reader/appbars/ReaderAppBars}.kt` | `// KUN` hunks | W4 |
| `…/ui/reader/viewer/{PagerViewer,WebtoonViewer,WebGpuViewer,WebGpuViewerContinuous,ReaderPageImageView,PagerPageHolder,WebtoonPageHolder}.kt` | `// KUN` (GPL: adapters) | W4 |
| `…/data/download/Downloader.kt` OCR hooks | `// KUN` | W3 |
| `core/common/.../mokuro/MokuroOcrInterceptor.kt` | clean-room generic HTTP cache (**Apache, no GPL imports**); `Mokuro*` model/parser goes to `:core:ocr` (§3.3) | W3 |

### 3.5 `:domain` / `data/` + tests (owner W3 schema / W5 tests)

| Path | Action | Owner |
|---|---|---|
| `domain/.../chapter/model/{Chapter.kt,ChapterUpdate.kt}` | add `isOcrReady` (**canonical**; the `:app` `domain/chapter/model/Chapter.kt` mirror, if present, is not edited) | W3 |
| `data/.../migrations/54.sqm`, `data/.../chapter.sq` + `data/.../chapter/ChapterMapper.kt` | add `ocr_ready`; regenerate | W3 |
| `core/dictionary/src/test/**`, `core/ocr/src/test/**`, `app/src/test/**`, corpus/*.jsonl, `MANIFEST.json` | create | W5 |
| `tools/yomitan-corpus/build.mjs`, `conformance-emulator.yml` | create | W5 |

### 3.6 Metro binding ownership (no shared `AppBindings.kt` edits)

`AppBindings.kt` is **W1-owned and frozen after W1**; W2–W5 never edit it. Feature bindings go to
workstream-owned `@BindingContainer` objects, registered once by W1 in `AppGraph.kt`:

| Container | File | Filled by |
|---|---|---|
| `DictionaryBindings` | `core/dictionary/.../di/DictionaryBindings.kt` | W1 (engine) |
| `OcrBindings` | `core/ocr/.../di/OcrBindings.kt` | W1/W3 |
| `DictionaryHostBindings` | `app/.../mihon/dictionary/host/DictionaryHostBindings.kt` | W2 |
| `OcrHostBindings` | `app/.../ui/reader/ocr/OcrHostBindings.kt` | W3 |

Rules: (a) W1 creates all four (app ones empty) and registers them in `AppGraph.kt`; (b)
`ReaderDictionaryHostImpl` (W2)/`ReaderOcrHostImpl` (W3) are `@Inject` + `@ContributesBinding(AppScope::class)`
— no container edit; (c) app-level `@Provides` (`DictionaryPreferences`, `OcrManager`/`ModelDownloader`) go in
the owning workstream's container, never `AppBindings.kt`. Removes the W2/W3 same-wave collision (B4).

---

## 4. Execution waves

| Wave | Runs in parallel | Must be serialized | Exit artifact |
|---|---|---|---|
| **W0 Forks/prep** | `hoshidicts` submodule add (pin `62fc9fa1…` already exists); **`W0b` webgpuviewer commit+tag+push** | fork prep is separate from W1; hoshidicts branch exists | hoshidicts pin recorded; **`webgpuviewer` tag/SHA (W0b, blocking W1 catalog edit + W4 adapter)** |
| **W1 Build scaffold** | — | sole owner W1 touches all shared files (§3.1), creates all four binding containers and registers them (§3.6). hoshidicts pin already verified | both modules compile with stubs; `verifyGplFree` runs |
| **W2 Libraries** | W1 `:core:dictionary` engine; W2 repo/assets; W3 `:core:ocr` + `SettingsOcrScreen` stub; W4 seam files; W5 test scaffolding | within `:core:dictionary`, engine (W1) and repo/ui (W2) are disjoint files → parallel; `strings.xml` not touched this wave | Phase-1 headless lookup test passes (01 §7) |
| **W3 App integration** | W2 dictionary UI/settings/strings + container fills; W3 OCR services + `ReaderOcrHostImpl` + `App.kt` worker factory + Downloader + `54.sqm`; W4 reader adapters/overlay/activity; W5 Anki files | `SettingsMainScreen`/`SettingsSearchScreen`/`strings.xml`/`Notifications.kt` = **W2 only**; `data/`+`:domain` chapter = W3 only; reader files = W4 only; `AppBindings.kt` = W1 only (frozen) | app compiles; settings/dictionary/OCR wire up |
| **W4 Wiring + Anki** | W4 webtoon/WebGPU adapters + continuous; W5 Anki bridge; W1 harness/docs | shared reader files already W4-owned; no W2/W3 collisions | full overlay across 4 backends; Anki stubs |
| **W5 Verify/docs** | tests, AnkiDroid manifest `<queries>`+permission, AGENTS/NOTICE finalisation | — | all gates green (§5) |

Rule: **never** run two waves that edit the same file; the contended files (§3.1/§3.4) have a single owner
per wave by construction.

---

## 5. Verification gates per wave

| Gate | W1 | W2 | W3 | W4 | W5 |
|---|---|---|---|---|---|
| `./gradlew spotlessApply` then `spotlessCheck` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `:core:dictionary:compileDebugKotlin` / `:core:ocr:compileDebugKotlin` | ✅ | ✅ | ✅ | ✅ | ✅ |
| native build (`:core:dictionary:externalNativeBuildDebug`, Paddle fetch) | ✅ | ✅ | ✅ | ✅ | ✅ |
| `:data:generateSqlDelightInterface` (after `54.sqm`) | — | — | ✅ | — | ✅ |
| `:data:verifyDebugDatabaseMigration` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `testDebugUnitTest` (T1: corpus sanity, OCR math, profiles/markers, JNI sig strings) | — | ✅ | ✅ | ✅ | ✅ |
| T2 host-conformance job (device-free, path-filtered) | — | ✅ | — | — | ✅ |
| `./gradlew verifyGplFree` (§1.9 boundary check; runs `scripts/verify-gpl-free.sh`) | ✅ | ✅ | ✅ | ✅ | ✅ |
| `assembleDebug` (or `assembleNightly -Ptelemetry…` for release) | — | — | ✅ | ✅ | ✅ |
| T3 emulator conformance | — | — | — | — | non-blocking |

---

## 6. Resolved conflicts

1. **`:core:ocr` license** → **GPL-3.0-or-later** (03 §1.5 wins over 01 §1.1). Chimahon's `OwOCRMerger`,
   `OcrTextOverlayPainter`, `OcrHitTester`, `ImageChunking`, `OcrLookupText`, `Mokuro*`, `OcrTextBlock` are
   GPL-derived, so the module cannot be Apache. **Why:** copying them as-is is the whole point of the
   accepted GPL decision; the "Apache, upstream-contributable" claim moves to the seam files only (§1.2/§1.3),
   which is all the port needs to keep contributable.
2. **Seam location** → **`:app`**, Apache; **GPL impls live in distinct packages** (`….ocr.impl`,
   `mihon.dictionary.impl`, viewer sub-packages) so `verifyGplFree` (§1.9) separates seam from impl.
   Neither `:core:ocr` (GPL) nor `:domain` can host the seams (`ReaderPage`/`@Composable` import app types;
   a library path is a cycle). 01 §6.1's `mihon.core.ocr.overlay` and 03 §7.1's "interface in `:domain`"
   are both dropped. **Why:** only `:app` satisfies the dependency direction, and the GPL-free guarantee for
   a *file* is "imports no GPL package", which holds. The one non-`:app` shared value type
   (`ReaderOcrSource`) lives in Apache `:core:common` (§1.3) so GPL `:core:ocr` services can use it too.
3. **ABI set** → `arm64-v8a, armeabi-v7a, x86_64` (§1.5). 01's 64-bit-only and 03's arm64+armv7 are both
   superseded. `x86` dropped as obsolete; both engines build all three; `webgpuviewer`'s AAR
   bundles its own `.so` for four ABIs (vendored, see §1.5).
   **Why:** one consistent set preserves existing armv7 users and CI emulators without shipping half-empty ABIs.
4. **Interface drift** → §2 is authoritative. `DictionarySession.lookup` defaults `(maxResults = 20,
   scanLength = 25)` (01; 02's 32/0 rejected). `OcrViewport` takes `page` + `OcrDisplayTransform` (04; 03's
   page-less sketch rejected). Single OCR→dict DTO = `DictionaryLookupRequest` (03/04 merged; `OcrLookupRequest`
   name retired). `ReaderOcrHost` exposes **no** `OcrTextBlock` (keeps reader Apache); `OcrOverlay.kt` is
   **GPL** and composed only by the GPL impl; `ReaderOcrSource` is Apache in `:core:common` (§1.3).
5. **Reader hook ownership** → **W4 owns every reader/viewer file**; W2 owns only the dictionary UI +
   `ReaderDictionaryHost` seam + its GPL impl. W2 no longer edits `ReaderActivity.kt` (02 §8.2 amended);
   W4 performs the single overlay insertion. **Why:** one editor per upstream file per wave.
6. **`:dictionary` module** (02 §1.3) → **dropped**; collapses into `:core:dictionary` + `:app`. **Why:**
   two modules keep the single GPL module the checkable boundary.
7. **Settings/i18n contention** → `SettingsMainScreen.kt`, `SettingsSearchScreen.kt`,
   `i18n-kmk/.../base/strings.xml`, and `Notifications.kt` are **single-editor (W2)**; W3/W4/W5 supply
   lists/specs. `SettingsOcrScreen.kt` is created by W3 in the **W2 wave** so W2 can register it in W3.
   **Why:** avoids copy/paste merge conflicts across waves.
8. **ODC "007"**: `webgpuviewer` needs no *JitPack* line (JitPack was already configured), but JitPack cannot
   build the fork, so a **local Maven repository line** (`maven { url = uri("$rootDir/maven") }`, §1.6) is
   required after all. 04 §3.4's "add `maven("https://jitpack.io")`" step is still a no-op.
9. **DI contention** → `AppBindings.kt` is **W1-only/frozen**; all feature bindings go to workstream-owned
   `@BindingContainer`s or `@ContributesBinding` (§3.6). **Why:** W2/W3 run in parallel and previously both
   edited `AppBindings.kt` (B4).
10. **`Mokuro*`** → GPL models/parser live in `:core:ocr` (§3.3); only a clean-room generic HTTP cache
    interceptor stays in Apache `:core:common` (§3.4). **Why:** GPL-derived code must not sit in a shared
    upstream module (reviewer S5).

## 7. Plan overrides (contract beats 01–05 here)

Where an implementer reads a plan that contradicts this file, follow the contract:

1. **01** — `:core:ocr` is **GPL**, not Apache (§6.1); seams in `:app` (§1.2); `:engine`/`:dictionary`
   modules and `DictPopup`-in-module dropped (§1.1, §6.6); `-DHOSHIDICTS_BUILD_TESTS` →
   `HOSHIDICTS_BUILD_CLI`/`_BENCHMARKS` (§1.6); delete the `probeEntryTypes` blob-walk task (§2.1);
   `@Binds` in `AppBindings.kt` → `@BindingContainer`s (§3.6).
2. **02** — `lookup(32, 0)` → 20/25 (§2.1); GPL impl `mihon.dictionary.host` → `mihon.dictionary.impl`
   (§1.2); `@Provides` in `AppBindings.kt` → `DictionaryHostBindings.kt` (§3.6).
3. **03** — license resolved GPL (§6.1); Paddle ABI adds **x86_64** + patch `supportedAbi()` (§1.5/§1.7);
   `ReaderOcrSource` → **`:core:common`** (§1.3); `OcrLookupRequest` → `DictionaryLookupRequest` (§2.3);
   `Mokuro*` → `:core:ocr` (§6.10).
4. **04** — `OcrViewport` → `:app` seam pkg (§1.2); **`blocksFor(page)`/`OcrOverlay(blocks)` dropped** —
   the seam exposes only `Overlay(viewport, modifier)` + `onPageLongPress(...): Boolean` and `OcrOverlay.kt`
   is GPL (§1.3/§2.3); "add jitpack.io"/new ref name → no-op, reuse `version.ref = "webgpuviewer"` (§2.6).
5. **05** — corpus → `:core:dictionary` tests (§3.5); Anki `SettingsAnkiScreen` path + manifest additions
   → W5 (§3.1).

## 8. Open questions (maintainer)

1. **armv7 engine build** — if `hoshidicts` on `armeabi-v7a` is unstable, drop `armeabi-v7a` from *both*
   native artifacts and the splits (fallback in §1.5). Confirm device priority.
2. **Import progress** — ship coarse phases (frozen) now, or patch a C progress callback into the
   `hoshidicts` fork now? (01 §9-R4.)
3. **Paddle fine-tune — resolved.** Only the official PaddleOCR PP-OCRv6 `small` models ship; the
   `manga_det/rec_fp16` fine-tune is not used (03 §7.2-R1).
4. **Anki scope** — Phase 6 remains last; confirm the Yomitan-canonical marker catalog + `// KUN` extras
   decision (05 §B2).
5. **AnkiDroid `:core:dictionary` dependency** — `com.ichi2.anki:api` is GPL-3.0; keep it optional/Phase 6.
6. **`webgpuviewer` pin — resolved**: tag `ocr-accessors-1` (`792aef3…`); coordinate
   `com.github.komi-kun.webgpuviewer:library:ocr-accessors-1`. JitPack was verified **unable** to build the
   fork (AGP `9.4.1` plugin markers don't resolve there), so the artifact is vendored in an in-repo Maven
   repository (`maven/`, §1.6) — resolution, `:app:compileDebugKotlin`, and `spotlessCheck` all green.
7. **`armeabi-v7a` Paddle/engine build cost** — the fork is host-verified on GCC/Clang; confirm the Android
   armv7 cross-builds (both artifacts) before locking §1.5.
