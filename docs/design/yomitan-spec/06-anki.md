# Yomitan spec — Anki export

Source: `yomidevs/yomitan` @ `e7334a9` (GPL-3.0). Markers, card building, media, duplicates, field
mapping. Paths are repo-relative.

> Provenance note: derived from Yomitan's GPL-3.0 source; treat as a behavioral reference. If the port
> stays Apache-2.0, implement from these behavioral facts, not by transcribing Yomitan code.

## (a) Marker catalog

Markers are `{name}` tokens matched by `\{([\p{Letter}\p{Number}_-]+)\}`
(`ext/js/data/anki-util.js:23`). **No `{marker:arg}` colon syntax** exists — args live inside the
Handlebars template defining the marker (e.g. `{{> glossary brief=true}}`). Rendering is a string
replace: each `{name}` renders its inline template and is spliced in (`anki-note-builder.js:237-249`);
unknown → `{name-render-error}` (`:244-247`).

| marker | template args | expands to |
|---|---|---|
| `{expression}` | — | term expression; merge joins all `、` (`default-anki-field-templates.handlebars:51-60`; `anki-note-data-creator.js:493-496`) |
| `{reading}` | — | likewise (`:201-210`, `:497-500`) |
| `{furigana}` | — | `<ruby>` segments (`:62-71`; `anki-template-renderer.js:178-192`) |
| `{furigana-plain}` | — | ` text[reading] ` (`:73-82`; `:195-210`) |
| `{glossary}` | `selectedDictionary`,`brief`,`noDictionaryTag` | `<div class="yomitan-glossary">` + tag/alias header + `<ul>`/`<li>` glosses; injected scoped `<style>` (`:84-116`) |
| `{glossary-no-dictionary}` | — | glossary, `noDictionaryTag=true` (`:118-120`) |
| `{glossary-brief}` | — | brief, drops header (`:122-124`) |
| `{glossary-plain}` | `selectedDictionary`,`noDictionaryTag` | plain text (`:126-146`) |
| `{glossary-plain-no-dictionary}` | — | (`:148-150`) |
| `{glossary-first}` | `brief`,`noDictionaryTag` | first definition only (`:152-179`) |
| `{glossary-first-brief}` / `{glossary-first-no-dictionary}` | — | (`:181-187`) |
| `{dictionary}` / `{dictionary-alias}` | — | primary dictionary / alias (`:43-49`; `creator:487-488`) |
| `{tags}` | — | term + definition tags, comma-joined (`:232-234`; `renderer:307-335`) |
| `{conjugation}` | — | inflection chain, `«`-joined (`:338-357`) |
| `{part-of-speech}` | — | deduped pretty word classes; `Unknown` if none (`:435-464`) |
| `{frequencies}` | `selectedDictionary` | `<ul>` of `(expr) alias: frequency` (`:359-376`) |
| `{frequency-harmonic-rank}` | — | harmonic mean, rank; `-1`→`9999999` (`:388-394`; `creator:213-225,352`) |
| `{frequency-harmonic-occurrence}` | — | occurrence; `-1`→`0` (`:396-402`; `:353`) |
| `{frequency-average-rank}` | — | arithmetic mean, rank (`:404-410`; `:233-245,355`) |
| `{frequency-average-occurrence}` | — | occurrence (`:412-418`) |
| `{pitch-accents}` | — | pitch notation text (`:286-288`; `renderer:878-906`) |
| `{pitch-accent-graphs}` / `{pitch-accent-graphs-jj}` | — | SVG graph / JJ (`:290-296`; `:897-900`) |
| `{pitch-accent-positions}` | — | downstep number (`:298-300`; `:901-902`) |
| `{pitch-accent-categories}` | — | comma-joined categories (`:302-304`; `:559-578`) |
| `{phonetic-transcriptions}` | — | `<ul>` of IPA + tags (`:307-326`) |
| `{audio}` | — | `[sound:filename]` if media present (`:33-37`) |
| `{screenshot}` | — | `<img src="filename" />` (`:244-248`) |
| `{clipboard-image}` | — | `<img src="filename" />` (`:328-332`) |
| `{clipboard-text}` | — | raw clipboard text (`:334-336`) |
| `{sentence}` | — | `definition.cloze.sentence` (`:212-214`; `creator:958-998`) |
| `{sentence-furigana}` / `{sentence-furigana-plain}` | `escape=false` | sentence with ruby / `text[reading]` (`:474-492`) |
| `{cloze-body}` / `{cloze-body-kana}` / `{cloze-prefix}` / `{cloze-suffix}` | — | sentence split around the term; `-kana` kana body (`:216-230`; `:991-997`) |
| `{document-title}` | — | `context.document.title` (`:250-252`) |
| `{url}` / `{url-plain}` | — | `<a href>` / raw URL (`:236-242`) |
| `{search-query}` | — | `context.fullQuery`, newlines→`<br>` (`:466-468`) |
| `{popup-selection-text}` | — | popup-selected text (`:470-472`; `builder:503-506`) |
| `{character}` *(kanji)* | — | `definition.character` (`:39-41`) |
| `{kunyomi}` / `{onyomi}` / `{onyomi-hiragana}` *(kanji)* | — | `, `-joined; last katakana→hiragana (`:189-199`) |
| `{stroke-count}` *(kanji)* | — | `Stroke count: N`/`Unknown` (`:420-433`) |
| `{single-frequency-number}` / `{single-frequency-number-<dict>}` | `selectedDictionary` | raw frequency numbers (`:378-386`; `anki-template-util.js:150-159`) |
| `{single-glossary-<dict>[-no-dictionary/-brief/-plain/-plain-no-dictionary]}` | — | per-dictionary glossary variants, generated dynamically (`anki-template-util.js:121-163,170-185`) |

Term markers listed `anki-template-util.js:28-78` (ja-only pitch set `:68-77`); kanji markers `:80-110`.
`{definition}`, `{definition-selected}`, `{selected-glossary}`, `{book-title}` **do not exist** in
Yomitan (no inline definitions).

## (b) Card-building flow

1. Card format = `{name, icon, deck, model, type, fields}`; `type` ∈ term/kanji; `fields` maps field →
   `{value, overwriteMode}` (`ext/data/schemas/options-schema.json:1051-1130`). Defaults:
   Expression/Reading/Kanji with **empty** deck/model/fields — Yomitan ships **no default note model**.
2. Field templates = user `anki.fieldTemplates` else the default template file, **plus** dynamic
   per-dictionary inline partials (`anki-template-util.js:121-163`; `display-anki.js:1088-1108`).
3. `createNote` resolves `duplicateScope: 'deck-root'` → deck of `getRootDeckName(deck)` + `checkChildren`
   (`anki-note-builder.js:64-70`; `anki-util.js:31-34`).
4. Compute media requirements first; `_injectMedia` only when non-empty (`:75-81`).
5. Blank `context.url` if a `chrome-extension:` URL (`:83-91`).
6. Build lazy `commonData`/`NoteData` (`:213-224`; `anki-note-data-creator.js:30-83`).
7. For every field, replace each `{marker}` by rendering its template against the data
   (`:95-115,232-250`); batch per template+data (`:298-386`).
8. Merge field `requirements` (deduped via `JSON.stringify`) into note requirements (`:101-115`).
9. Emit `{fields, tags, deckName, modelName, options:{allowDuplicate:true, duplicateScope,
   duplicateScopeOptions:{deckName, checkChildren, checkAllModels}}}` (`:117-132`).
10. Duplicate check before save: skeleton note per card format using only the **first field name** = best
    headword/character (`display-anki.js:413-457`; best-headword `builder:158-183`); query
    `canAddNotes`/`findNotes` unless `checkForDuplicates=false` (`display-anki.js:1159-1170`).
11. Duplicate behavior: `prevent` / `overwrite` (merge per field `overwriteMode`, `updateNoteFields`) /
    `new` (`display-anki.js:560-602,783-840,863-916`).
12. Optional `suspendNewCards` + force Anki sync (`:939-958`).

## (c) Media handling

- Requirements (`audio`, `screenshot`, `clipboardImage`, `clipboardText`, `popupSelectionText`,
  `textFurigana`, `dictionaryMedia`) emitted by helpers during rendering, then fulfilled in one
  `injectAnkiNoteMedia` round trip (`builder:394-498`; `template-renderer-media-provider.js:97-183`).
- **Audio**: `downloadTermAudio(...)`, ext from content-type or `.mp3`, name `yomitan_audio_<sha1>.<ext>`
  (`]` stripped), via `storeMediaFile` (`backend.js:2500-2531`; `anki-util.js:115-127`). → `[sound:…]`.
- **Screenshot**: page captured (`captureVisibleTab`), cropped/encoded `png|jpeg`,
  `yomitan_browser_screenshot_<timestamp>.<ext>` (`backend.js:2372-2419,2539-2551`;
  `display-anki.js:232-233,1250-1254`). → `<img src>`.
- **Clipboard image**: data-URL cached so identical clipboards reuse a filename;
  `yomitan_clipboard_image_<timestamp>.<ext>` (`backend.js:2558-2582`). → `<img src>`.
- **Dictionary media**: content-addressed `yomitan_dictionary_media_<sha1>.<ext>`; looked up by
  `{dictionary,path}`, hashed/stored once per unique pair (`backend.js:2590-2634`; provider `:130-153`).
- Dedup: content-addressed (SHA-1) for audio/dictionary media; screenshots/clipboard use timestamps
  (clipboard has a cache) (`anki-util.js:115-127`). Anki returns the actual stored filename, which is
  referenced (`anki-connect.js:279-289`).
- `textFurigana` is generated by scanning the sentence (`builder:515-532`), not stored as media.

## (d) Must-match checklist (Android / AnkiDroid)

- [ ] Marker scanner exactly `\{[\p{Letter}\p{Number}_-]+\}`; render errors as `{name-render-error}` (`anki-util.js:23`; `builder:244-247`).
- [ ] Full term/kanji marker catalog of §(a), incl. ja-only pitch markers and dynamic `single-*`; unknown must not crash.
- [ ] No colon arguments; args live in templates.
- [ ] `{audio}`→`[sound:…]`, `{screenshot}`/`{clipboard-image}`→`<img src="…">`; media added out-of-band (`:33-37,244-248,328-332`).
- [ ] Frequency harmonic/average, rank vs occurrence, `-1`→`9999999` (rank) / `0` (occurrence) (`:388-418`).
- [ ] Pitch formats `text|graph|graph-jj|position` + categories (`renderer:890-905,559-578`).
- [ ] `cloze-*` computed from sentence + term offset, with furigana kana body (`creator:958-998`).
- [ ] Output modes split/group/merge + glossary layout modes incl. compact (`creator:443-447,556-611`; `anki-compact-gloss-style.js:19-36`).
- [ ] Duplicate scope collection/deck/deck-root with `checkChildren`/`checkAllModels`; behavior prevent/overwrite/new; first-field-only skeleton (`builder:64-70,117-132`; `display-anki.js:413-457,560-602`).
- [ ] Field `overwriteMode`: coalesce/coalesce-new/skip/append/prepend/overwrite (`display-anki.js:901-916`).
- [ ] Content-addressed media names (SHA-1) + clipboard cache; strip `]` from audio filenames (`anki-util.js:115-127`; `backend.js:2529,2558-2582`).
- [ ] Tags = `anki.tags` + merged term/definition tags; `{tags}` merge rule (`renderer:307-335`).
- [ ] Blank extension-URL `{url}` (`builder:83-91`).

Out of scope for this fork: no bundled Anki note type/styling; field→value mapping is entirely
user-configured per card format.
