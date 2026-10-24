#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# build-host-conformance-lib.sh — build the vendored `hoshidicts` engine for the *host* so the
# Yomitan T2 conformance runner can execute it on a plain JVM (no device, no emulator).
#
# Plan 05 §A1.5/A4: the Android `.so` is an NDK cross-build and cannot be loaded by a desktop JVM,
# so T2 compiles the same C++ sources with the host toolchain and the same `hoshidicts_jni.cpp`
# bridge, then puts it on `java.library.path` for `:core:dictionary:conformanceHostTest`.
#
# Usage: build-host-conformance-lib.sh <output-dir>
#
# Output layout (Gradle declares <output-dir>/lib as an *input* of the conformance test, so the
# engine binary is content-hashed into that task — a rebuilt engine can never reuse a cached pass):
#
#   <output-dir>/lib/libhoshidicts_jni.so|.dylib   the library the JVM loads
#   <output-dir>/lib/BUILD_INFO.txt              compiler/cmake versions + sha256 (CI evidence)
#   <output-dir>/cmake/                          host CMake build tree (not consumed by the JVM)
#
# Toolchain: cmake >= 3.22, a JDK with jni.h ($JAVA_HOME), and a C++23 standard library that
# implements `std::ranges::to` and `std::views::as_rvalue` (the vendored engine uses both
# unconditionally, e.g. `lookup.cpp`). libstdc++ only gained `std::ranges::to` in **GCC 14**
# (GCC 14 release notes, libstdc++ "std::ranges::to function for converting ranges to
# containers"; GCC 13 does not have it), so **GCC >= 14** — or Clang with libc++ >= 18 / libstdc++
# >= 14. The requirement is *feature-probed* below rather than version-parsed, so the error names
# the actual missing feature for whichever compiler is selected.
#
# Reproducibility: after linking, the script fails if any engine source is newer than the produced
# library, so a stale artifact can never be handed to the JVM even if a caller skips Gradle.
#
# Idempotent: Gradle owns up-to-date checking; a manual re-run just rebuilds. Switching `CXX` wipes
# the CMake cache automatically (a cached compiler cannot be changed in place).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CPP_DIR="$MODULE_DIR/src/main/cpp"
ENGINE_DIR="$CPP_DIR/hoshidicts"
SHIM="$CPP_DIR/hoshidicts_jni.cpp"

if [[ $# -ne 1 ]]; then
  echo "usage: $(basename "$0") <output-dir>" >&2
  exit 2
fi
OUT_DIR="$1"
LIB_DIR="$OUT_DIR/lib"
BUILD_DIR="$OUT_DIR/cmake"
COMPILER="${CXX:-c++}"

fail_toolchain() {
  echo "error: $*" >&2
  echo "       selected C++ compiler: $COMPILER" >&2
  "$COMPILER" --version 2>/dev/null | head -1 >&2 || true
  exit 2
}

if ! command -v cmake >/dev/null; then
  echo "error: cmake not found (required for the T2 host conformance build, >= 3.22)" >&2
  exit 2
fi
if ! command -v "$COMPILER" >/dev/null; then
  fail_toolchain "C++ compiler '$COMPILER' not found; set CXX (GCC >= 14 for std::ranges::to)"
fi

CMAKE="cmake"
if command -v ninja >/dev/null; then
  CMAKE_GENERATOR=(-G Ninja)
else
  CMAKE_GENERATOR=()
fi

mkdir -p "$LIB_DIR" "$BUILD_DIR"

# --- C++23 library feature probe (fast failure before the real build) -------------------------
# The vendored engine pipes ranges through `std::views::as_rvalue | std::ranges::to<std::vector>()`
# (src/lookup.cpp, src/importer.cpp). Both are C++23 library features; libstdc++ shipped
# `std::ranges::to` in GCC 14, so GCC 13 and older fail here instead of deep inside the build.
PROBE="$BUILD_DIR/probe_cxx23.cpp"
cat > "$PROBE" <<'PROBE_SRC'
#include <ranges>
#include <utility>
#include <vector>

int main() {
  std::vector<int> values{1, 2, 3};
  auto out = std::move(values) | std::views::as_rvalue | std::ranges::to<std::vector>();
  return static_cast<int>(out.size()) == 3 ? 0 : 1;
}
PROBE_SRC
if ! "$COMPILER" -std=c++23 -fsyntax-only "$PROBE" >"$BUILD_DIR/probe_cxx23.log" 2>&1; then
  echo "error: $COMPILER does not provide the C++23 library features the vendored engine uses" >&2
  echo "       (std::ranges::to + std::views::as_rvalue: GCC >= 14, or Clang with a >= 14 libstdc++)." >&2
  sed -n '1,15p' "$BUILD_DIR/probe_cxx23.log" >&2
  fail_toolchain "host toolchain too old for the T2 conformance build"
fi

# --- JDK include dirs (jni.h + jni_md.h) -----------------------------------------------------
if [[ -n "${JAVA_HOME:-}" && -f "$JAVA_HOME/include/jni.h" ]]; then
  JDK_INC="$JAVA_HOME/include"
else
  JAVA_BIN="$(command -v java || true)"
  if [[ -z "$JAVA_BIN" ]]; then
    echo "error: java not found; set JAVA_HOME so jni.h can be located" >&2
    exit 2
  fi
  JAVA_BIN="$(readlink -f "$JAVA_BIN")"
  JDK_INC="$(cd "$(dirname "$JAVA_BIN")/.." && pwd)/include"
fi
if [[ ! -f "$JDK_INC/jni.h" ]]; then
  fail_toolchain "jni.h not found under $JDK_INC (set JAVA_HOME)"
fi
if [[ ! -f "$JDK_INC/darwin/jni_md.h" && ! -f "$JDK_INC/linux/jni_md.h" ]]; then
  fail_toolchain "jni_md.h not found under $JDK_INC; unsupported host"
fi

# --- 0. CMake cache sanity when CXX changes ---------------------------------------------------
CACHE="$BUILD_DIR/CMakeCache.txt"
if [[ -f "$CACHE" ]]; then
  CACHED_CXX="$(sed -n 's/^CMAKE_CXX_COMPILER:FILEPATH=//p' "$CACHE" | head -1)"
  RESOLVED_CXX="$(command -v "$COMPILER" || true)"
  if [[ -n "$CACHED_CXX" && -n "$RESOLVED_CXX" ]]; then
    if [[ "$(readlink -f "$CACHED_CXX")" != "$(readlink -f "$RESOLVED_CXX")" ]]; then
      echo "info: C++ compiler changed ($CACHED_CXX -> $RESOLVED_CXX); recreating $BUILD_DIR"
      rm -rf "$BUILD_DIR"
      mkdir -p "$BUILD_DIR"
    fi
  fi
fi

# --- 1. hoshidicts static library (host toolchain) -------------------------------------------
"$CMAKE" -S "$ENGINE_DIR" -B "$BUILD_DIR" "${CMAKE_GENERATOR[@]}" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DCMAKE_C_COMPILER="${CC:-$(dirname "$(command -v "$COMPILER")")/gcc}" \
  -DCMAKE_CXX_COMPILER="$(command -v "$COMPILER")" \
  -DHOSHIDICTS_BUILD_CLI=OFF \
  -DHOSHIDICTS_BUILD_BENCHMARKS=OFF \
  >/dev/null
"$CMAKE" --build "$BUILD_DIR" --target hoshidicts --parallel "$(nproc 2>/dev/null || echo 4)"

# --- 2. JNI bridge over the static library ---------------------------------------------------
LIB_NAME="libhoshidicts_jni.so"
EXTRA_LIBS=(-lpthread)
if [[ "$(uname -s)" == "Darwin" ]]; then
  LIB_NAME="libhoshidicts_jni.dylib"
  EXTRA_LIBS=()
fi
LIB="$LIB_DIR/$LIB_NAME"

# Digest of the engine sources this library is compiled from. It is baked into the library
# (`engineBuildFingerprint`) and recorded in BUILD_INFO.txt, so the conformance runner can prove
# the loaded library belongs to this build record (see `YomitanEngineConformanceTest`).
SOURCE_FINGERPRINT="$(
  cd "$CPP_DIR" && find . -type f \( -name '*.cpp' -o -name '*.hpp' -o -name '*.h' -o -name '*.c' \) -print0 |
    LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1
)"

"$COMPILER" -std=c++23 -O2 -fPIC -shared \
  -DHOSHIDICTS_ENGINE_SOURCE_FINGERPRINT="\"$SOURCE_FINGERPRINT\"" \
  -I"$CPP_DIR" \
  -I"$JDK_INC" -I"$JDK_INC/linux" -I"$JDK_INC/darwin" \
  "$SHIM" \
  "$BUILD_DIR/libhoshidicts.a" \
  "$BUILD_DIR/external/zstd/build/cmake/lib/libzstd.a" \
  "$BUILD_DIR/external/libdeflate/libdeflate.a" \
  "$BUILD_DIR/external/utf8proc/libutf8proc.a" \
  "${EXTRA_LIBS[@]}" \
  -o "$LIB"

# --- 3. Reproducibility guard: the library must be newer than every engine source ------------
STALE="$(find "$CPP_DIR" -type f \( -name '*.cpp' -o -name '*.hpp' -o -name '*.h' -o -name '*.c' \) \
  -newer "$LIB" -print -quit)"
if [[ -n "$STALE" ]]; then
  echo "error: stale host library: $STALE is newer than $LIB" >&2
  echo "       the JVM would load an engine that does not match the sources" >&2
  exit 3
fi

# --- 4. Record exactly what was built (CI evidence) -----------------------------------------
CMAKE_VERSION_STR="$("$CMAKE" --version | head -1)"
CXX_VERSION_STR="$("$COMPILER" --version | head -1)"
DIGEST=""
if command -v sha256sum >/dev/null; then
  DIGEST="$(sha256sum "$LIB" | cut -d' ' -f1)"
elif command -v shasum >/dev/null; then
  DIGEST="$(shasum -a 256 "$LIB" | cut -d' ' -f1)"
fi

{
  echo "library=$LIB"
  echo "cxx=$COMPILER"
  echo "cxx_version=$CXX_VERSION_STR"
  echo "cmake=$CMAKE_VERSION_STR"
  echo "source_fingerprint=$SOURCE_FINGERPRINT"
  echo "sha256=$DIGEST"
  echo "bytes=$(wc -c < "$LIB" | tr -d ' ')"
} > "$LIB_DIR/BUILD_INFO.txt"

echo "built $LIB"
echo "  compiler: $CXX_VERSION_STR"
echo "  engine:   $SOURCE_FINGERPRINT"
echo "  sha256:   ${DIGEST:-unavailable}"
