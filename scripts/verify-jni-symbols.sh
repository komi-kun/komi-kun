#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# verify-jni-symbols.sh — the host-side drift gate for the hand-bound JNI bridges.
#
# Neither native bridge uses `RegisterNatives`: `:core:dictionary` (`libhoshidicts_jni.so`, built by
# CMake) and `:core:ocr` (`libpaddle_ocr.so`, fetched by `fetchPaddleNativeLibs`) are bound by the
# JVM's *name-based* lookup, i.e. `Java_<package>_<Class>_<method>` with `_`, `/` and `$` escaped.
# Nothing in the build reads that contract, so a renamed C++ export, a changed signature, or a
# Kotlin `external fun` that drifted from the shim compiles cleanly, passes CI and fails only on a
# device, at the first call, with `UnsatisfiedLinkError`. Only 8 of the dictionary natives have any
# behavioural coverage and the OCR natives have none, so this is the only check that looks at the
# actual `.so`.
#
# What it does:
#   1. finds every `external fun` in `core/dictionary/src/main/java/**` and `core/ocr/src/main/java/**`
#      and derives the JNI symbol the JVM will look up (package + enclosing classes, `$` -> `_00024`,
#      `_` -> `_1`, and the `__<mangled-signature>` suffix when a method is overloaded);
#   2. reads the *exported dynamic* symbols of each module's `.so` for every ABI the module declares
#      in its `abiFilters` (NDK `llvm-nm --defined-only --dynamic`, GNU `nm -D` as fallback);
#   3. fails, naming the Kotlin declaration site, when a declared `external fun` has no exported
#      counterpart in any ABI; exports with no Kotlin declaration are reported as warnings.
#
# A missing library is an *error*, not a skip: a gate that silently passes when it has nothing to
# read is worse than no gate (the `fetchPaddleNativeLibs` checksum-less entry was exactly that bug).
#
# Declared-but-not-yet-exported natives are recorded in `scripts/jni-symbol-allowlist.txt` with a
# reason, so an intentional forward declaration is visible on every run instead of being invisible.
# A stale entry (the symbol is exported now) is reported as a warning, never a failure.
#
# Run directly (`bash scripts/verify-jni-symbols.sh`) or via `./gradlew verifyJniSymbols`, which
# depends on the CMake external build and the OCR native fetch and then runs this script.
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

# declarations_in FILE — one tab-separated `<line> \t <enclosing types> \t <method>` per
# `external fun`. The enclosing chain is found by indentation (Spotless keeps 4-space indents), so a
# nested class yields `Outer$Inner` — the shape the JNI name has to mangle.
declarations_in() {
  awk '
    function indent_of(s,   n) {
      n = 0
      while (substr(s, n + 1, 1) == " ") n++
      return n
    }
    function strip_lead(s) { sub(/^[[:space:]]+/, "", s); return s }
    BEGIN { depth = 0 }
    {
      raw = $0
      ind = indent_of(raw)
      line = strip_lead(raw)

      # A type declaration at this indentation closes every deeper (or same-depth sibling) type.
      if (line ~ /^(@[A-Za-z_][A-Za-z0-9_.]*[[:space:]]+)?((internal|private|public|protected|abstract|open|sealed|final|data|value|annotation|enum|inner|companion|fun|expect|actual)[[:space:]]+)*(class|object|interface)[[:space:]]+[A-Za-z_][A-Za-z0-9_]*/) {
        name = line
        sub(/^.*(class|object|interface)[[:space:]]+/, "", name)
        sub(/[^A-Za-z0-9_].*$/, "", name)
        while (depth > 0 && ind_of[depth] >= ind) depth--
        depth++
        ind_of[depth] = ind
        name_of[depth] = name
        next
      }

      if (line ~ /^((internal|private|public|protected|final|open|override)[[:space:]]+)*external[[:space:]]+fun[[:space:]]+[A-Za-z_][A-Za-z0-9_]*/) {
        method = line
        sub(/^.*external[[:space:]]+fun[[:space:]]+/, "", method)
        sub(/[^A-Za-z0-9_].*$/, "", method)
        # The enclosing types of *this* line: everything declared above at a smaller indent.
        chain = ""
        for (d = 1; d <= depth; d++) {
          if (ind_of[d] < ind) chain = chain (chain == "" ? "" : "$") name_of[d]
        }
        printf "%d\t%s\t%s\n", NR, chain, method
      }
    }
  ' "$1"
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
# Where the built/fetched `.so` lives: the CMake tree for the dictionary, the fetched jniLibs for
# OCR. Both are searched recursively for `<abi>/lib<name>.so`.
declare -A MODULE_LIB_ROOTS=(
  [dictionary]="core/dictionary/build/intermediates/cxx core/dictionary/build/intermediates/cmake core/dictionary/build/intermediates/stripped_native_libs"
  [ocr]="core/ocr/src/main/jniLibs"
)
declare -A MODULE_GRADLE=(
  [dictionary]="core/dictionary/build.gradle.kts"
  [ocr]="core/ocr/build.gradle.kts"
)

abis_of() {
  local module="$1" gradle="${MODULE_GRADLE[$1]}"
  if [[ -n "${VERIFY_JNI_ABIS:-}" ]]; then
    printf '%s\n' $VERIFY_JNI_ABIS
    return 0
  fi
  tr '\n' ' ' <"$gradle" |
    sed -n -E 's/.*abiFilters[[:space:]]*\+=[[:space:]]*listOf\(([^)]*)\).*/\1/p' |
    grep -oE '"[^"]+"' | tr -d '"'
}

# locate_lib MODULE ABI — the library path for one ABI, or empty.
locate_lib() {
  local module="$1" abi="$2" lib="lib${MODULE_LIB[$1]}.so" dir found
  if [[ "$module" == "ocr" ]]; then
    found="$ROOT_DIR/core/ocr/src/main/jniLibs/$abi/$lib"
    [[ -f "$found" ]] && printf '%s' "$found"
    return 0
  fi
  for dir in ${MODULE_LIB_ROOTS[$module]}; do
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
    while IFS=$'\t' read -r line chain method; do
      [[ -z "$method" ]] && continue
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
  done < <(find "${SOURCES[@]}" -name '*.kt' -not -path '*/build/*' | sort)
done

if [[ "${#DECL_METHOD[@]}" -eq 0 ]]; then
  err "found no 'external fun' declaration under ${MODULE_SOURCES[*]} — the parser is broken"
  exit 2
fi

# Derive each symbol, with the `__<signature>` suffix for overloads.
OVERLOADED=()
for i in "${!DECL_FILE[@]}"; do
  key="${DECL_FQN[$i]}#${DECL_METHOD[$i]}"
  symbol="Java_$(mangle_name "${DECL_FQN[$i]}")_${DECL_METHOD[$i]}"
  if [[ "${METHOD_COUNT[$key]}" -gt 1 ]]; then
    SOURCES=(${MODULE_SOURCES[${DECL_MODULE[$i]}]})
    signature="$(signature_of "${DECL_FILE[$i]}" "${DECL_LINE[$i]}")" || exit 2
    symbol="${symbol}__$(mangle_signature "$signature")"
    OVERLOADED+=("${DECL_METHOD[$i]}@${DECL_FQN[$i]} -> $symbol")
  fi
  DECL_SYMBOL+=("$symbol")
done

# ---------------------------------------------------------------------------------------------
# Check every declared symbol against every built ABI, and report exports nobody declares.
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
  abis=($(abis_of "$module"))
  if [[ "${#abis[@]}" -eq 0 ]]; then
    err "cannot read the ABI list from ${MODULE_GRADLE[$module]} (abiFilters += listOf(...))"
    exit 2
  fi

  declare -A union=()
  for abi in "${abis[@]}"; do
    lib="$(locate_lib "$module" "$abi")"
    if [[ -z "$lib" ]]; then
      err "no lib${MODULE_LIB[$module]}.so for '$abi' — build it first:"
      if [[ "$module" == "ocr" ]]; then
        err "    ./gradlew :core:ocr:fetchPaddleNativeLibs"
      else
        err "    ./gradlew :core:dictionary:externalNativeBuildDebug"
      fi
      err "  (searched: ${MODULE_LIB_ROOTS[$module]}; a half-verified gate must not pass)"
      exit 2
    fi
    exports="$(exported_symbols "$lib")" || exit 2
    java_exports="$(grep -E '^Java_' <<<"$exports" | sort -u || true)"
    while IFS= read -r symbol; do
      [[ -n "$symbol" ]] && union["$symbol"]=1
    done <<<"$java_exports"
    note "$module $abi: $(wc -l <<<"$java_exports" | tr -d ' ') exported Java_ symbol(s) in ${lib#"$ROOT_DIR"/}"
  done

  # Reverse direction: an export no Kotlin declaration claims is the other half of the drift.
  for symbol in "${!union[@]}"; do
    if ! grep -qw -- "$symbol" <<<"${DECLARED_BY_MODULE[$module]:-}"; then
      WARNINGS+=("exported but not declared: $symbol ($module)")
    fi
  done

  for i in "${!DECL_SYMBOL[@]}"; do
    [[ "${DECL_MODULE[$i]}" == "$module" ]] || continue
    symbol="${DECL_SYMBOL[$i]}"
    if [[ -n "${union[$symbol]:-}" ]]; then
      CHECKED=$((CHECKED + 1))
      [[ -n "${ALLOWED[$symbol]:-}" ]] &&
        WARNINGS+=("allow-list entry is stale, '$symbol' is exported now — remove it from $ALLOWLIST_FILE")
      continue
    fi
    if [[ -n "${ALLOWED[$symbol]:-}" ]]; then
      ALLOWLISTED=$((ALLOWLISTED + 1))
      warn "declared but not exported (allow-listed): $symbol — ${ALLOWED[$symbol]}"
      continue
    fi
    MISSING+=("$symbol"$'\t'"${DECL_FILE[$i]}:${DECL_LINE[$i]}"$'\t'"$(declaration_text "${DECL_FILE[$i]}" "${DECL_LINE[$i]}")"$'\t'"${abis[*]}")
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
  err "${#MISSING[@]} declared external fun(s) have no exported JNI symbol:"
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
  err "A C++ export was renamed or its signature changed, or the Kotlin declaration drifted from the"
  err "shim. Fix the bridge (both sides), or record an intentional forward declaration in $ALLOWLIST_FILE."
  exit 1
fi

note "OK: $CHECKED declared external fun(s) match an exported symbol in every ABI (${ALLOWLISTED} allow-listed, ${#WARNINGS[@]} warning(s))"
