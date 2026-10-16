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
  `chapter.sq`/`ChapterMapper` files. These must **import no GPL package**.
- New code is GPL-3.0-or-later **with an `SPDX-License-Identifier: GPL-3.0-or-later` header**; seam
  files use `Apache-2.0`. Never use hoshidicts' `main-mit` branch to claim an Apache-clean engine.

## The guard

`scripts/verify-gpl-free.sh` (wrapped by `./gradlew verifyGplFree`, a CI gate in
`build_pull_request.yml` / `build_push.yml`) greps the §1.3 files for imports of GPL packages:

- **Deny prefixes** (segment-boundary matched): `mihon.core.dictionary`, `mihon.core.ocr`,
  `mihon.dictionary`, `eu.kanade.tachiyomi.ui.reader.ocr`, `eu.kanade.tachiyomi.ui.reader.overlay`.
- **Deny FQNs** (mixed-package types that a seam file must not import): `…viewer.pager.SsivOcrViewport`,
  `…viewer.webtoon.WebtoonOcrViewport`, `…viewer.webgpu.WebGpuOcrViewport`,
  `eu.kanade.presentation.reader.OcrOverlay`.
- **Allowlist** of the Apache seam FQNs (so they aren't flagged).
- It **hard-fails if a listed §1.3 path is missing** (path drift can't silently drop coverage) and if a
  §1.3 file carries a GPL SPDX header.

## When to touch it

- **Adding a GPL-free file**: append its path to the §1.3 list in `scripts/verify-gpl-free.sh` **and**
  the contract; give it an `Apache-2.0` SPDX header.
- **Adding a GPL package** that a seam file could import: add a deny prefix (or an exact deny FQN if the
  package is mixed with Apache siblings).
- **Changing module dependencies**: `:core:ocr` must never depend on `:core:dictionary` (or vice
  versa); neither may depend on `:app`.
- **Contributability caveat**: the GPL-free files import `KMR`/`SYMR` strings that exist only in
  Komikku, so the upstream target is **Komikku**, not Mihon.

## Licensing artifacts to keep current

- `LICENSES/GPL-3.0-or-later.txt` (full text) and `NOTICE` (combined-work statement + every component
  with URL/pin: Chimahon, hoshidicts, Yomitan, the `webgpuviewer` fork, PaddleOCR/Paddle-Lite,
  AnkiDroid API). Update `NOTICE` when a vendored component or its pin changes.
- `AGENTS.md` "Licensing & native modules" + module table: keep it in step with reality.

## Verify

```bash
bash scripts/verify-gpl-free.sh
flock /tmp/opencode/gradle.lock ./gradlew verifyGplFree
```
