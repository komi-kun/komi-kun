---
name: yomitan-corpus-sync
description: Refresh the pinned Yomitan conformance corpus (tools/yomitan-corpus/ + core/dictionary/src/test/resources/yomitan/) when upstream Yomitan's tests change, and keep the manifest hashes honest so CI stays green. Use when asked to update the Yomitan behavior corpus, bump the pinned Yomitan commit, or when YomitanCorpusTest / verify-manifest.mjs reports drift.
---

# Refreshing the Yomitan conformance corpus

We pin a slice of upstream **Yomitan**'s own test data as a committed, hermetic corpus so the
dictionary behavior can be checked without a Yomitan checkout. Everything lives in
`tools/yomitan-corpus/` + `core/dictionary/src/test/resources/yomitan/`.

| File | Role |
|---|---|
| `tools/yomitan-corpus/build.mjs` | Deterministic generator: extracts **data-only** literals from Yomitan's `test/language/japanese-transforms.test.js` (+ `test/japanese-util.test.js`) and writes the JSONL + manifest |
| `tools/yomitan-corpus/verify-manifest.mjs` | Standalone hash/count checker (CI-equivalent) |
| `core/dictionary/src/test/resources/yomitan/{ja-transforms,ja-normalization,lookup-ja,lookup-ja.dict}.jsonl` + `manifest.json` | The committed corpus (currently ~1446 transform tuples + 405 normalization) |

The JVM test `core/dictionary/src/test/java/…/corpus/YomitanCorpusTest.kt` gates CI on the manifest
hashes/counts, so **corpus drift fails the PR build for free**.

## When to refresh

- Yomitan changed `test/language/japanese-transforms.test.js` or `test/japanese-util.test.js`.
- We bump the pinned Yomitan commit (recorded in the manifest).
- `YomitanCorpusTest` or `node tools/yomitan-corpus/verify-manifest.mjs` reports a hash/count mismatch.

## Refresh procedure

```bash
# 1. get the Yomitan source at the commit you want to pin
git clone --depth 1 https://github.com/yomidevs/yomitan.git /tmp/opencode/yomitan   # or git fetch
git -C /tmp/opencode/yomitan rev-parse HEAD      # the new pinned commit

# 2. bump the pin: edit the hardcoded `YOMITAN` literal (commit/commitShort/version) in build.mjs
#    — there is no `--commit` flag; the pin exists only in that literal

# 3. regenerate the JSONL + manifest.json (the manifest is written by this same run)
#    the script refuses unless the checkout HEAD equals `YOMITAN.commit` — bypass with
#    --skip-commit-check; a changed input hash is refused unless --update
node tools/yomitan-corpus/build.mjs --yomitan /tmp/opencode/yomitan --skip-commit-check --update

# 4. review the diff (added/removed tuples, counts), then commit
git diff --stat core/dictionary/src/test/resources/yomitan/
```

- Generators must stay **deterministic** — no timestamps; re-running must produce identical bytes.
- The pin is the `YOMITAN` literal in `build.mjs`; the run above writes `manifest.json` itself (commit +
  SHA-256/bytes/count of inputs and outputs). No separate manifest-refresh command exists —
  `verify-manifest.mjs` only *checks* the manifest against the files.

## Verify

```bash
node tools/yomitan-corpus/verify-manifest.mjs                 # hashes/counts
node tools/yomitan-corpus/verify-manifest.mjs --yomitan /tmp/opencode/yomitan   # + input hashes
flock /tmp/opencode/gradle.lock ./gradlew :core:dictionary:testDebugUnitTest
```

## Notes

- The schema/hash gate is `YomitanCorpusTest`; the corpus is also **executed** against the native
  engine by the T2 runner `core/dictionary/src/test/java/…/corpus/YomitanEngineConformanceTest.kt`
  (`./gradlew :core:dictionary:conformanceHostTest`). It builds a **host** copy of the
  `hoshidicts_jni` bridge (the Android `.so` cannot load in a desktop JVM), deinflects every transform
  tuple, runs the normalization expectations, and imports `lookup-ja.dict.jsonl` as a real dictionary
  to assert `lookup-ja.jsonl`. It is not part of `testDebugUnitTest` (it needs a host C++ toolchain);
  PR CI runs it in the path-filtered `conformance-t2` job. Engine gaps are committed per corpus row in
  `engine-divergences-ja.json`, and the runner fails **both** on an unlisted failure and on a recorded
  row that starts passing, so a refresh must re-derive that ledger (see
  `docs/design/implementation/05-testing-and-anki.md` §A1.5).
- The corpus is GPL-3.0-or-later (derived from Yomitan). Keep the provenance header/`manifest.spdx`.
