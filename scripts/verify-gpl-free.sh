#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# verify-gpl-free.sh — enforces the GPL-free boundary (contract §1.3/§1.9, owner W1).
#
# The §1.3 file set may be contributed upstream (to Komikku) and therefore must not reference any GPL
# code. The denied set is derived from the tree instead of being hand-maintained: every `.kt`/`.kts`
# under the scanned roots whose header carries `SPDX-License-Identifier: …GPL…` contributes its
# Kotlin package and the fully qualified names of its top-level declarations, and those names are
# denied in each of the three shapes a seam file can reach GPL code:
#
#   1. an `import` of a denied package / FQN (the original check);
#   2. an *unimported, same-package* reference to a GPL declaration — the package itself cannot be
#      denied wholesale because the Apache seam and its GPL `…Bindings` are siblings by design
#      (e.g. `…reader.ocr.OcrViewport` next to `…reader.ocr.OcrHostBindings`);
#   3. a fully-qualified use anywhere in the body, with no `import` line at all.
#
# It also fails if a §1.3 file declares a GPL license header, and if a listed path is missing
# (path drift must never silently drop coverage).
#
# Run directly (`bash scripts/verify-gpl-free.sh`) or via the Gradle task in :app
# (`./gradlew verifyGplFree`), which wraps this script — and its self-test
# (`scripts/test-verify-gpl-free.sh`) — so CI and local runs share one code path.
#
# Self-test plumbing (used by scripts/test-verify-gpl-free.sh, unused otherwise):
#   VERIFY_GPL_ROOT        tree to scan                (default: the repository root)
#   VERIFY_GPL_SEAM_LIST   file of seam paths, one per line, relative to that root (the list path
#                          itself is resolved against that root when it is relative)
#   VERIFY_GPL_SCAN_ROOTS  space-separated source roots to derive the denied set from
#
# Exit codes: 0 = clean, 1 = boundary violation(s), 2 = environment error.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="${VERIFY_GPL_ROOT:-$(cd "$SCRIPT_DIR/.." && pwd)}"
cd "$ROOT_DIR"

# ---------------------------------------------------------------------------------------------
# §1.3 GPL-free file set (repo-relative).
#
# Every path is scanned for GPL references. A *missing* path is a hard error so that path drift can
# never silently drop a file from coverage — except for paths listed in PENDING_WAVES, which a later
# wave creates; once such a file exists it is scanned automatically.
# ---------------------------------------------------------------------------------------------
SEAM_FILES=(
  "core/common/src/main/java/tachiyomi/core/common/ocr/ReaderOcrSource.kt"
  "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ocr/OcrViewport.kt"
  "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ocr/ReaderOcrHost.kt"
  "app/src/main/java/mihon/dictionary/host/ReaderDictionaryHost.kt"
  "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt"
  "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt"
  "app/src/main/java/eu/kanade/tachiyomi/ui/reader/setting/ReaderPreferences.kt"
  "app/src/main/java/eu/kanade/tachiyomi/ui/reader/setting/ReaderBottomButton.kt"
  "app/src/main/java/eu/kanade/presentation/reader/appbars/ReaderAppBars.kt"
  "domain/src/main/java/tachiyomi/domain/chapter/model/Chapter.kt"
  "domain/src/main/java/tachiyomi/domain/chapter/model/ChapterUpdate.kt"
  "data/src/main/sqldelight/tachiyomi/migrations/54.sqm"
  "data/src/main/java/tachiyomi/data/chapter/ChapterMapper.kt"
  "data/src/main/sqldelight/tachiyomi/data/chapter.sq"
  # Apache by necessity, not by accident: the §1.3 reader files gate on these two. The preference is
  # imported by `ReaderActivity`/`ReaderViewModel`; the two reader policies are *called* by
  # `ReaderActivity`/`ReaderViewModel` from the same package, so an import-based scan cannot see them
  # — listing them here is the only guard that they never gain a GPL header or GPL reference.
  "core/common/src/main/kotlin/tachiyomi/core/common/preference/DictionaryOcrPreferences.kt"
  "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderOcrTogglePolicy.kt"
  "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderOcrOverlayPolicy.kt"
)

# Created by a later wave; absent until then. (Empty: every seam file exists today — `54.sqm`
# landed with the chapter-update wave and is now part of SEAM_FILES above.)
PENDING_WAVES=()

# Source roots the denied set is derived from. Only Kotlin sources can declare a GPL FQN.
SCAN_ROOTS=(
  "app/src"
  "core"
  "domain"
  "data"
  "presentation-core"
  "presentation-widget"
  "source-api"
  "source-local"
)

if [[ -n "${VERIFY_GPL_SEAM_LIST:-}" ]]; then
  seam_list="$VERIFY_GPL_SEAM_LIST"
  [[ "$seam_list" = /* ]] || seam_list="$ROOT_DIR/$seam_list"
  mapfile -t GPL_FREE_FILES < <(
    sed -E -e 's/#.*$//' -e 's/^[[:space:]]+//' -e 's/[[:space:]]+$//' "$seam_list" | grep -v '^$' || true
  )
else
  GPL_FREE_FILES=("${SEAM_FILES[@]}")
fi
if [[ -n "${VERIFY_GPL_SCAN_ROOTS:-}" ]]; then
  read -r -a SCAN_ROOTS <<<"$VERIFY_GPL_SCAN_ROOTS"
fi

# ---------------------------------------------------------------------------------------------
# Rule data (contract §1.9). The prefixes below are the coarse rules; the exact FQNs are the
# documented exceptions (and are also re-derived from the tree, so the list can only grow).
# ---------------------------------------------------------------------------------------------
DENY_PREFIXES=(
  "mihon.core.dictionary"
  "mihon.core.ocr"
  "mihon.dictionary"
  "eu.kanade.tachiyomi.ui.reader.ocr"
  "eu.kanade.tachiyomi.ui.reader.overlay"
)

# GPL types that live in packages which also contain Apache (upstream) files that §1.3 files
# legitimately import, so the package cannot be denied wholesale:
#
#   eu.kanade.tachiyomi.ui.reader.viewer.pager.SsivOcrViewport   (pager package also has PagerViewer…)
#   eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonOcrViewport
#   eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuOcrViewport
#   eu.kanade.presentation.reader.OcrOverlay
#
# A §1.3 file must never reference these GPL viewport adapters / overlay (contract §1.2/§1.3), so they
# are denied by exact FQN. (The GPL `…reader.ocr.impl`/`…reader.ocr` files stay covered by the
# `eu.kanade.tachiyomi.ui.reader.ocr` deny prefix above, and every other GPL type is denied by the
# set derived from the SPDX headers below.)
DENY_FQNS=(
  "eu.kanade.tachiyomi.ui.reader.viewer.pager.SsivOcrViewport"
  "eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonOcrViewport"
  "eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuOcrViewport"
  "eu.kanade.presentation.reader.OcrOverlay"
)

ALLOWED_FQNS=(
  "mihon.dictionary.host.ReaderDictionaryHost"
  "mihon.dictionary.host.DictionaryLookupRequest"
  "eu.kanade.tachiyomi.ui.reader.ocr.OcrViewport"
  "eu.kanade.tachiyomi.ui.reader.ocr.ImagePoint"
  "eu.kanade.tachiyomi.ui.reader.ocr.OcrDisplayTransform"
  "eu.kanade.tachiyomi.ui.reader.ocr.ReaderOcrHost"
)

if command -v rg >/dev/null 2>&1; then
  HAVE_RG=1
else
  HAVE_RG=0
fi

# list_sources — every Kotlin source file under the scanned roots.
list_sources() {
  if [[ "$HAVE_RG" -eq 1 ]]; then
    rg --files -g '*.kt' -g '*.kts' -g '!**/build/**' "${SCAN_ROOTS[@]}" 2>/dev/null || true
  else
    find "${SCAN_ROOTS[@]}" -path '*/build/*' -prune -o \( -name '*.kt' -o -name '*.kts' \) -print 2>/dev/null || true
  fi
}

# match_in FILE REGEX — every match of REGEX in FILE, one per line.
match_in() {
  if [[ "$HAVE_RG" -eq 1 ]]; then
    rg --no-filename --only-matching "$1" "$2" 2>/dev/null || true
  else
    grep -oE "$1" "$2" 2>/dev/null || true
  fi
}

# strip_comments FILE — drop `//` comments and comment-only lines, so prose about a GPL type is not
# mistaken for a reference to it.
strip_comments() {
  sed -E -e 's/[[:space:]]+\/\/.*$//' -e 's/^[[:space:]]*(\/\/|\*|\/\*).*$//' "$1"
}

# referencing_code FILE — the file minus comments, its own `package` line and its `import` lines,
# i.e. the code that uses types. Imports are checked separately so the message can name the shape of
# the violation.
referencing_code() {
  strip_comments "$1" | grep -vE '^[[:space:]]*(import|package)[[:space:]]' || true
}

extract_imports() {
  match_in '^[[:space:]]*import[[:space:]]+[A-Za-z0-9_.]+' "$1" \
    | sed -E 's/^[[:space:]]*import[[:space:]]+//'
}

package_of() {
  match_in '^[[:space:]]*package[[:space:]]+[A-Za-z0-9_.]+' "$1" \
    | sed -E 's/^[[:space:]]*package[[:space:]]+//' \
    | head -n 1
}

# Top-level Kotlin declarations of a file: `class`/`interface`/`object`/`typealias` (with annotations
# and modifiers) starting in column 0. Nested declarations and local `object`s are indented, so they
# never match; their FQNs are covered by their parent's.
MODIFIERS='public|internal|private|protected|abstract|final|open|sealed|data|enum|annotation|value|inline|fun|operator|infix|tailrec|expect|actual|suspend|external|const|lateinit'
TOP_LEVEL_DECLARATION="^((@[A-Za-z_][A-Za-z0-9_.]*(\\([^)]*\\))?[[:space:]]+)|((${MODIFIERS})[[:space:]]+))*(class|interface|object|typealias)[[:space:]]+[A-Za-z_][A-Za-z0-9_]*"

top_level_declaration_names() {
  match_in "$TOP_LEVEL_DECLARATION" "$1" \
    | sed -E 's/.*(class|interface|object|typealias)[[:space:]]+([A-Za-z_][A-Za-z0-9_]*)$/\2/'
}

# Top-level functions and properties are importable/qualifiable FQNs too (`import
# eu.kanade.tachiyomi.ui.dictionary.DictionaryEntryWebView` imports a Composable *function*), so they
# are derived as well. Extension functions reduce to the extension name (`String.foo` -> `foo`),
# which is what an import names.
TOP_LEVEL_CALLABLE="^((@[A-Za-z_][A-Za-z0-9_.]*(\\([^)]*\\))?[[:space:]]+)|((${MODIFIERS})[[:space:]]+))*(fun|val|var)[[:space:]]+"

lines_matching() {
  if [[ "$HAVE_RG" -eq 1 ]]; then
    rg --no-filename "$1" "$2" 2>/dev/null || true
  else
    grep -E "$1" "$2" 2>/dev/null || true
  fi
}

top_level_callable_names() {
  lines_matching "$TOP_LEVEL_CALLABLE" "$1" \
    | sed -E -e 's/^(@[A-Za-z_][A-Za-z0-9_.]*(\([^)]*\))?[[:space:]]+)*//' \
      -e "s/^((${MODIFIERS})[[:space:]]+)*//" \
    | sed -E 's/^<[^>]*>[[:space:]]*//' \
    | sed -E -e 's/[[:space:]].*$//' -e 's/[(:={].*$//' \
    | sed -E -e 's/.*\.//' -e 's/[<>?].*$//' \
    | grep -E '^[A-Za-z_][A-Za-z0-9_]*$' || true
}

is_gpl_file() {
  head -n 5 "$1" 2>/dev/null | grep -q 'SPDX-License-Identifier:.*GPL'
}

# list_gpl_sources — the scanned Kotlin files whose *header* (first 5 lines) carries a GPL SPDX line.
# Batched into one pass: the per-file loop it replaces dominated the runtime (thousands of forks).
list_gpl_sources() {
  if [[ "$HAVE_RG" -eq 1 ]]; then
    rg --with-filename --line-number --no-heading 'SPDX-License-Identifier:.*GPL' \
      -g '*.kt' -g '*.kts' -g '!**/build/**' "${SCAN_ROOTS[@]}" 2>/dev/null \
      | awk -F: '$2 <= 5 { print $1 }' \
      | sort -u || true
  else
    list_sources | while IFS= read -r source; do
      is_gpl_file "$source" && printf '%s\n' "$source"
    done
  fi
}

# ---------------------------------------------------------------------------------------------
# Derive the denied set from the tree.
# ---------------------------------------------------------------------------------------------
declare -A GPL_TYPES_IN_PKG=()
DERIVED_FQNS=()

for root in "${SCAN_ROOTS[@]}"; do
  if [[ ! -d "$root" ]]; then
    echo "verify-gpl-free: ERROR: source root is missing: '$root' (cannot derive the denied set)" >&2
    exit 2
  fi
done

while IFS= read -r source; do
  [[ -z "$source" ]] && continue
  pkg="$(package_of "$source")"
  [[ -z "$pkg" ]] && continue
  while IFS= read -r name; do
    [[ -z "$name" ]] && continue
    DERIVED_FQNS+=("$pkg.$name")
    GPL_TYPES_IN_PKG["$pkg"]="${GPL_TYPES_IN_PKG[$pkg]:-}${GPL_TYPES_IN_PKG[$pkg]:+ }$name"
  done < <(top_level_declaration_names "$source")
  while IFS= read -r name; do
    [[ -z "$name" ]] && continue
    DERIVED_FQNS+=("$pkg.$name")
  done < <(top_level_callable_names "$source")
done < <(list_gpl_sources)

# Deterministic, duplicate-free: the pattern below is built from this array.
mapfile -t DERIVED_FQNS < <(printf '%s\n' ${DERIVED_FQNS[@]+"${DERIVED_FQNS[@]}"} | sort -u)

if [[ "${#DERIVED_FQNS[@]}" -eq 0 ]]; then
  echo "verify-gpl-free: ERROR: derived no GPL FQNs from '${SCAN_ROOTS[*]}' — the SPDX detection is broken" >&2
  exit 2
fi

# One regex that matches a denied name *or* any dotted chain that starts with it, so a
# fully-qualified use is caught even without an `import`.
escape_fqn() {
  printf '%s' "${1//./\\.}"
}

DENY_ALTERNATIVES=()
for prefix in "${DENY_PREFIXES[@]}"; do
  DENY_ALTERNATIVES+=("$(escape_fqn "$prefix")")
done
for fqn in "${DENY_FQNS[@]}" "${DERIVED_FQNS[@]}"; do
  DENY_ALTERNATIVES+=("$(escape_fqn "$fqn")")
done
DENY_PATTERN="($(
  IFS='|'
  printf '%s' "${DENY_ALTERNATIVES[*]}"
))(\.[A-Za-z_][A-Za-z0-9_]*)*"

is_allowed() {
  local fqn="$1" allowed
  for allowed in "${ALLOWED_FQNS[@]}"; do
    [[ "$fqn" == "$allowed" ]] && return 0
  done
  return 1
}

# A reference is allowed when it *is* a seam FQN or when it selects/invokes a member of one
# (`ReaderDictionaryHost.Companion`, `ReaderDictionaryHost.lookup(...)`).
is_allowed_use() {
  local fqn="$1" allowed
  for allowed in "${ALLOWED_FQNS[@]}"; do
    if [[ "$fqn" == "$allowed" || "$fqn" == "$allowed".* ]]; then
      return 0
    fi
  done
  return 1
}

matches_deny() {
  local fqn="$1" prefix
  for prefix in "${DENY_PREFIXES[@]}"; do
    if [[ "$fqn" == "$prefix" || "$fqn" == "$prefix".* ]]; then
      return 0
    fi
  done
  return 1
}

matches_deny_fqn() {
  local fqn="$1" denied
  for denied in "${DENY_FQNS[@]}" ${DERIVED_FQNS[@]+"${DERIVED_FQNS[@]}"}; do
    if [[ "$fqn" == "$denied" || "$fqn" == "$denied".* ]]; then
      return 0
    fi
  done
  return 1
}

is_pending() {
  local path="$1" pending
  for pending in ${PENDING_WAVES[@]+"${PENDING_WAVES[@]}"}; do
    [[ "$path" == "$pending" ]] && return 0
  done
  return 1
}

fail=0
scanned=0

for file in "${GPL_FREE_FILES[@]}"; do
  if [[ ! -f "$file" ]]; then
    if is_pending "$file"; then
      echo "verify-gpl-free: note: '$file' not present yet (created by a later wave); skipped"
      continue
    fi
    echo "verify-gpl-free: ERROR: GPL-free file is missing: '$file'" >&2
    fail=1
    continue
  fi

  scanned=$((scanned + 1))

  # §1.3 files keep an Apache-2.0 (or no) license header — a GPL header here would mean a GPL file
  # was added to (or a GPL relicense silently landed on) the upstream-contributable set.
  if grep -q 'SPDX-License-Identifier:.*GPL' "$file"; then
    echo "verify-gpl-free: ERROR: '$file' is in the GPL-free set (§1.3) but declares a GPL license" >&2
    fail=1
  fi

  # (1) imports
  while IFS= read -r fqn; do
    [[ -z "$fqn" ]] && continue
    fqn="${fqn%.\*}" # tolerate a `.*` star-import suffix
    is_allowed "$fqn" && continue
    if matches_deny "$fqn" || matches_deny_fqn "$fqn"; then
      echo "verify-gpl-free: ERROR: '$file' imports a GPL package: '$fqn'" >&2
      fail=1
    fi
  done < <(extract_imports "$file")

  # (3) fully-qualified use in the body (no `import` needed) — comments and imports excluded.
  while IFS= read -r reference; do
    [[ -z "$reference" ]] && continue
    is_allowed_use "$reference" && continue
    if matches_deny "$reference" || matches_deny_fqn "$reference"; then
      echo "verify-gpl-free: ERROR: '$file' references GPL code without an import: '$reference'" >&2
      fail=1
    fi
  done < <(referencing_code "$file" | grep -oE "$DENY_PATTERN" || true)

  # (2) same-package reference to a GPL declaration. The package cannot be denied wholesale (the
  # seam and its GPL `…Bindings` share it by design), but no seam file may *use* a GPL type that
  # lives beside it: that use has no import and no package qualifier, so neither pass above sees it.
  file_pkg="$(package_of "$file")"
  same_pkg_types=""
  if [[ -n "$file_pkg" ]]; then
    same_pkg_types="${GPL_TYPES_IN_PKG[$file_pkg]:-}"
  fi
  if [[ -n "$same_pkg_types" ]]; then
    same_pkg_pattern="\\b($(printf '%s' "$same_pkg_types" | tr ' ' '|'))\\b"
    while IFS= read -r reference; do
      [[ -z "$reference" ]] && continue
      echo "verify-gpl-free: ERROR: '$file' references GPL code in the same package: '$reference'" >&2
      fail=1
    done < <(referencing_code "$file" | grep -oE "$same_pkg_pattern" | sort -u || true)
  fi
done

if [[ "$fail" -ne 0 ]]; then
  echo "verify-gpl-free: FAILED — the GPL-free file set (§1.3) references GPL code (§1.9)." >&2
  exit 1
fi

echo "verify-gpl-free: OK — $scanned GPL-free file(s) reference no GPL package or type (${#DERIVED_FQNS[@]} GPL FQN(s) denied)."
