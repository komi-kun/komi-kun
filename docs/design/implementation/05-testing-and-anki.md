# 05 — Testing & conformance + Anki phase (implementation plan)

Status: **planning (no code)** · Branch: `feat/introduce-ocr-and-yomitan` · Depends on:
`../yomitan-manga-ocr-port.md` (design/decisions), `01-architecture-build.md` (engine/vendoring),
`03-ocr.md` (OCR pipeline seams), `../yomitan-spec/` (behavioral contract).

Reference sources (read-only): `/tmp/opencode/chimahon` (GPL-3.0), `/tmp/opencode/yomitan`
(conformance oracle, `test/`).

Decisions assumed: combined work ships **GPL-3.0**; engine is **vendored `hoshidicts`**; OCR is
**PaddleOCR + `.mokuro`**; **Anki mining is in scope but is the last phase**. Tests must run in CI
(`.github/workflows/`) and on a dev machine **without a device** for pure logic.

---

## Part A — Test & conformance strategy

### A0. Constraints & tiers

| Tier | Runs on | Tooling | Gates PR? |
|---|---|---|---|
| **T1 pure logic** | JVM (host) | JUnit 5 + Kotest + MockK (`libs.bundles.test`) | yes |
| **T2 engine conformance (host)** | JVM (host) + host-native `.so` | JNI test against a **linux-x64 build** of `hoshidicts` | yes (engine/corpus changes) |
| **T3 engine conformance (device)** | emulator/device | `androidTest` or a `hoshidicts` CLI | no (nightly/manual) |
| **T4 manual** | real device + AnkiDroid | checklist | no |

Key fact that shapes everything: `hoshidicts` deinflection/ranking is **C++**. A JVM unit test can
only exercise it if the engine is built for the **host** (T2) — the Android `.so` (NDK) won't load
on the CI host. Everything we write in Kotlin (OCR glue, markers, profiles, crop math) stays T1.

### A1. Yomitan conformance corpus

**A1.1 What it asserts (the oracle).** Yomitan's `test/language/japanese-transforms.test.js`
(`/tmp/opencode/yomitan`) encodes the deinflection contract as tuples
`{term, source, rule, reasons}` — 1324 full tuples / 1446 `term:` lines across categories
(`adjectives`, `ichidan verbs`, `-う/-く/… verbs`, `irregular verbs`, `kansai-ben …`). The semantics
come from `test/fixtures/language-transformer-test.js:30-56`: for `source` the transform must yield a
candidate `text == term` whose `trace.map(f=>f.transform) == reasons` and whose condition flags match
`rule`. Negative categories (`valid:false`, e.g. `incorrect chains`) assert **no** matching candidate.
This mirrors spec `../yomitan-spec/03-japanese-deinflection.md` §4/§6.

Normalization helpers have their own oracle: `test/japanese-util.test.js` (1266 lines) for
katakana↔hiragana, half-width, combining marks, etc. (`03-japanese-deinflection.md` §5).

**A1.2 Format & storage.** Convert the JS literals to a committed, language-neutral corpus:

```
<engine-or-immersion-module>/src/test/resources/yomitan-corpus/
  ja-transforms.jsonl        # one JSON object/line: {category,source,term,rule,reasons,valid}
  ja-normalization.jsonl     # {op, input, args?, expected}
  lookup-ja.jsonl            # {dict, query, expect:[{expression,reading,rules}]}  (T2/T3 only)
  MANIFEST.json              # {yomitanCommit:"e7334a9", generatedAt, sha256 per file}
```

Use **JSONL** (append-friendly, streaming, diff-friendly). Keep the corpus small in git: transforms
(~1324 lines × ~60 B ≈ 80 KB) and normalization are fine to commit; **do not commit dictionaries**
(see A1.4).

**A1.3 Builder.** Add a committed generator (not run in CI) so the corpus is reproducible and pinned:

- `tools/yomitan-corpus/build.mjs` — `node`-imports `test/language/*.test.js`, extracts the `tests`
  arrays (they are plain literals; easiest is a tiny module that re-exports the arrays, or a robust
  regex/`acorn` parse), and writes the `.jsonl` + `MANIFEST.json` with the source commit.
- Runs against a pinned checkout/sha; the generator is a dev tool, **not** a build dependency
  (no Node/network in CI).
- Provenance: the corpus derives from GPL-3.0 Yomitan, so it is GPL-3.0 — fine for the combined
  work; add an SPDX header to `MANIFEST.json` and a `NOTICE` entry.
- A CI check (`:…:verifyCorpusManifest`) recomputes the hashes and fails on silent drift.

**A1.4 Real-dictionary lookup fixtures.** Deinflection alone isn't the lookup path
(scan → prefix candidates → deinflect → DB match → 10-rule ranking; spec `02-lookup-algorithm.md`).
Add a *small* lookup fixture:

- One **synthetic** dict built by the builder (a few dozen entries: `食べる`, `行く`, `高い`, …) — no
  redistribution question, covers ranking deterministically. Commit it (a few KB ZIP/folder).
- Optionally one **free/redistributable** real dict (e.g. a CC-licensed small JMdict subset) fetched
  in CI by pinned URL + checksum (never committed). Licensing of third-party dicts is *not*
  automatically GPL; verify per dict. If unclear, ship only the synthetic dict.
- `lookup-ja.jsonl` asserts: `lookup("食べました")` contains `食べる`; `query("食べる")` exact; a
  multi-dict merge case exercises `rebuildQuery`.

**A1.5 Runners.**

| Tier | Entry point | What it does |
|---|---|---|
| T1 | `:…:test` | parse **every** `.jsonl`; validate schema/non-empty/unique; assert TUPLES are internally consistent (e.g. `valid=false` categories are the known negatives). This is a cheap "corpus is sane" gate. |
| T2 | `:…:conformanceHostTest` | load a **host** `libhoshidicts` (CMake host toolchain, `linux-x64`) via JNI; for each tuple assert `lookup/transform` returns `term` with `reasons`; run `lookup-ja.jsonl`. |
| T3 | `:…:conformanceDeviceTest` (`androidTest`) | same corpus against the real NDK `.so`; emulator-runner, non-blocking. |
| future | Rust `engine-core` test | reads the *same* `.jsonl`; keeps the engine swappable (design §5.3). |

The T2 host build is the important one: it gives CI a **device-free** engine gate. If the vendored
`hoshidicts` can't be host-built cheaply, fall back to running its **own C++ test binary** over the
corpus (convert `.jsonl`→a small JSON it reads) as a Gradle `Exec` task; still T2.

**A1.6 Engine-under-test seam.** Test through the thin Kotlin facade (architecture plan `01`), not raw
JNI types, so the same corpus works for a future Rust engine. Model on Chimahon's shape
`LookupResult{matched,deinflected,process,term,preinflected…}` (design §5.2).

### A2. OCR pipeline unit seams (mostly T1, pure Kotlin)

Port these as **`android.*`-free** classes so `src/test` runs on the JVM. Current Chimahon sizes as a
proxy for risk:

| # | Seam | Reference file | Test focus |
|---|---|---|---|
| 1 | `NormalizedBBox` math | `chimahon/ocr/EngineLine.kt` | width/height/center, degenerate boxes |
| 2 | `OcrPreprocessor.clean` | `chimahon/ocr/OcrPreprocessor.kt` | punctuation collapsing (ellipsis/>`?`/>`!` priority), `･･･` |
| 3 | `EngineLine` derived fields | `chimahon/ocr/EngineLine.kt` | `hasJpText`/`hasKanji` regex, `characterSize` for TTB vs LTR |
| 4 | Preprocess/chunk | `chimahon/ocr/OcrPreprocessor.kt`, `ImageChunking.kt` (285 L) | split thresholds, resize-to-max-px, overlap math |
| 5 | **Line merger + reading order** | `chimahon/ocr/OwOCRMerger.kt` (1261 L) | highest risk: port `OwOCRMergerEnglishOrderTest.kt`; add **JA vertical RTL** cases; snapshot normalized blocks for fixed inputs |
| 6 | Hit-testing | `chimahon/ocr/OcrHitTester.kt` | `findBlock` scale/padding around **center**; `hitCharacter` rotated/vertical offset; `isLineVertical` (>1.2 ratio) |
| 7 | Lookup-text extraction | `chimahon/ocr/OcrLookupText.kt` | start-char predicate, trailing span, `shouldScanWholeWord`/`effectiveScanResolution` (CJK = character) |
| 8 | `.mokuro` | `MokuroModels/Parser/PageResolver/Converter.kt` (30/14/35/74 L) | lenient parse (unknown keys, malformed → null), filename/basename match + index fallback, coordinate normalization + degenerate-box/blank-line rejection, line polygons |
| 9 | Crop presets | `chimahon/ocr/CropPresets.kt` | aspect lookup + centering (drives screenshot crop) |
| 10 | **`OcrViewport` coordinate map** | *new* (design §7.1–7.2) | image-normalized ↔ screen-normalized round-trips per viewer: pager/webtoon/WebGPU paged/continuous, from synthetic page state (`page.x/y/scale`) |
| 11 | Reading-order sorter | design §6.3 | vertical RTL ordering over synthetic boxes |

Put the *math* in pure classes (seam 10) and keep only the viewer wiring Android-side — this is what
makes the WebGPU/pager matrix testable without a device.

### A3. App layer: what to test vs manual

**T1 (JVM, `app/src/test` / module `src/test`):**

- **Profile store** — port `AnkiProfile`/`AnkiProfileStore` but **switch `org.json` → kotlinx
  serialization** (already a dep) so they run on the JVM without Robolectric. Assert:
  `toJson/fromJson` round-trip; `migrateIfEmpty` from legacy flat keys
  (`DictionaryPreferences.kt:235-242`: `pref_anki_deck`, `pref_anki_model`, `pref_anki_field_map`,
  `pref_anki_default_tags`, `pref_anki_duplicate_check/scope/action`, `pref_dict_anki_crop_mode`);
  `addProfile`/`deleteProfile` (last-profile guard).
- **`LapisPreset`** — `defaultFieldMapFor`, `applyDefaults`, `isLapisLikeModel`, `hasCoreFields`.
- **Marker engine** — `parseFieldMap`, the **marker scanner**, `renderMarker` for each catalog entry
  (`expression/reading/furigana(-plain)/glossary*/frequency*/pitch*/cloze-*/tags/conjugation/
  part-of-speech/audio/screenshot/…`), driven by small expected-output fixtures. Furigana
  distribution and cloze splitting get their own cases (spec `06-anki.md` §a).
- **Duplicate gate** — port `AnkiDuplicateGateTest.kt` (stripe serialization under
  `runTest`/`advanceUntilIdle`).
- **Media preparation** — port `AnkiCardMediaPreparationTest.kt` and
  `AnkiSentenceAudioCommitterTest.kt` (non-fatal per-media failure, transparent-GIF fallback).
- **`AnkiDroidBridge` pure helpers** — `fieldChecksum` (strip HTML → SHA-1 → first 8 hex),
  `stripHTML`/`stripHTMLMedia`, `parseFieldNames` (JSON-array vs `\u001f`), `sanitizePreferredBaseName`.
- **ViewModels/settings** — state reducers only (no Compose); profile switch → field-map recompute.

**T4 manual (checklist, never a CI gate):** AnkiDroid installed/missing, permission grant/deny/revoke,
deck+model auto-create, real `canAddNotes`/duplicate behaviour, media actually visible in AnkiDroid,
the **WebView `addToAnki` bridge** end-to-end, screenshot crop tap→region→card, OCR overlay hit at
multiple zooms across all four viewers, PaddleOCR accuracy/latency, OCR model download, DB round-trip
after sync.

### A4. CI wiring

Existing pipeline (`.github/workflows/build_pull_request.yml`): `spotlessCheck` (:81) →
`assembleNightly` (:85/:89) → **`testDebugUnitTest` (:93)** → `:data:verifyDebugDatabaseMigration`
(:96). `testDebugUnitTest` already runs every module's `src/test`, so **all T1 tests gate PRs for
free** — just put them in the right modules.

Additions:

| Step | Job | Gate | Notes |
|---|---|---|---|
| T1 (all) | `build_pull_request.yml` | PR (existing step) | pure logic + corpus sanity + profiles/markers |
| Corpus manifest check | same job | PR | `verifyCorpusManifest` (hash vs pinned commit) |
| T2 host conformance | new job in `build_pull_request.yml` (cached) | PR **only when** `:…` engine/corpus paths change (`paths:` filter or `if:`) | needs CMake host toolchain + JNI host `.so`; cache `~/.cargo`/`target`/CMake if engine is Rust later |
| T3 device conformance | new workflow `conformance-emulator.yml` | **nightly / manual** (never blocking) | `reactivecircus/android-emulator-runner` or a prebuilt `hoshidicts` CLI |
| OCR model/`.so` presence | manual/nightly | no | models are runtime assets, not build deps |

If the engine ships as a **published AAR**, only T2 changes (host build lives in the engine repo's own
CI); komi-kun's PR job stays as today. Keep `--stop`/`-Xmx4g` OOM mitigation in mind
(`AGENTS.md` §Common issues).

### A5. Module placement

| Test | Where | Why |
|---|---|---|
| Corpus data + T2/T3 runners | the **engine module** (vendored `hoshidicts` facade, per `01-architecture-build.md`), `src/test/resources` + `androidTest` | co-located with the JNI facade; only this module needs native code |
| OCR pure-logic tests (A2) | the **immersion/OCR module** (or `:domain` if the glue is portable) — whichever owns the `android.*`-free classes | keeps JVM-only, no device |
| Profile store, markers, ViewModels | **`app/src/test`** | app-only, needs app deps/`org.json`→kotlinx fix |
| `OcrLookupText`/scan resolution | same module as the OCR glue | pure; note it currently imports `AnkiProfile` — keep the dependency one-way or move the enum |

Rule: **never** put a test that loads an Android `.so` in `src/test`; that's a T3/`androidTest` test.

---

## Part B — Anki phase plan (deferred, last)

Ship after the dictionary UI (phase 2) and OCR phases 3–5 are stable. Read spec `06-anki.md`
(marker catalog + flow) as the **contract**; Chimahon is the reference **implementation**.

### B1. Files to port + ownership map

Source: `/tmp/opencode/chimahon/chimahon/src/main/java/chimahon/anki/*` and app-side wiring.

| Chimahon file | Lines | Kind | Target (komi-kun) | Test |
|---|---|---|---|---|
| `AnkiDroidBridge.kt` | 708 | Android/ContentResolver | engine/anki module | T1 helpers + T4 |
| `AnkiCardCreator.kt` | 2362 | pure build/logic (+markers) | download/anki module | **T1 heavy** |
| `AnkiProfile.kt` | 190 | data + JSON | module (→ kotlinx.serialization) | T1 |
| `AnkiProfileStore.kt` | 153 | data + JSON | module (→ kotlinx.serialization) | T1 |
| `AnkiDuplicateGate.kt` | 34 | coroutines | module | T1 |
| `AnkiSentenceAudio.kt` | 144 | video-only machinery | *thin stub* (see B4) | T1 stub |
| `AnkiScreenshotMode.kt` | 13 | enum | module | T1 |
| `LapisPreset.kt` | 100 | field map/预设 | module | T1 |
| `AnkiWebViewBridge.kt` | 41 | Android WebView | app (bridge) | T4 |
| `AnkiCardMediaPreparation.kt` | 62 | coroutines/media | module | T1 |
| `SettingsAnkiScreen.kt` | 21 | UI shell | `app/.../settings/screen/` | manual |
| `SettingsDictionaryScreen.ankiPreferences()` | (large) | settings + profile UI | wrap `// KUN` | manual |
| `DictionaryPreferences.kt` anki keys | — | prefs | wrap `// KUN` | T1 migration |
| `OcrLookupPopup` / `ReaderActivity` / `ReaderViewModel` | — | lookup→mine wiring | wrap `// KUN` | T4 |

Reference tests to port/replace: `AnkiDroidBridgeTest.kt` (41 L, MockK static/constructor),
`AnkiDuplicateGateTest.kt` (43 L), `AnkiCardMediaPreparationTest.kt` (54 L),
`AnkiCardCreatorSentenceAudioTest.kt` (208 L, mostly video sentence audio — trim), all under
`chimahon/src/test/{java,kotlin}/chimahon/anki/`.

**Ownership/marking.** New files are mostly pure additions but touch upstream reader/DI/settings, so
wrap edits in `// KUN -->`/`// KUN <--` (never `KMK`/`SY`). `.sq`/mappers use `-- KUN -->`. GPL files
get SPDX + `NOTICE`; files intended for upstream must stay Apache + GPL-free (design §4.2).

### B2. Contract: marker catalog + DOM→field mapping

Implement from spec `06-anki.md` §a/§b. **Do not** transcribe; the catalog is the acceptance test.

- **Scanner** — must be exactly `\{[\p{Letter}\p{Number}_-]+\}` (spec §d, `anki-util.js:23`).
  ⚠️ Chimahon's `AnkiCardCreator.kt:591` uses `Regex("""\{([\w\W]+?)\}""")` (any char) — **fix to the
  spec regex**; unknown marker → literal `{name-render-error}` (`06-anki.md:244-247`).
- **No `{marker:arg}` syntax** — args live in the Handlebars/template definition (`06-anki.md:9-16`).
- **Catalog** — term + kanji markers per the table (`06-anki.md:17-61`), incl. ja-only pitch,
  dynamic `single-glossary-*`/`single-frequency-number-*`, `frequency-harmonic/average-*` with
  `-1`→`9999999` (rank) / `0` (occurrence), pitch `text|graph|graph-jj|position` + categories,
  `cloze-*` from sentence + offset, `{sentence-furigana(-plain)}`, `{audio}`→`[sound:…]`,
  `{screenshot}`/`{clipboard-image}`→`<img src>`.
- **Divergence to decide** — spec §d says `{definition}`,`{definition-selected}`,`{selected-glossary}`,
  `{book-title}` do **not** exist in Yomitan. Chimahon adds non-Yomitan markers
  (`SELECTED_GLOSSARY`, `MEDIA_NAME`, `WORD_AUDIO`, `SENTENCE_AUDIO`, `CHAPTER`, `MORAE`,
  `SENTENCE_TRANSLATION`, `MISC_INFO` — `AnkiCardCreator.kt:52-106`) and `LapisPreset` uses
  `{selected-glossary}`/`{media-name}` (`LapisPreset.kt:41,52`). **Decision: Yomitan catalog is
  canonical; Chimahon extras are `// KUN` extensions** and must degrade to `""` when absent.
- **Field mapping** — user-configured `field → template`; default preset `LapisPreset.fields` (22
  fields, `LapisPreset.kt:10-33`) + `defaultFieldMap` (`:35-53`). `applyDefaults` only fills Lapis-like
  models. No bundled note type (spec §d bottom).
- **Card flow** (spec §b): format `{name,icon,deck,model,type,fields}`; first-field-only duplicate
  skeleton; `overwriteMode` ∈ coalesce/coalesce-new/skip/append/prepend/overwrite; duplicate scope
  deck/deck-root/collection with `checkChildren`/`checkAllModels`; behaviour prevent/overwrite/new.

### B3. AnkiDroid integration specifics

From `AnkiDroidBridge.kt`; verify against current AnkiDroid before shipping.

- **Permission** `com.ichi2.anki.permission.READ_WRITE_DATABASE` (`:42`); request code 2001
  (`:43`) via `requestPermissions` (`:102-104`). Declare `<uses-permission>` in the manifest.
- **Provider** authority `com.ichi2.anki.flashcards` (`:45`); URIs `notes`, `notes_v2`, `models`,
  `decks`, `media` (`:47-51`). Field separator `\u001f` (`:77`).
- **Finding duplicates** — `notes_v2` queried by `csum` = first 8 hex chars of SHA-1 of
  `stripHTMLMedia(firstFieldValue)` (`:206-235`, `:685-703`). Must match AnkiDroid's checksum exactly.
- **Notes** — insert into `notes` then `moveCardsToDeck` per card `ord` (`:237-272`, `:559-584`);
  `updateNoteFields` merges by field name (`:274-302`).
- **Media** — write to `getExternalFilesDir(null)/anki_media`, expose via **FileProvider
  `${packageName}.provider`**, `grantUriPermission("com.ichi2.anki", …)`, insert into `media` with
  `file_uri`+`preferred_name`, then delete the temp file; AnkiDroid returns the stored filename
  (`:586-618`). komi-kun already has the provider + `${applicationId}.provider` authority
  (`AndroidManifest.xml:288-296`) and `provider_paths.xml` covers `external-files-path` (fine for
  `getExternalFilesDir`).
- **Intents** — `guiBrowse` `anki://x-callback-url/browser?search=` (`:304-314`); `guiEditNote` falls
  back to `nid:` browser (no editor intent filter) (`:316-320`); `triggerSync`
  `com.ichi2.anki.DO_SYNC` with a 120 s cooldown (`:371-396`).
- **Manifest additions** — `com.ichi2.anki.permission.READ_WRITE_DATABASE` +
  `<queries><package android:name="com.ichi2.anki"/></queries>` (`QUERY_ALL_PACKAGES` exists in
  komi-kun but an explicit query is cleaner). FileProvider already present.
- **Package name** — replace hard-coded `"chimahon"` defaults (tags/deck/base names) with `komikun`.

### B4. Screenshot crop + sentence audio + duplicates

- **Screenshot** (`AnkiScreenshotMode` FULL/CROP/NONE/ANIMATED_SCENE): the reader reads the page
  **byte stream** (not the view) per design §7.1 and crops it *before* the card is created, so
  `{screenshot}` is written inline by `AnkiCardCreator`. komi-kun deliberately has **no** second
  "patch the note afterwards" phase and no cropper activity — Chimahon's manual cropper
  (`com.canhub.cropper`) is not vendored, and a deterministic crop needs no user gesture.
  `AnkiScreenshotCrop` (pure, T1-tested) decides the region: `FULL`/`ANIMATED_SCENE` → the page,
  `CROP` → the tapped OCR block grown by 8 % and expanded to the `CropPresets` aspect (slid back
  inside the page rather than clipped), `NONE` → no image. `ReaderOcrScreenshot` decodes *only* that
  region at ≤1024 px (`ImageDecoder.decodeToBitmap(region, sampleSize)`) and re-encodes WebP; the
  provider reaches the popup through `ReaderOcrPopupAnchor`, so the Apache seam DTO is unchanged.
  Media name stays `komikun_<sha1-12>.webp`. This is why `AnkiMiningController.updateScreenshot`
  (the two-phase patch, which also bypassed `commitPreparedAnkiCardMedia`) was removed.
- **Which settings the capture reads.** Mode and preset come from the **flat** keys
  (`AnkiScreenshotSettings`, `pref_anki_crop_mode`/`pref_anki_crop_preset`), not from
  `AnkiPreferences.activeProfile()`: the settings screen's `ListPreference` runs its change callback
  (`persistActiveFromFlat()`) *before* writing the new value (`PreferenceItem.kt`), so the persisted
  profile is one edit stale and "No screenshot" would keep capturing. Both the **mode** and the
  **crop preset** have UI (`SettingsAnkiScreen.screenshotGroup`); the preset list is built from
  `AnkiScreenshotPresetOptions` (the shared `CropPresets` vocabulary plus `"full"`), so the picker
  can only offer keys the reader resolves. The default stays `"full"` ⇒ no forced aspect and `CROP`
  frames the block + margin; a chosen key is mapped by `AnkiCropPresetAspect` (through
  `CropPresets.aspectByKey`, pinned by `AnkiCropPresetAspectTest`) to the `AnkiCropAspect`
  `AnkiScreenshotCrop` expands the block to.
- **Sentence audio** — Chimahon's `AnkiSentenceAudio*` is **video-only** (FFmpeg extraction, track
  mapping). Manga has no audio source, so **port only the marker plumbing + a `null` provider**
  (`prepareSentenceAudioForMarker`, `AnkiSentenceAudio.kt:132-144`); `{sentence-audio}` renders `""`.
  Keep the enum/`Unavailable` types as stubs so markers never crash. Do **not** port
  `MediaCaptureService`/`SentenceAudio` (player).
- **Duplicates** — `AnkiDuplicateGate` (16 fixed mutex stripes; `preflight`/`commit` around the media
  write+insert so the same key can't race, `AnkiDuplicateGate.kt:16-33`). Behaviour per spec §b:
  `prevent` (return existing), `overwrite` (merge per-field `overwriteMode` + `updateNoteFields`),
  `new`. First-field-only skeleton for the duplicate probe.
- **Media commit** — `commitPreparedAnkiCardMedia` is the single write boundary; optional-media
  failures are non-fatal (screenshot/word-audio → drop, dictionary media → transparent GIF,
  sentence audio → warning) (`AnkiCardMediaPreparation.kt:36-61`).

### B5. i18n, settings registration

- **Strings:** all new keys in `i18n-kmk/src/commonMain/moko-resources/base/strings.xml` (`KMR`);
  never `MR`/`SYMR`, never non-base locales (`AGENTS.md`). Reuse existing `pref_category_anki`,
  `pref_anki_settings_summary`, `anki_card_added`, `pref_anki_permission_denied`, `anki_not_configured`
  where possible.
- **Settings:** `SettingsAnkiScreen` (thin, delegates to shared `ankiPreferences()`); register in
  `SettingsMainScreen.kt` (`pref_category_anki` entry) and add to `SettingsSearchScreen.kt`'s list.
- **Prefs:** new keys namespaced `pref_anki_*` via the module's `*Preferences`; keep the
  `AnkiProfileStore` persistence keys (`pref_anki_profiles`, active id) and the legacy migration keys.

### B6. Risks

1. **Checksum/duplicate parity** with AnkiDroid (field-stripping + SHA-1 truncation) — a mismatch
   silently creates duplicates. Lock via T1 tests + T4.
2. **ContentResolver/provider drift** — AnkiDroid's `flashcards` provider columns change across
   versions; behaviour is untestable headlessly (T4 only).
3. **Marker catalog divergence** — Chimahon extras vs Yomitan spec (B2 decision); the wrong choice
   breaks Anki field templates.
4. **`org.json` on the JVM** — will "not mocked" fail in `src/test`; must move profiles to
   kotlinx.serialization (or Robolectric). Do this **first** in the phase.
5. **Media/FileProvider + scoped storage** — grant must be read-only and temp files deleted.
6. **GPL/Apache boundary** — reader/settings hunks must remain Apache + GPL-free if intended upstream
   (isolate behind interfaces; design §4.2).
7. **Scope creep** — do not pull video/audio/novel paths.

### B7. Ordered tasks

1. Decide marker-catalog scope (Yomitan-canonical + `// KUN` extras); record in this doc.
2. Move `AnkiProfile`/`AnkiProfileStore` to kotlinx.serialization; port + T1 tests (round-trip,
   `migrateIfEmpty`, CRUD).
3. Port `LapisPreset`, `AnkiScreenshotMode`; T1 tests.
4. Port the marker engine → spec regex + full catalog; fixture-driven T1 tests (incl. negative/unknown).
5. Port `AnkiDuplicateGate` + `AnkiCardMediaPreparation` + tests.
6. Port `AnkiDroidBridge` (pure helpers first, T1; ContentResolver paths behind the
   `AnkiCardBridge` interface for a fake in tests).
7. Manifest: permission + `<queries>`; verify FileProvider coverage.
8. Wire lookup → mine (`OcrLookupPopup`/`ReaderActivity`/`ReaderViewModel`) with `// KUN`; screenshot
   crop; sentence-audio stub.
9. `SettingsAnkiScreen` + profile UI + `KMR` keys + settings/search registration.
10. CI: T1 gate already runs; add T2 conformance job + corpus-manifest check; T3 emulator workflow.
11. Manual verification checklist (B-A3) + `spotlessApply`/`spotlessCheck`/`assembleDebug`.
12. Changelog/`AGENTS.md` update if module layout or markers change.
