# Yomitan spec — Search UX semantics

Source: `yomidevs/yomitan` @ `e7334a9` (GPL-3.0). Paths relative to repo root.

> Provenance note: derived from Yomitan's GPL-3.0 source; treat as a behavioral reference.

## (a) Query syntax (term search)

The search box (`#search-textbox`) is a plain textarea; the query is passed verbatim
(`ext/js/display/search-display-controller.js:612-647`). There is **no** `#kanji`, `tag:`,
`dictionary:`, or quoted-phrase syntax anywhere in the search path. The only in-band operator is the
wildcard, honored only when the `wildcards` URL param isn't `off`:

| Input | Effect | Ref |
|---|---|---|
| `*foo` / `foo*` | leading/trailing `*` (or fullwidth `＊`) = suffix/prefix DB match (`matchType: 'suffix'|'prefix'`); forces `deinflect: false` | `ext/js/display/display.js:1356-1372` |
| `*` only | regex `/^([*\uff0a]*)([\w\W]*?)([*\uff0a]*)$/`; empty middle → empty source | `:1360` |
| `?` | unsupported (not in regex) | `:1360` |
| wildcards off | `wildcards=off` param skips parsing | `:1382`, `:1903-1905` |

Lookup order is a fallback chain, not a mode toggle: `terms` mode tries `termsFind` then `kanjiFind`;
`kanji` mode the reverse (`ext/js/display/display.js:1332-1348`). API: `termsFind`
(`ext/js/background/backend.js:554-561`), `kanjiFind` (`:544-552`).

Options into `findTerms` (`backend.js:2744-2786`): `matchType` (default `'exact'`), `deinflect`
(default `true`), `primaryReading`, `removeNonJapaneseCharacters` (=`!scanning.alphanumeric` for
ja/zh/yue/ko, `translator.js:243-246`), `searchResolution`, text replacements, dictionary map
(`useDeinflections`, `partsOfSpeechFilter`, `allowSecondarySearches`). `general.maxResults` (default
32) truncates results (`backend.js:556-561`; default `options-util.js:295`).
`general.resultOutputMode` (default `'group'`) selects mode `simple|group|term|merge`
(`translator.js:86-140`). DB match types exact/prefix/suffix on term+reading indexes
(`dictionary-database.js:288-314`).

## (b) Kanji search semantics

`findKanji` (`translator.js:150-187`): text filtered to CJK if configured (`:151-153`), split into
**unique code points** (no reading/radical/wildcard query; each char looked up independently,
`:157-159`), via `findKanjiBulk` (`:161`), sorted, stats expanded (`_expandKanjiStats`, `:1404-1429`),
kanji meta/frequency merged (`_addKanjiMeta`, `:1365-1397`). Result fields
(`_createKanjiDictionaryEntry`, `:1600-1615`): `type:'kanji'`, `character`, `dictionary`,
`dictionaryIndex`, `dictionaryAlias`, `onyomi`, `kunyomi`, `tags`, `stats`, `definitions`,
`frequencies`.

Trigger: clicking `.headword-kanji-link` (`display-generator.js:668-673`) runs `_onKanjiLookup` with
the anchor text, opening a new history entry (`display.js:971-1002`). In the popup/scanner path kanji
is a fallback after terms and scans only `1` char (`text-scanner.js:1289-1314`, `:1238-1246`). Kanji
meta mode handled is `freq` only (`translator.js:1376-1395`); pitch/IPA are term-only.

## (c) Recursive lookup — trigger & DOM contract

Lookable words are driven by DOM classes:

- `.gloss-item.click-scannable` wraps each glossary item (`ext/templates-display.html:68`).
- `.headword-kanji-link` → kanji lookup (`display.js:2081-2082`).
- `.inflection[data-reason]` and `.tag-label` click handlers (`display.js:2084-2089`).
- Scanning constrained by `includeSelector='.click-scannable,.click-scannable *'`,
  `excludeSelector='.scan-disable,.scan-disable *'` (`display.js:2120-2122`; `text-scanner.js:1629-1655`).

The display owns a `TextScanner` (`searchTerms:true`, `searchOnClick`, `searchOnClickOnly`) enabled
only when `scanning.enablePopupSearch` (and, on the search page, `enableOnSearchPage`)
(`display.js:2099-2168`). On `searchSuccess` it calls `setContent({historyMode:'new'})` — the
definition replaces popup content with a back stack (`display.js:2178-2206`, history push `:507-539`).

True *nested popups* use a child `Frontend`/`PopupProxy` while `depth < scanning.popupNestingMaxDepth`
(default `10`, `options-util.js:349`), `allowRootFramePopupProxy:true` (`display.js:1948-2011`, esp.
`:2003`). Hovering host text calls `Frontend._showPopupContent` → `popup.showContent`
(`ext/js/app/frontend.js:823-845`), routed via `DisplayContentManager.executeMediaRequests` for
dictionary media (`display-content-manager.js:83-88`). Clicking a parsed query segment re-searches
(`display.js:876-902`).

**Scanning parameters.** `scanLength` (`scanning.length`, default `10`; class fallback `1`),
`layoutAwareScan` (default `false`), `deepContentScan`, `scanResolution` (`'character'` default, or
`'word'` which expands the start offset to the word boundary — only for non-ja/zh/yue/ko)
(`text-scanner.js:96-120`, `:28`, `:501-505`; `options-util.js:339-356`, `:1516-1521`). There is
**no** sentence/paragraph scan mode; a "sentence" is only extracted as lookup context
(`text-scanner.js:1271-1279`). `preventMiddleMouseOnPage|OnTextHover`, `preventBackForward`
(`:687-692`, `:656-666`). Scanned text trimmed until nodes match include / don't match exclude
(`_constrainTextSource`, `:1629-1644`); final end offset reset to `originalTextLength` from the lookup
(`:1254-1282`).

## (d) Frequency modes

`frequencyMode` comes from `index.json`, stored in the dictionary summary:
`'occurrence-based' | 'rank-based'` (`dictionary-importer.js:445,458`;
`types/ext/dictionary-data.d.ts:22`). Resolved per dictionary title (`translator.js:1729-1739`) and
copied onto every term/kanji frequency entry (`:1277-1291`, `:1380-1392`, `:1585-1586`, `:1724-1725`).

Displayed marker: each value renders as `.frequency-value` with `data-frequency` (numeric) and
`data-displayValue`; `title` carries the raw number only when it differs (`display-generator.js:1029-1060`).
So the visible marker is the dictionary's own `displayValue`, not a mode-derived number.

The mode matters for **sorting** and Anki: `sortFrequencyDictionaryOrder` defaults `ascending` for
rank-based, `descending` for occurrence-based (`sort-frequency-dictionary-controller.js:154-164`);
`_updateSortFrequencies` sorts by min when ascending else by −max (`translator.js:2340-2378`). Anki
exposes `frequency-harmonic-rank`/`-occurrence` (+ average), computed only from entries whose
`frequencyMode` matches (`anki-note-data-creator.js:178-225`, `:352-356`; `anki-template-util.js:41-42`).
An "Average" pseudo-dictionary uses the harmonic mean (`dictionary-data-util.js:107-125`). Layout by
`general.frequencyDisplayMode` (`options-schema.json:326-330`).

## (e) Audio sources

Types (`display-audio.js:59-69`): `jpod101`, `language-pod-101`, `jisho`, `lingua-libre`, `wiktionary`,
`text-to-speech`, `text-to-speech-reading`, `custom`, `custom-json`. Default
`{enabled:true, sources:['jpod101'], volume:100, autoPlay:false}` (`options-util.js:330-337`).

Resolution: `DisplayAudio._createTermAudio` iterates sources, caching per term/reading
(`display-audio.js:578-609`), resolving each `Info` (`:617-679`): `{type:'url'}` →
`AudioSystem.createAudio` (local files via API, `audio-system.js:83-103`); `{type:'tts'}` →
speech synthesis (`:111-117`). Providers `ext/js/media/audio-downloader.js:49-59`; `custom`/`custom-json`
substitute `{term}`,`{reading}`,`{language}` (`:481-501`), `custom-json` parses `audioSources[]`
(`:437-471`). Defaults for ja = jpod101/language-pod-101/jisho, else
lingua-libre/language-pod-101/wiktionary (`:608-636`).

Fallback: if no source yields audio, the fallback sound plays (`display-audio.js:443-446`), configured
by `fallbackSoundType` → `fallback-click.mp3` / `fallback-bloop.mp3` / silent (`audio-system.js:59-76`).
Playback resets `currentTime`, applies `volume`, `play()` (`display-audio.js:459-472`); autoplay skips
kanji entries (`:224-245`).

## Deinflect display setting

`deinflect:false` disables algorithmic deinflection — exact-only single pseudo-deinflection
(`translator.js:393-397`). Wildcards force it off (`display.js:1364,1367`). Per-dictionary
`dictionaries[].useDeinflections` gates dictionary-provided `(formOf, rules)` deinflections
(`translator.js:433-437`). API default `true` (`backend.js:2747`).

## (f) Must-match checklist (manga-focused Android port)

**Core:**
1. Term lookup exact→deinflect, term-first with kanji fallback (`display.js:1332-1348`).
2. `maxResults` truncation, default 32 (`backend.js:556-561`).
3. Leading/trailing `*` prefix/suffix wildcard with `deinflect=false` (`display.js:1356-1372`).
4. Kanji lookup = per-codepoint unique search; `onyomi/kunyomi/stats/definitions/frequencies` (`translator.js:150-187`, `:1600-1615`).
5. Tap-to-lookup inside a definition via a scannable-content DOM contract + back stack (`display.js:2099-2206`).
6. Frequency display value + `data-frequency`; rank/occurrence only affects sort direction (`display-generator.js:1029-1060`).
7. Audio: at least `custom`/`custom-json` URL templating + fallback sound; autoPlay off for kanji (`audio-downloader.js:481-501`).

**Lower priority / optional:**
- TTS sources (`audio-system.js:111-117`).
- Jisho/Wiktionary/LinguaLibre scrapers (`audio-downloader.js:283-363`) — network-fragile.
- Nested popup depth (`display.js:1948-2011`) — simplify to single-level on mobile.
- `scanResolution:'word'` expansion (`text-scanner.js:28,501-505`) — excluded for ja/zh/ko/yue anyway.
- Deep DOM/shadow scanning (`text-scanner.js:1341-1346`).
- `frequencyDisplayMode` variants (7 layouts) — one sane layout suffices.
