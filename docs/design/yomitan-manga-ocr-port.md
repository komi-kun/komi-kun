# Design: Yomitan-style lookup + manga OCR in komi-kun

Status: **proposal / investigation** · Branch target: `master` · Upstream reference: [Chimahon](https://github.com/Chimahon/chimahon)

This document investigates how to bring **Yomitan-style dictionary lookup** and **on-device OCR for
manga** into komi-kun, the challenges involved, and a recommended path. It is deliberately scoped to
manga reading only — no novel reader, no anime/video player, no screen OCR, no home-screen widgets.

---

## 1. Summary

- **Chimahon is based on Komikku**, the same upstream as komi-kun, so the *lineage* matches. But its
  immersion features are **not a self-contained patch**: ~35–40k LOC across **two new Gradle modules
  plus a native toolchain** (`:chimahon`, `:chimahon-local-ocr`) and deep edits to the reader,
  DI, settings, and manifest.
- **Licensing: releases ship as GPL-3.0** (decided). Chimahon, its native dictionary engine
  `hoshidicts`, and Yomitan are GPL-3.0. Apache-2.0 code may be combined *into* a GPL-3.0 work, so the
  *distributed APK* is GPL-3.0 while upstream files keep their Apache headers. Files that stay
  GPL-free can still be contributed back to Mihon/Komikku (see §4.2).
- **Recommended: vendor the engine, don't rewrite it.** Reuse `hoshidicts` (GPL C++) plus Chimahon's
  Kotlin/JS, adapted to Metro and komi-kun's reader. A Rust reimplementation is **not worth it** now
  that the license barrier is gone (§5). OCR: **PaddleOCR directly** + `.mokuro`; drop the cloud
  Google Lens path and the proprietary on-device Lens binaries (both a licensing and a robustness
  win).
- **The reader integration is the other big cost.** komi-kun's reader has **more render backends than
  Chimahon's** — including the newer **WebGPU viewer** (external MIT library, Compose-based). This
  changes the OCR overlay design from "subclass `SubsamplingScaleImageView`" into a
  **viewer-agnostic overlay + a per-viewer coordinate adapter**.

---

## 2. Goals / non-goals

**Goals**

- Import Yomitan/Yomichan dictionaries (ZIP) and look up terms while reading manga.
- Popup dictionary UI: term, reading, glossaries, frequency, pitch accent, dictionary CSS, media,
  recursive lookup.
- Optional Anki mining (create cards from a lookup) — Yomitan's signature feature.
- OCR overlay on manga pages: recognise text boxes, tap a word to look it up; support `.mokuro`.
- All of the above **cleanly licensed** for an Apache-2.0 app and integrated with komi-kun's
  existing architecture (Metro DI, Voyager, i18n-kmk, `// KUN` markers).

**Non-goals (explicitly dropped from the Chimahon port)**

- Anime/video player (MPV/FFmpeg), subtitles, Jimaku, YouTube extension.
- Novel reader (`.epub`), illustration viewer.
- Screen OCR ("any app"), camera OCR, home-screen OCR widget.
- Panel-by-panel detection (LiteRT YOLO) — **separate feature**, evaluated later, not "OCR".
- The `i18n-ank` module (unused in Chimahon for immersion anyway).

---

## 3. What Chimahon is

- Fork of **Komikku** (`settings.gradle.kts` → `rootProject.name = "Komikku"`), app id
  `app.chimahon`.
- Adds modules `:chimahon` (dictionary + Anki + OCR glue + audio + novel), `:chimahon-local-ocr`
  (on-device OCR engines), `:i18n-ank` (leftover), and an anime subsystem.
- **Build/stack drift vs komi-kun:** Gradle 9.3.1 vs 9.8.1 · AGP 8.13.2 vs 9.4.1 · Kotlin 2.4.0
  vs 2.4.21 · compileSdk 36 vs 37.2 · **Injekt vs Metro** · NDK/CMake vs none.
- Its own changes have **no dedicated marker** (they sit unmarked or inside `// KMK` blocks; `// AY`
  marks the anime subsystem). Port candidates must be found by diff, not by grep.

---

## 4. Licensing analysis (the core constraint)

| Component | License | Compatible with Apache-2.0 app? |
|---|---|---|
| komi-kun (this repo) | Apache-2.0 | — |
| Chimahon (all its Kotlin/JS/C++) | **GPL-3.0** | ❌ (copied code forces GPL-3.0) |
| `hoshidicts` (dictionary engine, C++) | **GPL-3.0** | ❌ |
| Yomitan (deinflection rules, renderer) | **GPL-3.0** | ❌ |
| ~~PaddleOCR / Paddle-Lite~~ → PP-OCRv6 models (Apache-2.0) + ONNX Runtime (MIT) | Apache-2.0 / MIT | ✅ |
| LiteRT (panel detection) | Apache-2.0 | ✅ |
| `ca.mpreg:webgpuviewer` | MIT | ✅ (already a dep) |
| Google Lens on-device engine + models | proprietary | ❌ |
| Manga panel YOLO model (HF `leoxs22/…`) | *unverified* | ⚠️ verify |

> **Backend note (2026):** the OCR port now runs PaddleOCR's models through **ONNX Runtime** (MIT),
> not Paddle-Lite, on **PP-OCRv6 `small`** — see `docs/design/implementation/ort-v6-migration.md`.
> Licence-wise the PaddleOCR/Paddle-Lite row above still stands (PaddleOCR models are Apache-2.0;
> ONNX Runtime is MIT).

**One-way compatibility:** Apache-2.0 code may be combined *into* a GPL-3.0 work; the reverse is not
permitted. So vendoring any of Chimahon/`hoshidicts`/Yomitan means the whole komi-kun distribution
must become GPL-3.0.

**Decision: the combined work ships as GPL-3.0.** So Chimahon/`hoshidicts`/Yomitan code may be reused
directly — no clean-room constraint on the immersion code. Keep a boundary (§4.2) between the GPL
immersion code and any upstream-derived files you still want to contribute back.

> ⚠️ Engineering assessment, not legal advice.

### 4.1 The deinflection data problem

Deinflection (e.g. Japanese `食べました` → `食べる`) is the genuinely license-encumbered part:
Yomitan's rules (`ext/js/language/ja/japanese-transforms.js`) are GPL-3.0 **code**, and Chimahon's
per-language `*Deinflector.kt` tables are **derived from Yomitan** (its own commit history says
"Align deinflectors with yomitan"; Japanese deinflection is in native `hoshidicts` and its
Acknowledgements credit Yomitan's rules).

Permissive alternatives for the rules themselves:

- **Lindera + UniDic / SudachiDict** (MIT / Apache-2.0): derive lemmas from a real morphology
  dictionary instead of hand-written rule tables. This is what the MPL-2.0 `wordbase` project does.
- **Reimplement rules from grammar** (JMdict carries part-of-speech but no conjugation rules);
  feasible but more work, and must be written independently of Yomitan.

### 4.2 If the project accepts GPL-3.0 (and how to still contribute upstream)

Licensing is **per file**; distribution is **per work**. Apache-2.0 and GPL-3.0 are compatible one
way: Apache-2.0 files may be combined *into* a GPL-3.0 work distributed under GPL-3.0. So komi-kun
**may** ship GPL code while keeping Apache headers on the upstream files — but the **distributed APK
is then GPL-3.0** (source offer, no added restrictions, etc.).

What that means for "contribute unrelated code back to Mihon/Komikku":

- Mihon/Komikku are Apache-2.0, so they can only accept **Apache-2.0** contributions. Any file/hunk
  you want to send upstream must stay Apache-2.0 **and must not derive from GPL code**.
- Keep a **hard boundary**: GPL code lives in its own module(s); files shared with upstream (reader,
  UI, …) must **not import** the GPL module. Isolate behind an interface (e.g. a Metro-bound
  `ReaderOcrHost`) so the shared files stay Apache.
- Track it with per-file SPDX headers + a `NOTICE`/`LICENSE` stating the combined work is GPL-3.0.

**On "Rust GPL / bindings LGPL":** that doesn't achieve the goal. LGPL is still copyleft (can't be
merged into Apache-2.0 Mihon); bindings generated from a GPL engine are derivative of it; and LGPL's
relinking obligations are awkward on Android. If you accept GPL, use plain **GPL-3.0** (not AGPL) for
the engine, and keep your own unrelated Kotlin files **Apache-2.0**.

⚠️ Not legal advice.

**Trade-off table**

| | Stay Apache-2.0 (clean-room) | Accept GPL-3.0 |
|---|---|---|
| Dictionary engine | Reimplement (Rust) | **Can vendor `hoshidicts`/Yomitan directly** (much less work) |
| OCR | Reimplement glue on PaddleOCR | Port Chimahon's glue directly |
| App license | Apache-2.0 | GPL-3.0 (combined work) |
| Contribute unrelated code upstream | Everything | Only files kept Apache + GPL-free |
| Effort | High | Low–medium |

**Decision (adopted): vendor.** With GPL accepted, the licensing motivation for a Rust rewrite is
gone, so the recommended engine is the C++ `hoshidicts` plus Chimahon's Kotlin/JS (§5). A Rust engine
remains a *quality/architecture* option (UniFFI ergonomics, no C++23/NDK-29 pin), not a requirement.

---

## 5. The dictionary engine: vendor `hoshidicts` (recommended)

**Recommendation: vendor, don't rewrite.** The complete, battle-tested engine already exists:
`hoshidicts` (C++ — Yomitan-format import/index/query + Japanese deinflection), with a working JNI layer
and Android integration shipped in Chimahon. Under GPL that is directly reusable, so the engine — the
*hardest correctness surface* (57 Japanese transforms, exact ranking, every bank format) — comes
essentially for free. A rewrite re-creates that risk for **no licensing benefit**, and the Kotlin/JS UI
work still has to be done either way.

Concretely:

- Vendor `hoshidicts` (fork to `komi-kun/hoshidicts` for control) as a git submodule; build via
  CMake/NDK (C++23, NDK 29) — accept the toolchain cost.
- Port Chimahon's dictionary layer: `HoshiDicts.kt`, `DictionaryRepository.kt`, `dictionary/**`,
  `hoshidicts_jni.cpp`, assets (`renderer.js`/`index.html`/`base.css`), the Anki bridge, and the
  app-side dictionary UI — all GPL-3.0, wrapped `// KUN` where they touch upstream files.
- Keep the C++ engine behind a thin Kotlin facade so it could later be swapped for a Rust engine
  (`docs/design/yomitan-spec/` documents the behavioral contract) — without committing to that now.
- The swarm spec is still the reference for **testing/review** and for the parts we *do* write fresh
  (OCR glue, overlay, settings).

The subsections below describe the **alternative** (a Rust rewrite) and remain as reference if that is
ever revisited.

### 5.1 Alternative: a Rust rewrite — what exists and why we're not doing it

Research summary of the ecosystem (see §13 for links):

- **No clean permissive Rust Yomitan engine exists.** Every full implementation is GPL-3.0
  (`yomichan_rs`, `deinflector`, `Manhhao/hoshidicts-rs`, `tentoku-rs`) or has dubious provenance
  (MIT-labelled ports of GPL code — `Gilfaro/hoshidicts-rs`, `bunpo`).
- **Closest real option:** `aecsocket/wordbase` (**MPL-2.0**) — Yomitan ZIP import, SQLite index,
  term/kanji/freq/pitch, deinflection via Lindera+UniDic, and an Android app. MPL is file-level
  copyleft and *can* be combined with an Apache-2.0 app (modified MPL files stay MPL and are
  published). Usable either as a dependency or as a strong reference.
- **Permissive building blocks:** `lindera` (MIT), `sudachi.rs`/`sudachi` dict (Apache-2.0),
  `vibrato`/`vaporetto` (Apache-2.0), `fst` (Unlicense/MIT), `daachorse` (Apache-2.0),
  `jieba-rs` (MIT), `serde`/`zip`/`zstd` (MIT/Apache).
- **Format is fair game:** Yomitan dictionaries are ZIPs of `index.json`, `term_bank_*`,
  `term_meta_bank_*`, `kanji_bank_*`, `kanji_meta_bank_*`, `tag_bank_*`, `styles.css` and media,
  documented by JSON Schemas in the Yomitan repo (`ext/data/schemas/`) and yomitan.wiki.

**Recommendation:** new repo `komi-kun/<engine>` (Apache-2.0/MIT), a Rust crate that imports
Yomitan ZIPs, builds a lookup index, and serves term/kanji/frequency/pitch/media queries with
deinflection. Use `wordbase` (MPL-2.0) as a *reference*; prefer a from-scratch Apache/MIT
implementation with Lindera/Sudachi for Japanese deinflection. If MPL is acceptable, `wordbase` can
be a starting point under its own license terms.

### 5.2 Contract to reproduce

The Kotlin-facing data model Chimahon's JNI exposes (from `chimahon/HoshiDicts.kt`) — a
reimplementation should match this shape:

- `ImportResult{success,title,termCount,metaCount,freqCount,pitchCount,kanjiCount,mediaCount}`
- `TermResult{expression,reading,rules,glossaries,frequencies,pitches}`
- `GlossaryEntry{dictName,glossary,definitionTags,termTags}` · `FrequencyEntry{dictName,frequencies}`
- `PitchEntry{dictName,pitchPositions}` · `KanjiEntry/…` · `DictionaryStyle{dictName,styles}`
- `LookupResult{matched,deinflected,process,term,preprocessorSteps}`

Query operations: `lookup` (deinflecting substring scan), `query` (exact expression), `queryKanji`,
`getStyles`, `getMediaFile`, plus `rebuildQuery(termPaths,freqPaths,pitchPaths,kanjiPaths)` for
multi-dictionary merge. We are **not** bound to hoshidicts' on-disk `hash.table`/`blobs.bin` layout
(no existing installs to migrate) — we can define our own index format.

### 5.3 Kotlin-facing interface (UniFFI)

Design goal: a small, ergonomic API the app calls **directly**, with the Rust core doing all the work;
no hand-rolled JNI marshalling in the app.

**Crate layout** (in the engine repo):

- `engine-core` — pure Rust, no FFI: ZIP import, index, query, deinflection, styles, media. Unit-testable
  on its own.
- `engine-ffi` — thin UniFFI layer (`uniffi::Record`/`Object`/`export`); no logic.
- `engine-android` — builds the AAR: UniFFI-generated Kotlin + `.so` per ABI + `consumer-rules.pro`.

**Kotlin API the app sees** (what we design *backwards* from):

```kotlin
val engine = DictionaryEngine()
val counts = engine.probeEntryTypes(dictDir)                  // [term, freq, pitch, kanji]
val result = engine.importDictionary(zip, outDir) { done, total -> /* progress */ }

val session = engine.openSession(termPaths, freqPaths, pitchPaths, kanjiPaths)
val hits: List<LookupResult> = session.lookup("食べました", maxResults = 8, scanLength = 0)
val exact: List<TermResult> = session.query("食べる")
val kanji = session.queryKanji("食")
val styles: List<DictionaryStyle> = session.styles()
val media: ByteArray? = session.mediaBytes("jitendex", "img/1.png")
```

**Rust side** (UniFFI proc-macros; no separate `.udl` needed):

```rust
#[derive(uniffi::Record)]
pub struct LookupResult {
    pub matched: String,
    pub deinflected: String,
    pub process: Vec<String>,
    pub term: TermResult,
    pub preprocessor_steps: u32,
}

#[derive(uniffi::Object)]
pub struct DictionarySession { /* Arc<Mutex<Session>> */ }

#[uniffi::export]
impl DictionarySession {
    fn lookup(&self, text: String, max_results: u32, scan_length: u32) -> Vec<LookupResult> { … }
    fn query(&self, text: String) -> Vec<TermResult> { … }
    fn query_kanji(&self, character: String) -> KanjiResult { … }
    fn styles(&self) -> Vec<DictionaryStyle> { … }
    fn media_bytes(&self, dict: String, path: String) -> Result<Option<Vec<u8>>, DictionaryError> { … }
}

#[uniffi::export]
fn import_dictionary_async(
    zip: String, out: String, progress: Box<dyn ImportProgress>,
) -> Result<ImportResult, DictionaryError>;

#[uniffi::export(callback_interface)]
pub trait ImportProgress { fn on_progress(&self, done: u64, total: u64); fn is_cancelled(&self) -> bool; }
```

**Design rules**

- **Coarse calls.** One `lookup` returns the whole result graph; avoid per-field accessors (a JNA hop
  per call adds up).
- **Handles, not globals.** A `DictionarySession` object owns the native lookup state; the app creates
  one per configured dictionary set. Internally `Mutex`-guarded, or expose `async` for long scans.
- **Records → Kotlin data classes** automatically; `Vec<u8>` → `ByteArray`; errors → typed exceptions.
- **Media as bytes** (or a data-URI built on the Kotlin side) so HTML/CSS rendering stays in the WebView.
- **Import is long-running** → `async` + progress/cancel callback; the app wraps it in a coroutine.
- A thin hand-written Kotlin facade (`DictionaryEngine`/`DictionarySession`) can ship in the same AAR to
  give a **stable, versioned API** independent of the generated `uniffi.*` names.

### 5.4 Rust → Android integration

From the 2026 tooling research:

- **Bindings:** **UniFFI 0.32.x** (production-ready Kotlin, async support) for a coarse-grained API;
  hand-written `jni` crate only if needed. Keep calls coarse (return whole records/buffers).
- **Build:** `cargo-ndk` 4.x. **NDK r28.2.x** (AGP 9.4 default, 16 KB-page aligned) or r29. Targets:
  `aarch64-linux-android`, `armv7-linux-androideabi`, `x86_64-linux-android`, `i686-linux-android`.
- **Delivery — recommended:** the engine repo **publishes an AAR** (UniFFI-generated Kotlin +
  `.so` for all ABIs + `consumer-rules.pro`) to GitHub Packages / Maven, and komi-kun consumes it
  like Signal/Element X consume their Rust SDKs. This keeps **no Rust toolchain in the app build**.
  During bring-up, a **git submodule + a manual `cargo-ndk` `Exec` task** is the safe fallback
  (avoid the `org.mozilla.rust-android-gradle` plugin until AGP 9 support is verified).
- **Packaging:** `jniLibs` + ABI splits/universal APK (already partially configured); keep a
  non-stripped `.so` for `ndk-stack`; ship the engine's `LICENSE`/`NOTICE` in the OSS licences
  screen.

> Note: "separate repo with a git reference" is easy for *source*, but Gradle can't consume a Cargo
> git dependency directly — the reference becomes a **submodule built by CI** or, preferably, a
> **published AAR**. Both keep the GPL boundary clean because the engine is our own Apache/MIT code.

### 5.5 Japanese deinflection: what a UniDic analyzer covers, what Yomitan's rules add

A **Lindera + UniDic/SudachiDict** analyzer tokenizes and lemmatizes straight to the dictionary form,
which covers most *standard* conjugation for words in its lexicon — arguably cleaner than generating
candidates:

- Covered (typical): 食べました→食べる, 食べなかった→食べる, 書いた→書く (音便), 読んだ→読む,
  話しません→話す, 待って→待つ, 食べさせられた→食べる (causative-passive), 食べられる→食べる
  (potential/passive), 食べよう→食べる, 食べれば→食べる, 高かった→高い, plus reading/POS from UniDic.

**Gaps to cover with a small, independently-written rule layer** (this is what Yomitan's rules buy you
that a lexically-bound analyzer does not):

- **Suffixes on names/general words** — 〜さん / 〜くん / 〜ちゃん / 〜氏 / 〜様 / 〜達. UniDic marks
  suffixes but you must strip and re-query; Yomitan has explicit rules.
- **Colloquial contractions** — 〜てる / 〜でる, 〜とく / 〜どく, 〜ちゃう / 〜じゃう, 〜なきゃ,
  〜なくちゃ, 〜っす, 〜っけ.
- **Unknown / out-of-lexicon words** — slang, dialect, names, coined and domain terms. An analyzer
  can't lemmatize what isn't in its lexicon, whereas Yomitan's suffix rules still generate dictionary
  candidates (this is the biggest practical difference).
- **Substring deinflection** — Yomitan deinflects the exact highlighted span (`scan_length`),
  independent of sentence segmentation; the analyzer segments first and you must map spans to tokens.
- **Orthographic normalization** — UniDic's lemma spelling ≠ the dictionary headword (kanji/kana,
  okurigana, old-form variants). Needs a normalized-lookup/kana-index layer to match.
- **Philosophy** — Yomitan deliberately *over-generates* candidate forms and lets the dictionary
  decide; the analyzer commits to one best analysis. The rule layer above restores the "try many
  candidates" behaviour.

⚠️ Quantify with a **test corpus**: a list of inflected forms with expected headwords. Output doubles
as the deinflection regression suite.

---

## 6. The OCR plan

### 6.1 Engine choice

| Chimahon engine | Verdict |
|---|---|
| **Cloud Google Lens** (`LensClient`, hardcoded API key + undocumented endpoint) | ❌ Drop — fragile, ToS/keys, network. |
| **On-device Google Lens** (`:chimahon-local-ocr`, vendored JNI, proprietary `.so`+models) | ❌ Drop — proprietary binaries, NDK shim that hooks ELF GOT. |
| **PaddleOCR** (`PaddleOcrEngine`, `libpaddle_ocr.so` + det/rec models) | ✅ **Use directly** — Apache-2.0, on-device, no key. Preferred. |
| **`.mokuro`** (JSON sidecar) | ✅ Keep — pure data, no engine needed. |

**Open item:** PaddleOCR for Android means shipping a Paddle-Lite/PaddleOCR `.so` + det/rec models.
We must **build or source these reproducibly under Apache-2.0** (Chimahon fetches them from its own
release). Decide: build in our own repo/CI, or pin a known upstream build with a recorded checksum
and license. This is the OCR equivalent of the dictionary-engine decision.

> **Resolved (2026):** we build our own artifact (`komi-kun/komi-kun-ocr-models`) and the backend is
> now **ONNX Runtime + PP-OCRv6 `small`** rather than Paddle-Lite. See
> `docs/design/implementation/ort-v6-migration.md`; the paragraph above is kept as the historical
> problem statement.

### 6.2 OCR pipeline to reimplement (clean-room, permissive)

Chimahon's OCR glue is small and reimplementable:

- Normalised box IR (`EngineLine`, `NormalizedBBox` 0–1), so engines and the merger are decoupled.
- Preprocessing/chunking (`OcrPreprocessor`, `ImageChunking`: split large pages, resize to max px).
- Line merging/sorting (`OwOCRMerger` — 1.2k LOC; the most involved piece — reimplement, don't copy).
- Overlay painting (`OcrTextOverlayPainter`) and hit-testing (`OcrHitTester`: block/char hit,
  vertical-line detection).
- `.mokuro` parse/resolve/convert (`Mokuro*`, pure Kotlin; format is a public JSON schema).

### 6.3 Reading order / vertical text

Japanese manga is vertical right-to-left. Reimplement sort order (Chimahon has a platform sorter) and
keep the normalised-box contract so a different engine can be swapped later.

---

## 7. Reader integration (and the WebGPU consequence)

This is where komi-kun differs most from Chimahon and drives the architecture.

### 7.1 komi-kun has more render backends than Chimahon

| Viewer | Backing | Notes |
|---|---|---|
| Pager (`PagerViewer`, R2L/L2R) | `SubsamplingScaleImageView` (SSIV) | Chimahon's overlay target |
| Webtoon (`WebtoonViewer`) | SSIV + komi-kun's `WebtoonStripImageView` | komi-kun-specific strip path |
| **WebGPU paged** (`WebGpuViewer`) | **`ca.mpreg:webgpuviewer` (MIT), Compose `AbstractComposeView`** | Not in Chimahon |
| **WebGPU continuous** (`WebGpuViewerContinuous`) | same lib, `ImageViewContinuous` | Not in Chimahon |

Chimahon's OCR overlay is **`OcrSubsamplingImageView extends SubsamplingScaleImageView`** plus an
`OcrCoordinateMapper` built on SSIV's `sourceToViewCoord`. **None of that applies to the WebGPU
viewer**, which:
- renders pages to a GPU surface via `ImageViewer.renderSnapshot` / `drawLive` (no `Canvas.onDraw`,
  no SSIV tile state);
- is a **Compose** view whose `ContentView` is the library's `ImageViewer(state)`;
- exposes **`ImageViewerState`** with `onTap: ((Offset)->Unit)?` / `onLongTap: ((Offset)->Unit)?`
  where the offset is **normalised to the viewport** (`x/width`, `y/height`);
- keeps pan/zoom **per `ImagePage`** (`page.x`, `page.y`, `page.scale`, `minX/maxX/minY/maxY`,
  `getPage(0)` = current).

So the consequences are:

1. **Overlay must be viewer-agnostic.** Draw OCR boxes in the **existing Compose overlay**
   (`ReaderActivity` → `reader_activity.xml` `compose_overlay`, a sibling drawn *above*
   `viewer_container` for every viewer). Verified: `app/src/main/res/layout/reader_activity.xml:26`
   and `ReaderActivity.kt:309` (`setComposeOverlay`).
2. **Coordinate mapping must be per-viewer.** Introduce a small abstraction, e.g.
   `OcrViewport` with `imageNormalizedToScreen(...)` and `screenToImageNormalized(...)`,
   implemented for (a) SSIV pager, (b) webtoon strip, (c) WebGPU paged, (d) WebGPU continuous.
   SSIV implementations reuse Chimahon's math; the WebGPU one derives the transform from
   `ImageViewerState.getPage(0)` (`x/y/scale`) and dual-spread layout.
3. **Tap routing differs.** Pager/webtoon route through the viewers' gesture layers
   (`Pager.tapListener`/`longTapListener`) to `ReaderActivity.onPageLongTap(page)`. The WebGPU
   viewer already forwards `onLongTap` to `ReaderActivity.onPageLongTap(page)`
   (`WebGpuViewer.kt:968-970`) and passes a **normalised viewport offset** — currently discarded
   (`{ _ -> }`) — so we must retain it to know *where* the long-press landed.
4. **OCR input acquisition must be decoupled from rendering.** SSIV viewers hand you a decoded
   `Bitmap`; the WebGPU viewer does not (it decodes to GPU textures). Therefore run OCR off the
   **page byte stream / cached file** (the same input `.mokuro` and caching use), not off the view.
   This also makes OCR cacheable and viewer-independent — arguably cleaner than Chimahon's design.

### 7.2 Concrete hook points in komi-kun

- Overlay surface: `ReaderActivity.setComposeOverlay()` (`ReaderActivity.kt:309`, the `Box`), on top
  of `viewer_container`.
- Page views: `ReaderPageImageView.kt` (`:58`), `PagerPageHolder.kt`, `WebtoonPageHolder.kt`.
- Tap/long-press: `viewer/pager/Pager.kt` (`tapListener`/`longTapListener`) →
  `ReaderActivity.onPageLongTap()`; WebGPU: `WebGpuViewer.kt:958-970`.
- Reader menus/actions: `ReaderActivity.AppBars()` (`:635`), `viewer/setting/ReaderBottomButton.kt`.
- Per-manga OCR source enum + prefs: extend `reader/setting/ReaderPreferences.kt`.

### 7.3 Do we need to fork the WebGPU library?

Short answer: **no fork for the overlay/hit-test plumbing; a small upstream accessor (or replicated
math) for coordinates.**

Usable **as-is** (all public):

- **Overlay drawing** happens in komi-kun's own Compose layer (`compose_overlay` sits *above*
  `viewer_container`), so no render-pass hook is needed. The library's `ViewerSurface` uses
  `AndroidExternalSurface`/`AndroidEmbeddedExternalSurface` with `isOpaque = false`, so an overlay
  composites correctly above it.
- **Tap/long-press**: `ImageViewerState.onTap` / `onLongTap` (`(Offset) -> Unit`) give a
  **viewport-normalized** touch point — exactly the space hit-testing needs.
- **Per-page transform**: `ImagePage.x` / `y` / `scale` are public `var`s; with `width`/`height`,
  `state.getPage(0)`, and `state.width`/`height` we can map image-normalized ↔ screen-normalized
  coordinates.

Where it gets awkward:

- `ImagePage.pageRect(dst)` is public but **requires a `GPUTexture`**; and the dual-page helpers
  `leafRect` / `spineX` / `wholeRect` are **`internal`**, so a spread's per-leaf rect can't be read
  from the app module. Image placement also accounts for `trim` and `WebGpuRenderer.offsetX/offsetY`.
- **Continuous mode** places several pages with its own math.

**Decision: fork first, upstream later** (agreed). Build against a fork of `mpreg-ca/webgpuviewer` we
control, add the accessors we need, ship against it, then reduce the diff to a minimal PR and move
back to upstream once merged.

Fork plan:

1. Fork to `komi-kun/webgpuviewer`, branch `feat/ocr-transform-accessors`.
2. Add a **minimal, non-invasive** public API:
   - expose the on-screen pages, or a `pageRect` that doesn't require a `GPUTexture`;
   - make the per-leaf mapping reachable (`leafRect` / `spineX` / `wholeRect`), or add
     `fun ImageViewerState.imageRect(page: ImagePage, normalized: RectF): FloatArray?` that folds in
     `trim` + `WebGpuRenderer.offsetX/offsetY`.
   - additive + documented, so the upstream PR stays small.
3. Consume the fork pinned by commit/tag (JitPack for speed; publish an AAR to GitHub Packages for
   stable CI). When upstream merges, switch the version catalog back and drop the fork.
4. Keep app code behind our `OcrViewport` interface so swapping the fork out is a one-line dependency
   change.

Order: implement **paged** (single + spread) first; continuous mode later.

---

## 8. App-side architecture (Metro-first)

Chimahon wires its services with **Injekt** (`Injekt.get<T>()`, `AppModule.kt`); komi-kun uses
**Metro** (`mihon/app/di/AppGraph.kt`, `AppBindings.kt`). Port = adapt, not copy.

- **Services** (dictionary repo, OCR manager/store/cache, model downloader, Anki bridge): plain
  `@Inject` constructors resolved by Metro; `@Provides`/`@Binds` in `AppBindings.kt` only for
  interfaces/third-party types. The Rust engine is reached through its generated Kotlin facade
  (injected like any other service).
- **ViewModels** (`ReaderViewModel` OCR state): follow komi-kun's `StateFlow`/`State` pattern and
  keep OCR state out of the (already large) `ReaderViewModel` where possible — prefer a dedicated
  `DictionaryViewModel` / `ReaderOcrViewModel`-style collaborator obtained via `metroViewModel`.
- **Settings:** new Voyager `Screen`s under
  `app/src/main/java/eu/kanade/presentation/more/settings/screen/` (e.g. `SettingsDictionaryScreen`,
  `SettingsOcrScreen`, `SettingsAnkiScreen`), registered in `SettingsMainScreen.kt` and
  `SettingsSearchScreen.kt`.
- **i18n:** all new strings in `i18n-kmk/src/commonMain/moko-resources/base/strings.xml` (`KMR`).
  Do **not** add to `i18n/` (`MR`) or `i18n-sy/` (`SYMR`). Do **not** touch non-base locales.
- **Markers:** every new/changed hunk wrapped in `// KUN -->` … `// KUN <--` (and `-- KUN -->` in
  `.sq`). New files are pure additions (still `// KUN` when they touch upstream behaviour).
- **Manifest:** additive entries only as needed for the chosen scope (e.g. AnkiDroid permission +
  FileProvider if Anki mining is included). No camera/`PROCESS_TEXT`/widgets (out of scope).

---

## 9. Build system changes

komi-kun has **no NDK today**; this port introduces native code (Rust engine; possibly Paddle `.so`
shipped prebuilt).

- `settings.gradle.kts`: no new local modules if the engine ships as an AAR; if vendored, add a
  module.
- `app/build.gradle.kts`: consume the engine AAR (or add the Gradle↔cargo task); `jniLibs`/ABI
  splits/universal APK; `packaging` rules for the `.so`.
- Version catalogs under `gradle/`: add the engine artifact/version.
- **CI** (`.github/workflows/`): if the app builds the engine from source, install Rust + `cargo-ndk`
  + NDK r28.2 and cache `~/.cargo` + `target` + Gradle. If the engine is a published AAR, CI is
  unchanged.
- Spotless/`assembleDebug`/`testDebugUnitTest` gates still apply (see `AGENTS.md`).

---

## 10. Phased plan

| Phase | Deliverable | Notes |
|---|---|---|
| **0** | Decide + record: license (GPL-3.0 combined work), engine (vendor `hoshidicts`), OCR (Paddle direct), fork the WebGPU lib | This doc |
| **1** | Vendor `hoshidicts` submodule + NDK/CMake; port `HoshiDicts.kt`/`DictionaryRepository.kt` + JNI; get a lookup working headless | Proves the native build + Kotlin binding |
| **2** | Dictionary UI: WebView popup (assets + bridges), settings screen, i18n, per-manga config | Largest UI chunk; port from Chimahon, Metro-adapted |
| **3** | `.mokuro` support + viewer-agnostic OCR overlay in the Compose layer + `OcrViewport` for pager | Base OCR plumbing; OCR runs off page bytes, not the view |
| **4** | PaddleOCR engine integration + OCR cache/store + settings; wire hit-testing | Paddle `.so`/models artifact decision lands here |
| **5** | Extend overlay/hit-testing to webtoon + WebGPU viewers (fork's accessor) | The komi-kun-specific work |
| **6** | Anki mining (AnkiDroid bridge, field mapping, dedup, screenshot crop) | Yomitan's signature feature |
| **later** | Panel detection (LiteRT YOLO), extra languages | Separate scope |

Each phase: Metro-adapted, `// KUN`-wrapped, Spotless-clean, with unit tests for pure logic (box
merging, `.mokuro` parsing, coordinate mapping). Keep a thin Kotlin facade over the engine so it stays
swappable.

---

## 11. Risks & open questions

1. **Licensing boundary** — the APK is GPL-3.0, but files intended for upstream (Mihon/Komikku) must
   stay Apache-2.0 **and GPL-free**. Enforce with per-file SPDX headers and an import boundary.
2. **Paddle `.so` + models provenance** — who builds them, where hosted, checksums, license
   paperwork.
3. **Native build/CI complexity** — new NDK/C++ toolchain (and the Rust path if ever taken); ABI
   splits; APK size (models are large and downloaded at runtime → hosting + offline UX).
4. **WebGPU viewer coupling** — coordinate/hit-testing depends on the external MIT library's state
   (`page.x/y/scale`, `getPage`). Mitigated by the fork-first plan; upstream the accessor when stable.
5. **Vendored `hoshidicts` upstream is small** (Manhhao + the Chimahon fork) — fork to
   `komi-kun/hoshidicts` and treat it as ours; the C++23/NDK-29/CMake build is a new CI burden.
6. **Reader divergence** — reader patches won't apply; all integration is manual.
7. **Scope creep** — Chimahon's engine is shared with novel/anime; resist pulling those paths.

---

## 12. Decision log

- **Licensing: releases ship as GPL-3.0** (combined work; Apache upstream files keep their headers).
  Files kept GPL-free may still be contributed to Mihon/Komikku. See §4.2. *(decided)*
- **Dictionary engine: vendor `hoshidicts` (C++) + port Chimahon's Kotlin/JS**; fork to
  `komi-kun/hoshidicts`; behind a thin Kotlin facade. Rust rewrite is a deferred alternative (§5).
  *(decided — recommendation accepted)*
- **OCR: PaddleOCR directly** (drop cloud Lens + proprietary on-device Lens); `.mokuro` supported.
  *(decided)*
- **Overlay: viewer-agnostic, Compose-based, with per-viewer `OcrViewport` adapters** (handles the
  WebGPU viewer). *(decided)*
- **WebGPU: fork `mpreg-ca/webgpuviewer` first**, build against it, then upstream a minimal accessor
  PR. *(decided)*
- **Anki mining: include, later phase.** *(open)*
- **Panel detection: out of scope for now.** *(open)*

---

## 13. References / links

- Chimahon — <https://github.com/Chimahon/chimahon> (GPL-3.0)
- `hoshidicts` — <https://github.com/Manhhao/hoshidicts> (GPL-3.0)
- Yomitan — <https://github.com/yomidevs/yomitan> (GPL-3.0); dictionary schemas under `ext/data/schemas/`
- `wordbase` (MPL-2.0, Rust Yomitan engine w/ Lindera) — <https://github.com/aecsocket/wordbase>
- Lindera (MIT) — <https://github.com/lindera/lindera> · Sudachi.rs (Apache-2.0) —
  <https://github.com/WorksApplications/sudachi.rs>
- PaddleOCR (Apache-2.0) — <https://github.com/PaddlePaddle/PaddleOCR>
- LiteRT (Apache-2.0) — <https://github.com/google-ai-edge/LiteRT>
- `webgpuviewer` (MIT) — <https://github.com/mpreg-ca/webgpuviewer>
- Rust on Android tooling: `cargo-ndk` — <https://github.com/bbqsrc/cargo-ndk> · UniFFI —
  <https://mozilla.github.io/uniffi-rs/>
