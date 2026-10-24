# :core:dictionary

GPL-3.0-or-later. The vendored `hoshidicts` dictionary engine (nested git submodule + JNI) and
the Kotlin dictionary facade (`mihon.core.dictionary`). Build wiring and the native toolchain are
pinned per the implementation contract (`docs/design/implementation/00-implementation-contract.md`).

## Pins

| Item | Pin |
|---|---|
| `core/dictionary/src/main/cpp/hoshidicts` | `komi-kun/hoshidicts` @ **`62fc9fa16c3c4aac4b7ad06302af86cca09ada9a`** (branch `komi-kun/ndk29-build-guards`) |
| hoshidicts nested externals (8) | `git submodule update --init --recursive` |
| NDK | `29.0.14206865` (`AndroidConfig.NDK_VERSION`) |
| CMake | `3.22.1` (`AndroidConfig.CMAKE_VERSION`) |
| ABIs | `arm64-v8a`, `armeabi-v7a`, `x86_64` |
| C++ | C++23, `-DANDROID_STL=c++_shared`, `-Oz`, `-Wl,-z,max-page-size=16384` |

The submodule is never tracked on a branch — the commit above is the source of truth.

## Layout

- `src/main/cpp/CMakeLists.txt` — builds the vendored `hoshidicts` static library and links it into
  `hoshidicts_jni` (`HOSHIDICTS_BUILD_CLI`/`HOSHIDICTS_BUILD_BENCHMARKS` OFF).
- `src/main/cpp/hoshidicts_jni.cpp` — JNI bridge; `FindClass` strings must match the Kotlin model
  constructors in `mihon.core.dictionary.model` (contract §2.1/§3.5).
- `src/main/java/mihon/core/dictionary/engine/` — `DictionaryEngine`, `DictionarySession`
  (`DictionarySession.kt`), `HoshiDictionaryEngine`, `HoshiDictsNative`.
- `src/main/java/mihon/core/dictionary/model/` — JNI-mirrored records.
- `src/main/java/mihon/core/dictionary/di/DictionaryBindings.kt` — Metro `@BindingContainer`
  (registered in `AppGraph.bindingContainers`).

## Yomitan conformance (T1 + T2)

| Tier | Entry point | What it proves |
|---|---|---|
| T1 | `:core:dictionary:testDebugUnitTest` → `corpus/YomitanCorpusTest` | the committed corpus is intact (schema + `manifest.json` sha256/bytes/count) |
| T2 | `:core:dictionary:conformanceHostTest` → `corpus/YomitanEngineConformanceTest` | the **engine** meets the corpus **modulo the recorded gaps** (see below) |

```bash
./gradlew :core:dictionary:conformanceHostTest
```

T2 is a **host** run: the NDK `.so` cannot load in a desktop JVM, so
`scripts/build-host-conformance-lib.sh` compiles the same `hoshidicts` sources and
`hoshidicts_jni.cpp` with the host toolchain (cmake + a C++23 stdlib — `std::ranges::to` needs
**GCC ≥ 14**; the script feature-probes the compiler) into
`build/conformance/host-lib/lib/libhoshidicts_jni.so`, records the compiler/sha256/engine-source
digest in `BUILD_INFO.txt` next to it, and the test task puts that directory on `java.library.path`.
The runner deinflects every `ja-transforms.jsonl` tuple, runs every `ja-normalization.jsonl`
expectation, imports `lookup-ja.dict.jsonl` as a real Yomitan ZIP and asserts `lookup`/`query`
against `lookup-ja.jsonl`.

Staleness is impossible by construction: `src/main/cpp` (submodule included) is an input of the
engine build, the built library is an **input of the test task** (so changed engine bytes re-run the
corpus instead of reusing an `UP-TO-DATE` result), and `the executed engine matches the recorded
host build` asserts that the digest baked into the loaded library equals the build record and that
the file still hashes to it.

It is deliberately **not** part of `testDebugUnitTest` (that would make every unit-test run need a
host C++ toolchain); `testDebugUnitTest` excludes the class by name. PR CI runs it in the
path-filtered `conformance-t2` job of `.github/workflows/build_pull_request.yml`.

### Transform API

`engine/DictionaryTransforms.kt` is the dictionary-independent transform seam
(`HoshiDictsNative.deinflect` / `posToConditions` / `textVariants`, `internal`). Its KDoc is the
contract for the three non-obvious semantics the corpus has to map onto a C++ port:

- `trace` is in **application order**; Yomitan's fixtures list `reasons` reversed.
- `conditions` is the last rule's `conditionsOut` bitmask. `posConditions` **ignores** tags it does
  not know, so it returns a *partial* mask for a mixed list and `0` only when nothing maps
  (`v1d`); `0` therefore means "cannot be checked", never "matches anything".
- `textVariants` is a **set** (the engine fuses Yomitan's individual `japanese-util` ops into one
  pipeline, e.g. NFKC covers both CJK-compatibility and combining-mark normalisation).

### Known engine gaps

`src/test/resources/yomitan/engine-divergences-ja.json` lists every corpus row the engine cannot
meet today, with the gap that explains it. The T2 test re-derives all verdicts and fails if a
failure is unlisted **or** if a listed row starts passing (stale exemption). Current gaps:

| Gap | Rows |
|---|---|
| historical-kana source variants (は行/ひ/ふ/ゐ/やう/ませう/さう) absent from the rule table | 93 |
| `-やしない` transform group absent (やしない / やしません / やしない+た) | 35 |
| `-ふ` transform group absent | 2 |
| `Deinflector::deinflect` returns only the identity for 1-code-point input | 4 |
| `katakana_to_hiragana` has no `keepProlongedSoundMarks` mode | 1 |
