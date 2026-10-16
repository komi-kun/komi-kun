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
