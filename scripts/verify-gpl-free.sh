#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# verify-gpl-free.sh — enforces the GPL-free boundary (contract §1.3/§1.9, owner W1).
#
# The §1.3 file set may be contributed upstream (to Komikku) and therefore must import no GPL
# package. This script greps ONLY that set for `import` lines and fails when an imported FQN
# starts with a deny prefix (or is an exact denied FQN for a GPL type living in an otherwise
# Apache package, e.g. the viewer OCR adapters / `OcrOverlay`) and is not exactly one of the
# allowed Apache seam FQNs. It also fails if a §1.3 file declares a GPL license header.
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
  # Apache by necessity, not by accident: the §1.3 reader files gate on these two. The preference is
  # imported by `ReaderActivity`/`ReaderViewModel`; the two reader policies are *called* by
  # `ReaderActivity`/`ReaderViewModel` from the same package, so an import-based scan cannot see them
  # — listing them here is the only guard that they never gain a GPL header or GPL import.
  "core/common/src/main/kotlin/tachiyomi/core/common/preference/DictionaryOcrPreferences.kt"
  "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderOcrTogglePolicy.kt"
  "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderOcrOverlayPolicy.kt"
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

# GPL types that live in packages which also contain Apache (upstream) files that §1.3 files
# legitimately import, so the package cannot be denied wholesale:
#
#   eu.kanade.tachiyomi.ui.reader.viewer.pager.SsivOcrViewport   (pager package also has PagerViewer…)
#   eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonOcrViewport
#   eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuOcrViewport
#   eu.kanade.presentation.reader.OcrOverlay
#
# A §1.3 file must never import these GPL viewport adapters / overlay (contract §1.2/§1.3), so they
# are denied by exact FQN. (The GPL `…reader.ocr.impl`/`…reader.ocr` files stay covered by the
# `eu.kanade.tachiyomi.ui.reader.ocr` deny prefix above.)
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

matches_deny_fqn() {
  local fqn="$1" denied
  for denied in "${DENY_FQNS[@]}"; do
    [[ "$fqn" == "$denied" ]] && return 0
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

  # §1.3 files keep an Apache-2.0 (or no) license header — a GPL header here would mean a GPL file
  # was added to (or a GPL relicense silently landed on) the upstream-contributable set.
  if grep -q 'SPDX-License-Identifier:.*GPL' "$file"; then
    echo "verify-gpl-free: ERROR: '$file' is in the GPL-free set (§1.3) but declares a GPL license" >&2
    fail=1
  fi

  while IFS= read -r fqn; do
    [[ -z "$fqn" ]] && continue
    fqn="${fqn%.\*}" # tolerate a `.*` star-import suffix
    is_allowed "$fqn" && continue
    if matches_deny "$fqn" || matches_deny_fqn "$fqn"; then
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
