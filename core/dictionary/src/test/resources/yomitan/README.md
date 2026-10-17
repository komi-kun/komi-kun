# Yomitan conformance corpus

Generated, pinned, GPL-3.0-or-later. **Do not edit these files by hand.**

Regenerate from upstream with `tools/yomitan-corpus/build.mjs`; see
[`tools/yomitan-corpus/README.md`](../../../../../../tools/yomitan-corpus/README.md).

- Upstream: [yomidevs/yomitan](https://github.com/yomidevs/yomitan)
- Pinned commit: `e7334a96106a6c74e6fa75bd33a5ea1219d92712` (`e7334a9`)
- Source files: `test/language/japanese-transforms.test.js`, `test/japanese-util.test.js`

`manifest.json` records the upstream commit, the sha256/bytes of each upstream input, and the
sha256/bytes/record-count of each committed corpus file. `YomitanCorpusTest` recomputes those
hashes in `:core:dictionary:testDebugUnitTest` (the PR gate); `tools/yomitan-corpus/verify-manifest.mjs`
does the same standalone.
