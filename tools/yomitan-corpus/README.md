# Yomitan conformance corpus tooling

Reproducible, pinned builder for the Yomitan conformance corpus consumed by the
`:core:dictionary` tests (plan 05 §A1).

The committed corpus lives in
[`core/dictionary/src/test/resources/yomitan/`](../../core/dictionary/src/test/resources/yomitan/)
so tests are **hermetic**: they never need the Yomitan clone, Node, or the network.

## Provenance

| | |
|---|---|
| Upstream | [yomidevs/yomitan](https://github.com/yomidevs/yomitan) (GPL-3.0) |
| Pinned commit | `e7334a96106a6c74e6fa75bd33a5ea1219d92712` (`e7334a9`, tag `26.10.12.0`) |
| Source files | `test/language/japanese-transforms.test.js`, `test/japanese-util.test.js` |
| Corpus license | `GPL-3.0-or-later` (recorded in `manifest.json` as `spdx`) |

The corpus derives from GPL-3.0 Yomitan; the combined komi-kun work ships GPL-3.0-or-later, so
this is compatible. `manifest.json` is the machine-readable record of the commit, input hashes
and output hashes.

## Files

| Tool | Purpose |
|---|---|
| `build.mjs` | Regenerates the corpus + `manifest.json` from a pinned Yomitan checkout |
| `verify-manifest.mjs` | Recomputes the corpus hashes and fails on drift from `manifest.json` |

Generated corpus (append-friendly JSONL, one JSON object per line):

| File | Schema | Records |
|---|---|---|
| `ja-transforms.jsonl` | `{category, source, term, rule, reasons, valid}` | 1446 |
| `ja-normalization.jsonl` | `{op, input, args, expected}` | 405 |
| `lookup-ja.jsonl` | `{dict, query, expect:[{expression, reading, rules}]}` | 12 |
| `lookup-ja.dict.jsonl` | `{dict, expression, reading, rules}` (synthetic dictionary) | 24 |
| `manifest.json` | provenance + sha256/bytes/count per file | — |

`rule` is `null` when the source fixture used `null`; `reasons` is `null` for negative
categories that assert *no* candidate. `lookup-ja.jsonl` asserts against the small synthetic
dictionary in `lookup-ja.dict.jsonl` (no third-party dictionary is redistributed — plan 05
§A1.4). The dictionary is a JSONL term bank; the W5 T2/T3 engine harness converts it into a
`hoshidicts` dictionary.

## Regenerating

```bash
# Point at a Yomitan checkout already on the pinned commit:
node tools/yomitan-corpus/build.mjs --yomitan /path/to/yomitan

# Accept a new upstream revision on purpose:
node tools/yomitan-corpus/build.mjs --yomitan /path/to/yomitan --update
```

`build.mjs` is a **dev tool**, never a build or CI dependency. It:

- refuses to run unless the checkout `HEAD` equals the pinned commit (bypass with
  `--skip-commit-check`);
- refuses to overwrite the corpus when an input hash changed unless `--update` is passed, so
  upstream fixture drift is loud;
- is **deterministic**: no timestamps, fixed field order, LF-terminated UTF-8, stable source
  order. Re-running against the same checkout produces byte-identical files.

`generatedAt` is intentionally omitted from `manifest.json` (plan 05 §A1.2 suggests it) because
it would make the output non-deterministic; the commit + input hashes pin the provenance just as
well.

## CI sync check

Drift is caught two ways, both without needing Yomitan:

1. **`YomitanCorpusTest`** (`core/dictionary/src/test/java/…/corpus/`) parses every corpus file,
   validates the schema, and recomputes each file's sha256/bytes/count against `manifest.json`.
   It runs in `:core:dictionary:testDebugUnitTest`, which the PR workflow already gates on
   (`.github/workflows/build_pull_request.yml`), so corpus drift fails CI for free.
2. **`verify-manifest.mjs`** — the standalone/developer equivalent and what a CI step can call
   directly:

   ```bash
   node tools/yomitan-corpus/verify-manifest.mjs
   node tools/yomitan-corpus/verify-manifest.mjs --yomitan /path/to/yomitan   # also checks inputs
   ```

   A sha256/bytes/count mismatch exits non-zero.
