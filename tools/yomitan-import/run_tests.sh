#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Run the self-contained tests for the Yomitan import converter.
# Bootstraps a throwaway virtualenv (outside the repo) so only `python3` is required.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
venv="${TMPDIR:-/tmp}/yomitan-import-test-venv"

if [ ! -x "$venv/bin/python" ]; then
  echo "creating test venv at $venv" >&2
  python3 -m venv "$venv"
fi
"$venv/bin/pip" install --quiet --disable-pip-version-check -r "$here/requirements.txt"

cd "$here"
exec "$venv/bin/python" -m unittest -v test_dexie_to_yomitan
