#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# verify-jni-symbols.sh — the host-side drift gate for the hand-bound JNI bridges.
#
# Neither native bridge uses `RegisterNatives`: `:core:dictionary` (`libhoshidicts_jni.so`, built by
# CMake) and `:core:ocr` (`libpaddle_ocr.so`, fetched by `fetchPaddleNativeLibs`) are bound by the
# JVM's *name-based* lookup, i.e. `Java_<package>_<Class>_<method>` with `_`, `/` and `$` escaped in
# the class part *and* `_` escaped in the method part (`native_open` is looked up as
# `Java_…_native_1open`, never `Java_…_native_open`). Nothing in the build reads that contract, so a
# renamed C++ export or a Kotlin `external fun` that drifted from the shim compiles cleanly, passes CI
# and fails only on a device, at the first call, with `UnsatisfiedLinkError`. Only 8 of the dictionary
# natives have any behavioural coverage and the OCR natives have none, so this is the only check that
# looks at the actual `.so`.
#
# What it covers — symbol *names*, and nothing else:
#   1. finds every `external fun` in `core/dictionary/src/main/java/**` and `core/ocr/src/main/java/**`
#      and derives the JNI symbol the JVM will look up: package + enclosing classes (`_` -> `_1`,
#      `.` -> `_`, `$` -> `_00024`, so a `companion object` is `Outer_00024Companion`), the method name
#      escaped the same way (`_` -> `_1` first, then the other escapes), and the
#      `__<mangled-signature>` suffix when — and only when — a method is overloaded;
#   2. reads the *exported dynamic* symbols of each module's `.so` for **every** ABI the module
#      declares in its `abiFilters` (NDK `llvm-nm --defined-only --dynamic`, GNU `nm -D` as fallback)
#      and requires every declared symbol in **each** ABI. Per-ABI, not a union: a union would let one
#      ABI's export hide another ABI's drift. A short ABI is named individually in the failure. The
#      ABI *set* is the union of every `abiFilters += listOf(…)` in the module file — a second list
#      can only widen what is checked, never narrow it — and an `abiFilters` this cannot read is an
#      error, not a guess;
#   3. fails, naming the Kotlin declaration site, when a declared `external fun` has no exported
#      counterpart in an ABI; exports with no Kotlin declaration are reported as warnings;
#   4. fails when it finds an `external fun` it did *not* parse (a raw token count per file, compared
#      with the parsed declarations): a declaration the parser cannot read must never drop silently
#      out of the gate, and an annotation that renames the JVM entry point (`@JvmName`) is changed by
#      the JVM, not by us — so `@JvmName` is **rejected**, not honoured. A split modifier list (a line
#      that is just `external`, with `fun …` on the next line) is likewise rejected, not read: there
#      is no `external fun` token for the raw count to see, so it would otherwise disappear entirely.
#
# What it does NOT cover (and cannot, by name lookup alone):
#   * **Signature drift.** The `__<signature>` suffix exists only for *overloaded* natives; the JVM
#     binds a non-overloaded native method by name alone, so changing `Int` -> `Long`, reordering two
#     parameters of the same type, or changing the C++ implementation behind an unchanged export keeps
#     every name intact and passes this gate. Nothing on the host covers it: the module unit tests
#     never load the `.so` (they are Android-ABI binaries a host JVM cannot link), so the only
#     mitigation is device-side — `-Xcheck:jni`, or a behavioural test running on a device.
#   * **Arity of the exported implementation** (a C++ function that matches the name but takes other
#     arguments). Same reason; same mitigation.
#
# Variants: the gate reads the **Debug** artifact the Gradle task depends on
# (`:core:dictionary:externalNativeBuildDebug`, `fetchPaddleNativeLibs`). A tree that only has some
# other variant fails with an explicit message instead of silently validating a different build.
#
# A missing library is an *error*, not a skip: a gate that silently passes when it has nothing to
# read is worse than no gate (the `fetchPaddleNativeLibs` checksum-less entry was exactly that bug).
#
# Declared-but-not-yet-exported natives are recorded in `scripts/jni-symbol-allowlist.txt` with a
# reason, so an intentional forward declaration is visible on every run instead of being invisible.
# A stale entry (the symbol is exported in every ABI now) is reported as a warning, never a failure.
#
# Run directly (`bash scripts/verify-jni-symbols.sh`) or via `./gradlew verifyJniSymbols`, which
# depends on the CMake external build and the OCR native fetch, runs this script, and then runs its
# self-test (`scripts/test-verify-jni-symbols.sh`, fixtures in `scripts/test-fixtures/jni-symbols/`).
#
# Environment overrides (for exercising the gate on a fixture tree):
#   VERIFY_JNI_ROOT       tree to scan                       (default: the repository root)
#   VERIFY_JNI_ABIS       space-separated ABI list override  (default: each module's abiFilters)
#   VERIFY_JNI_ALLOWLIST  allow-list path (relative to root)  (default: scripts/jni-symbol-allowlist.txt)
#
# Exit codes: 0 = every declared native is exported in every ABI, 1 = mismatch, 2 = environment
# error (missing library, no symbol reader, unparseable declaration).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="${VERIFY_JNI_ROOT:-$(cd "$SCRIPT_DIR/.." && pwd)}"
cd "$ROOT_DIR"

ALLOWLIST_FILE="${VERIFY_JNI_ALLOWLIST:-scripts/jni-symbol-allowlist.txt}"

err() { echo "verify-jni-symbols: ERROR: $*" >&2; }
warn() { echo "verify-jni-symbols: warning: $*" >&2; }
note() { echo "verify-jni-symbols: $*"; }

# ---------------------------------------------------------------------------------------------
# Symbol reader. The NDK's `llvm-nm` reads every ELF this repo produces; GNU `nm -D` is the
# fallback for a machine with a different toolchain layout, but only if it can actually list the
# objects (its output is verified below rather than assumed).
# ---------------------------------------------------------------------------------------------
ndk_llvm_nm() {
  local base ndk candidate
  for base in "${ANDROID_NDK_HOME:-}" "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
    "$HOME/Android/Sdk" /opt/android-sdk "/usr/lib/android-sdk"; do
    [[ -n "$base" && -d "$base" ]] || continue
    for ndk in "$base"/*/ "$base" "$base"/ndk/*/; do
      for candidate in \
        "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm" \
        "$ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-nm" \
        "$ndk/llvm/bin/llvm-nm"; do
        [[ -x "$candidate" ]] && {
          printf '%s' "$candidate"
          return 0
        }
      done
    done
  done
  return 0
}

optionally() {
  local tool
  tool="$(command -v "$1" 2>/dev/null || true)"
  printf '%s' "$tool"
}

NDK_NM="$(ndk_llvm_nm)"
REPO_NM="$(optionally nm)"
[[ -z "$NDK_NM" && -z "$REPO_NM" ]] &&
  {
    err "no symbol reader: neither the NDK's llvm-nm (ANDROID_HOME/ANDROID_NDK_HOME) nor 'nm' is available"
    exit 2
  }

# exported_symbols LIB — every defined dynamic symbol of LIB. Tries the NDK reader first, then GNU
# nm, and fails when neither can read the library (a gate that reads nothing must not pass).
exported_symbols() {
  local lib="$1" tool out
  for tool in "$NDK_NM" "$REPO_NM"; do
    [[ -n "$tool" ]] || continue
    out="$("$tool" --defined-only --dynamic "$lib" 2>/dev/null | awk 'NF >= 3 { print $NF }' || true)"
    if [[ -n "$out" ]]; then
      printf '%s\n' "$out"
      return 0
    fi
  done
  err "cannot read the exported symbols of '$lib' (no working llvm-nm or nm)"
  return 2
}

# ---------------------------------------------------------------------------------------------
# Kotlin side: packages, enclosing types and `external fun` declarations.
# ---------------------------------------------------------------------------------------------
package_of() {
  sed -n -E 's/^[[:space:]]*package[[:space:]]+([A-Za-z0-9_.]+).*/\1/p' "$1" | head -n 1
}

# declarations_in FILE — one tab-separated
# `<line> \t <enclosing types> \t <method> \t <jvm-name annotation?>` per `external fun`.
# The enclosing chain is found by indentation (Spotless keeps 4-space indents), so a nested class
# yields `Outer$Inner` — the shape the JNI name has to mangle. A *companion object* is a real JVM
# class (`Outer$Companion` / `Outer$Name`), so it stays in the chain either way, named or not.
# The fourth field is non-empty when a preceding-line annotation renames the JVM entry point
# (`@JvmName`): that is a name this script cannot derive, so it is reported and rejected.
# A declaration the parser cannot read is *not* printed here; the raw-vs-parsed token count below
# turns it into a failure instead of a silent drop.
declarations_in() {
  awk '
    function indent_of(s,   n) {
      n = 0
      while (substr(s, n + 1, 1) == " ") n++
      return n
    }
    function strip_lead(s) { sub(/^[[:space:]]+/, "", s); return s }
    function push_type(tname, tind) {
      while (depth > 0 && ind_of[depth] >= tind) depth--
      depth++
      ind_of[depth] = tind
      name_of[depth] = tname
    }
    function chain_at(tind,   chain, d) {
      chain = ""
      for (d = 1; d <= depth; d++) {
        if (ind_of[d] < tind) chain = chain (chain == "" ? "" : "$") name_of[d]
      }
      return chain
    }
    BEGIN { depth = 0; annotation = "" }
    {
      raw = $0
      ind = indent_of(raw)
      line = strip_lead(raw)

      # Blank and comment-only lines carry nothing; they do not end an annotation block.
      if (line == "" || line ~ /^\/\// || line ~ /^\*/ || line ~ /^\/\*/) next

      # An annotation on its own line belongs to the next declaration. Remember it (an `@JvmName`
      # would rename the JVM entry point away from the Kotlin name derived here).
      if (line ~ /^@/) {
        annotation = annotation " " line
        next
      }

      # An *unnamed* `companion object` (followed by `{`, `: Super()` or nothing) is the JVM class
      # `Outer$Companion`, so it contributes `Companion` to the chain. A *named* companion is a named
      # object and is caught by the rule below.
      if (line ~ /^((internal|private|public|protected)[[:space:]]+)*companion[[:space:]]+object([[:space:]]*[{(:]|$)/) {
        push_type("Companion", ind)
        annotation = ""
        next
      }

      # A type declaration at this indentation closes every deeper (or same-depth sibling) type.
      if (line ~ /^(@[A-Za-z_][A-Za-z0-9_.]*[[:space:]]+)?((internal|private|public|protected|abstract|open|sealed|final|data|value|annotation|enum|inner|companion|fun|expect|actual)[[:space:]]+)*(class|object|interface)[[:space:]]+[A-Za-z_][A-Za-z0-9_]*/) {
        name = line
        sub(/^.*(class|object|interface)[[:space:]]+/, "", name)
        sub(/[^A-Za-z0-9_].*$/, "", name)
        push_type(name, ind)
        annotation = ""
        next
      }

      if (line ~ /^((internal|private|public|protected|final|open|override)[[:space:]]+)*external[[:space:]]+fun[[:space:]]+[A-Za-z_][A-Za-z0-9_]*/) {
        method = line
        sub(/^.*external[[:space:]]+fun[[:space:]]+/, "", method)
        sub(/[^A-Za-z0-9_].*$/, "", method)
        # The enclosing types of *this* line: everything declared above at a smaller indent.
        printf "%d\t%s\t%s\t%s\n", NR, chain_at(ind), method, (annotation ~ /JvmName/ ? "renamed" : "")
        annotation = ""
        next
      }

      annotation = ""
    }
  ' "$1"
}

# code_lines FILE — every line of FILE as `<line> \t <code text>`, with `//` and `/* */` comments
# stripped. Comment text is not code: an `external fun` (or a bare `external`) inside a KDoc/`//`
# remark must never be mistaken for a declaration.
code_lines() {
  awk '
    BEGIN { blk = 0 }
    {
      s = $0
      out = ""
      i = 1
      n = length(s)
      while (i <= n) {
        if (blk) {
          p = index(substr(s, i), "*/")
          if (p == 0) { i = n + 1; break }
          i = i + p + 1
          blk = 0
        } else {
          p = index(substr(s, i), "/*")
          c = index(substr(s, i), "//")
          if (c > 0 && (p == 0 || c < p)) { out = out substr(s, i, c - 1); i = n + 1; break }
          if (p > 0) { out = out substr(s, i, p - 1); i = i + p + 1; blk = 1 }
          else { out = out substr(s, i); i = n + 1 }
        }
      }
      printf "%d\t%s\n", NR, out
    }
  ' "$1"
}

# code_of LINE_RECORD — the code text of one `code_lines` record (`<line> \t <code>`).
code_of() {
  local rest="${1#*$'\t'}"
  printf '%s' "$rest"
}

# external_fun_lines FILE — every line holding an `external fun` token sequence in *code*, as
# `<line> \t <code text>`. Compared against declarations_in's output: an occurrence the parser did
# not record is a declaration that would otherwise drop silently out of the gate, so the caller fails
# on it.
external_fun_lines() {
  local record
  while IFS= read -r record; do
    [[ "$(code_of "$record")" =~ (^|[^A-Za-z0-9_])external[[:space:]]+fun([^A-Za-z0-9_]|$) ]] &&
      printf '%s\n' "$record"
  done < <(code_lines "$1")
  return 0
}

# split_external_lines FILE — lines whose *code* puts `external` and `fun` on different lines (a line
# that is just `external`, or ends with it). `declarations_in()` reads one line at a time, so that
# declaration is invisible to it — and `external_fun_lines()` needs both tokens on one line, so the
# raw-vs-parsed count cannot see it either. The declaration would therefore vanish from the gate
# entirely, which is exactly what the coverage checks exist to prevent: the caller fails on it.
split_external_lines() {
  local record code
  while IFS= read -r record; do
    code="$(code_of "$record")"
    # A declaration the parser does read is not a split one.
    [[ "$code" =~ (^|[^A-Za-z0-9_.])external[[:space:]]+fun([^A-Za-z0-9_]|$) ]] && continue
    # `external` as a keyword: not part of a qualified name (`a.external`), not inside an identifier,
    # and not a quoted string literal (`"external"` in a log message is prose, not a declaration).
    [[ "$code" =~ (^|[^A-Za-z0-9_."])external([^A-Za-z0-9_"]|$) ]] && printf '%s\n' "$record"
  done < <(code_lines "$1")
  return 0
}

# declaration_text FILE LINE — the declaration, with continuation lines, up to the closing paren.
declaration_text() {
  awk -v start="$2" '
    NR < start { next }
    {
      text = $0
      sub(/^[[:space:]]+/, "", text)
      if (NR == start) {
        out = text
      } else {
        out = out " " text
      }
      if (index(out, "(") > 0) {
        opens = gsub(/\(/, "(", out)
        closes = gsub(/\)/, ")", out)
        if (opens > 0 && opens <= closes) { print out; exit }
      } else if (NR > start) {
        print out; exit
      }
      if (NR > start + 40) { print out; exit }
    }
  ' "$1"
}

# params_of TEXT — the raw text between the declaration's parentheses.
params_of() {
  awk '{
    out = ""
    depth = 0
    started = 0
    for (i = 1; i <= length($0); i++) {
      c = substr($0, i, 1)
      if (c == "(") { depth++; if (depth == 1) { started = 1; continue } }
      else if (c == ")") { depth--; if (depth == 0 && started) { print out; exit } }
      if (started) out = out c
    }
    print out
  }' <<<"$1"
}

# mangle_name FQN — JNI's escape for the class part: `_1` for `_`, `_` for `.`, `_00024` for `$`.
mangle_name() {
  local s="$1"
  s="${s//_/_1}"
  s="${s//./_}"
  s="${s//\$/_00024}"
  printf '%s' "$s"
}

# mangle_method NAME — JNI's escape for the *method* part. The same rules apply (and in the same
# order: `_` -> `_1` first, then `/` -> `_`, `;` -> `_2`, `[` -> `_3`), so `native_open` is looked up
# as `…_native_1open`. Skipping this escape (a) passes a `.so` that exports the unescaped name, which
# the JVM can never bind, and (b) reports a correct `.so` as missing.
mangle_method() {
  local s="$1"
  s="${s//_/_1}"
  s="${s//\//_}"
  s="${s//;/_2}"
  s="${s//\[/_3}"
  printf '%s' "$s"
}

# mangle_signature SIG — JNI's escape for the `__<sig>` suffix: `/` -> `_`, `_` -> `_1`,
# `;` -> `_2`, `[` -> `_3`.
mangle_signature() {
  local s="$1"
  s="${s//_/_1}"
  s="${s//\//_}"
  s="${s//;/_2}"
  s="${s//\[/_3}"
  printf '%s' "$s"
}

# type_declared NAME — is NAME declared as a type (class/interface/object/typealias) in the scanned
# module sources? Used to refuse to guess a descriptor.
type_declared() {
  local name="$1" root
  for root in "${SOURCES[@]}"; do
    if grep -rqE "^((internal|private|public|abstract|open|sealed|final|data|value|annotation|enum|inner|expect|actual)[[:space:]]+)*(class|interface|object|typealias)[[:space:]]+${name}([[:space:]<:{]|$)" "$root"; then
      return 0
    fi
  done
  return 1
}

# descriptor_of TYPE FILE — the JVM descriptor of a Kotlin type, or a hard failure when it is not a
# type this script understands (guessing here would verify the wrong symbol).
descriptor_of() {
  local type="$1" file="$2"
  case "$type" in
    Long) printf 'J' ;;
    Int) printf 'I' ;;
    Boolean) printf 'Z' ;;
    Float) printf 'F' ;;
    Double) printf 'D' ;;
    Byte) printf 'B' ;;
    Short) printf 'S' ;;
    Char) printf 'C' ;;
    Unit) printf 'V' ;;
    String) printf 'Ljava/lang/String;' ;;
    ByteArray) printf '[B' ;;
    LongArray) printf '[J' ;;
    IntArray) printf '[I' ;;
    BooleanArray) printf '[Z' ;;
    FloatArray) printf '[F' ;;
    DoubleArray) printf '[D' ;;
    ShortArray) printf '[S' ;;
    CharArray) printf '[C' ;;
    Array\<*)
      local inner="${type#Array<}"
      inner="${inner%>}"
      # `Array<String>` and `Array<out Foo>` both describe an element type.
      inner="${inner#out }"
      printf '[%s' "$(descriptor_of "$inner" "$file")"
      ;;
    Bitmap) printf 'Landroid/graphics/Bitmap;' ;;
    *)
      local pkg
      pkg="$(sed -n -E "s/^[[:space:]]*import[[:space:]]+([A-Za-z0-9_.]+)\.${type}([[:space:]]|$).*/\1/p" "$file" | head -n 1)"
      if [[ -z "$pkg" ]] && type_declared "$type"; then
        pkg="$(package_of "$file")"
      fi
      if [[ -z "$pkg" ]]; then
        err "cannot resolve the JVM type of '$type' (declared in $file) — add it to descriptor_of()"
        return 1
      fi
      printf 'L%s/%s;' "${pkg//./\/}" "$type"
      ;;
  esac
}

# signature_of FILE LINE — the JNI `(args)ret` signature of the declaration at LINE.
signature_of() {
  local file="$1" line="$2" text params param type ret args="" first=1
  text="$(declaration_text "$file" "$line")"
  params="$(params_of "$text")"
  ret="${text#*)}"
  ret="${ret#*:}"
  # Drop a default value / expression body from the return type, then trim.
  ret="${ret%%=*}"
  ret="${ret%%{*}"
  ret="$(printf '%s' "$ret" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//')"
  ret="${ret%% *}"

  if [[ -n "${params//[[:space:]]/}" ]]; then
    local IFS=','
    for param in $params; do
      type="${param#*:}"
      type="${type%%=*}"
      type="$(printf '%s' "$type" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//')"
      type="${type%% *}"
      [[ $first -eq 1 ]] || args="$args,"
      first=0
      args="$args$(descriptor_of "$type" "$file")"
    done
  fi

  if [[ -z "$ret" ]]; then
    err "cannot read the return type of $file:$line"
    return 1
  fi
  printf '(%s)%s' "$args" "$(descriptor_of "$ret" "$file")"
}

# ---------------------------------------------------------------------------------------------
# Allow list: `<symbol><whitespace><reason>`, `#` comments.
# ---------------------------------------------------------------------------------------------
declare -A ALLOWED=()
if [[ -f "$ALLOWLIST_FILE" ]]; then
  while IFS= read -r entry; do
    entry="${entry%%#*}"
    entry="$(printf '%s' "$entry" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//')"
    [[ -z "$entry" ]] && continue
    ALLOWED["${entry%%[[:space:]]*}"]="$(printf '%s' "$entry" | sed -E 's/^[^[:space:]]+[[:space:]]*//')"
  done <"$ALLOWLIST_FILE"
fi

# ---------------------------------------------------------------------------------------------
# Module table.
# ---------------------------------------------------------------------------------------------
MODULES=(dictionary ocr)
declare -A MODULE_SOURCES=(
  [dictionary]="core/dictionary/src/main/java"
  [ocr]="core/ocr/src/main/java"
)
declare -A MODULE_LIB=(
  [dictionary]="hoshidicts_jni"
  [ocr]="paddle_ocr"
)
# Where the *Debug* artifact lives — the same build the Gradle task depends on
# (`:core:dictionary:externalNativeBuildDebug`, `fetchPaddleNativeLibs`). Only these roots are read:
# a `Release` object from an earlier build must never stand in for a Debug rebuild, or a rename the
# Debug build picked up would be verified against stale bytes.
declare -A MODULE_DEBUG_LIB_ROOTS=(
  [dictionary]="core/dictionary/build/intermediates/cmake/debug/obj core/dictionary/build/intermediates/cxx/Debug core/dictionary/build/intermediates/stripped_native_libs/debug"
  [ocr]="core/ocr/src/main/jniLibs"
)
# Every variant — used only to *diagnose* a missing Debug artifact, never to validate against.
declare -A MODULE_ANY_LIB_ROOTS=(
  [dictionary]="core/dictionary/build/intermediates/cxx core/dictionary/build/intermediates/cmake core/dictionary/build/intermediates/stripped_native_libs"
  [ocr]="core/ocr/src/main/jniLibs"
)
declare -A MODULE_GRADLE=(
  [dictionary]="core/dictionary/build.gradle.kts"
  [ocr]="core/ocr/build.gradle.kts"
)

# abis_of MODULE — every ABI the module declares, as the *union* of all its `abiFilters += listOf(…)`
# lists. Joining the file and keeping one match would let a second list (a per-variant or per-flavour
# `abiFilters +=`) silently narrow "every declared ABI"; a union can only widen the checked set, so a
# missing ABI is never hidden by a later list. A shape this cannot read (`abiFilters +=` with anything
# but `listOf(…)`) is an error, not a guess — for the same reason: the ABIs it mentioned would go
# unchecked.
abis_of() {
  local module="$1" gradle="${MODULE_GRADLE[$1]}" joined filters readable lists abis
  if [[ -n "${VERIFY_JNI_ABIS:-}" ]]; then
    printf '%s\n' $VERIFY_JNI_ABIS
    return 0
  fi
  # `//` comments are not declarations: the prose around the list often quotes the shape itself.
  joined="$(sed -E 's://.*::' "$gradle" | tr '\n' ' ')"
  filters="$(printf '%s' "$joined" | grep -o 'abiFilters' | wc -l)"
  lists="$(printf '%s' "$joined" | grep -oE 'abiFilters[[:space:]]*\+=[[:space:]]*listOf\([^)]*\)' || true)"
  readable="$(printf '%s' "$lists" | grep -c 'abiFilters' || true)"
  if [[ "$filters" -ne "$readable" ]]; then
    err "cannot read every 'abiFilters' in $gradle: found $filters reference(s) but only $readable as"
    err "  'abiFilters += listOf(\"…\")'. A list this cannot parse would silently narrow 'every declared"
    err "  ABI', so it is an error rather than a fallback (VERIFY_JNI_ABIS overrides the list)."
    return 1
  fi
  abis="$(printf '%s\n' "$lists" | grep -oE '"[^"]+"' | tr -d '"' | awk '!seen[$0]++')"
  if [[ -z "$abis" ]]; then
    err "cannot read the ABI list from $gradle (abiFilters += listOf(...))"
    return 1
  fi
  printf '%s\n' "$abis"
}

# locate_lib MODULE ABI — the *Debug* library path for one ABI, or empty.
locate_lib() {
  local module="$1" abi="$2" lib="lib${MODULE_LIB[$1]}.so" dir found
  for dir in ${MODULE_DEBUG_LIB_ROOTS[$module]}; do
    [[ -d "$ROOT_DIR/$dir" ]] || continue
    found="$(find "$ROOT_DIR/$dir" -type f -path "*/$abi/$lib" -printf '%T@ %p\n' 2>/dev/null |
      sort -nr | head -n 1 | cut -d' ' -f2- || true)"
    [[ -n "$found" ]] && {
      printf '%s' "$found"
      return 0
    }
  done
  return 0
}

# locate_other_variant_lib MODULE ABI — a library for that ABI that is *not* a Debug artifact, used
# only to explain why locate_lib came up empty (never to validate).
locate_other_variant_lib() {
  local module="$1" abi="$2" lib="lib${MODULE_LIB[$1]}.so" dir path debug is_debug
  for dir in ${MODULE_ANY_LIB_ROOTS[$module]}; do
    [[ -d "$ROOT_DIR/$dir" ]] || continue
    while IFS= read -r path; do
      [[ -n "$path" ]] || continue
      is_debug=0
      for debug in ${MODULE_DEBUG_LIB_ROOTS[$module]}; do
        [[ "$path" == "$ROOT_DIR/$debug/"* ]] && is_debug=1
      done
      [[ $is_debug -eq 0 ]] && {
        printf '%s' "$path"
        return 0
      }
    done < <(find "$ROOT_DIR/$dir" -type f -path "*/$abi/$lib" 2>/dev/null | sort)
  done
  return 0
}

# ---------------------------------------------------------------------------------------------
# Collect the declared natives of every module.
# ---------------------------------------------------------------------------------------------
DECL_MODULE=()
DECL_FILE=()
DECL_LINE=()
DECL_FQN=()
DECL_METHOD=()
DECL_SYMBOL=()

declare -A METHOD_COUNT=()
declare -A METHOD_LINE=()

UNPARSED=()
RENAMED=()
SPLIT_EXTERNAL=()

for module in "${MODULES[@]}"; do
  SOURCES=(${MODULE_SOURCES[$module]})
  for source in "${SOURCES[@]}"; do
    [[ -d "$source" ]] || {
      err "source root is missing: '$source' (a moved native declaration must never drop out of the gate)"
      exit 2
    }
  done
  while IFS= read -r file; do
    pkg="$(package_of "$file")"
    [[ -z "$pkg" ]] && continue
    parsed_lines=" "
    while IFS=$'\t' read -r line chain method renamed; do
      [[ -z "$method" ]] && continue
      parsed_lines="$parsed_lines$line "
      [[ -z "$renamed" ]] || RENAMED+=("$file:$line"$'\t'"$(declaration_text "$file" "$line")")
      fqn="$pkg.${chain:-}"
      [[ -n "$chain" ]] || {
        err "cannot determine the enclosing type of $file:$line"
        exit 2
      }
      DECL_MODULE+=("$module")
      DECL_FILE+=("$file")
      DECL_LINE+=("$line")
      DECL_FQN+=("$fqn")
      DECL_METHOD+=("$method")
      METHOD_COUNT["$fqn#$method"]=$((${METHOD_COUNT["$fqn#$method"]:-0} + 1))
      METHOD_LINE["$fqn#$method"]="$file:$line"
    done < <(declarations_in "$file")
    # Every `external fun` in the file must be one of those declarations: a token the parser did not
    # record is a native that would otherwise be missing from the gate entirely.
    while IFS=$'\t' read -r line text; do
      [[ -z "$line" ]] && continue
      [[ "$parsed_lines" == *" $line "* ]] && continue
      UNPARSED+=("$file:$line"$'\t'"$text")
    done < <(external_fun_lines "$file")
    # A split modifier list (`external` on one line, `fun …` on the next) produces no `external fun`
    # token, so the count above cannot see it — it is caught here instead, and refused rather than
    # guessed at.
    while IFS=$'\t' read -r line text; do
      [[ -z "$line" ]] && continue
      SPLIT_EXTERNAL+=("$file:$line"$'\t'"$text")
    done < <(split_external_lines "$file")
  done < <(find "${SOURCES[@]}" -name '*.kt' -not -path '*/build/*' | sort)
done

if [[ "${#SPLIT_EXTERNAL[@]}" -gt 0 ]]; then
  err "${#SPLIT_EXTERNAL[@]} 'external' line(s) split a declaration across lines, so it would silently"
  err "escape the gate (there is no 'external fun' token for the coverage count to see):"
  for entry in "${SPLIT_EXTERNAL[@]}"; do
    err "  ${entry%%$'\t'*}: $(code_of "$entry" | sed -E 's/^[[:space:]]+//')"
  done
  err "Write the whole declaration on one line — modifiers, then 'external fun <name>(' … ')' — which"
  err "is the shape declarations_in() reads. This gate refuses a declaration it cannot read; it never"
  err "drops one."
  exit 2
fi

if [[ "${#RENAMED[@]}" -gt 0 ]]; then
  err "${#RENAMED[@]} external fun(s) carry a JVM-name annotation, which this gate cannot derive:"
  for entry in "${RENAMED[@]}"; do
    err "  ${entry%%$'\t'*}: ${entry#*$'\t'}"
  done
  err "An '@JvmName' renames the JVM entry point, so the derived symbol would be wrong. Remove the"
  err "annotation: it cannot apply to a member function anyway, and the allow-list cannot clear this"
  err "check — it is consulted only after this exit, and its entries are symbols this script never"
  err "derives. If a renamed entry point is genuinely required, teach declarations_in() to read the"
  err "annotation's value and derive the symbol from it (and add a fixture for that shape)."
  exit 2
fi

if [[ "${#UNPARSED[@]}" -gt 0 ]]; then
  err "${#UNPARSED[@]} 'external fun' occurrence(s) were not parsed, so they would silently escape the gate:"
  for entry in "${UNPARSED[@]}"; do
    err "  ${entry%%$'\t'*}: ${entry#*$'\t'}"
  done
  err "Write the declaration in the shape the parser reads — modifiers, then 'external fun <name>(' on"
  err "one line, with any annotation on its own line (an '@JvmName' that renames the JVM entry point is"
  err "refused above), or extend declarations_in()."
  exit 2
fi

if [[ "${#DECL_METHOD[@]}" -eq 0 ]]; then
  err "found no 'external fun' declaration under ${MODULE_SOURCES[*]} — the parser is broken"
  exit 2
fi

# Derive each symbol, with the `__<signature>` suffix for overloads.
OVERLOADED=()
for i in "${!DECL_FILE[@]}"; do
  key="${DECL_FQN[$i]}#${DECL_METHOD[$i]}"
  symbol="Java_$(mangle_name "${DECL_FQN[$i]}")_$(mangle_method "${DECL_METHOD[$i]}")"
  if [[ "${METHOD_COUNT[$key]}" -gt 1 ]]; then
    SOURCES=(${MODULE_SOURCES[${DECL_MODULE[$i]}]})
    signature="$(signature_of "${DECL_FILE[$i]}" "${DECL_LINE[$i]}")" || exit 2
    symbol="${symbol}__$(mangle_signature "$signature")"
    OVERLOADED+=("${DECL_METHOD[$i]}@${DECL_FQN[$i]} -> $symbol")
  fi
  DECL_SYMBOL+=("$symbol")
done

# ---------------------------------------------------------------------------------------------
# Check every declared symbol in *every* ABI the module declares, and report exports nobody declares.
# ---------------------------------------------------------------------------------------------
declare -A DECLARED_BY_MODULE=()
for i in "${!DECL_SYMBOL[@]}"; do
  DECLARED_BY_MODULE[${DECL_MODULE[$i]}]="${DECLARED_BY_MODULE[${DECL_MODULE[$i]}]:-} ${DECL_SYMBOL[$i]}"
done

MISSING=()
CHECKED=0
ALLOWLISTED=0
WARNINGS=()

for module in "${MODULES[@]}"; do
  abi_list="$(abis_of "$module")" || exit 2
  abis=($abi_list)
  if [[ "${#abis[@]}" -eq 0 ]]; then
    err "cannot read the ABI list from ${MODULE_GRADLE[$module]} (abiFilters += listOf(...))"
    exit 2
  fi

  # `<abi>|<symbol>` -> 1. Per ABI on purpose: a module-wide union would let one ABI's export cover
  # another ABI's missing symbol, and the summary would still claim "every ABI".
  declare -A exported_in=()
  for abi in "${abis[@]}"; do
    lib="$(locate_lib "$module" "$abi")"
    if [[ -z "$lib" ]]; then
      err "no Debug lib${MODULE_LIB[$module]}.so for '$abi' — build it first:"
      if [[ "$module" == "ocr" ]]; then
        err "    ./gradlew :core:ocr:fetchPaddleNativeLibs"
      else
        err "    ./gradlew :core:dictionary:externalNativeBuildDebug"
      fi
      err "  (searched: ${MODULE_DEBUG_LIB_ROOTS[$module]}; a half-verified gate must not pass)"
      other="$(locate_other_variant_lib "$module" "$abi")"
      if [[ -n "$other" ]]; then
        err "  only a non-Debug build is present: ${other#"$ROOT_DIR"/}"
        err "  this gate validates the Debug artifact the Gradle task depends on, so a stale/other"
        err "  variant is an error, not a fallback: rebuild the Debug target above."
      fi
      exit 2
    fi
    exports="$(exported_symbols "$lib")" || exit 2
    java_exports="$(grep -E '^Java_' <<<"$exports" | sort -u || true)"
    while IFS= read -r symbol; do
      [[ -n "$symbol" ]] && exported_in["$abi|$symbol"]=1
    done <<<"$java_exports"
    note "$module $abi: $(wc -l <<<"$java_exports" | tr -d ' ') exported Java_ symbol(s) in ${lib#"$ROOT_DIR"/}"
  done

  # Reverse direction: an export no Kotlin declaration claims is the other half of the drift.
  for key in "${!exported_in[@]}"; do
    symbol="${key#*|}"
    abi="${key%%|*}"
    if ! grep -qw -- "$symbol" <<<"${DECLARED_BY_MODULE[$module]:-}"; then
      WARNINGS+=("exported in $abi but not declared: $symbol ($module)")
    fi
  done

  for i in "${!DECL_SYMBOL[@]}"; do
    [[ "${DECL_MODULE[$i]}" == "$module" ]] || continue
    symbol="${DECL_SYMBOL[$i]}"
    absent_in=()
    for abi in "${abis[@]}"; do
      [[ -n "${exported_in["$abi|$symbol"]:-}" ]] || absent_in+=("$abi")
    done
    if [[ "${#absent_in[@]}" -eq 0 ]]; then
      CHECKED=$((CHECKED + 1))
      [[ -n "${ALLOWED[$symbol]:-}" ]] &&
        WARNINGS+=("allow-list entry is stale, '$symbol' is exported in every ABI now — remove it from $ALLOWLIST_FILE")
      continue
    fi
    if [[ -n "${ALLOWED[$symbol]:-}" ]]; then
      ALLOWLISTED=$((ALLOWLISTED + 1))
      warn "declared but not exported (allow-listed): $symbol — not in: ${absent_in[*]} — ${ALLOWED[$symbol]}"
      continue
    fi
    MISSING+=("$symbol"$'\t'"${DECL_FILE[$i]}:${DECL_LINE[$i]}"$'\t'"$(declaration_text "${DECL_FILE[$i]}" "${DECL_LINE[$i]}")"$'\t'"missing in: ${absent_in[*]}")
  done
done

for warning in ${WARNINGS[@]+"${WARNINGS[@]}"}; do
  warn "$warning"
done

if [[ "${#OVERLOADED[@]}" -gt 0 ]]; then
  note "overloaded external fun(s) — verified with the __<signature> suffix:"
  for entry in "${OVERLOADED[@]}"; do
    note "    $entry"
  done
else
  note "overloaded external fun(s): none (so no __<signature> suffix applies)"
fi

if [[ "${#MISSING[@]}" -gt 0 ]]; then
  err "${#MISSING[@]} declared external fun(s) have no exported JNI symbol in every declared ABI:"
  for entry in "${MISSING[@]}"; do
    symbol="${entry%%$'\t'*}"
    rest="${entry#*$'\t'}"
    site="${rest%%$'\t'*}"
    rest="${rest#*$'\t'}"
    text="${rest%%$'\t'*}"
    abis="${rest##*$'\t'}"
    err "  $symbol"
    err "      declared at $site  ($abis)"
    err "      $text"
  done
  err "A C++ export was renamed, the Kotlin declaration drifted from the shim, or an ABI is behind."
  err "Fix the bridge (both sides), rebuild the missing ABI, or record an intentional forward"
  err "declaration in $ALLOWLIST_FILE."
  exit 1
fi

note "scope: symbol names per ABI (plus the __<signature> suffix for overloaded natives) — a changed"
note "       parameter list or return type is NOT checked here; JNI binds a non-overloaded native by"
note "       name alone, so cover signature drift on a device ('-Xcheck:jni' or a behavioural test),"
note "       never with the host unit tests: they do not load these Android-ABI libraries."
note "OK: $CHECKED declared external fun(s) match an exported symbol in every ABI (${ALLOWLISTED} allow-listed, ${#WARNINGS[@]} warning(s))"
