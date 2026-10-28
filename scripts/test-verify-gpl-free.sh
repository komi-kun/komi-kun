#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# test-verify-gpl-free.sh — self-test for `scripts/verify-gpl-free.sh`.
#
# A guard that passes on the current tree proves nothing: it may simply not look at the shapes a
# violation can take. This runner plants one violation of each shape in a fixture tree (see
# `scripts/test-fixtures/gpl-free-guard/`) and asserts that the guard fails, for the *right* reason,
# and still passes on a clean file:
#
#   1. `import` of a GPL FQN                     (the original check)
#   2. unimported same-package use of a GPL type (evasion (a))
#   3. fully qualified use with no import        (evasion (c))
#   4. star import of a *mixed* package plus an unqualified use of its GPL declaration, and the
#      negative counterpart (star import, Apache sibling only) — plus a same-package GPL name in a
#      string literal (must pass) and in a string template (must fail).
#
# The last three cases repeat (1)/(a) against the *real* tree, with FQNs the hand-maintained deny list
# never contained (a GPL class, a GPL top-level function, and a GPL bindings object next to its seam),
# so a regression in the SPDX derivation is caught too.
#
# Run directly (`bash scripts/test-verify-gpl-free.sh`) or via `./gradlew verifyGplFree`, which runs
# it after the boundary scan.
#
# Exit codes: 0 = the guard behaved as required, 1 = a self-test case failed.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
GUARD="$SCRIPT_DIR/verify-gpl-free.sh"
FIXTURE_REL="scripts/test-fixtures/gpl-free-guard"
FIXTURE_DIR="$ROOT_DIR/$FIXTURE_REL"
FIXTURE_ROOT="$FIXTURE_DIR/tree"

pass=0
fail=0

# run_case NAME ROOT SEAM_LIST SCAN_ROOTS EXPECTED_STATUS EXPECTED_TEXT
#   SEAM_LIST is absolute here (it is resolved against ROOT when relative). An empty SCAN_ROOTS means
#   "let the guard use its defaults".
run_case() {
  local name="$1" root="$2" list="$3" roots="$4" expected_status="$5" expected_text="$6"
  local output status

  output="$(
    cd "$ROOT_DIR" &&
      VERIFY_GPL_ROOT="$root" \
        VERIFY_GPL_SEAM_LIST="$list" \
        VERIFY_GPL_SCAN_ROOTS="$roots" \
        bash "$GUARD" 2>&1
  )"
  status=$?

  if [[ "$status" -ne "$expected_status" ]]; then
    echo "test-verify-gpl-free: FAIL — $name: exit $status, expected $expected_status" >&2
    echo "$output" >&2
    fail=$((fail + 1))
    return
  fi
  if ! grep -Fq "$expected_text" <<<"$output"; then
    echo "test-verify-gpl-free: FAIL — $name: output does not contain '$expected_text'" >&2
    echo "$output" >&2
    fail=$((fail + 1))
    return
  fi

  echo "test-verify-gpl-free: ok — $name"
  pass=$((pass + 1))
}

# 0. A clean seam file passes, even though it shares its package with a GPL declaration.
run_case "clean fixture passes" \
  "$FIXTURE_ROOT" "$FIXTURE_DIR/cases/clean.list" "gpl" 0 \
  "verify-gpl-free: OK"

# 1. An `import` of a denied FQN fails.
run_case "import of a GPL FQN fails" \
  "$FIXTURE_ROOT" "$FIXTURE_DIR/cases/import.list" "gpl" 1 \
  "imports a GPL package: 'fixture.gpl.GplThing'"

# 2. An unimported same-package reference to a GPL declaration fails.
run_case "same-package reference fails" \
  "$FIXTURE_ROOT" "$FIXTURE_DIR/cases/same-package.list" "gpl" 1 \
  "references GPL code in the same package: 'FixtureGplHelper'"

# 3. A fully qualified use with no import fails.
run_case "fully qualified use fails" \
  "$FIXTURE_ROOT" "$FIXTURE_DIR/cases/qualified.list" "gpl" 1 \
  "references GPL code without an import: 'fixture.gpl.GplThing"

# 3a. A star import of a mixed package plus an unqualified use of its GPL declaration fails.
run_case "star import of a mixed package fails" \
  "$FIXTURE_ROOT" "$FIXTURE_DIR/cases/star-import.list" "mixed" 1 \
  "references GPL code through the star import 'fixture.mixed.*': 'GplMixedThing'"

# 3b. …but star-importing that package to use its Apache declaration passes.
run_case "star import of a mixed package using only the Apache sibling passes" \
  "$FIXTURE_ROOT" "$FIXTURE_DIR/cases/star-import-clean.list" "mixed" 0 \
  "verify-gpl-free: OK"

# 3c. A same-package GPL name in a string literal (a log tag) is prose, not a reference: it passes.
run_case "string literal naming a same-package GPL declaration passes" \
  "$FIXTURE_ROOT" "$FIXTURE_DIR/cases/string-literal.list" "gpl" 0 \
  "verify-gpl-free: OK"

# 3d. …while the same name used inside a string *template* is still a reference: it fails.
run_case "string template using a same-package GPL declaration fails" \
  "$FIXTURE_ROOT" "$FIXTURE_DIR/cases/string-interpolation.list" "gpl" 1 \
  "references GPL code in the same package: 'FixtureGplHelper'"

# 4. Real tree: a GPL class the hand-maintained deny list never listed.
run_case "real tree: import of an unlisted GPL class fails" \
  "$ROOT_DIR" "$FIXTURE_REL/cases/realtree-import.list" "" 1 \
  "imports a GPL package: 'eu.kanade.tachiyomi.ui.reader.loader.ArchiveReadGuard'"

# 5. Real tree: a GPL top-level function (not a type) is denied as well.
run_case "real tree: import of a GPL function fails" \
  "$ROOT_DIR" "$FIXTURE_REL/cases/realtree-import-function.list" "" 1 \
  "imports a GPL package: 'eu.kanade.tachiyomi.ui.dictionary.DictionaryEntryWebView'"

# 6. Real tree: a seam file using the GPL bindings object of its own package fails.
run_case "real tree: same-package GPL reference fails" \
  "$ROOT_DIR" "$FIXTURE_REL/cases/realtree-same-package.list" "" 1 \
  "references GPL code in the same package: 'OcrHostBindings'"

if [[ "$fail" -ne 0 ]]; then
  echo "test-verify-gpl-free: FAILED — $fail of $((pass + fail)) case(s) did not behave as required." >&2
  exit 1
fi

echo "test-verify-gpl-free: OK — $pass case(s): the guard fails on every planted violation and passes the clean file."
