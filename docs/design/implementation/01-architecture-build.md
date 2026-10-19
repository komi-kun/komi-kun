# 01 — Architecture & Build: vendored dictionary engine + OCR modules

Status: **implementation plan (planning only)** · Branch: `feat/introduce-ocr-and-yomitan` · Owner: architecture/build workstream
Consumers of this doc: dictionary-UI agent, OCR agent, reader-integration agent, tests agent.
Reads from: `docs/design/yomitan-manga-ocr-port.md`, `docs/design/yomitan-spec/*`, `AGENTS.md`.

> **This document defines the shared contracts other workstreams code against (§3, §6).**
> It does not implement code; every path below is a plan.

---

## 0. Scope, assumptions, and one contradiction to flag

**In scope:** Gradle module layout, vendoring `hoshidicts`, the Kotlin↔JNI contract, Metro DI wiring,
build/CI/packaging/licensing, and a file-ownership/interface index.

**Out of scope here (owned by other agents):** popup DOM/CSS details, OCR box-merging algorithm,
reader coordinate math, `.mokuro` semantics, Anki field mapping.

**Fixed decisions assumed:** combined work ships **GPL-3.0**; **vendor `hoshidicts`** (no rewrite);
OCR = **PaddleOCR + `.mokuro`** (no Lens, no camera/screen-OCR/widgets); every komi-kun hunk wrapped
`// KUN -->` … `// KUN <--`; new strings only in `i18n-kmk` base (`KMR`).

**Contradiction flagged — import progress.** `yomitan-manga-ocr-port.md` §5.3 sketches a Rust
`ImportProgress`/`on_progress` callback. The C++ `dictionary_importer::import(zip, out, low_ram)`
(`hoshidicts/include/hoshidicts/importer.hpp:19`) is **synchronous and reports no progress**, and
Chimahon's JNI runs it on a 16 MB-stack `pthread` and joins (`hoshidicts_jni.cpp:296-325`). So the
facade's `importDictionary(..., onProgress)` must either (a) report **coarse Kotlin-side phases**
(queued → importing → done) with an indeterminate spinner, or (b) require a **small patch to the
vendored fork** to add a C callback. **Recommendation:** ship (a) in Phase 1, add (b) later if the
import UX needs a real bar (see §3.4, §9-R4).

**Second flag — `hoshidicts` has an MIT branch.** `hoshidicts/README.md:5` says a MIT version lives on
`main-mit`, but the Japanese deinflection rules it embeds are still Yomitan (GPL-3.0) derived. We
vendor **`main` (GPL-3.0)** per the licensing decision; do **not** switch to `main-mit` assuming it
makes the combined work Apache-clean.

---

## 1. Module layout

### 1.1 Decision: **two new modules**, split by license boundary

| Module | Path | Namespace / package root | License | Why a separate module |
|---|---|---|---|---|
| `:core:dictionary` | `core/dictionary/` | `mihon.core.dictionary` | **GPL-3.0** | Contains the vendored GPL engine, its JNI, the ported Chimahon Kotlin, and the GPL Yomitan renderer assets. Single GPL "blast radius". |
| `:core:ocr` | `core/ocr/` | `mihon.core.ocr` | ~~Apache-2.0~~ → **GPL-3.0-or-later** | PaddleOCR + `.mokuro` + box-merge + overlay math were planned as **GPL-free** → this module (and the reader hunks that use it) would stay upstream-contributable. Must **not** import `:core:dictionary`. |

> **Superseded (2026):** this plan predates the OCR backend migration. The module described here now
> runs **ONNX Runtime + PP-OCRv6 `small`** rather than a Paddle-Lite `.nb` shim; the current contract is
> `00-implementation-contract.md` §1.7 and the migration record is `ort-v6-migration.md`. The module
> layout and the GPL-free boundary below still hold — but the **`:core:ocr` licence row above does
> not**: the module is **GPL-3.0-or-later**, not Apache-2.0 (`00-implementation-contract.md`
> §1.1/§1.3 and §6.1); only the seam files stay Apache-2.0. Persistent OCR cache is keyed with `v8`
> generation (`OcrManager.kt`), thresholds include `DEFAULT_REC_FLOOR = 35` (`OcrPreferences.kt`), and
> `PaddleOcrEngine` declares `nativeGetOrtError()` as a forward-compatible stub.

Rationale:
- **Engine wrapper vs OCR split** mirrors the real license split, not just code size. The design
  doc's hard boundary (§4.2) is easiest to enforce with *two modules and one dependency rule*:
  `:core:ocr ∌ :core:dictionary`.
- `:core:*` matches komi-kun's existing grouping (`core:common`, `core:archive`, `core:metro`) and
  gives typed accessors `projects.core.dictionary` / `projects.core.ocr`.
- Cross-module rule for other agents: **OCR/reader code depends on `:core:ocr`; only dictionary UI /
  settings / Anki code depends on `:core:dictionary`.** A one-way arrow only.

### 1.2 Comparison with Chimahon

| Chimahon | komi-kun plan | Reason for the change |
|---|---|---|
| `:chimahon` — one 40k-LOC module: dictionary + Anki + audio + novel + OCR glue + reader screens | Split `:core:dictionary` (GPL) | Drop novel/anime/audio/screens; keep the GPL boundary in one small module |
| `:chimahon-local-ocr` — proprietary on-device Lens (dropped) | **dropped**; `:core:ocr` (Apache) holds PaddleOCR instead | PaddleOCR is Apache-2.0, no proprietary `.so` |
| engine nested in `chimahon/src/main/cpp/hoshidicts` | submodule at `core/dictionary/src/main/cpp/hoshidicts` | same relative-path simplicity, keeps submodule inside its module |
| Injekt `Injekt.get<T>()` + `AppModule.kt` | Metro `@BindingContainer` + `@Inject` | komi-kun uses Metro (see §4) |
| namespace `mihon.chimahon` | `mihon.core.dictionary` / `mihon.core.ocr` | komi-kun package roots |

### 1.3 Package layout inside the modules

```
core/dictionary/src/main/java/mihon/core/dictionary/
  engine/       HoshiDictsNative.kt, DictionaryEngine.kt, HoshiDictionaryEngine.kt, DictionarySession.kt
  model/        ImportResult.kt, TermResult.kt, LookupResult.kt, KanjiResult.kt, DictionaryStyle.kt, …
  repository/   DictionaryRepository.kt, DictionaryPaths.kt, DictionaryStore.kt
  anki/         AnkiDroidBridge.kt, AnkiCardCreator.kt, AnkiProfile*.kt         (Phase 6)
  di/           DictionaryBindings.kt
  ui/           DictionaryPopup.kt (Compose host for the WebView)             (Phase 2)
core/dictionary/src/main/assets/dictionary/  index.html, renderer.js, base.css

core/ocr/src/main/java/mihon/core/ocr/
  OcrEngine.kt, OcrResult.kt, EngineLine.kt, NormalizedBBox.kt
  mokuro/       MokuroParser.kt, MokuroModels.kt, MokuroPageResolver.kt, MokuroConverter.kt
  merge/        LineMerger.kt
  overlay/      OcrTextOverlayPainter.kt, OcrHitTester.kt, OcrViewport.kt
  paddle/       PaddleOcrEngine.kt, OcrBitmapDecoder.kt, OcrPreprocessor.kt
  di/           OcrBindings.kt
core/ocr/src/main/jniLibs/<abi>/libpaddle_ocr.so + libonnxruntime.so + libomp.so   (fetched artifact — see §5.4)
```

---

## 2. Vendoring `hoshidicts`

### 2.1 Submodule

- **Fork:** `https://github.com/komi-kun/hoshidicts` (already exists; local clone at
  `/tmp/opencode/hoshidicts` is on `main` @ `156f586a5bc67d72e5e6b315e84464719415583c`, which already
  carries the NDK-29 `#embed` fix).
- **Path:** `core/dictionary/src/main/cpp/hoshidicts`
- **Pin:** pin an exact commit/tag (start at `156f586`); never track a moving branch. Record the pin in
  `AGENTS.md`/the module README.
- Add to root `.gitmodules` (root currently has **no** submodules):

```gitconfig
[submodule "core/dictionary/src/main/cpp/hoshidicts"]
	path = core/dictionary/src/main/cpp/hoshidicts
	url = https://github.com/komi-kun/hoshidicts.git
```

### 2.2 Pulling `hoshidicts`' own `external/` submodules

`hoshidicts/.gitmodules` lists 8 nested submodules: `utfcpp`, `glaze`, `zstd`,
`unordered_dense`, `xxHash`, `libdeflate`, `utf8proc`, `kanji-processor`.

- Fetch recursively: `git submodule update --init --recursive` (or `submodules: recursive` in CI
  checkout — see §5.3).
- **Fix the fork's `.gitmodules`:** `external/utf8proc` uses an **SSH** URL
  (`git@github.com:JuliaStrings/utf8proc.git`, `hoshidicts/.gitmodules:21`). Change it to
  `https://github.com/JuliaStrings/utf8proc.git` in `komi-kun/hoshidicts`, or CI auth fails. **Do
  this in the fork first**, then pin.
- The `kanji-processor` submodule feeds a generated `include/kanji_full_list.h`; the `156f586` commit
  replaced a `#embed` with that generated header, so `kanji-processor` is only needed to *regenerate*
  the header. Still init it recursively for reproducibility.

### 2.3 CMake / NDK config for AGP 9.4 (externalNativeBuild)

Use **`externalNativeBuild` + CMake** (source build), not a prebuilt `.so` — mirrors Chimahon and
keeps ABI/16 KB correctness under AGP's control.

- **NDK:** pin `ndkVersion` explicitly; use **r29** (`29.0.14206865`, installed locally) or **r28.2**.
  C++23 (`glaze`) needs a recent clang; r28.2/r29 both work. Add `NDK_VERSION`/`CMAKE_VERSION` to
  `AndroidConfig.kt` so the module and CI agree (see §5).
- **CMake:** pin `version = "3.22.1"` in `externalNativeBuild.cmake`.
- **ABIs:** `abiFilters += listOf("arm64-v8a", "x86_64")` (Chimahon parity). See §5.5 for the 32-bit
  split consequence.
- **STL:** `-DANDROID_STL=c++_shared` (one shared `.so` here, but keeps the option open and avoids
  surprises if more native libs appear).
- **16 KB pages:** NDK r28+ defaults to 16 KB-aligned shared libs and AGP 9 packages them; ensure
  `useLegacyPackaging = false` (AGP default) and add `-Wl,-z,max-page-size=16384` to be explicit.
- **Size flags:** `-Oz` / `CMAKE_BUILD_TYPE=Release`, LTO off for the first bring-up.
- **Patch the fork's top-level CMake:** guard the `hoshidicts-cli` and `benchmark-*`
  `add_executable` targets behind `if(HOSHIDICTS_BUILD_CLI)` / `HOSHIDICTS_BUILD_TESTS)`; otherwise
  AGP cross-compiles Android executables we never ship.
- **Debug symbols:** keep an unstripped `.so` for `ndk-stack`; add `libhoshidicts_jni` to the app
  `packaging.jniLibs.keepDebugSymbols` list (see §5.6).

### 2.4 Gradle DSL sketch

`core/dictionary/build.gradle.kts`:

```kotlin
import mihon.buildlogic.AndroidConfig

plugins {
    id("mihon.library")
    id("mihon.library.compose")     // only because ui/ hosts the popup host (Phase 2)
}

android {
    namespace = "mihon.core.dictionary"
    ndkVersion = AndroidConfig.NDK_VERSION      // e.g. "29.0.14206865"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_STL=c++_shared",
                    "-DHOSHIDICTS_BUILD_CLI=OFF",
                    "-DHOSHIDICTS_BUILD_TESTS=OFF",
                )
                cppFlags += "-Oz"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = AndroidConfig.CMAKE_VERSION   // "3.22.1"
        }
    }

    packaging {
        jniLibs {
            // keep unstripped libhoshidicts_jni.so for ndk-stack; set keepDebugSymbols here or in :app
        }
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(libs.metro.runtime)
    implementation(platform(kotlinx.coroutines.bom))
    implementation(kotlinx.coroutines.core)
    implementation(kotlinx.serialization.json)
    implementation(compose.foundation)   // popup host (Phase 2)
    // AnkiDroid API added in Phase 6 (see §5.1)
}
```

`core/dictionary/src/main/cpp/CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(hoshidicts_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(HOSHIDICTS_BUILD_CLI OFF CACHE BOOL "")
set(HOSHIDICTS_BUILD_TESTS OFF CACHE BOOL "")
add_subdirectory(hoshidicts)

add_library(hoshidicts_jni SHARED hoshidicts_jni.cpp)
target_link_libraries(hoshidicts_jni PRIVATE hoshidicts)
```

### 2.5 R8 / `consumer-rules.pro`

JNI `FindClass`/`GetMethodID` resolve classes by **exact binary name + signature**, so R8 must keep
the whole model + engine package (renamed from Chimahon's `chimahon.**`):

```proguard
# JNI in libhoshidicts_jni.so looks up these classes by exact name/signature.
-keep class mihon.core.dictionary.engine.** { <init>(...); *; }
-keep class mihon.core.dictionary.model.**  { <init>(...); *; }
-keepclassmembers class mihon.core.dictionary.engine.** { <init>(...); *; }
-keepclassmembers class mihon.core.dictionary.model.**  { <init>(...); *; }
-keepattributes Signature
-keepattributes *Annotation*
-keep class androidx.annotation.Keep
```

Also annotate the JNI-constructed records with `@Keep` as belt-and-braces. **The C++ `FindClass`
strings must be updated in the same change** (see §3.5).

---

## 3. Kotlin facade + JNI contract **(the shared contract)**

Two layers, deliberately separated so the app never touches raw handles and the engine stays
swappable:

1. **`HoshiDictsNative`** — thin `object` with the `external fun`s (1:1 with JNI). `internal`.
2. **`DictionaryEngine` / `DictionarySession`** — stable Kotlin facade that Metro injects and other
   workstreams call.

### 3.1 Model records (mirror the JNI, add what hoshidicts exposes but Chimahon dropped)

```kotlin
package mihon.core.dictionary.model

data class ImportResult(
    val success: Boolean, val title: String,
    val termCount: Long, val metaCount: Long, val freqCount: Long,
    val pitchCount: Long, val kanjiCount: Long, val mediaCount: Long,
    val errors: List<String> = emptyList(),
)
data class DictionaryStyle(val dictName: String, val styles: String)
data class Frequency(val value: Int, val displayValue: String)
data class GlossaryEntry(val dictName: String, val glossary: String,
                         val definitionTags: String, val termTags: String)
data class FrequencyEntry(val dictName: String, val frequencies: List<Frequency>)
data class PitchEntry(val dictName: String, val pitchPositions: IntArray,
                      val transcriptions: List<String>)
data class TransformGroup(val name: String, val description: String)
data class TermResult(val expression: String, val reading: String, val rules: String,
                      val score: Int,
                      val glossaries: List<GlossaryEntry>,
                      val frequencies: List<FrequencyEntry>,
                      val pitches: List<PitchEntry>)
data class LookupResult(val matched: String, val deinflected: String,
                        val process: List<TransformGroup>,
                        val term: TermResult, val preprocessorSteps: Int)
data class KanjiEntry(val dictName: String, val onyomi: String, val kunyomi: String,
                      val tags: String, val definitions: List<String>,
                      val stats: Map<String, String>)
data class KanjiResult(val character: String, val entries: List<KanjiEntry>)

data class DictionaryPaths(
    val termPaths: List<String> = emptyList(),
    val freqPaths: List<String> = emptyList(),
    val pitchPaths: List<String> = emptyList(),
    val kanjiPaths: List<String> = emptyList(),
)
data class EntryTypeCounts(val term: Long, val freq: Long, val pitch: Long, val kanji: Long)
```

These are exactly Chimahon's `chimahon/HoshiDicts.kt` records **plus** `kanjiCount`/`errors`
(import result) and `KanjiEntry`/`KanjiResult`/`score`/`transcriptions` (which hoshidicts already
returns in `query.hpp` but Chimahon's JNI omitted). Arrays (`IntArray`, `ByteArray`) stay arrays.

### 3.2 JNI externs (low level)

```kotlin
package mihon.core.dictionary.engine

internal object HoshiDictsNative {
    init { System.loadLibrary("hoshidicts_jni") }

    external fun createSession(): Long
    external fun destroySession(session: Long)
    external fun rebuildQuery(
        session: Long,
        termPaths: Array<String>, freqPaths: Array<String>,
        pitchPaths: Array<String>, kanjiPaths: Array<String>,
    )
    external fun importDictionary(zipPath: String, outputDir: String): ImportResult
    external fun probeEntryTypes(dictPath: String): LongArray            // [term, freq, pitch, kanji]
    external fun lookup(session: Long, text: String, maxResults: Int, scanLength: Int): Array<LookupResult>
    external fun query(session: Long, text: String): Array<TermResult>
    external fun queryKanji(session: Long, character: String): KanjiResult
    external fun getStyles(session: Long): Array<DictionaryStyle>
    external fun getMediaFile(session: Long, dictName: String, mediaPath: String): ByteArray?
}
```

> `probeEntryTypes` returns 4 longs now (add `kanji`). Chimahon returns 3
> (`hoshidicts_jni.cpp:376-470`); extend the hand-written blob-walk to count `type==2`/`kanji`
> entries, or replace it with a tiny native `DictionaryQuery::query`-based probe.

### 3.3 Facade (what every other workstream codes against)

```kotlin
package mihon.core.dictionary.engine

interface DictionaryEngine {
    /** True once the native lib loaded. Callers must degrade gracefully when false. */
    val isAvailable: Boolean

    /** Long-running, CPU-heavy; import blocks on a 16 MB-stack native thread. Run on Dispatchers.IO. */
    suspend fun importDictionary(
        zip: java.io.File,
        outputDir: java.io.File,
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportResult

    fun probeEntryTypes(dictDir: java.io.File): EntryTypeCounts
    fun openSession(paths: DictionaryPaths): DictionarySession
}

/** Coarse phases only — the native importer reports no counters (§0). */
sealed interface ImportProgress {
    data object Started : ImportProgress
    data class Finished(val result: ImportResult) : ImportProgress
}

interface DictionarySession : AutoCloseable {
    fun lookup(text: String, maxResults: Int = 20, scanLength: Int = 25): List<LookupResult>
    fun query(text: String): List<TermResult>
    fun queryKanji(character: String): KanjiResult
    fun styles(): List<DictionaryStyle>
    fun mediaBytes(dictName: String, path: String): ByteArray?
    /** Rebuild the query from a new dictionary set; cheap no-op when paths are unchanged. */
    fun rebuild(paths: DictionaryPaths)
}
```

**Threading rule (contract for callers):** a `DictionarySession` wraps **one non-thread-safe native
handle**. `HoshiDictionaryEngine` serialises all session calls (a `synchronized`/single-thread
dispatcher) and the repository owns exactly one long-lived session, mirroring Chimahon's
`@Synchronized DictionaryRepository` (`DictionaryRepository.kt:32-136`). Don't share a session across
threads; open one per configured dictionary set.

`HoshiDictionaryEngine(context: Context)` resolves `isAvailable` by catching
`UnsatisfiedLinkError` on `System.loadLibrary` (so 32-bit ABIs without the engine still run the app).
`HoshiDictsNative` must therefore move `System.loadLibrary` out of a hard `init {}` into a guarded
initialiser.

### 3.4 Import execution (C++ side)

Port `hoshidicts_jni.cpp` nearly verbatim, changing only package strings and adding
`queryKanji`/kanji paths. Key facts to preserve:

- `importDictionary` runs on a **16 MB-stack `pthread`** because the radix sort overflows the default
  stack (`hoshidicts_jni.cpp:303-317`). Keep this — do **not** call `dictionary_importer::import` on
  the JNI thread.
- `DictionaryQuery` is replaced wholesale on `rebuildQuery`; recreate the `Lookup` (it holds a
  reference to the query). Chimahon does this at `hoshidicts_jni.cpp:281-294`.
- Convert `jlong handle ↔ LookupObject*` via `reinterpret_cast`, as today.

### 3.5 C++ string de-risking (must-match)

All `FindClass`/`GetMethodID` literals move from `chimahon/*` to
`mihon/core/dictionary/model/*` (records) and the JNI class to
`mihon/core/dictionary/engine/HoshiDictsNative`. Constructor signatures change exactly when the
record fields do — e.g. `ImportResult` becomes `(ZLjava/lang/String;JJJJJLjava/util/List;)V`,
`LookupResult` keeps `[Lmihon/core/dictionary/model/TransformGroup;` etc. **Generate these strings
from the Kotlin data classes** and add a unit test asserting signature strings (see §6.4) so a field
change can't silently break JNI at runtime.

---

## 4. Metro DI registration

komi-kun is **Metro**, not Injekt (`AGENTS.md`, `AppGraph.kt`, `AppBindings.kt`).

- Put a `@BindingContainer` in each new module so `:app`'s `AppBindings.kt` (an upstream file) stays
  **GPL-free**:

  ```kotlin
  // core/dictionary/src/main/java/mihon/core/dictionary/di/DictionaryBindings.kt
  package mihon.core.dictionary.di

  @BindingContainer
  object DictionaryBindings {
      @Provides @SingleIn(AppScope::class)
      fun providesDictionaryEngine(context: Context): DictionaryEngine = HoshiDictionaryEngine(context)
  }
  ```

- Register the containers in `AppGraph` (one `// KUN`-wrapped edit) and add the app inject targets
  needed by the feature (e.g. reader/dictionary screens via `metroViewModel`, no activity inject
  needed unless a GPL-only Activity is added):

  ```kotlin
  @DependencyGraph(
      scope = AppScope::class,
      bindingContainers = [
          AppBindings::class,
          // KUN -->
          mihon.core.dictionary.di.DictionaryBindings::class,
          mihon.core.ocr.di.OcrBindings::class,
          // KUN <--
      ],
  )
  interface AppGraph : ViewModelGraph { /* … */ }
  ```

- **Consumption:**
  - ViewModels/screens: constructor `@Inject` (or `metroViewModel { }`) taking `DictionaryEngine`,
    `DictionaryRepository`, `OcrEngine`.
  - Reader/UI in `:app` that must stay Apache (**§6.2**) depends only on the *interfaces* listed in
    §6.1, never on the GPL classes directly.
- `:core:dictionary` and `:core:ocr` need `libs.metro.runtime`; `AppScope`/`@Provides`/`@SingleIn`
  come from Metro. `:core:ocr` must **not** be in the same edited file as GPL imports.
- Comment in `AGENTS.md`’s module table + architecture section must be updated in the same PR (the
  guide is living documentation — `AGENTS.md` "Keep this guide current").

---

## 5. Build / CI / packaging

### 5.1 Version catalog + `settings.gradle.kts`

`settings.gradle.kts` (add near the other `core:*` includes):
```kotlin
// KUN -->
include(":core:dictionary")
include(":core:ocr")
// KUN <--
```

`gradle/libs.versions.toml` additions (native engine is **not** a Maven artifact — the submodule is,
so only JVM deps go here):
```toml
[versions]
# KUN -->
anki-api = "<pin>"            # com.ichi2.anki:api (GPL-3.0) — Phase 6 only
paddle-ocr = "<pin>"          # only if PaddleOCR ships as an AAR rather than jniLibs
webgpuviewer = "52"           # unchanged; fork pin handled by the reader workstream
# KUN <--

[libraries]
# KUN -->
anki-api = { module = "com.ichi2.anki:api", version.ref = "anki-api" }
# KUN <--
```

### 5.2 `buildSrc` — shared toolchain constants

`buildSrc/src/main/kotlin/mihon/buildlogic/AndroidConfig.kt` (wrapped `// KUN`):
```kotlin
// KUN -->
const val NDK_VERSION = "29.0.14206865"   // or 28.2.13676358
const val CMAKE_VERSION = "3.22.1"
// KUN <--
```
Pin one NDK for both the module and CI.

### 5.3 `.github/workflows/` — NDK/CMake + submodules

The three build workflows (`build_pull_request.yml`, `build_push.yml`, `build_nightly.yml`) currently
checkout **without** submodules and have **no** NDK/CMake. Required edits:

1. Every `actions/checkout` that builds: add `submodules: recursive` (pulls `hoshidicts` + its 8
   externals).
2. After `setup-java` (JDK 21, `.github/.java-version`), provision SDK components — either
   `android-actions/setup-android@…` + `sdkmanager "ndk;$NDK_VERSION" "cmake;$CMAKE_VERSION"`, or a
   Gradle command that auto-provisions. Pin the NDK to `AndroidConfig.NDK_VERSION`.
3. Cache: Gradle already uses `gradle/actions/setup-gradle`; add a C/C++ build cache if build time
   hurts (CMake objects are per-ABI under `**/.cxx`). Optional: cache `~/.android`/`.cxx`.
4. `assembleNightly -Pdist=ci` and `testDebugUnitTest` then build the native libs automatically via
   `externalNativeBuild`. Spotless gate unchanged (native sources are not Kotlin/XML).

`checkout` with submodules is safe for authorized forks but beware: PRs from forks get no secrets, and
nested submodules must be public (they are).

### 5.4 PaddleOCR artifact (~~Apache-2.0~~ → GPL-3.0-or-later) — decision needed

> **Superseded (2026):** this section is kept as the historical decision record. The OCR backend later
> moved from Paddle-Lite to **ONNX Runtime + PP-OCRv6 `small`** (`ort-v6-migration.md`); option A was
> taken, and the artifact now ships `libpaddle_ocr.so` + `libonnxruntime.so` + `libomp.so` for
> `arm64-v8a`, `armeabi-v7a` and `x86_64`. The heading's **“Apache-2.0” is also superseded**:
> `:core:ocr` is **GPL-3.0-or-later** (`00-implementation-contract.md` §1.1/§1.3, §6.1). Option A
> itself still holds.

`:core:ocr` ships `libpaddle_ocr.so` + det/rec models under Apache-2.0. Options:
- **A (recommended for now):** `jniLibs/<abi>/libpaddle_ocr.so` committed via a pinned, checksummed
  artifact fetched by a Gradle `Exec`/`download` task; **models downloaded at runtime** (not in APK).
- **B:** build PaddleOCR in our own CMake/CI and publish an AAR to GitHub Packages.
Record the checksum + license in the OSS-licenses screen. This is the OCR analogue of the engine
decision (`yomitan-manga-ocr-port.md` §6.1) and is the **biggest open provenance item**.

### 5.5 ABIs, splits, APK size

- Engine module: `arm64-v8a`, `x86_64` only.
- `:app` currently splits `armeabi-v7a, arm64-v8a, x86, x86_64` + universal
  (`app/build.gradle.kts:145-152`). With the engine present only on 64-bit, the 32-bit and universal
  APKs simply lack `libhoshidicts_jni.so`. Two acceptable paths:
  1. **Keep all splits + graceful degradation:** `HoshiDictionaryEngine.isAvailable=false` disables
     the feature on 32-bit (`UnsatisfiedLinkError` caught). Lowest blast radius.
  2. **Drop 32-bit splits** for this fork (arm64-v8a + x86_64 + universal only). Smaller CI matrix,
     but changes existing release artifacts.
  **Recommendation:** path 1 for Phase 1, revisit before Phase 4 packaging.
- Size: engine `.so` ≈ single-digit MB per ABI (glaze/zstd/libdeflate/utf8proc static); Paddle
  `.so` + models are the heavy part → models download at runtime, so APK growth is mostly the `.so`s.
  Ship per-ABI APKs (already the case) to avoid a universal-APK blow-up.

### 5.6 `:app` `build.gradle.kts` + packaging

- `dependencies { implementation(projects.core.dictionary); implementation(projects.core.ocr) }`
  (wrapped `// KUN`).
- `packaging.jniLibs.keepDebugSymbols += "**/libhoshidicts_jni.so"` (and Paddle's) alongside the
  existing list (`app/build.gradle.kts:155-166`).
- No manifest changes for the dictionary engine. Phase 6 Anki adds an AnkiDroid package-visibility
  `<queries>` entry + FileProvider (`// KUN`).

### 5.7 Licensing artifacts

- Root `LICENSE` is Apache-2.0 today; the **combined distribution is GPL-3.0**. Add
  `LICENSES/GPL-3.0-or-later.txt` (full text) and a `NOTICE` describing the combined work + the GPL
  components (hoshidicts, Yomitan renderer/deinflection, AnkiDroid API). Keep the Apache `LICENSE`
  for Apache files.
- Per-file SPDX headers: new GPL files `// SPDX-License-Identifier: GPL-3.0-or-later`; Apache files
  unchanged. Existing upstream files keep their Apache headers; changed hunks are `// KUN`-wrapped.
- Add the engine + externals to the OSS-licenses screen (aboutLibraries) — Chimahon ships
  `hoshidicts/LICENSE`; replicate via `aboutLibraries` config or an asset.
- **Never** let `:core:ocr` (Apache) import `:core:dictionary` (GPL); add a lightweight CI check
  (grep the module's dependency graph / forbidden import) as a guard.

---

## 6. File-ownership map + interface index (for the other planning agents)

### 6.1 Interface index — who calls what

| Interface / type | Module : package | Used by |
|---|---|---|
| `DictionaryEngine`, `DictionarySession`, `ImportProgress` | `:core:dictionary` `mihon.core.dictionary.engine` | dictionary-UI, Anki, tests |
| `ImportResult`, `TermResult`, `LookupResult`, `KanjiResult`, `DictionaryStyle`, `GlossaryEntry`, `FrequencyEntry`, `PitchEntry`, `TransformGroup`, `DictionaryPaths`, `EntryTypeCounts` | `:core:dictionary` `mihon.core.dictionary.model` | dictionary-UI, Anki, reader-integration (via gateway) |
| `DictionaryRepository` | `:core:dictionary` `mihon.core.dictionary.repository` | dictionary-UI, settings |
| `DictionaryGateway` *(Apache interface, GPL-free)* | `:domain` `tachiyomi.domain.dictionary` | reader-integration (stays Apache) |
| `DictionaryPopupProvider` *(Apache interface, `@Composable`)* | `:domain` or `:presentation-core` | reader-integration |
| `OcrEngine`, `EngineLine`, `NormalizedBBox`, `OcrResult` | `:core:ocr` `mihon.core.ocr` | OCR, reader-integration |
| `OcrViewport`, `OcrTextOverlayPainter`, `OcrHitTester` | `:core:ocr` `mihon.core.ocr.overlay` | reader-integration |
| `MokuroParser`, `MokuroModels`, `MokuroPageResolver` | `:core:ocr` `mihon.core.ocr.mokuro` | OCR, reader-integration |
| `LineMerger` | `:core:ocr` `mihon.core.ocr.merge` | OCR |
| `ReaderOcrHost` *(Apache interface)* | `:core:ocr` | reader-integration |

### 6.2 Reader hooks (referenced, owned by reader-integration agent — verified paths)

- Overlay surface: `ReaderActivity.setComposeOverlay()` (`ReaderActivity.kt:309`), layout
  `reader_activity.xml:27` (`compose_overlay`, above `viewer_container`).
- Long-press routing: `ReaderActivity.onPageLongTap(page, extraPage?)` (`ReaderActivity.kt:1165`);
  pager `Pager.kt:52`/`PagerViewer.kt:144`; webtoon `WebtoonViewer.kt:155`; WebGPU
  `WebGpuViewer.kt:968-970` (currently discards the normalised offset — must be retained).
- Settings: `app/.../presentation/more/settings/screen/Settings*Screen.kt` + register in
  `SettingsMainScreen.kt`/`SettingsSearchScreen.kt`; per-manga OCR prefs in
  `reader/setting/ReaderPreferences.kt`.

### 6.3 Files to **create**

`:core:dictionary`
- `core/dictionary/build.gradle.kts`, `consumer-rules.pro`, `src/main/AndroidManifest.xml`
- `core/dictionary/src/main/cpp/CMakeLists.txt`, `hoshidicts_jni.cpp`, `hoshidicts/` (submodule)
- `core/dictionary/src/main/java/mihon/core/dictionary/{engine,model,repository,anki,di,ui}/…`
- `core/dictionary/src/main/assets/dictionary/{index.html,renderer.js,base.css}`
- `core/dictionary/src/test/java/mihon/core/dictionary/…` (JVM) and
  `src/androidTest/…` (native smoke test)

`:core:ocr`
- `core/ocr/build.gradle.kts`, `src/main/AndroidManifest.xml`
- `core/ocr/src/main/java/mihon/core/ocr/{paddle,mokuro,merge,overlay,di}/…`
- `core/ocr/src/main/jniLibs/<abi>/libpaddle_ocr.so` (or artifact task)
- `core/ocr/src/test/java/…`

Apache interfaces: `domain/src/main/java/tachiyomi/domain/dictionary/DictionaryGateway.kt`,
`DictionaryPopupProvider.kt` (if the interface-boundary approach is chosen).

Licensing: `LICENSES/GPL-3.0-or-later.txt`, `NOTICE`.

### 6.4 Files to **modify** (all `// KUN`-wrapped)

- `settings.gradle.kts` — include both modules.
- `buildSrc/src/main/kotlin/mihon/buildlogic/AndroidConfig.kt` — `NDK_VERSION`, `CMAKE_VERSION`.
- `app/build.gradle.kts` — deps on `projects.core.dictionary`/`projects.core.ocr`; `keepDebugSymbols`.
- `app/src/main/java/mihon/app/di/AppGraph.kt` — `bindingContainers` (add the two containers).
- `gradle/libs.versions.toml` — Anki/Paddle pins.
- `.gitmodules` — new submodule; `.github/workflows/{build_pull_request,build_push,build_nightly}.yml`
  — `submodules: recursive` + NDK/CMake provisioning.
- `NOTICE` — combined-work description.
- `AGENTS.md` — module table, DI, build flags, licensing (living doc).
- (Phase 2) settings screens + `SettingsMainScreen.kt`/`SettingsSearchScreen.kt`; `i18n-kmk`
  base `strings.xml` (`KMR`).
- (Phase 3–5) reader files listed in §6.2.

---

## 7. Ordered task breakdown

| # | Task | Depends on | Output |
|---|---|---|---|
| 1 | Add submodule `core/dictionary/src/main/cpp/hoshidicts` → `komi-kun/hoshidicts` @ `156f586`; fix `utf8proc` SSH→HTTPS in the fork; init externals | — | `.gitmodules`, submodule |
| 2 | Patch fork: guard CLI/benchmarks; add `queryKanji`/kanji-path probe if missing | 1 | fork commits |
| 3 | Create `:core:dictionary` skeleton (build.gradle.kts, CMakeLists, manifest, `AndroidConfig` NDK/CMake, settings include) | 1,2 | module builds an empty `.so` |
| 4 | Port `hoshidicts_jni.cpp` with new package strings + kanji; add `HoshiDictsNative`, models | 3 | JNI compiles/links |
| 5 | `DictionaryEngine`/`HoshiDictionaryEngine`/`DictionarySession` facade + guarded `loadLibrary` | 4 | facade |
| 6 | `DictionaryBindings` + `AppGraph` edit; headless lookup test (androidTest) | 5 | Metro-wired engine, Phase-1 gate |
| 7 | `DictionaryRepository` + `DictionaryPaths` + `DictionaryStore` (ported, Metro-adapted) | 5 | repo |
| 8 | CI: `submodules: recursive` + NDK/CMake provisioning in the 3 workflows | 3 | green CI |
| 9 | Licensing: `LICENSES/GPL-3.0-or-later.txt`, `NOTICE`, SPDX headers, OSS screen | 4 | license-clean |
| 10 | Create `:core:ocr` skeleton (Apache, jniLibs placeholder) + `OcrBindings` | 3 | module |
| 11 | OCR Paddle artifact decision + fetch task (checksum/license) | 10 | `.so` source |
| 12 | Port/implement OCR glue (engine, mokuro, merge, overlay, viewport) | 10,11 | `:core:ocr` API |
| 13 | Apache gateway interfaces in `:domain`; wire adapters | 6,12 | boundary |
| 14 | Anki bridge (Phase 6) in `:core:dictionary` | 7 | optional |

Phase-1 acceptance: a headless instrumented test imports a sample Yomitan ZIP and returns a
`LookupResult` for `食べました` via `:core:dictionary` only (native build proven end to end).

---

## 8. Maintainability recommendations

1. **One contract file.** Keep §3.3 as the single source of truth; generate JNI signature tests so a
   record change fails loudly (§3.5).
2. **Never leak handles.** `Long` sessions stay `internal`; everything public is `DictionarySession`.
3. **Guard the license arrow with CI.** A check that `:core:ocr` (and `:domain`) never depend on
   `:core:dictionary` prevents accidental GPL leakage into upstream-bound files.
4. **Pin everything:** submodule commit, NDK, CMake, Paddle artifact checksum. Record pins in one
   README under `core/dictionary/`.
5. **Patch the vendored fork minimally and upstream-friendly:** every change to `komi-kun/hoshidicts`
   should be a small, documented commit (CLI guard, progress callback, utf8proc URL) so the fork stays
   rebasable on `Manhhao/hoshidicts`.
6. **Feature-flag the engine** (`isAvailable`) and keep a no-op path so 32-bit/unsupported ABIs and
   test environments degrade instead of crashing.
7. **Avoid touching `AppBindings.kt`** (upstream Apache) — put bindings in the new modules'
   `@BindingContainer`s and only edit `AppGraph.bindingContainers`.
8. **Update `AGENTS.md` in the same PR** (module table, DI, build flags, licensing).

---

## 9. Risks & unknowns

| # | Risk / unknown | Severity | Mitigation |
|---|---|---|---|
| R1 | **PaddleOCR `.so`+models provenance/checksums/hosting** — unresolved | High | Decide §5.4 before Phase 4; prefer Apache-2.0 source build or pinned checksummed artifact |
| R2 | **NDK/CI complexity** — new native toolchain in 3 workflows; nested submodules incl. an SSH URL | Med | Pin NDK/CMake; fix `utf8proc` URL in the fork; `submodules: recursive` |
| R3 | **C++23 + heavy templates** (`glaze`) build time / memory on CI | Med | `-Oz`, LTO off initially; cache `.cxx`; `-Xmx` tuning |
| R4 | **Import progress** has no native callback (§0) | Med | Ship coarse phases; add a C callback to the fork later |
| R5 | **JNI signature drift** when models change | Med | Generated/asserted signature strings + `@Keep` + consumer rules |
| R6 | **32-bit ABIs** lack the engine; universal APK too | Low | `isAvailable` degradation; decide split policy before Phase 4 |
| R7 | **License boundary erosion** (GPL import into Apache files) | High | Two modules + CI dependency check + SPDX headers |
| R8 | **`probeEntryTypes` blob-walk** is hand-written and format-coupled | Low | Extend for kanji or replace with a native probe |
| R9 | **AnkiDroid API is GPL-3.0** and adds a runtime dependency | Low | Keep in `:core:dictionary`; Phase 6; confirm with users |
| R10 | **Submodule pin drift / maintenance** of the fork vs upstream | Low | Minimal, documented fork commits; periodic rebase |
| R11 | **`hoshidicts` MIT branch** might tempt an Apache-only claim | Low | Keep GPL `main`; deinflection remains GPL-derived |

---

## 10. Open questions for the maintainer

1. `:core:ocr` as a **separate Apache module** (recommended) vs folding OCR into `:app`?
2. Drop 32-bit ABI splits, or keep them with graceful degradation (§5.5)?
3. Ship the Paddle `.so` via `jniLibs` + runtime models, or build a published AAR (§5.4)?
4. Confirm the interface-boundary approach for reader files (Apache interfaces in `:domain`) vs
   accepting reader files as GPL+`// KUN`?
5. Add the import-progress C callback to `komi-kun/hoshidicts` now, or later (§0)?
