---
name: gpl-boundary
description: Maintain the GPL-3.0 / Apache-2.0 boundary of the Yomitan/OCR port — keep the GPL-free seam files importable for upstream contribution, keep verifyGplFree green, extend the seam file list + guard patterns, and keep the licensing artifacts correct. Use when touching a seam interface, adding a new Apache or GPL file, changing module dependencies, or when verifyGplFree fails.
---

# Maintaining the GPL boundary

The port makes the **combined distribution GPL-3.0-or-later** while upstream-derived files keep their
Apache-2.0 headers. A small set of **GPL-free seam files** stays Apache so it can be contributed back
to **Komikku**. Details: `NOTICE`, `LICENSES/GPL-3.0-or-later.txt`, and the implementation contract
`docs/design/implementation/00-implementation-contract.md` §1.2/§1.3/§1.9.

## The rules

- GPL modules: `:core:dictionary` (`mihon.core.dictionary.*`), `:core:ocr` (`mihon.core.ocr.*`), and the
  GPL impls `mihon.dictionary.impl.*`, `…ui.reader.ocr.impl.*`, the reader/viewer OCR adapters
  (`eu.kanade…viewer.{pager,webtoon,webgpu}` OCR classes) and `eu.kanade.presentation.reader.OcrOverlay`.
- **GPL-free seam files** (Apache, contract §1.3): `mihon.dictionary.host.ReaderDictionaryHost`,
  `eu.kanade.tachiyomi.ui.reader.ocr.{OcrViewport,ReaderOcrHost}`,
  `tachiyomi.core.common.ocr.ReaderOcrSource`, and the listed `ReaderActivity`/`ReaderViewModel`/
  `ReaderPreferences`/`ReaderBottomButton`/`ReaderAppBars`/`Chapter`/`ChapterUpdate`/`54.sqm`/
  `chapter.sq`/`ChapterMapper` files. These must **reference no GPL code** — no import, no
  same-package use, no fully qualified use (the guard below enforces all three).
- New code is GPL-3.0-or-later **with an `SPDX-License-Identifier: GPL-3.0-or-later` header**; seam
  files use `Apache-2.0`. Never use hoshidicts' `main-mit` branch to claim an Apache-clean engine.

## The guard

`scripts/verify-gpl-free.sh` (wrapped by `./gradlew verifyGplFree`, which also runs its self-test
`scripts/test-verify-gpl-free.sh`; a CI gate in `build_pull_request.yml` / `build_push.yml`) scans
**only the §1.3 file set**. The **seam list is hand-maintained** (`SEAM_FILES` in the script + contract
§1.3); the **deny side is derived from the tree**: every `.kt`/`.kts` under the scanned source roots
whose first five lines carry `SPDX-License-Identifier: …GPL…` contributes its package and the fully
qualified names of its top-level declarations (`class`/`interface`/`object`/`typealias`) and top-level
`fun`/`val`/`var`.

A seam file fails when it:

- **imports** a denied package or FQN;
- **uses a GPL declaration of its own package** with no import and no qualifier (the seam and its GPL
  `…Bindings` are siblings by design, so the package itself cannot be denied wholesale);
- **star-imports a mixed package** (`import a.b.*`) and then uses one of that package's GPL declarations;
- **uses a fully qualified GPL name** in the body, with no `import` line at all.

It also **hard-fails if a listed §1.3 path is missing** (path drift can't silently drop coverage) and if
a §1.3 file carries a GPL SPDX header. Coarse seeds remain in the script — **deny prefixes**
(`mihon.core.dictionary`, `mihon.core.ocr`, `mihon.dictionary`, `eu.kanade.tachiyomi.ui.reader.ocr`,
`eu.kanade.tachiyomi.ui.reader.overlay`), a handful of **deny FQNs** for GPL types inside otherwise
Apache packages, and an **allowlist** of the Apache seam FQNs — but the set derived from the SPDX headers
is what denies a GPL declaration nobody listed.

Each shape above has a case in `scripts/test-verify-gpl-free.sh`, against the fixture tree under
`scripts/test-fixtures/gpl-free-guard/`; the negative cases (a seam file that must **pass**) matter as
much as the violations.

## When to touch it

- **Adding a GPL-free file**: append its path to the §1.3 list in `scripts/verify-gpl-free.sh` **and**
  the contract; give it an `Apache-2.0` SPDX header.
- **Adding a GPL file**: normally nothing — its `SPDX-License-Identifier: …GPL…` header puts its
  package and declarations in the derived deny set. Add a **deny prefix** only when the whole package
  must be denied, an **exact deny FQN** when the package is mixed with Apache siblings and only the GPL
  type must be denied, and an **allowlist** entry only for an Apache seam FQN.
- **Changing the guard**: add a self-test case for the shape (or false positive) you touched, and run
  `/tmp/opencode`-style proof that the new case fails against the pre-fix script.
- **Changing module dependencies**: `:core:ocr` must never depend on `:core:dictionary` (or vice
  versa); neither may depend on `:app`.
- **Contributability caveat**: the GPL-free files import `KMR`/`SYMR` strings that exist only in
  Komikku, so the upstream target is **Komikku**, not Mihon.

## Licensing artifacts to keep current

- `LICENSES/GPL-3.0-or-later.txt` (full text) and `NOTICE` (combined-work statement + every component
  with URL/pin: Chimahon, hoshidicts, Yomitan, the `webgpuviewer` fork, PaddleOCR, ONNX Runtime,
  AnkiDroid API). Update `NOTICE` when a vendored component or its pin changes.
- `AGENTS.md` "Licensing & native modules" + module table: keep it in step with reality.

## Verify

```bash
bash scripts/verify-gpl-free.sh
bash scripts/test-verify-gpl-free.sh
flock /tmp/opencode/gradle.lock ./gradlew verifyGplFree
```
