# Final review — Yomitan dictionary + manga OCR port

Status: **review** · Reviewer: fresh-eyes subagent · Date: 2026-10-16
Branch: `feat/introduce-ocr-and-yomitan` vs `master` @ `9101b3a7d6` (26 commits, 202 files, +31 381/-27)
Scope: whole branch diff; design + contract + plans 01–05 + `yomitan-spec/`; `core/dictionary/**`,
`core/ocr/**`, app-side integration (`ui/dictionary`, `ui/reader/ocr`, viewer hunks, settings, `data`,
`App.kt`, `Downloader.kt`, `AndroidManifest.xml`).
Method: read the docs, then verified concrete claims against the tree (DI, seams, assets, DB, CI).
No code was changed. Findings ranked blocker / should-fix / nit.

---

## Verdict

The **dictionary** half is essentially delivered: Yomitan ZIP assets + JNI engine facades, the
WebView popup/tab renderer, Metro seams (`ReaderOcrHost`/`ReaderDictionaryHost`), per-manga profiles,
settings and a coherent DB migration. The **OCR** half, however, is not runnable end-to-end, the
**Anki mining goal is unreachable from the UI**, the reader's OCR enable-path is split across two
disagreeing preferences, and the CI/boundary gates the contract promises are not actually wired.
This branch should not ship as-is.

---

## Blockers

### B1 — OCR produces nothing at runtime: no native engine, no `.mokuro` path
- `core/ocr/src/main/jniLibs/` is **absent from the tree and git-ignored** (`.gitignore:10`), and
  `fetchPaddleNativeLibs` (`core/ocr/build.gradle.kts:65-130`) is deliberately **not wired into
  `assemble`/`preBuild`** and is **never invoked by any workflow** (grep of `.github/workflows/`
  finds no `fetchPaddleNativeLibs`). Therefore a released/nightly APK ships **no
  `libpaddle_ocr.so`**, `PaddleOcrEngine.init()` returns false and `recognize()` returns an empty
  list (its own KDoc, `core/ocr/src/main/java/mihon/core/ocr/paddle/PaddleOcrEngine.kt:10-16`).
- The `.mokuro` runtime path is **explicitly a follow-up**: `ReaderOcrBlockLoader.load()` returns
  `null` for non-persistent sources and says "sidecar loading is a follow-up"
  (`app/…/ui/reader/ocr/impl/ReaderOcrBlockLoader.kt:76-78`). Nothing anywhere fetches a `.mokuro`
  sidecar or calls `Mokuro.parseMokuro` outside tests.
- The one piece that was written for this, `MokuroOcrInterceptor`
  (`core/common/src/main/java/tachiyomi/core/common/mokuro/MokuroOcrInterceptor.kt:24`), is
  **registered nowhere** — no OkHttp client adds it, no caller uses `cacheFile`/`isMokuroRequest`.
  It is dead code.
- Consequence: even with `ocrSource` set to `PADDLE`/`MOKURO` and chapters queued, the OCR queue
  completes with zero blocks and the reader overlay shows nothing. Goal §2 ("OCR overlay on manga
  pages … support `.mokuro`") is not delivered.

### B2 — Anki mining is unreachable: the "Add to Anki" button is never rendered
- `buildConfigPayload` hard-codes `put("ankiEnabled", false)` with a "wired in the Anki phase" note
  (`core/dictionary/src/main/java/mihon/core/dictionary/ui/DictionaryPayloadBuilder.kt:206`), and
  `renderer.js` only creates `.anki-add-btn`/`.anki-open-btn` **inside `if (ankiEnabled)`**
  (`core/dictionary/src/main/assets/dictionary/renderer.js:2684`, `2712`). So no dictionary surface
  (popup or tab) can ever emit an Anki action.
- Even if enabled, the context is not passed: `AnkiJsBridge.addToAnki(index, glossary, selectedDict,
  popupSelection)` carries **no sentence/offset/screenshot**
  (`core/dictionary/.../ui/DictionaryWebViewBridges.kt:78`), and the default handler calls
  `ankiMining.mine(result, glossaryIndex, selectedDict, popupSelection, forceOpen)` with defaults
  `sentence=""`, `screenshotBytes=null` (`app/.../ui/dictionary/DictionaryEntryWebView.kt:274-290`).
  `DictionaryLookupRequest.sentence`/`sentenceOffset` are captured by the OCR host then dropped:
  `DictionaryPopup` never forwards them (`app/.../ui/dictionary/DictionaryPopup.kt:210-217`,
  `DictionaryLookupRequest` at `mihon/dictionary/host/ReaderDictionaryHost.kt`).
- `AnkiMiningController.updateScreenshot(...)` (`core/dictionary/.../anki/AnkiMiningController.kt:68`)
  has **zero callers**, so `{screenshot}` (and `{sentence}`/`{sentence-furigana}`/`{cloze-body}`)
  always render empty. The marker engine is well unit-tested in isolation (42 `@Test`s) but is not
  reachable.

### B3 — Reader OCR enable-path is split across two disagreeing preferences
- The reader gates the overlay and the long-press on `ReaderPreferences.ocrOverlayEnabled`
  (`pref_reader_ocr_overlay_enabled`, default false) — `ReaderActivity.kt:334,366,1223`,
  `ReaderPreferences.kt:223`. The reader bottom-bar button toggles the same key via
  `viewModel.toggleOcrOverlay()`.
- But `SettingsOcrScreen`'s "Show OCR outlines" switch binds **`OcrPreferences.overlayEnabled`**
  (`pref_ocr_overlay_enabled`) — `SettingsOcrScreen.kt:117`, `OcrPreferences.kt:42`. That preference
  is **read by nobody**. So enabling OCR in Settings does nothing in the reader, and the label
  ("Show OCR outlines") is bound to the wrong key (outline drawing actually reads
  `OcrPreferences.outlineVisible`, `ReaderOcrHostImpl.kt:67`).
- `OcrPreferences.twoFingerGestureEnabled` (`OcrPreferences.kt:45`) is also write-only — no gesture
  consumes it.

---

## Should-fix

1. **`verifyGplFree` is not a CI gate.** The contract (§5) calls it a per-wave gate, but no workflow
   runs it (grep `.github/` → nothing; only the Gradle task exists, `app/build.gradle.kts:509-516`).
   It is local-only, so the boundary can silently regress.
2. **`verify-gpl-free.sh` deny-list is incomplete.** `scripts/verify-gpl-free.sh:53-59` denies
   `mihon.core.dictionary`, `mihon.core.ocr`, `mihon.dictionary`, `eu.kanade.tachiyomi.ui.reader.ocr`,
   `…reader.overlay` — but the GPL viewport adapters live in
   `eu.kanade.tachiyomi.ui.reader.viewer.{pager,webtoon,webgpu}.{Ssiv,Webtoon,WebGpu}OcrViewport`
   and `OcrOverlay` lives in `eu.kanade.presentation.reader`; **none of those packages is denied**. A
   seam file importing a GPL adapter/overlay would pass the check. Script also never verifies the
   §1.3 files remain Apache-headed.
3. **Tab index/order mismatch.** `DictionaryTab` declares `TabOptions.index = 5u`
   (`app/.../ui/dictionary/DictionaryTab.kt:56`) but is inserted *before* `MoreTab` (`index = 4u`)
   in `HomeScreen.tabs`; `MoreTab.kt:61` was not bumped. Visual order and declared index disagree.
4. **Ranking diverges from the spec.** `orderLookupResultsForDisplay` re-sorts with only
   3 of Yomitan's 10 comparator rules (length desc → dictionary priority → input index;
   `DictionaryPayloadBuilder.kt:151-170`), dropping `matchPrimaryReading`,
   `sourceTermExactMatchCount`, `score`, shortest-chain, etc.
   (`docs/design/yomitan-spec/02-lookup-algorithm.md` §c). It can override the engine's correct order.
   Also `MAX_RESULTS=20/SCAN_LENGTH=25` (`DictionaryRepository.kt:206-207`) intentionally differ from
   the spec's 32/16 — contract-frozen, but worth recording.
5. **The "conformance" gate is schema-only.** `YomitanCorpusTest` only checks sha256/bytes/count/shape
   of the committed JSONL (`YomitanCorpusTest.kt`); the "T2 host-conformance" / "T3 emulator" runners
   the contract and README reference do not exist (no `conformance-emulator.yml`, no test runs the
   1 446 transform tuples through the engine). Deinflection/ranking behaviour is therefore
   **unverified** — the claim of conformance is not backed by a test.
6. **Dead/unused wiring.** `ReaderOcrBlockLoader.invalidate()` is never called
   (`ReaderOcrBlockLoader.kt:66`); `DictionaryRepository.queryKanji` has no UI caller
   (`DictionaryRepository.kt:84` — kanji lookup unused); `ReaderViewModel.{isOcrOverlayEnabled,
   getOcrSource,setOcrSource}` are never called (`ReaderViewModel.kt:256-271`); `ReaderPreferences.
   {ocrOutlineVisible,ocrActiveBlockHighlight,ocrBoxScale}` are never read (`ReaderPreferences.kt`).
7. **`.mokuro` parser + `Mokuro*` are GPL in `:core:ocr`** — good — but shipping a *tested* parser
   with no runtime caller is wasted surface; either wire it or drop it from the "delivered" claim.
8. **Resource/perf.** `UniFileChapterImageProvider.pageBytes` re-opens the CBZ and scans from the
   first entry for every page → O(pages²) `ZipInputStream` passes per chapter
   (`OcrWorker.kt`, `UniFileChapterImageProvider`). `ReaderOcrHostImpl.Overlay` mutates
   `this.viewport` during composition (side effect in a composable, `ReaderOcrHostImpl.kt:64`).
   `DictionarySession.close()`/`DictionaryRepository.close()` are never invoked from any lifecycle.

---

## Nits

- `DictionaryRepository.LookupResult2` is an odd public name (`DictionaryRepository.kt:197`).
- `DictionaryPopup` has no tap-outside/scrim dismissal despite the host comment "shows its own
  scrim" (`ReaderDictionaryHostImpl.kt:8-9`); dismissal only via the renderer back button.
- `consumer-rules.pro:17` `-keep class androidx.annotation.Keep` is not a valid keep of annotated
  members (should be `-keep @androidx.annotation.Keep class *`).
- The 15.6 MB vendored `maven/.../library-ocr-accessors-1.aar` is committed; fine per the design
  decision but a repo-size/upstream-merge burden.
- `App.kt`'s "lazy so OCR is only built when a worker runs" comment is defeated: `Downloader` takes
  `OcrManager` as a constructor dependency (`Downloader.kt:107`), and `Downloader` is `@Inject`
  (`Downloader.kt:88`), so `OcrManager`/`PaddleOcrEngine` are constructed with the download graph.
- `AnkiJsBridge`'s private `CoroutineScope(Dispatchers.Main + Job())`
  (`DictionaryWebViewBridges.kt:75`) is never cancelled.

---

## What is correct / low-risk (verified)

- Metro DI is coherent: all four `@BindingContainer`s are registered (`AppGraph.kt:101-107`), the
  impls are `@ContributesBinding(AppScope::class)`, and `ReaderActivity` injects only the Apache
  interfaces (`ReaderActivity.kt:174-176`). `ReaderOcrHost.Overlay`/`readerDictionaryHost.Popup()` are
  both mounted inside `binding.composeOverlay.setComposeContent` (the reader overlay above
  `viewer_container`), so they are reachable in the composition tree.
- `verify-gpl-free.sh` **passes** locally (`14 GPL-free file(s) import no GPL package`) and the
  seam/impl split (`…ocr.impl`, `mihon.dictionary.impl`) is real.
- DB change is coherent: `54.sqm` + all `chapter.sq` SELECT/insert/update sites + `ChapterMapper`
  + `RestoreRepositoryImpl` + `ChapterRepositoryImpl` + domain model (`isOcrReady`) all line up.
- CI does check out submodules recursively and provisions NDK `29.0.14206865`/CMake `3.22.1` in all
  four build workflows, and drops the `x86` artifact consistently.
- Pure-logic tests for OCR math/merger/`.mokuro` and the Anki marker engine are substantive.

---

## Single most important thing to fix before shipping

**Make the OCR feature actually function, or explicitly ship it disabled.** Right now the APK ships
no `libpaddle_ocr.so` (fetch task unwired, `jniLibs` empty/ignored), the `.mokuro` sidecar is never
fetched/parsed at runtime (`MokuroOcrInterceptor` unregistered, `ReaderOcrBlockLoader` defers it),
and the reader's overlay is gated by a *different* preference than the one Settings writes
(`ReaderPreferences.ocrOverlayEnabled` vs `OcrPreferences.overlayEnabled`). A user can follow every
UI step and get an OCR overlay that never draws a single box. Wire the engine fetch into the release
build (or gate the whole feature behind `OcrEngine.isAvailable` and disable its UI), add the
`.mokuro` runtime path, and unify the overlay preference — then the Anki button
(`buildConfigPayload`'s `ankiEnabled=false`) and sentence/screenshot plumbing can follow.
