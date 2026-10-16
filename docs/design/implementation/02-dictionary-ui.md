# 02 — Dictionary UI (Yomitan popup, settings, import/management)

Implementation plan for the **dictionary UI** half of the Yomitan/manga-OCR port.
Companion docs: `01-architecture-build.md` (engine module + `DictionarySession` facade + Metro/build wiring),
`../yomitan-manga-ocr-port.md` (decisions), `../yomitan-spec/05-rendering.md` (DOM contract),
`../yomitan-spec/07-search-ux.md` (lookup/recursive UX).

Reference source (GPL-3.0): `/tmp/opencode/chimahon`. Target repo: this one, branch `feat/introduce-ocr-and-yomitan`.
This plan is **UI + storage + settings only**. OCR overlay/hit-testing/viewers are a separate plan.

> Scope lock: **drop** novel, anime, video/player OCR, screen OCR (`ScreenLookup*`), camera OCR (`CameraOcr*`),
> `ProcessText*`, and panel detection. Manga-only.

---

## 0. Interfaces this plan needs from 01 (assumed facade)

`01` owns the engine module and the Kotlin facade. This plan is written against:

```kotlin
// provided by :engine (GPL) — names may differ; adapt call sites
interface DictionarySession {
    fun lookup(text: String, maxResults: Int = 32, scanLength: Int = 0): List<LookupResult>
    fun query(text: String): List<TermResult>
    fun queryKanji(character: String): KanjiResult
    fun styles(): List<DictionaryStyle>
    fun mediaBytes(dictName: String, mediaPath: String): ByteArray?   // ← required for data-URIs
    fun close()
}
interface DictionaryEngine {
    fun importDictionary(zip: String, outDir: String, progress: ((Long, Long) -> Unit)? = null): ImportResult
    fun probeEntryTypes(dictPath: String): LongArray                     // [term, freq, pitch, kanji]
    fun openSession(paths: DictionaryPaths): DictionarySession           // or session.rebuild(paths)
}
```

**Flagged additions I need** (raise with `01` if missing):
1. `mediaBytes(...) -> ByteArray?` — the payload builder turns bytes into `data:` URIs (Chimahon `HoshiDicts.getMediaFile`, `DictionaryRepository.kt:177`).
2. `styles()` returning `DictionaryStyle(dictName, styles)` (Chimahon `HoshiDicts.getStyles`).
3. `DictionaryPaths(termPaths, freqPaths, pitchPaths, kanjiPaths)` (Chimahon `DictionaryPaths.kt:3`).
4. Session rebuild/`openSession` keyed on paths so `warmUp` can no-op when unchanged (Chimahon `DictionaryRepository.kt:36-54`).
5. `ImportResult{success,title,termCount,metaCount,freqCount,pitchCount,kanjiCount,mediaCount}` (Chimahon `HoshiDicts.kt:3`).

Everything else (prefs, WebView, payload, profile store) is produced by this plan.

---

## 1. Licensing boundary + module layout

**Decision recap (fixed):** the distributed APK is GPL-3.0; per-file licenses are mixed.
A small set of **upstream-facing files must stay Apache-2.0 and GPL-free** and therefore must not import GPL types.

### 1.1 The GPL-free set (must not import the GPL module)

Reader core only:

- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/**` (incl. `pager/**`, `webtoon/**`, `webgpu/**`, `ReaderPageImageView.kt`)

### 1.2 Boundary mechanism

Define a tiny **Apache-2.0** interface package that the reader imports:

```
app/src/main/java/mihon/dictionary/host/ReaderDictionaryHost.kt   (Apache, // KUN)
```

```kotlin
interface ReaderDictionaryHost {
    fun warm(context: Context, languageCode: String)          // pre-warm the WebView shell
    fun showLookup(request: DictionaryLookupRequest)          // OCR/tap → open popup
    fun dismiss()
    @Composable fun Popup()                                   // renders the popup inside compose_overlay
}
```

- The **implementation** (`ReaderDictionaryHostImpl`, GPL) lives with the dictionary UI and is bound with Metro
  (`@ContributesBinding(AppScope::class)` or `@Binds` in `AppBindings.kt`), exposed as
  `AppGraph.readerDictionaryHost`. Reader reads `val host = LocalContext.current.appGraph.readerDictionaryHost`.
- Reader edits are wrapped `// KUN --> … // KUN <--` and its import graph stays GPL-free.
- **Key constraint (verified):** Compose screens that implement app-module interfaces (`SearchableSettings` in
  `…/settings/screen/SearchableSettings.kt`, `Tab` in `…/presentation/util/Navigator.kt`, `Screen`) **cannot live
  in a library module** without creating a cycle. So the `app`-facing UI stays in `app/` (GPL-marked via SPDX
  header + `// KUN`); only Android-light, app-interface-free code moves into the GPL module.

### 1.3 Proposed module split

| Layer | Location | License |
|---|---|---|
| Native engine + facade (`DictionarySession`) | `:engine` module (owned by 01) | GPL-3.0 |
| Dictionary repository, prefs, profile store/resolver, payload builder, WebView shell/bridges/state, `KanaConverter`, renderer assets | new `:dictionary` library module, namespace `mihon.dictionary` | GPL-3.0 |
| Compose popup, `DictionaryTab`, settings screens, `DictionaryEntryWebView` | `app/` (`eu.kanade.tachiyomi.ui.dictionary`, `eu.kanade.presentation.more.settings.screen`) | GPL-3.0 (SPDX + `// KUN`) |
| `ReaderDictionaryHost` interface package | `app/` (`mihon.dictionary.host`) | Apache-2.0 |

`:dictionary` may depend on `domain`, `core:common`, `core:metro`, `i18n-kmk`, `:engine` — **never** on `app`.
`app` depends on `:dictionary`. Add the module to `settings.gradle.kts` in 01's build change; this plan adds the source tree.

> Simpler fallback if `01` keeps one module: put the "?dictionary library" files in `app/src/main/java/mihon/dictionary/**`
> with SPDX headers. The reader-boundary via `ReaderDictionaryHost` still holds; only the module boundary weakens.

---

## 2. File map — copy vs adapt vs rewrite

All Chimahon paths below are under `/tmp/opencode/chimahon`. Target paths are komi-kun.

| Chimahon source | Action | komi-kun target |
|---|---|---|
| `chimahon/src/main/assets/dictionary/{renderer.js,kanji-renderer.js,base.css,index.html}` | **copy as-is** | `dictionary/src/main/assets/dictionary/` |
| `chimahon/…/HoshiDicts.kt` | **rewrite** → facade (01) | `:engine` (`DictionarySession`) |
| `chimahon/…/DictionaryRepository.kt` | **adapt** | `dictionary/.../mihon/dictionary/DictionaryRepository.kt` |
| `chimahon/…/DictionaryPaths.kt` | **copy** (or from 01) | `dictionary/.../DictionaryPaths.kt` |
| `chimahon/src/main/java/chimahon/dictionary/DictionaryProfileResolver.kt` | **adapt** (manga-only) | `dictionary/.../profile/DictionaryProfileResolver.kt` |
| `chimahon/…/anki/AnkiProfile.kt` + `AnkiProfileStore.kt` | **adapt/rename** → `DictionaryProfile*` | `dictionary/.../profile/` |
| `app/.../ui/dictionary/DictionaryWebView.kt` | **copy + adapt** (Metro) | `dictionary/.../ui/DictionaryWebView.kt` |
| `app/.../ui/dictionary/DictionaryWebViewBridges.kt` | **copy + adapt** (drop `WordAudioBridge` if audio deferred) | `dictionary/.../ui/DictionaryWebViewBridges.kt` |
| `app/.../ui/dictionary/DictionaryBootstrapHtml.kt` | **adapt** — **drop `chimahon.novel.data.FontManager`**, use komi-kun font util | `dictionary/.../ui/DictionaryBootstrapHtml.kt` |
| `app/.../ui/dictionary/DictionaryPayloadBuilder.kt` | **copy + adapt** (facade types) | `dictionary/.../ui/DictionaryPayloadBuilder.kt` |
| `app/.../ui/dictionary/DictionaryEntryWebView.kt` | **adapt** (Metro prefs) | `app/.../ui/dictionary/DictionaryEntryWebView.kt` |
| `app/.../ui/dictionary/DictionaryPopupWebViewWarmup.kt` | **copy + adapt** | `app/.../ui/dictionary/DictionaryPopupWebViewWarmup.kt` |
| `app/.../ui/dictionary/DictionaryPreferences.kt` | **adapt** (Metro; drop anime/novel/screen-OCR keys) | `app/.../ui/dictionary/DictionaryPreferences.kt` |
| `app/.../ui/dictionary/KanaConverter.kt` | **copy as-is** | `dictionary/.../ui/KanaConverter.kt` |
| `app/.../ui/dictionary/DictionaryTab.kt` | **adapt** (drop camera-OCR button; keep search + history + lookup stack) | `app/.../ui/dictionary/DictionaryTab.kt` |
| `app/.../ui/reader/viewer/OcrLookupPopup.kt` | **split**: extract dictionary popup → **rewrite** as `DictionaryPopup` | `app/.../ui/dictionary/DictionaryPopup.kt` |
| `app/.../presentation/more/settings/screen/SettingsDictionaryScreen.kt` | **adapt** | `app/.../settings/screen/SettingsDictionaryScreen.kt` |
| `…/SettingsDictionaryPopupScreen.kt` | **copy** | `…/SettingsDictionaryPopupScreen.kt` |
| `…/SettingsAnkiScreen.kt` | **copy** (phase 6 stub) | `…/SettingsAnkiScreen.kt` |
| `app/.../data/dictionary/DictionaryUpdateJob.kt` | **adapt** (opt-in; see §6) | `app/.../data/dictionary/DictionaryUpdateJob.kt` |
| `app/.../data/dictionary/DictionaryBackupManager.kt` | **copy** (opt-in) | `app/.../data/dictionary/DictionaryBackupManager.kt` |
| `app/.../data/dictionary/DictionaryUpdateNotifier.kt` | **copy** | `app/.../data/dictionary/DictionaryUpdateNotifier.kt` |
| `CameraOcr*.kt`, `ScreenLookup*.kt`, `ProcessTextLookup*.kt`, `OcrOverlayComponents.kt`, player/novel/anime paths, non-JA deinflectors | **DROP** | — |

---

## 3. WebView dictionary popup

### 3.1 Where it renders

The popup is a **Compose composable inside `ReaderActivity`'s existing overlay**, above `viewer_container`:

- Host anchor: `app/src/main/res/layout/reader_activity.xml` `compose_overlay` and
  `ReaderActivity.kt:309` `setComposeOverlay()` → the `Box(modifier = Modifier.fillMaxSize())` at `ReaderActivity.kt:330`.
- Insert **one** `// KUN` call `AppGraph.readerDictionaryHost.Popup()` into that `Box` (after `ContentOverlay`, before `AppBars`).
- Chimahon precedent: it hosts `OcrLookupPopup(...)` in the same overlay with `usePopup = false`
  (`chimahon/…/reader/ReaderActivity.kt:744-788`). We keep the **`usePopup=false` path**: an absolutely-positioned
  `Box` offset to `(anchorX, anchorY)` (Chimahon `OcrLookupPopup.kt:1324-1348`), not a `Popup` window — this composites
  correctly with the WebGPU external surface (per `../yomitan-manga-ocr-port.md` §7.1).
- The popup is **viewer-agnostic**: it only needs an anchor point + lookup text/sentence/offset. OCR supplies the anchor
  (separate plan); a future "text selection lookup" can reuse it.

### 3.2 Compose structure (`app/.../ui/dictionary/DictionaryPopup.kt`)

Extract the dictionary-only parts of Chimahon `OcrLookupPopup.kt` (drop OCR anchoring, crop, screenshot, sentence-audio):

```
DictionaryPopup(                                    // @Composable, GPL, reached via ReaderDictionaryHost
  visible, lookupString, fullText, charOffset,
  anchorX, anchorY, anchorWidth, anchorHeight, isVertical,
  profile: DictionaryProfile,                       // §4
  onDismiss, modifier)
├─ lookup history stack (single state object)       // Chimahon OcrLookupPopup.kt:170-209
│   data class LookupStackState(stack, activeIndex); currentFrame = stack[activeIndex]
│   pushLookup() → session.lookup()/queryKanji()    // :300-460
│   tabs = stack.map { TabInfo(query.take(16), active) }
├─ theme resolution (DictionaryPopup chrome)        // :258-298 (resolveDictionaryTheme)
├─ Card/Surface + rounded shape/border (eInk)       // :1223-1228
│   ├─ RecursiveLookupChrome() + optional close     // :1230-1233
│   └─ Box(weight 1f) { DictionaryEntryWebView(...) }  // :1234-1288
└─ warm shell: keep composed offscreen when hidden  // :1302-1323 (alpha=0, offset -10000dp)
```

State ownership (avoid bloating `ReaderViewModel`):
- The **popup is self-contained** and uses `remember`/`rememberCoroutineScope` (Chimahon already does this).
- If we need durable state (survive config change), back it with a dedicated `DictionaryViewModel` obtained via
  `metroViewModel<DictionaryViewModel>()` (pattern: `…/settings/screen/advanced/ClearDatabaseScreen.kt:76`). Do **not**
  add fields to `ReaderViewModel` (Chimahon instead spread `ocrPopupState`/`ocrWebView`/`dictionaryRepository` across
  `ReaderActivity` — we should not replicate that spread).
- `ReaderDictionaryHostImpl` holds the popup request state + the warmed `WebView` and exposes `showLookup/dismiss`.

Recursive lookup: tap inside a definition → `onRecursiveLookup(query, sentence, offset, x, y, "kanji"|null)`
(Chimahon `DictionaryWebView.kt:92,106`; `DictionaryPopup.kt:1350-1395` child popup). Simplify to **single-level child**
per `07-search-ux.md` §(f) "Nested popup depth … simplify to single-level on mobile".

MediaInfo/crop/screenshot/Anki button wiring belongs to the later Anki phase (§5); keep the call sites as `null`.

### 3.3 WebView + JS bridge contract

`prepareDictionaryWebViewShell(context, webView, bootstrapHtml, backgroundColor, languageCode)` — from
`DictionaryWebView.kt:17-121`:

- WebView settings: JS on, DOM storage on, `blockNetworkLoads=true`, file/content access on, zoom on, no scrollbars,
  force-dark off (`DictionaryWebView.kt:33-54`); safe-browsing disabled (`:318`).
- Shell loaded via `loadDataWithBaseURL("https://<app>.local/popup/", html, …)` — **rename `chima.local` → `komikun.local`**
  (`:215`).
- State lives on `webView.tag` as `DictionaryWebViewState` (`:123-316`) with `flush()` diffing
  (`lastPayload`/`lastRenderSignature`) and pending-* fields for pre-`onPageFinished` writes.
- **JS interfaces** (`addJavascriptInterface`, `:58-61`):
  | JS name | Kotlin class | Purpose |
  |---|---|---|
  | `DictionaryReadyBridge` | `DictionaryReadyBridge` | `contentReady()` after first paint via `postVisualStateCallback` (`Bridges:24-54`) |
  | `PayloadBridge` | `PayloadBridge` | `getPayloadJson()/getEntryCount()/getEntry(i)` — pulls the large JSON natively to avoid huge `evaluateJavascript` strings (`Bridges:62-70`) |
  | `AnkiJsBridge` | `AnkiJsBridge` | `addToAnki/openInAnki` (Anki phase) (`Bridges:77-101`) |
  | `WordAudioBridge` | `WordAudioBridge` | `fetchAudio/playAudio` (optional; defer with audio) (`Bridges:106-172`) |
- **URL scheme** (`shouldOverrideUrlLoading`, `:77-114`): rename `chima://` → `komikun://`. Hosts:
  `lookup` (`q`, `sentence`, `offset`, `x`, `y`) → recursive lookup; `tab` (`index`) → tab select; `back` → back;
  `kanji` (`q`) → kanji lookup.
- Font/custom CSS injection: `injectFontSize()` / `injectCustomCss()` (`:155-182`).

### 3.4 Payload builder (`DictionaryPayloadBuilder.kt`)

Keep essentially as-is; only swap `chimahon.*` types for the facade types and `DictionaryProfile`:

- `DictionaryRenderSignature` (`:24-43`) — the diff key for `flush`.
- `buildConfigPayload(...)` (`:158-214`) — config + `dictionaryOrder` (titles), display/collapse modes, style list,
  media map, tabs, `results` left empty; result entries pulled lazily.
- `buildResultEntryJsonStrings(...)` (`:220-230`) + `buildResultEntryJson(...)` (`:45-132`) — per-entry JSON via
  `PayloadBridge.getEntry(i)`.
- `buildKanjiEntryJson(...)` (`:345-371`).
- `orderLookupResultsForDisplay(...)` (`:134-152`) + `getDictionaryTitle/invalidateDictionaryTitle` (`:235-267`)
  (reads `index.json` title or user display name).
- `getDictionaryPaths(context, profile)` — currently in `DictionaryTab.kt:105-205` and cached there; **move to the
  repository/module** (`mihon.dictionary`), keyed on `(dictDirModified, profile.hashCode())`.

### 3.5 Rendering the Yomitan DOM contract

`renderer.js` (3178 lines) already emits the contract in `05-rendering.md`; keep it verbatim. Key points to verify/preserve:

- **Entry skeleton:** `.entry[data-type=term|kanji]`, `.entry-body-section[data-section-type=…]`, `.headword`,
  `.definition-list > li.definition-item`, `.gloss-list > li.gloss-item.click-scannable > span.gloss-content`
  (`renderer.js:2639-2748`, `createHeadwordNode` `:1838`).
- **Structured content:** `structured-content` + `gloss-sc-<tag>` (`:1333`, `:1457`, table `:1497`), `data`→`data-sc-<CamelKey>`
  (`applyDataAttributes` `:552`).
- **Pitch:** `.pronunciation-mora[data-pitch][data-pitch-next]`, downstep `[n]`, SVG graph
  (`createPitchDiagram` `:2373`, `createPitchTextLine` `:2470`, section `:2565`).
- **Frequency:** `.frequency-group-item`, `.frequency-value[data-frequency]`, `Average` group
  (`appendFrequenciesSection` `:2216`, `appendAverageFrequencyChip` `:2194`).
- **`data-dictionary` scoping:** set on `.entry` only when `groupTerms=false` (`:2645-2653`); otherwise per
  dictionary-group (in `appendDefinitionsSection` `:2065`).

**Styles scoping** (`buildScopedDictionaryCss` `:479-520`, `buildDictionaryScopedStyle` `:414-477`):
each `DictionaryStyle.styles` is wrapped in `[data-dictionary="<escaped title>"]` — three-tier: native CSS `@scope`,
CSSOM constructed sheets, string-parser fallback. Title escaping via `cssDoubleQuotedContent` (`:535`). Injected into
`<style id="dictionary-styles">`. **No change needed** — just confirm `styles()` returns the raw `styles.css`.

**Media / data-URIs:**
- Kotlin builds `Map<"dictName\u0000path", "data:<mime>;base64,…">` in `DictionaryRepository.buildMediaDataUris`
  (`:157-189`), limits 20 items / 256 KB each (`:274-275`), mime from extension (`:230`), path candidates normalized
  (`:243`).
- JS matches with `mediaCandidates`/`resolveMediaSrc` (NUL key, `renderer.js:1123-1150`) and lazy-loads via
  `IntersectionObserver` in `applyMediaToImageLink` (`:1178-1194`), toggling `data-image-load-state`.
- Media is loaded **off the critical path** via `repository.loadMediaAsync` (Chimahon `DictionaryRepository.kt:124-135`),
  then patched with `DictionaryRenderer.updateMediaDataUris(...)` (`DictionaryWebView.kt:249-252`).

Media goes into the payload as `mediaDataUris` object; entry images emit `data-path`/`data-dictionary` and the
`gloss-image-*` tree (`createImageNode` `renderer.js:1622`).

---

## 4. Per-manga dictionary profiles (manga-only simplification)

Chimahon model: `AnkiProfile` (`anki/AnkiProfile.kt:17-60`) bundles Anki fields **and** dictionary selection
(`dictionaryOrder`, `enabledDictionaries`, `collapseMode`, `displayModes`, `languageCode`, `scan/searchResolution`),
resolved by `DictionaryProfileResolver` (`:44-92`) with manga/anime/novel/source overrides.

**Simplification for komi-kun (rename `AnkiProfile` → `DictionaryProfile`):**

- Keep: `id, name, languageCode, dictionaryOrder, enabledDictionaries, dictionaryCollapseMode,
  dictionaryDisplayModes, scanResolution, searchResolution`.
- Move/remove Anki fields (deck/model/fieldMap/tags/dup/crop/sync) to a **separate `AnkiProfile`** consumed only by the
  Anki phase — do not block dictionary UI on them.
- `DictionaryProfileResolver.resolve(mangaId, sourceId, sourceLang)` — keep priority **manga override → source override
  → language auto-match → global active**; **drop** anime/novel override keys (`DictionaryProfileResolver.kt:61-73,94-106`).
- Pref keys (Metro `DictionaryPreferences`): `pref_dictionary_profiles` (JSON), `pref_active_dictionary_profile_id`,
  `pref_dict_profile_manga_<id>`, `pref_dict_profile_source_<id>` (keys from `DictionaryProfileResolver.kt:96,102`).
  `deleteProfileWithOverrides` sweeps only manga+source prefixes (`DictionaryPreferences.kt:210-229`).

**Per-manga UI** (Chimahon pattern to reproduce, trimmed):
- `MangaScreenModel`: `Dialog.SetDictionaryProfile(manga)`, `showSetDictionaryProfileDialog()`,
  `setMangaDictionaryProfile(profileId?)` writing `rawProfileOverride(mangaOverrideKey(id))`
  (`MangaScreenModel.kt:2041,2114-2128`).
- `MangaScreen`: action + `DictionaryProfileDialog` (`MangaScreen.kt:470,669-682`); add `// KUN` entries.
- Reader resolution call sites (`ReaderViewModel.kt:1306,1327,2344`; `ReaderActivity.kt:357,1214,2524`) become the
  host's responsibility where possible so the reader files stay thin.

---

## 5. Settings surface

### 5.1 Screens to add

| Screen | Source | Contents |
|---|---|---|
| `SettingsDictionaryScreen` | adapt Chimahon `SettingsDictionaryScreen.kt` | profiles group, imported-dictionary list (reorder/enable/delete/rename/display-name/filter), updates group, (word-audio group optional) |
| `SettingsDictionaryPopupScreen` | copy (`SettingsDictionaryPopupScreen.kt:12-21`) | popup chrome: width/height, font+size, theme/custom color/AMOLED/eInk, paginated scroll, swipe-to-dismiss/threshold, volume-key nav, frequency/pitch display, `groupTerms`, `groupPitches`, custom CSS, recursive mode, nav buttons |
| `SettingsAnkiScreen` | copy (`SettingsAnkiScreen.kt:12-21`) | **Phase 6**: Anki deck/model/field-map/profile; ship as stub or defer entirely |
| `SettingsOcrScreen` | other plan | out of scope here |

Split rationale: `SettingsDictionaryPopupScreen`/`SettingsAnkiScreen` are intentionally thin (`getPreferences()` delegates
to `SettingsDictionaryScreen.popupPreferences()/ankiPreferences()`, `:581-591`).

### 5.2 Registration

- `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsMainScreen.kt` — add `Item(...)` entries into
  the `items` list (`:184-270`) with `KMR.strings.pref_category_dictionaries_and_audio`,
  `…_dictionary_popup`, `…_anki`, and icons; wrap in `// KUN`.
- `…/SettingsSearchScreen.kt` — add the three objects to `settingScreens` (`:297-314`), wrap in `// KUN`.
- `SettingsDictionary` etc. are shown from the tab bar via `DictionaryTab` (see §6.3).

### 5.3 Metro adaptation (Injekt → Metro)

- **`DictionaryPreferences`**: `@Inject @SingleIn(AppScope::class) class DictionaryPreferences(private val preferenceStore: PreferenceStore)`
  (same shape as `exh/source/ExhPreferences.kt`). Expose read-only `fun x() = preferenceStore.get…`; add
  `val dictionaryPreferences: DictionaryPreferences` to `AppGraph.kt` (`:122-134` block).
- **`DictionaryRepository` / `DictionaryProfileStore` / `DictionaryProfileResolver`**: `@Inject` constructors; if they
  need `File`/`Context`/engine, wire in `app/.../di/AppBindings.kt` with `@Provides @SingleIn(AppScope::class)`
  (precedent: existing `@Provides` at `AppBindings.kt:46+`). Expose `val dictionaryRepository` / a
  `DictionarySessionFactory` on `AppGraph`.
- **Compose access**: `val prefs = LocalContext.current.appGraph.dictionaryPreferences`
  (precedent `MangaInfoHeader.kt:264`, `Scale…`). `collectAsState()` for reactive prefs.
- **Dictionaries list / import** are heavier: prefer a `DictionaryManagementViewModel` via
  `metroViewModel<DictionaryManagementViewModel>()` (precedent `ClearDatabaseScreen.kt:76`) over the current
  file-scope `MutableStateFlow` singletons Chimahon uses (`SettingsDictionaryScreen.kt:343-344`) and over the
  `remember{ Injekt.get() }` calls scattered through the screens.
- `DictionaryEntryWebView.kt` currently does `Injekt.get<DictionaryPreferences>()` (`:69,74-75`): replace with
  `appGraph.dictionaryPreferences` passed down from the popup/tab.
- `DictionaryBootstrapHtml.kt` uses `Injekt.get<UiPreferences>()` and `FontManager.getFontUri/getKanjiStrokeFontUri`
  (`:9,15,42-53`): drop the novel `FontManager` import; use a komi-kun font helper or inline the family name; read
  `UiPreferences` via `appGraph`.

---

## 6. Import / management UI + storage

### 6.1 On-disk location

`File(context.getExternalFilesDir(null), "dictionaries")` with type subdirs `term/`, `frequency/`, `pitch/`, `kanji/`,
one directory per dictionary (`<type>/<dictTitle>/…`) — `DictionaryRepository.kt:277`, `DictionaryTab.kt:105-205`,
`loadDictionaryList` `SettingsDictionaryScreen.kt:315-337`. Keep unchanged (no migration burden).

### 6.2 Import (SAF, no storage permission)

- Import via `ActivityResultContracts.OpenMultipleDocuments()` → `importDictionaryFromStream(context, stream, profile)`
  (`SettingsDictionaryScreen.kt:363-414,2757-2887`): write temp zip → `engine.importDictionary` → probe entry types →
  copy `importedDir` into each `<type>/<title>` → append title to profile `dictionaryOrder`.
- **Drop** the kanji-stroke-font auto-download (`:2830-2845`) unless the kanji renderer keeps its font dependency —
  verify `renderer.js`/`kanji-renderer.js` font usage; if needed, replace with a komi-kun font source.
- **Drop** the "all files access" fallback dialog (that was for the word-audio DB, `:476-512`) if word audio is deferred.
- Update `DictionaryUpdateJob` (see §6.4) writes to the same tree; uses `readOldRevision`/`readNewRevision`
  (`DictionaryUpdateJob.kt:142,158,173-193`).

### 6.3 Dictionary tab (full-screen search)

- `DictionaryTab.kt:208`: Voyager `Tab`. Adapt: remove the camera-OCR button (`:643`), keep search field, search history
  (`GetSearchHistory`/`UpsertSearchHistory`/`DeleteSearchHistory` on `SearchHistory.SCOPE_DICTIONARY`), the lookup
  history stack + tabs, and `DictionaryEntryWebView`.
- Register: `app/.../ui/home/HomeScreen.kt` (`NavTabLayout.KEY_DICTIONARY` map `:100`, `Tab.Dictionary` `:224`) and
  `app/.../ui/more/MoreTab.kt` (`:115`) — add `// KUN`. Add `Tab.Dictionary` enum entry.
- Kana auto-conversion via `KanaConverter` gated by `autoKanaConversion()` (`DictionaryPreferences.kt:80`).

### 6.4 Update / backup (optional scope — recommend include)

- **Update:** `DictionaryUpdateJob` (`CoroutineWorker`) + `DictionaryUpdateNotifier` + `chimahon.dictionary.checkDictionaryUpdates`
  (`DictionaryUpdateJob.kt:52-92`). Add `Notifications.CHANNEL_DICTIONARY_UPDATE` + IDs in
  `app/.../data/notification/Notifications.kt` (`// KUN`). Register WorkManager in `App.kt` (Chimahon does it there).
  Settings group: `SettingsDictionaryScreen.getDictionaryUpdatesGroup()` (`:524-579`).
- **Backup:** `DictionaryBackupManager` (`:14-274`) — ZIP export/import of the `dictionaries/` tree with a
  `manifest.json` and revision-based dedup. Hook into `SettingsDataScreen`/backup flow as an opt-in `// KUN` item.
- If deferred, the dictionary tree is still user-visible via SAF; note it as a gap.

---

## 7. i18n — key names + placement

**Rule:** every new string goes to `i18n-kmk/src/commonMain/moko-resources/base/strings.xml`, referenced as `KMR`.
Verified: **none** of the dictionary/Anki keys exist in komi-kun's `i18n/` (MR) today, so nothing can be reused from MR —
all are added as `KMR`. (Chimahon wrongly parked `pref_dict_*`/`anki_*` in Mihon's `i18n/`; do not copy that.)

Existing KMR keys already present in komi-kun (reuse): `custom_color_description`, `pref_custom_color`.

Key names to add (grouped; **base locale only**):

- **Categories/tabs:** `pref_category_dictionaries_and_audio`, `pref_category_dictionary_popup`, `pref_category_anki`,
  `label_dictionary`.
- **Profiles:** `pref_dictionary_profiles`, `pref_anki_profile_active`, `pref_anki_profile_new`, `pref_anki_profile_rename`,
  `pref_anki_profile_clone`, `pref_anki_profile_delete`, `pref_anki_profile_delete_confirm`, `pref_anki_profile_name_hint`.
- **Import/list:** `pref_import_dictionary`, `pref_import_dictionary_summ`, `pref_import_dictionary_success`,
  `pref_import_dictionary_failed`, `pref_dict_imported_list`, `pref_dict_none_imported`, `pref_dict_delete`.
- **Popup chrome:** `pref_dict_popup_width`, `pref_dict_popup_height`, `pref_dict_popup_font_size`,
  `pref_dict_popup_swipe_to_dismiss`, `pref_dict_popup_swipe_threshold`, `pref_dict_custom_css`,
  `pref_dict_custom_css_summary`, `pref_dict_theme_pure_black`, `pref_dict_layout`, `pref_dict_typography`,
  `pref_dict_content`, `pref_dict_recursive_mode`, `pref_dict_recursive_mode_tabs`, `pref_dict_recursive_mode_popup`,
  `pref_dict_recursive_mode_back`, `pref_dict_show_navigation_buttons`, `pref_dict_show_navigation_buttons_summary`.
- **Display:** `pref_dict_frequency_display`, `pref_dict_show_frequency_average`, `pref_dict_pitch_accent_display`,
  `pref_dict_pitch_diagram`, `pref_dict_pitch_number`, `pref_dict_pitch_text`, `pref_dict_group_terms`,
  `pref_dict_group_terms_summary`, `pref_dict_group_pitches`, `pref_dict_group_pitches_summary`,
  `pref_dict_auto_kana_conversion`, `pref_dict_auto_kana_conversion_summary`.
- **Updates:** `check_for_updates`, `dict_update_progress_title`, `dict_update_complete_title`,
  `dict_update_complete_text`, `dict_update_error_title`.
- **Anki (phase 6):** `pref_anki_enable`, `pref_anki_deck`, `pref_anki_model`, `pref_anki_field_mapping`,
  `pref_anki_default_tags`, `pref_anki_check_duplicates`, `pref_anki_duplicate_scope`, `pref_anki_duplicate_action`,
  `pref_anki_duplicate_prevent`, `pref_anki_duplicate_add`, `pref_anki_duplicate_overwrite`, `pref_anki_select_deck`,
  `pref_anki_select_model`, `pref_anki_loading`, `pref_anki_no_ankidroid`, `pref_anki_permission_denied`,
  `pref_anki_screenshot_animated_scene`, `anki_card_added`, `anki_card_error`, `anki_card_exists`, `anki_not_configured`.

**Also:** replace hardcoded English in the ported settings screens (Chimahon has many literals, e.g.
"Delete", "Set display name", "Expand all dictionaries", `SettingsDictionaryScreen.kt:1300-1420`) with `KMR` keys.
Do **not** touch non-`base` locale files (Weblate-owned).

`MR` strings that already exist upstream may be reused (e.g. `MR.strings.action_cancel`, `action_ok`, `action_search`,
`no_results_found`, `storage_failed_to_create_directory`). Add only the genuine gaps to `i18n-kmk`.

---

## 8. Maintainability, file ownership, risks, task order

### 8.1 Maintainability rules

1. **Thin reader seam.** Reader files see only `mihon.dictionary.host.*` (Apache). All dictionary state/logic stays
   out of `ReaderViewModel`/`ReaderActivity` (fix Chimahon's sprawl of `ocrPopupState`/`ocrWebView` fields).
2. **Facade boundary.** App code calls `DictionarySession` (01) only through `DictionaryRepository`/a small
   `DictionaryManager`; never `HoshiDicts` statics. Keeps a future engine swap possible.
3. **Single source for paths** (`DictionaryPaths`/`getDictionaryPaths`) — one cached implementation in `:dictionary`.
4. **Self-contained popup.** Popup composable owns its stack + WebView; expose via the host interface.
5. **Markers + SPDX.** Every hunk into an upstream file wrapped `// KUN --> … // KUN <--`; new `.sq` uses `-- KUN`.
   New files carry an SPDX header; GPL module files use `GPL-3.0-only`, the host interface package `Apache-2.0`.

### 8.2 File-ownership map

| Area | Owner | Files |
|---|---|---|
| Engine module, `DictionarySession` facade, build/CI, version catalogs | **01** | `:engine`, `settings.gradle.kts`, `gradle/*`, `app/build.gradle.kts` |
| Renderer assets, repository, prefs, profile store/resolver, payload/WebView shell, `KanaConverter`, module wiring | **02 (this)** | `dictionary/**`, `AppGraph.kt`, `AppBindings.kt` |
| Popup composable, `DictionaryTab`, settings screens, `DictionaryEntryWebView`, `DictionaryPopupWebViewWarmup` | **02 (this)** | `app/.../ui/dictionary/**`, `app/.../settings/screen/Settings{Dictionary,DictionaryPopup,Anki}Screen.kt` |
| Reader seam (`ReaderDictionaryHost`, host injection, `// KUN` call in overlay) | **02 (this)** | `app/.../mihon/dictionary/host/**`, `ReaderActivity.kt` |
| OCR overlay/hit-test/viewer adapters, `.mokuro` | **03 (OCR plan)** | `reader/viewer/**`, `data/ocr/**` |
| Anki mining | **06 (later)** | `dictionary/.../anki/**`, `SettingsAnkiScreen.kt` |

### 8.3 Risks

1. **Module-cycle constraint** (§1.2): Compose screens implementing app interfaces cannot move to `:dictionary`; only the
   Android-light layer does. Confirm with 01 how strict the boundary must be before finalising the split.
2. **Facade drift** — `DictionarySession` must expose `styles()` + `mediaBytes()` + path-keyed rebuild; missing these
   forces threading `HoshiDicts` back in (§0).
3. **`data:` URI cost** — base64 media in JSON can spike memory; keep the 20 items / 256 KB caps and the lazy
   `updateMediaDataUris` patch (`DictionaryRepository.kt:274-275`, `DictionaryWebView.kt:249`).
4. **WebView warm-up races** — the warm-shell/`MutableContextWrapper` approach (`DictionaryPopupWebViewWarmup.kt`) was
   added to dodge an Android 16 attach race; port it intact.
5. **i18n re-homing** — all `pref_dict_*`/`anki_*` strings must land in `i18n-kmk`, not `i18n/`; easy to get wrong by
   copying Chimahon verbatim.
6. **Chimahon's file-scope singletons** (`_isImporting`, `_dictionaryNames`, `SettingsDictionaryScreen.kt:343-344`)
   leak across screens — replace with a ViewModel.
7. **Novel/anime leakage** — `DictionaryBootstrapHtml.kt` imports `chimahon.novel.data.FontManager`; `DictionaryPreferences`
   has anime/novel/OCR-only keys. Prune aggressively.

### 8.4 Ordered task breakdown

1. **Module + facade wiring** — create `:dictionary` (or app package), move `DictionaryPaths`, repository, prefs,
   `DictionaryProfile*`; add `AppGraph`/`AppBindings` entries; swap `HoshiDicts.*` → `DictionarySession` (needs 01).
2. **Assets + WebView shell** — copy `renderer.js`/`kanji-renderer.js`/`base.css`/`index.html`; port
   `DictionaryBootstrapHtml` (drop `FontManager`), `DictionaryWebView`, `DictionaryWebViewBridges`,
   `DictionaryPopupWebViewWarmup`; rename scheme/host.
3. **Payload + entry WebView** — port `DictionaryPayloadBuilder`, `DictionaryEntryWebView`; add
   `DictionaryRenderSignature` diffing; verify the 05-rendering checklist end-to-end with one imported dictionary.
4. **Reader popup** — extract `DictionaryPopup` from `OcrLookupPopup`; define `ReaderDictionaryHost` + impl; add the
   `// KUN` host call in `ReaderActivity.compose_overlay`; wire recursive lookup + tabs; keep single-level nesting.
5. **Dictionary tab + search history** — port `DictionaryTab` (minus camera OCR); register in `HomeScreen`/`MoreTab`.
6. **Settings** — port `SettingsDictionaryScreen` + popup/Anki screens; register in `SettingsMainScreen`/`SettingsSearchScreen`;
   convert to Metro prefs + a management ViewModel; add KMR strings.
7. **Profiles (per-manga)** — `DictionaryProfileResolver` (manga+source only) + `MangaScreen` dialog.
8. **Import/management + updates/backup (opt-in)** — SAF import, list reorder/enable/delete/rename, `DictionaryUpdateJob`
   + notification channel; `DictionaryBackupManager` if in scope.
9. **Verify** — `./gradlew spotlessApply spotlessCheck :app:compileDebugKotlin` (or `assembleDebug`); manual check of
   theme/eInk, styles scoping, media images, pitch SVG, frequency, recursive lookup, per-manga profile, import.

Each step wraps upstream-file edits in `// KUN`; new files carry SPDX headers.
