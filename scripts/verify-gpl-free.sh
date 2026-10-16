#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# verify-gpl-free.sh — enforces the GPL-free boundary (contract §1.3/§1.9, owner W1).
#
# The §1.3 file set may be contributed upstream (to Komikku) and therefore must import no GPL
# package. This script greps ONLY that set for `import` lines and fails when an imported FQN
# starts with a deny prefix and is not exactly one of the allowed Apache seam FQNs.
#
# Run directly (`bash scripts/verify-gpl-free.sh`) or via the Gradle task in :app
# (`./gradlew verifyGplFree`), which wraps this script so CI and local runs share one code path.
#
# Exit codes: 0 = clean, 1 = boundary violation(s), 2 = environment error.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

# ---------------------------------------------------------------------------------------------
# §1.3 GPL-free file set (repo-relative).
#
# Every path is scanned for GPL imports. A *missing* path is a hard error so that path drift can
# never silently drop a file from coverage — except for paths listed in PENDING_WAVES, which a
# later wave creates; once such a file exists it is scanned automatically.
# ---------------------------------------------------------------------------------------------
GPL_FREE_FILES=(
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
)

# Created by a later wave; absent until then (W3 adds 54.sqm).
PENDING_WAVES=(
  "data/src/main/sqldelight/tachiyomi/migrations/54.sqm"
)

# ---------------------------------------------------------------------------------------------
# Rule data (contract §1.9). Keep these lists exactly in sync with the contract.
# ---------------------------------------------------------------------------------------------
DENY_PREFIXES=(
  "mihon.core.dictionary"
  "mihon.core.ocr"
  "mihon.dictionary"
  "eu.kanade.tachiyomi.ui.reader.ocr"
  "eu.kanade.tachiyomi.ui.reader.overlay"
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
  extract_imports() {
    rg --no-filename --only-matching --replace '$1' \
      '^[[:space:]]*import[[:space:]]+([A-Za-z0-9_.]+)' "$1" || true
  }
else
  extract_imports() {
    grep -oE '^[[:space:]]*import[[:space:]]+[A-Za-z0-9_.]+' "$1" 2>/dev/null \
      | sed -E 's/^[[:space:]]*import[[:space:]]+//' || true
  }
fi

is_allowed() {
  local fqn="$1" allowed
  for allowed in "${ALLOWED_FQNS[@]}"; do
    [[ "$fqn" == "$allowed" ]] && return 0
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

is_pending() {
  local path="$1" pending
  for pending in "${PENDING_WAVES[@]}"; do
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

  while IFS= read -r fqn; do
    [[ -z "$fqn" ]] && continue
    fqn="${fqn%.\*}" # tolerate a `.*` star-import suffix
    is_allowed "$fqn" && continue
    if matches_deny "$fqn"; then
      echo "verify-gpl-free: ERROR: '$file' imports a GPL package: '$fqn'" >&2
      fail=1
    fi
  done < <(extract_imports "$file")
done

if [[ "$fail" -ne 0 ]]; then
  echo "verify-gpl-free: FAILED — the GPL-free file set (§1.3) imports GPL code (§1.9)." >&2
  exit 1
fi

echo "verify-gpl-free: OK — $scanned GPL-free file(s) import no GPL package."
