# Agent handoff — komi-kun Yomitan / OCR / dictionary port

**Purpose.** This file lets a fresh agent (or a post-compaction continuation) pick up this work
**without searching the repository**. It records the state, the architecture, the rules, every
outstanding issue with its known root cause and next step, and the platform traps that have
already burned us. Read this first, then `AGENTS.md` (mandatory), then only the specific files
named below.

Last updated: 2026-10-19.

---

## 0. Start here (60-second orientation)

- This is a **personal fork of Komikku** (`komi-kun`) that ports **Yomitan-style dictionary lookup**,
  **manga OCR** and **Anki mining** from Chimahon. The combined work is **GPL-3.0-or-later**;
  upstream-derived files keep Apache-2.0 headers; GPL-free "seam" files stay Apache-2.0.
- All recent work lives on **`feat/introduce-ocr-and-yomitan`**. **`master` is only 2 commits
  ahead of the old fork point and must not be touched** without the user asking.
- The OCR backend was migrated from Paddle-Lite + PP-OCRv5 to **ONNX Runtime + PP-OCRv6 `small`**.
- Everything below has been verified on real hardware except where explicitly marked
  **UNVERIFIED**.

---

## 1. Current state (authoritative)

| | |
|---|---|
| Repo | `/home/luca/development/komi-kun` |
| Working branch | **`feat/introduce-ocr-and-yomitan`** @ **`0df028e181`** |
| `master` | `64fb7b9bf5` (branding + launcher icon; **already merged into the branch**) |
| Open PR | **#7** `feat/introduce-ocr-and-yomitan` → `master` (the only PR) |
| Other PRs | none open |
| Test device | OnePlus Pad `OPD2403`, serial **`4df231a3`**, arm64, Android 16 |
| App under test | package **`app.komikun.dev`**, installed `versionName` **`1.14.1-10750`** |
| Build gates | green (see §4) |

`versionName` is `1.14.1-<commit-count>`; **use it to identify a build** (debug APKs are not
byte-reproducible, so SHA-256 is not a reliable build id).

---

## 2. Ground rules (non-negotiable)

1. **Read `AGENTS.md`.** It is the mandatory rulebook and is kept current. If you change
   architecture, DI, modules, markers, i18n, build flags or workflows, update it **in the same change**.
2. **Fork markers.** Code we add/modify is wrapped `// KUN -->` … `// KUN <--` (`.sq` files:
   `-- KUN -->`). `// KMK` = Komikku upstream, `// SY` = TachiyomiSY upstream, no marker = Mihon.
   Never mark non-upstream code as `KMK`/`SY`.
3. **i18n.** New strings go **only** to `i18n-kmk/src/commonMain/moko-resources/base/` and are
   referenced via **`KMR`**. **Never edit non-base locales** (Weblate owns them).
4. **Git.**
   - Never commit/push to `master` unless explicitly asked.
   - Merging **into a feature branch**: use a **merge commit** (`--no-ff`) — that preserves the
     per-commit SSH signatures.
   - Merging **into `master`**: **squash** with `gh pr merge --squash --delete-branch`; never a
     merge commit. (This split is documented in `AGENTS.md`.)
   - No force-push, no rebasing shared branches.
   - Commits are **SSH-signed**. If signing fails because the Bitwarden agent is locked, retry the
     command with `env -u SSH_AUTH_SOCK`.
5. **Licensing boundary.** `./gradlew verifyGplFree` must stay green. The GPL-free seam files
   (`OcrViewport`, `ReaderOcrHost`, `ReaderDictionaryHost`, `DictionaryLookupRequest` in `:app`;
   `ReaderOcrSource` in `:core:common`) are Apache-2.0 and must not import GPL packages.
6. **Merging workflow (current preference).** Merge worktree branches **directly** into
   `feat/introduce-ocr-and-yomitan` (no PR) unless you specifically want CI. Open a PR only when
   an independent full CI build is worth the ~15 min.

---

## 3. Environment / tooling

### Build & gates (serialise Gradle when anything else may build)

```bash
flock /tmp/opencode/gradle.lock ./gradlew spotlessApply spotlessCheck \
  :app:compileDebugKotlin :app:testDebugUnitTest \
  :core:ocr:testDebugUnitTest :core:dictionary:testDebugUnitTest \
  verifyGplFree :app:assembleDebug
# after .sq/.sqm changes:
./gradlew :data:verifyDebugDatabaseMigration
```

### Device (real hardware — treat it as the user's phone)

```bash
adb -s 4df231a3 install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk   # update, keeps data
adb -s 4df231a3 shell pm list packages | grep -i komik     # ALWAYS check package ids first
```

- **Only `app.komikun.dev` may be installed/uninstalled/cleared.** The user's own Komikku is
  `app.komikku*` — **never** uninstall, clear, force-stop, disable or modify it.
- The user enabled **"stay unlocked while charging"** in Developer options. **Do not change power
  settings** (`svc power stayon`, `screen_off_timeout`) and **never disable the lock screen**
  (`locksettings set-disabled`) — that was done once by mistake and had to be undone.
- `adb shell screenrecord` is **blocked** on this device ("Permission denied") — use `screencap`.
- **Multi-touch cannot be injected** (`/dev/input` write denied; `input` has no multitouch), so the
  **two-finger OCR-overlay toggle can only be exercised by hand.**
- Local source layout (needed to add a test chapter): `<base storage>/local/<Series Title>/<chapter>.cbz`.
  **Directories directly under `local/` are the series**; a bare `.cbz` in that folder is ignored.
  Then Browse → Local source → refresh.

### Test manga (real data)

`/home/luca/development/MangaIngestWithUpscaling/test_data/library/original/` — 5 titles.
The sibling `upscale/` set is AI-upscaled and was **measured to hurt OCR** (do not use it as a
quality reference; it exists to prove that upscaling is not a lever).

### Host tooling / evidence — ⚠️ **the `/tmp` artefacts are GONE**

Subagents wrote reports and screenshots under
`/tmp/opencode/{ocr-bench,coo-onomatopoeia,ppocr-finetune,onomatopoeia-page6,textlayer-fix2,verify-merged,...}`.
**As of 2026-10-19 `/tmp/opencode` is empty** — the machine rebooted/cleared tmp overnight, so **all of
those files are lost.**

Nothing essential is lost: the **findings, numbers and conclusions are reproduced in this document**
(§8.5, §10, and **Appendix A**). Anything else can be regenerated — §10 and Appendix A record exactly
what each report contained and how it was produced.

Wireless/other serials are not relevant; the tablet is USB-attached as `4df231a3`.

---

## 4. Architecture map

```
:app            UI (Voyager), Metro ViewModels, reader + reader OCR integration, dictionary UI,
                importers, settings. Package roots: eu.kanade.*, mihon.*, exh.*
:domain         use cases (tachiyomi.domain.*.interactor), models, repo interfaces
:data           SQLDelight DB + repository implementations
:core:common    shared network/security/storage; Apache seam `ReaderOcrSource`
:core:dictionary  GPL-3.0 — hoshidicts C++ engine (nested submodule, NDK/CMake/JNI), Kotlin facade,
                Yomitan renderer assets, settings mapper, audio, LookupPath
:core:ocr       GPL-3.0 — PP-OCRv6 small via ONNX Runtime, cache, merger, .mokuro, model downloader
i18n-kmk        `KMR` strings (base locale only) — ALL new strings
i18n / i18n-sy  upstream `MR` / `SYMR` — do not add to them
```

**DI:** Metro (`dev.zacsweers.metro`). Feature `@BindingContainer`s: `DictionaryBindings`,
`OcrBindings`, `DictionaryHostBindings`, `OcrHostBindings` (registered in `AppGraph`).
`AppBindings.kt` is intentionally frozen for this port.

**Native artifact.** `komi-kun/komi-kun-ocr-models`, release **`ocr-models-v3`**: our Apache-2.0
`libpaddle_ocr.so` (JNI shim over the **ORT C API**, `dlopen`s `libonnxruntime.so`), Microsoft's
`libonnxruntime.so` **1.31.0** (MIT), `libomp.so`, and **PP-OCRv6 small** det/rec ONNX + dict.
Fetched + checksum-verified by `./gradlew :core:ocr:fetchPaddleNativeLibs` into
`core/ocr/src/main/jniLibs/<abi>/` (git-ignored). The artifact repo's **`main`** holds the v3
sources. ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64` (OCR now works on all three).

---

## 5. How OCR works end-to-end (file map)

| Concern | File |
|---|---|
| Engine (ORT, lazy init, models from `filesDir/paddle_ocr`) | `core/ocr/src/main/java/mihon/core/ocr/paddle/PaddleOcrEngine.kt` |
| Model download + verify + stale cleanup | `core/ocr/.../paddle/ModelDownloader.kt` |
| Manifest / pins | `core/ocr/paddle-manifest.json` |
| Cache (persisted blocks) | `core/ocr/.../cache/OcrManager.kt`, `OcrCacheManager.kt` |
| Line → block merging | `core/ocr/.../merge/OwOCRMerger.kt`, `OcrMergeGeometry.kt` |
| `.mokuro` sidecar path | `core/ocr/.../mokuro/**` |
| Reader block loading / per-frame anchor | `app/.../reader/ocr/impl/ReaderOcrBlockLoader.kt`, `ReaderOcrHostImpl.kt` |
| Overlay drawing (boxes + text layer) | `app/.../presentation/reader/OcrOverlay.kt`, `OcrTextLayout.kt` |
| Per-viewer geometry | `app/.../reader/viewer/{Pager,Webtoon,WebGpu}OcrViewport.kt`, `OcrViewport.kt` |
| Popup fit / placement / restore | `app/.../reader/viewer/popup/OcrPopupFit.kt` |
| Volume-key policy | `app/.../reader/viewer/VolumeKeyPagingPolicy.kt` |

**Data layout:** models + native libs in `filesDir/paddle_ocr/`; OCR cache in
`filesDir/ocr_cache/<sourceId>/<mangaId>/<chapterId>.json` — the cache variant now includes the
**engine generation (`v6`)** so v5-era results are never reused.

**Prefs:** `ReaderPreferences` (`ocrTextOverlayVisible`, `ocrTextScale` 50–150 %,
`ocrOverlayEnabled`, two-finger gesture), `OcrPreferences` (detection tuning: box-score threshold
25 %, binarisation 15 %, unclip 1.40, recognition confidence floor 0/off).

---

## 6. How the dictionary/popup works (file map)

| Concern | File |
|---|---|
| Popup host | `app/.../ui/dictionary/DictionaryPopup.kt`, `DictionaryEntryWebView.kt` |
| Popup sizing | `app/.../ui/dictionary/DictionaryPopupSizing.kt` |
| Recursive-lookup tab path | `core/dictionary/.../ui/LookupPath.kt` (+ `DictionaryPopup` wiring) |
| Renderer (HTML/CSS/JS) | `core/dictionary/src/main/assets/dictionary/renderer.js`, `base.css`, bootstrap |
| Engine access / media preload | `:core:dictionary` JNI + `app/.../DictionaryRepository` (media base64 → `data:` URI) |
| Yomitan settings import | `core/dictionary/.../yomitan/YomitanSettingsMapper.kt`, `app/.../YomitanSettingsApplier.kt` |
| In-app Dexie backup import | `core/dictionary/.../DexieBackupConverter.kt`, `DictionaryImportCoordinator` |
| Offline converter tool | `tools/yomitan-import/dexie_to_yomitan.py` |
| Word audio | `core/dictionary/.../audio/**` (`AudioResolver`, `AudioUrlTemplate`, `AudioModels`), `WordAudioBridge`, `WordAudioPlayer` |

**Imported dictionaries** land in `externalFilesDir/dictionaries/<term|kanji|frequency|pitch>/<title>/`;
the native importer packs media into `media.bin` + `media.idx` (not loose files). Images are
displayed by base64-inlining them into `data:` URIs (cap: 20 items / 256 KB per lookup).

---

## 7. What was fixed recently (context for the code you will read)

| Area | Fix |
|---|---|
| OCR backend | Paddle-Lite + PP-OCRv5 → **ORT + PP-OCRv6 small**; x86_64 support restored |
| Reader crash | `ViewConfiguration.get(this)` in a property initializer ran before attach → **every chapter open crashed**; made lazy |
| OCR deadlock | `OcrCacheManager` re-entered a **non-reentrant** `Mutex` via `load`→`loadFrom` → OCR hung before init; split into non-locking helpers; added a load watchdog |
| Overlay | boxes now track page turns (live `pageOffset` for WebGPU; SSIV transform version hashes holder position); draws all visible pages + primes neighbours; text layered over the printed text |
| Tategaki | real vertical layout: upright glyphs, per-cell punctuation, ASCII rotated in-cell (was a rotated horizontal run) |
| Text layer | sized to the printed ink (not the padded det box), solid fill (halo removed), top-anchored, `ocrTextScale` slider |
| Box merge | touching/abutting boxes no longer merge (new `OcrMergeGeometry`) |
| Popup | non-modal; single-tap lookup; configurable size + fit/restore; **bottom-edge block now places the popup above** (and `fitPaged` solves **x** as well as y, plus a 600 ms settle grace) |
| Tabs | recursive lookup is a **path** (truncate on a new lookup) |
| Volume keys | with the overlay on, volume keys adjust **media volume**, not paging |
| Thresholds | detection tuning exposed; cache keyed to engine generation |
| Audio | Yomitan word audio (Local Audio Server, custom URL/JSON, JPod101, Jisho), renderer-driven source/sample picker; regex crash fixed (see §11) |
| Yomitan import | offline converter, in-app Dexie import, settings importer (profiles/order/enabled/alias, popup, Anki, audio) |

---

## 8. OUTSTANDING ISSUES (prioritised)

### 8.1 Box merge may now over-correct — **UNVERIFIED, highest risk**
- **Symptom to look for:** a single line, tight paragraph, or tategaki column **fragmented into two
  blocks** (the opposite failure of the bug that was fixed).
- **Why:** the new `OcrMergeGeometry` requires run-axis overlap and a **positive** gap; real Paddle
  boxes come from unclip padding and often overlap slightly.
- **Files:** `core/ocr/.../merge/OcrMergeGeometry.kt`, `OwOCRMerger.kt`; tests
  `OcrMergeGeometryTest`, `OwOCRMergerTest`.
- **Next step:** open dense-text pages on the device (dialogue-heavy and tategaki), compare block
  count/coverage with the previous build. If fragmenting, relax the run-axis overlap (allow a small
  negative overlap) while keeping "abutting at a border does not merge".
- **Evidence location:** `/tmp/opencode/verify-merged/` has earlier page renders.

### 8.2 Popup placement only verified on WebGPU — UNVERIFIED
- The tablet uses the **WebGPU** viewer. The new placement rules (`PopupSide`/`popupSide`/
  `popupTopFor`) and the **tall-top `Partial` path** are unproven on **webtoon** and **SSIV**.
- **Files:** `app/.../reader/viewer/popup/OcrPopupFit.kt`, `ReaderOcrHostImpl.kt` (600 ms settle
  grace), `DictionaryPopup.kt`.
- **Next step:** switch reader mode, repeat bottom-edge and tall-block lookups; confirm placement +
  restore-on-close + no spurious dismissal.

### 8.3 Tab path — pill row shrinking unverified
- Logic is unit-tested (`LookupPath`, 14 cases) but the **visible** pill row shrinking after a
  truncating lookup was never confirmed on device; risk is the WebView's async `onTabSelect` frame swap.
- **Files:** `DictionaryPopup.kt` (`path.push/select/back`), `LookupPath.kt`, `renderer.js` tab bar.

### 8.4 Volume keys — real presses unverified
- Cannot inject volume keys reliably over adb. Verify by hand: overlay **on** → media volume;
  overlay **off** → the reader's volume-key paging pref behaves as before.
- **Files:** `VolumeKeyPagingPolicy.kt`, the three viewers, `DictionaryPopup.kt`
  (`volumeKeyNavigationAllowed`).

### 8.5 Stylised onomatopoeia is fundamentally misread — **not fixable cheaply**
- Measured on the user's page 6 (chapter 第51.2話 of ずたぼろ令嬢…): `ざわ` → `干S` (0.50),
  `手15` (0.71), `115`/`415` (0.57); `ぽっ` → `出` (0.18). Dialogue on the same page reads 0.98–1.00.
- **Diagnosis:** brush/hand-drawn kana. The charset *contains* the correct characters (clean-font
  `ざわざわ` reads 0.999). **Exhausted:** every orientation, per-glyph split, padding, grayscale,
  Otsu/adaptive binarisation, inversion, ink-crop, 2–4× upscale, 15/30/50 % box expansion, whole-band
  crop, and **48 threshold combinations** — all wrong. The rec ONNX has a **static 48 px** input, so
  taller crops are rejected.
- **Only lever:** the rec-confidence floor suppresses them (0.18–0.71) but also drops *correct*
  reads (`かしっ` 0.58, `くらっ` 0.65) and some garbage is confident (`七5` 0.96) — not a clean SFX filter.
- **Real fix:** fine-tune the rec model (parked as a side project — see §10).
- **Optional unimplemented idea (user hasn't decided):** flag implausible short mixed-script blocks
  (e.g. `干S`) as low-confidence in the UI instead of displaying them as authoritative.
- **Evidence:** full numbers reproduced in **Appendix A.2** (the original report lived in `/tmp` and was wiped).

### 8.6 Full dictionary import needs headroom
- In-app import of the user's **2.3 GB** Dexie backup needs **~3.5 GB free**; the tablet was at
  3.3 GB (limit is deliberate: the app refuses gracefully). The **offline converter**
  (`tools/yomitan-import/`) needs far less scratch and is the recommended route for the full set.
- A **146 MB subset** was imported successfully in-app (4 dictionaries, 80,819 terms) and lookups
  worked — so the path itself is proven.

### 8.7 Audio gaps
- Only **Custom URL**, **Custom JSON**, **Local Audio Server**, **JapanesePod101** and **Jisho** are
  resolved. **Modelled but not resolved:** `language-pod-101`, `lingua-libre`, `wiktionary`,
  `text-to-speech(-reading)` (`core/dictionary/.../audio/AudioResolver.kt`).
- The fallback "bloop" sound is a setting with **no bundled asset**.
- Media preload cap is 20 items / 256 KB per lookup (`DictionaryRepository`).

### 8.8 `nativeGetOrtError` is an inert stub
- The pinned `libpaddle_ocr.so` exports only `nativeInit/nativeProcess/nativeRelease`, so the Kotlin
  accessor always throws (guarded) and logs "unavailable". It becomes functional only after a shim
  rebuild in `komi-kun/komi-kun-ocr-models`. The shim's `nativeInit` already logs the real reason to
  logcat, so diagnostics are not lost. Low priority.

### 8.9 Engine/engine-adjacent limitations (not bugs to fix here)
- **hoshidicts silently drops `tag_bank` and `sequence`.**
- The user's 2024 Yomitan dictionary dump has **no recoverable `styles.css`** (predates Yomitan
  storing it) → dictionary-specific styling is limited; image layout is unaffected (bundled `base.css`).
- Overlay text residual: the system CJK font is **lighter** than manga lettering; column pitch can be
  ~4 % wide due to det-box imprecision; ~1 in 93 blocks has an engine-level orientation mis-detection.

### 8.10 Doc debt
- `docs/design/implementation/00-implementation-contract.md` and `03-ocr.md` are **authoritative**.
- `01-architecture-build.md` and `docs/design/yomitan-manga-ocr-port.md` are **historical** — they
  have "superseded" notes; do not treat their Paddle-Lite statements as current.

---

## 9. Verification playbook

1. Run the gate suite in §3 (must be green).
2. `adb -s 4df231a3 install -r …/app-arm64-v8a-debug.apk` (update install keeps dictionaries,
   settings, local-source series and OCR cache).
3. On device, for OCR/reader work: open a chapter with a local-source series, enable the overlay
   (two fingers — by hand), and check boxes + text layer + single-tap lookup.
4. For dictionary work: look up a word, check the popup placement/size/selection, recursive tabs,
   and the audio menu (long-press the speaker).
5. Capture `screencap` evidence; `screenrecord` is blocked.
6. Remember the cache is keyed by engine generation and by non-default thresholds — when comparing
   before/after behaviour, consider clearing the OCR cache or changing a threshold to force re-OCR.

---

## 10. Rejected / deferred (with evidence, to avoid redoing the analysis)

- **COO (ku21fan/coo-comic-onomatopoeia) as a bundled model — rejected.** Dataset/benchmark, not a
  model release. Its best recogniser (TRBA+2D) genuinely fixes onomatopoeia (**1/23 → 10/23** exact on
  the user's manga) **but**: 200 MB, **kana-only** (0/8 on dialogue), worse than PP-OCR on non-text
  with high-confidence hallucinations, **int8 export broken**, and weights are **unlicensed /
  Manga109-derived**. Only revisit if a **<60 MB quantised** artefact can be produced.
  Evidence: **Appendix A.1** (original report in `/tmp`, wiped).
- **Fine-tuning PP-OCR rec on onomatopoeia — parked as the user's personal side project.**
  Scoping is done: full Manga109 is academic-gated (**tainted**); **Manga109-s** permits commercial
  use of ML-derived models with attribution (~80 % COO coverage, application required); clean
  fallback is own manga + synthetic lines from OFL fonts (`trdg`, MIT). Rehearsal corpora:
  `nvidia/OCR-Synthetic-Multilingual-v1` (CC BY 4.0, 1.89 M ja), Manga109-s dialogue, `llm-jp`.
  PaddleX 3.7 has a real `PP-OCRv6_small_rec` training config; the shipped dict is byte-identical to
  `ppocrv6_dict.txt`; export path preserves `[N,3,48,W]→[N,T,18710]`. Recipe: rehearsal ~5–8:1 +
  **L2-SP** + two-stage unfreeze; dual-track eval with a **≤0.5 pt** general-Japanese regression gate.
  Cost ~8–14 dev-days, 5–20 GPU-hours. Evidence: **Appendix A.3** (original report in `/tmp`, wiped).
- **PaddleOCR-VL / PP-OCRv6 beyond `small`** — v6 has no Paddle-Lite path (needs ORT); VL is 0.9 B
  and server-only. `small` is the newest on-device-compatible tier.
- **AI upscaling as an OCR aid — measured to hurt** (degrades stronger models, inflates detector
  boxes 25→48/page). Classical resampling ≈ no effect.

---

## 11. Hard-won platform traps (these cost real time — do not relearn them)

1. **Android ICU regex ≠ JVM regex.** `Regex("\\{([^}]*)}")` compiles on the desktop JVM but throws
   `PatternSyntaxException` at `<clinit>` on Android (unescaped `}`). Prefer **no regex** for
   user/template strings. (`AudioUrlTemplate` was rewritten as a manual scanner.)
2. **WebView viewport units are 0** in this app's dictionary WebView: `vh`/`dvh`/`svh`/`lvh` and
   height media queries report **0**, while `innerWidth/Height` are correct. Never size UI with `vh`
   here — measure and use px. (This made the audio menu `max-height: 0` and invisible.)
3. **`kotlinx.coroutines.sync.Mutex` is not reentrant.** Nested `withLock` **suspends forever**
   (silent hang, no exception). This disabled OCR entirely for a while.
4. **Compose property initializers run before `attachBaseContext`.** No `getResources()`,
   `ViewConfiguration.get(this)`, `inflate`, etc. there — it NPEs and crashes the Activity on open.
5. **`visiblePages()` is not Compose snapshot state.** Read it per frame into `produceState` (or
   equivalent) or the overlay won't recompose when the viewer reports a page.
6. **Stale caches survive engine migrations.** Key persisted OCR by engine generation; changing
   thresholds must also invalidate (it extends the cache variant).
7. **Debug APKs are not byte-reproducible** — don't use SHA to identify builds; use `versionName`.
8. **Only one agent in the main working tree at a time.** Two concurrent agents caused a merge race;
   use separate worktrees (they are under `/home/luca/.local/share/opencode/worktree/3f9da5/`).
9. **Don't trust "high quality renderer = WebGPU".** It is capability-gated; the app silently falls
   back to SSIV and logs `High quality renderer unavailable, falling back to the SSIV viewer: …`.
   Check `dumpsys activity top`.
10. **`screenrecord` is blocked**; multitouch can't be injected — plan verification around that.

---

## 12. Open decisions for the user

1. **Recognition-confidence floor** — currently off. Set a default (~0.5) to suppress false
   positives, or leave it to the user? (It is *not* a good onomatopoeia filter — see §8.5.)
2. **Flag implausible blocks** (`干S`-style) as low-confidence in the UI? Not implemented.
3. **Fine-tuning project** — the user parked it; scope is in §10.
4. **Merge #7 → `master`** — squash + delete-branch, after a full CI run.

---

## 13. Known-good commands cheat sheet

```bash
# state
git -C /home/luca/development/komi-kun log --oneline -1 origin/feat/introduce-ocr-and-yomitan
gh pr list -R komi-kun/komi-kun --state open
adb devices

# build + gates (serialise!)
flock /tmp/opencode/gradle.lock ./gradlew spotlessApply spotlessCheck \
  :app:compileDebugKotlin :app:testDebugUnitTest \
  :core:ocr:testDebugUnitTest :core:dictionary:testDebugUnitTest \
  verifyGplFree :app:assembleDebug

# install (update, keeps data)
adb -s 4df231a3 install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb -s 4df231a3 shell dumpsys package app.komikun.dev | grep versionName

# merge a finished worktree branch into the integration branch (no PR)
git fetch origin && git merge origin/<branch> --no-ff && git push
git push origin --delete <branch>

# OCR evidence
adb -s 4df231a3 logcat -d -s AndroidRuntime:E        # crashes
adb -s 4df231a3 logcat -d | grep -E 'Paddle|recognize:|ReaderOcr|OcrCache|DictionaryRender'
```

---

## Appendix A — research findings (durable copy; the `/tmp` reports were wiped)

### A.1 COO onomatopoeia model evaluation

- **What it is:** `ku21fan/coo-comic-onomatopoeia` is the **ECCV-2022 dataset/benchmark**
  (arXiv:2207.04675), **not a model release**. Three tracks: **detection** (onomatopoeia polygons),
  **recognition**, **link prediction** (stitching truncated SFX). Annotations are Manga109 XML
  (`<onomatopoeia …>ちょーん</onomatopoeia>`, polygon + text; 63,726 elements). Manga109 images are
  **not shipped** (licence).
- **"Leaderboard"** = a static README table, last updated 2025-02-18:
  - Detection: **DBNet++ 90.8 / 60.9 / 72.9** (116 MB)
  - Recognition: **TRBA+2D 81.2 %** (199.7 MB) > MASTER 74.6 % (652.9 MB) > ABINet 70.6 % (445.6 MB)
  - Link: M4C-COO 70.2 H
  - Caveat: metrics are on **gold polygons** (easier than real detector boxes).
- **Leading candidate TRBA+2D:** TPS→ResNet→2×BiLSTM→attention (SAR 0/90/270 vote);
  **49.9 M params, 199.5 MB fp32**, 3×100×100 input, **187-class kana-only** charset; **code MIT**
  (on Apache-2.0 dtrb) → GPL-compatible; **weights carry no explicit licence and are
  Manga109-derived** ⚠️.
- It was exported to **ONNX** (opset 17, 200.7 MB, 2152 nodes, **text parity 24/24** vs PyTorch,
  **70 ms/crop** on 4 desktop threads).
- **Measured** (5 titles, 176 pages, identical det crops, hand-labelled GT):
  - **Onomatopoeia: PP-OCR 1/23 exact → TRBA 10/23 exact** (lenient 4/23→15/23; full-box 1/18→10/18).
  - **Dialogue: PP-OCR 8/8, TRBA 0/8** (no kanji in the charset → structurally cannot generalise).
  - **Non-text: TRBA worse** (conf 0.57–0.97 hallucinating ぬ/イイ/ゼー vs PP 0.08–0.45); its
    confidence is **useless as a gate** (1.00 on wrong reads).
  - **int8 dynamic quantisation fails to load** (LSTM/attention MatMul shape inference); static QDQ untried.
- **Integration options:** (A) Kotlin second-opinion pass, +200 MB, ~3–4 dev-days; (B) native shim,
  4–6 dev-days; (C, recommended) fine-tune PP-OCR rec on the COO annotations, 5–10 dev-days + GPU.
- **Verdict: do not ship as-is.** Phone latency was extrapolated, not measured.

### A.2 Page-6 onomatopoeia diagnosis

- Page 6 = `ずたぼろ令嬢は姉の元婚約者に溺愛される`, chapter **第51.2話** (10 pages); clean page
  `page6_original_836x1187.jpg`. Host det boxes matched the device's cached output **to three decimals**.
- Failures (det score | normalised box | PP-OCR output | confidence):

  | printed | det | box (norm) | PP-OCR returns | conf |
  |---|---|---|---|---|
  | ざわ | 0.91 | 0.795,0.331–0.916,0.456 (vertical) | **干S** | 0.50 |
  | ざわ | 0.76 | 0.728,0.510–0.830,0.630 | **手15** | 0.71 |
  | ざわ | 0.93 | 0.831,0.605–0.917,0.727 | **115** (device: `415`) | 0.57 |
  | ぽっ | 0.91 | 0.545,0.109–0.656,0.205 | **出** | 0.18 |

- Dialogue on the **same page** reads 0.98–1.00. The dict contains ざ/わ/ぽ; clean-font `ざわざわ` reads 0.999.
- **Exhausted:** every orientation (portrait, rotated, upright reflow, ±90°), per-glyph split, padding,
  grayscale, Otsu *and* adaptive binarisation, inversion, tight ink-crop, 2–4× upscale, +15/30/50 %
  box expansion, whole-band crop. The rec ONNX has a **static 48-px input** (taller crops rejected).
- **Threshold sweep:** 48 combinations (thresh 0.10–0.30 × box-thresh 0.20–0.40 × unclip 1.0–2.5) —
  the SFX is **never** correct; dialogue stays 0.99–1.00.
- **Rec-confidence floor:** these score 0.18–0.71, so ~0.75 suppresses them — but it also removes
  **correct** reads (`かしっ` 0.58, `くらっ` 0.65), and some garbage is *confident* (`七5` at 0.96).
- **Conclusion:** stylised hand-drawn brush kana; **no cheap fix**; needs fine-tuning.

### A.3 PP-OCR fine-tuning scoping

- **Licence.** COO annotations are CC BY 4.0 (clean); COO images are **Manga109**, and full Manga109
  is **academic-only** (institutional email) → a COO×full-Manga109 model is **tainted**. **Manga109-s**
  (87 of 109 books) terms explicitly permit using *"results obtained from machine learning experiments
  … including pre-trained models … for commercial use"* (condition 5) with an attribution duty
  (condition 7) → arguably distributable in a GPL-3.0 app, with a residual grey area, ~80 % COO
  coverage, and an application wait. **Not legal advice.** Clean fallback: own manga + synthetic SFX
  from **OFL fonts** (`trdg`, MIT).
- **Rehearsal data.** PaddleOCR's own training data is **not released**. Obtainable:
  `nvidia/OCR-Synthetic-Multilingual-v1` (**CC BY 4.0, 1.89 M ja**), Manga109-s dialogue annotations,
  `llm-jp` vertical-Japanese sets, own OFL synthesis. **Avoid** `deepcopy/japanese-synthetic-ocr-150k`
  (commercial fonts).
- **Tooling: yes.** PaddleX 3.7 ships a real **`PP-OCRv6_small_rec`** training config; the official
  `.pdparams` is public; the **shipped dict is byte-identical** (SHA-256 `b5f2bfe2…`) to
  `ppocrv6_dict.txt`; the model is **PPLCNetV4 + LightSVTR + CTC/NRTR**, exported CTC-only; the path
  `tools/train.py` → `export_model.py` → `paddlex --paddle2onnx` preserves
  **`[N,3,48,W] → [N,T,18710]`**. RTX 5070 Ti (sm_120) is supported on Linux/CUDA 12.9.
- **Anti-forgetting:** rehearsal at **~5–8:1** + **L2-SP** (ICML 2018) across a **two-stage unfreeze**
  (head-only 2–3 epochs, then the full net at LR 1e-4 with an α sweep); charset and 48 px geometry
  stay fixed by construction. L2-SP needs ~100 LOC (not native to Paddle).
- **Eval:** Track A (COO held-out + ≥150 real crops; beat 1/23, approach TRBA's 10/23) vs Track B
  (Manga109 dialogue, NVIDIA-ja val/test, latin/Greek, full-page golden set) with **≤0.5 pt** drop
  gates. Build and freeze it **before** training.
- **Cost:** ~8–14 dev-days, 5–20 GPU-hours.
- **Verdict: conditional go** — settle the data, then the eval, then train. No Manga109-s and no clean
  synthetic corpus ⇒ don't ship; take the cheap rec-confidence-floor win instead.
