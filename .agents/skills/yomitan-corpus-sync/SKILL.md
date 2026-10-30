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

# 2. regenerate (the script refuses unless the checkout HEAD matches the pinned commit, unless --update)
node tools/yomitan-corpus/build.mjs --update --yomitan /tmp/opencode/yomitan --commit <sha>

# 3. review the JSONL diff (added/removed tuples, counts), then commit
git diff --stat core/dictionary/src/test/resources/yomitan/
```

- Generators must stay **deterministic** — no timestamps; re-running must produce identical bytes.
- The manifest records the Yomitan commit + the SHA-256 of inputs and outputs; keep it current.

## Verify

```bash
node tools/yomitan-corpus/verify-manifest.mjs                 # hashes/counts
node tools/yomitan-corpus/verify-manifest.mjs --yomitan /tmp/opencode/yomitan   # + input hashes
flock /tmp/opencode/gradle.lock ./gradlew :core:dictionary:testDebugUnitTest
```

## Notes

- The corpus is a **schema/hash gate today**, not a runtime oracle — nothing yet feeds the tuples
  through the native engine. Adding a T2 host-conformance (or emulator) runner that exercises
  deinflection against these tuples is a known follow-up (see `docs/design/implementation/05-testing-and-anki.md`).
- The corpus is GPL-3.0-or-later (derived from Yomitan). Keep the provenance header/`manifest.spdx`.
