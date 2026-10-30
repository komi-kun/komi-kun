#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# test-verify-jni-symbols.sh — self-test for `scripts/verify-jni-symbols.sh`.
#
# A gate that passes on the current tree proves nothing: it may simply not look at the shape a
# defect takes, and the two bugs this runner was written for (the method name was never escaped, and
# "every ABI" was really a union across ABIs) both passed on a green tree. Each case below plants one
# shape in a fixture tree (sources in `scripts/test-fixtures/jni-symbols/cases/`, module skeleton in
# `.../tree/`) whose libraries are real ELF objects built here with `cc`, and asserts that the gate
# fails for the *right* reason — or passes when it must pass:
#
#   clean              symbol exported in all three ABIs                          -> PASS
#   underscore-wrong   `native_open` with only `…_native_open` exported           -> FAIL, names
#                      `…_native_1open` (the name the JVM actually looks up)
#   underscore-correct the same declaration with `…_native_1open` exported        -> PASS
#   abi-missing        exported in two of three ABIs                              -> FAIL, names only
#                      the short ABI (a union across ABIs would pass this)
#   nested             `Outer$Inner` and `Outer$Companion` mangled with `_00024`  -> PASS
#   nested-wrong       the un-nested names exported instead                       -> FAIL, names the
#                      `_00024` symbols (a flat parser derives the wrong name)
#   overload           both `__<signature>` suffixes exported                     -> PASS
#   allowlisted        a forward declaration missing in every ABI, allow-listed   -> PASS + warning
#   annotated-inline   `@JvmName(…)` on the declaration line                      -> FAIL loudly
#   annotated-above    `@JvmName(…)` on its own line                              -> FAIL loudly
#   variant-both       correct Debug tree + newer Release tree                    -> PASS (reads Debug)
#   variant-release-only  only a Release tree present                            -> FAIL loudly instead
#                      of validating a build the Gradle task does not produce
#
# Run directly (`bash scripts/test-verify-jni-symbols.sh`) or via `./gradlew verifyJniSymbols`, which
# runs the gate on the real tree and then this self-test.
#
# Exit codes: 0 = the gate behaved as required in every case, 1 = a case failed (or no C compiler).

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
GUARD="$SCRIPT_DIR/verify-jni-symbols.sh"
FIXTURE_DIR="$ROOT_DIR/scripts/test-fixtures/jni-symbols"
ABIS=(arm64-v8a armeabi-v7a x86_64)

CC="$(command -v cc || command -v gcc || command -v clang || true)"
if [[ -z "$CC" ]]; then
  echo "test-verify-jni-symbols: FAIL — no C compiler (cc/gcc/clang): the fixtures are real ELF objects," >&2
  echo "  and a self-test that cannot build them must not report success." >&2
  exit 1
fi

SCRATCH="$(mktemp -d "${TMPDIR:-/tmp}/test-verify-jni-symbols.XXXXXX")"
trap 'rm -rf "$SCRATCH"' EXIT

pass=0
fail=0

# build_lib ROOT MODULE ABI VARIANT SYMBOLS
#   SYMBOLS is a newline-separated list of exported function names; `fixture_pad` is always exported
#   so the symbol reader has something to print (an empty read is an error, not an empty set).
build_lib() {
  local root="$1" module="$2" abi="$3" variant="$4" symbols="$5"
  local dir lib cfile sym
  if [[ "$module" == "dictionary" ]]; then
    lib="libhoshidicts_jni.so"
    case "$variant" in
      debug) dir="$root/core/dictionary/build/intermediates/cmake/debug/obj/$abi" ;;
      release) dir="$root/core/dictionary/build/intermediates/cxx/Release/fixture/obj/$abi" ;;
      *)
        echo "test-verify-jni-symbols: bad variant '$variant'" >&2
        return 1
        ;;
    esac
  else
    lib="libpaddle_ocr.so"
    dir="$root/core/ocr/src/main/jniLibs/$abi"
  fi
  mkdir -p "$dir"
  cfile="$dir/fixture.c"
  sfile="$dir/fixture.s"
  printf '/* built by scripts/test-verify-jni-symbols.sh — not part of the repository */\nvoid fixture_pad(void) {}\n' >"$cfile"
  # The exported names are declared as quoted assembler labels: a JNI name is not always a C
  # identifier (the overloaded form carries `(`, `)` and `;`) and only the *names* matter here.
  {
    echo '/* built by scripts/test-verify-jni-symbols.sh — not part of the repository */'
    echo '.text'
    while IFS= read -r sym; do
      [[ -n "$sym" ]] || continue
      printf '.globl "%s"\n"%s":\n' "$sym" "$sym"
    done <<<"$symbols"
  } >"$sfile"
  "$CC" -shared -fPIC -o "$dir/$lib" "$cfile" "$sfile" || {
    echo "test-verify-jni-symbols: FAIL — cc could not build $dir/$lib" >&2
    return 1
  }
  rm -f "$cfile" "$sfile"
}

# exports_for SPEC KEY — the `<abi> <symbol>` lines of one key, for make_libs.
exports_for() {
  awk -v k="$2" '$1 == k { print $2, $3 }' "$1"
}

# make_libs ROOT MODULE VARIANT SPEC KEY — one library per declared ABI; an ABI with no line for KEY
# gets a pad-only library (the symbol is missing there, which is a case in its own right).
make_libs() {
  local root="$1" module="$2" variant="$3" spec="$4" key="$5" abi symbols
  for abi in "${ABIS[@]}"; do
    symbols="$(exports_for "$spec" "$key" | awk -v a="$abi" '$1 == a { print $2 }' | sort -u)"
    build_lib "$root" "$module" "$abi" "$variant" "$symbols" || return 1
  done
}

# run_case NAME CASE EXPECT_STATUS EXPECT_TEXT [ABSENT_TEXT]
run_case() {
  local name="$1" case_dir="$2" expected_status="$3" expected_text="$4" absent_text="${5:-}"
  local work spec module dict_libs primary other output status

  work="$SCRATCH/$name"
  mkdir -p "$work"
  cp -R "$FIXTURE_DIR/tree/." "$work/"
  spec="$case_dir/spec"
  module="$(awk '$1 == "module" { print $2; exit }' "$spec")"
  dict_libs="$(awk '$1 == "dictionary-libs" { print $2; exit }' "$spec")"
  [[ -n "$module" ]] || {
    echo "test-verify-jni-symbols: FAIL — $name: no 'module' in $spec" >&2
    fail=$((fail + 1))
    return
  }

  # Both module source roots must exist (the gate refuses a missing root), the case's own sources
  # only go under the module it targets.
  mkdir -p "$work/core/dictionary/src/main/java" "$work/core/ocr/src/main/java" "$work/scripts"
  mkdir -p "$work/core/$module/src/main/java/fixture"
  cp "$case_dir"/src/*.kt "$work/core/$module/src/main/java/fixture/"

  primary="debug"
  [[ "$module" == "dictionary" && "$dict_libs" == "release" ]] && primary="release"
  make_libs "$work" "$module" "$primary" "$spec" export || {
    fail=$((fail + 1))
    return
  }
  # A second, deliberately wrong tree (the variant cases).
  if [[ -n "$(exports_for "$spec" export-release)" ]]; then
    make_libs "$work" dictionary release "$spec" export-release || {
      fail=$((fail + 1))
      return
    }
  fi
  # The module the case does not target still needs readable libraries: the gate reads every module.
  other="dictionary"
  [[ "$module" == "dictionary" ]] && other="ocr"
  if [[ "$other" == "dictionary" ]]; then
    make_libs "$work" dictionary debug "$spec" empty || {
      fail=$((fail + 1))
      return
    }
  else
    make_libs "$work" ocr debug "$spec" empty || {
      fail=$((fail + 1))
      return
    }
  fi

  [[ -f "$case_dir/allowlist" ]] && cp "$case_dir/allowlist" "$work/scripts/jni-symbol-allowlist.txt"

  output="$(
    cd "$ROOT_DIR" &&
      VERIFY_JNI_ROOT="$work" bash "$GUARD" 2>&1
  )"
  status=$?

  if [[ "$status" -ne "$expected_status" ]]; then
    echo "test-verify-jni-symbols: FAIL — $name: exit $status, expected $expected_status" >&2
    echo "$output" >&2
    fail=$((fail + 1))
    return
  fi
  if ! grep -Fq "$expected_text" <<<"$output"; then
    echo "test-verify-jni-symbols: FAIL — $name: output does not contain '$expected_text'" >&2
    echo "$output" >&2
    fail=$((fail + 1))
    return
  fi
  if [[ -n "$absent_text" ]] && grep -Fq "$absent_text" <<<"$output"; then
    echo "test-verify-jni-symbols: FAIL — $name: output must not contain '$absent_text'" >&2
    echo "$output" >&2
    fail=$((fail + 1))
    return
  fi

  echo "test-verify-jni-symbols: ok — $name"
  pass=$((pass + 1))
}

# The happy path: one native, exported everywhere.
run_case clean "$FIXTURE_DIR/cases/clean" 0 "verify-jni-symbols: OK"

# (a) The method name is escaped: a `.so` exporting only `…_native_open` can never be bound, so the
# gate must fail and name the `…_native_1open` the JVM looks up. Without the escape this passes.
run_case underscore-wrong "$FIXTURE_DIR/cases/underscore-wrong" 1 \
  "Java_fixture_ocr_Natives_native_1open"

# (b) …and the correct tree (exporting the escaped name) must pass, not be reported as missing.
run_case underscore-correct "$FIXTURE_DIR/cases/underscore-correct" 0 "verify-jni-symbols: OK"

# (c) A symbol present in two of three ABIs must fail, naming the short ABI only.
run_case abi-missing "$FIXTURE_DIR/cases/abi-missing" 1 \
  "missing in: armeabi-v7a" "missing in: arm64-v8a"

# (d) `_00024` for a nested class and for an unnamed companion: correct in the exports, so it passes…
run_case nested "$FIXTURE_DIR/cases/nested" 0 "verify-jni-symbols: OK"

# …and the flat `Outer_method` names a naive parser derives must be reported missing.
run_case nested-wrong "$FIXTURE_DIR/cases/nested-wrong" 1 \
  "Java_fixture_ocr_Outer_00024Inner_nativeWay"

# The `__<signature>` suffix path still has to work for overloaded natives.
run_case overload "$FIXTURE_DIR/cases/overload" 0 "Java_fixture_ocr_Natives_dup__(I)J"

# A forward declaration recorded in the allow-list passes, with the missing ABIs named.
run_case allowlisted "$FIXTURE_DIR/cases/allowlisted" 0 \
  "allow-listed): Java_fixture_ocr_Natives_nativeMissing — not in: arm64-v8a armeabi-v7a x86_64"

# (e) An `external fun` the parser cannot read must fail loudly, never drop out of the gate.
run_case annotated-inline "$FIXTURE_DIR/cases/annotated-inline" 2 "were not parsed"
run_case annotated-above "$FIXTURE_DIR/cases/annotated-above" 2 "JVM-name annotation"

# The Debug artifact the Gradle task depends on is what gets read…
run_case variant-both "$FIXTURE_DIR/cases/variant-both" 0 "verify-jni-symbols: OK"

# …and a tree with only another variant fails instead of validating it.
run_case variant-release-only "$FIXTURE_DIR/cases/variant-release-only" 2 \
  "only a non-Debug build is present"

if [[ "$fail" -ne 0 ]]; then
  echo "test-verify-jni-symbols: FAILED — $fail of $((pass + fail)) case(s) did not behave as required." >&2
  exit 1
fi

echo "test-verify-jni-symbols: OK — $pass case(s): the gate fails on every planted defect and passes the clean tree."
