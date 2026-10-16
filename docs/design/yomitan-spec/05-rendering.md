# Yomitan spec — Entry rendering / DOM contract

Source: `yomidevs/yomitan` @ `e7334a9` (GPL-3.0). The DOM a replacement renderer (Android WebView)
must emit so dictionary/user CSS keeps working.

> Provenance note: derived from Yomitan's GPL-3.0 source; treat as a behavioral reference. If the
> port stays Apache-2.0, implement from these behavioral facts, not by transcribing Yomitan code.

## Entry skeleton

Each entry is one `.entry` inserted into the display container, tagged `data-index`
(`ext/js/display/display.js:1493`). Templates: `ext/templates-display.html`.

- **Term**: `<div class="entry" data-type="term">` (`templates-display.html:4`), built by
  `createTermEntry` (`display-generator.js:82`). Entry-level `data-*` counters: `data-format`
  (=`type`), `headword-count`, `definition-count`, `pronunciation-dictionary-count`,
  `pronunciation-count`, `unique-term-count`, `unique-reading-count`, `frequency-count`,
  `grouped-frequency-count`, `primary-match-types` (`display-generator.js:113-122`). Body sections:
  `.entry-body-section[data-section-type=frequencies|pronunciations|definitions]`
  (`templates-display.html:29-37`); empty sections hidden by CSS via those counters
  (`ext/css/display.css:902-905`).
- **Kanji**: `<div class="entry kanji-entry" data-type="kanji">` (`templates-display.html:100`), built
  by `createKanjiEntry` (`display-generator.js:202`), root gets `data-dictionary` (`:204`).

**Headwords**: `.headword-list` (`.headword` each, `templates-display.html:40`) with `data-index`,
`data-is-primary`, `data-reading-is-same`, `data-frequency`, `data-match-types`, `data-match-sources`,
`data-pronunciation-categories`, `data-word-classes` (`display-generator.js:480-493`). Term text in
`.headword-term` (ruby furigana via `.headword-term>ruby>rt`); reading in `.headword-reading`; kanji in
`<a class="headword-kanji-link">` (`:668`). Tag list `.headword-list-tag-list`; inflection chains
`.inflection-rule-chains` (`.inflection-rule-chain`, `.inflection[data-reason]`,
`.inflection-source-icon[data-inflection-source]` `:524-552`).

**Definitions**: `.definition-list` → `li.definition-item[data-dictionary]` (`:573`) with
`.definition-tag-list`, `.definition-disambiguation-list`
(`.definition-disambiguation[data-term]` `:657`), and `ul.gloss-list`. Each
`li.gloss-item.click-scannable` holds `span.gloss-content` (`templates-display.html:68`). String glosses
become text + `<br>`; the node gets `.structured-content` (`display-generator.js:610-615`).

## (a) Structured-content element catalog

`appendStructuredContent` adds class `structured-content` to the gloss container and recurses
(`ext/js/display/structured-content-generator.js:43-46`). Content is `string | Element | Content[]`
(`types/ext/structured-content.d.ts:244`). Dispatch: `_createStructuredContentGenericElement`
(`structured-content-generator.js:294`):

| `tag` values | Rendered element / class | Notes |
|---|---|---|
| `br` | `<br class="gloss-sc-br">` | no children (`:297`) |
| `ruby`,`rt`,`rp` | same tag `.gloss-sc-<tag>` | children (`:299`) |
| `table` | `div.gloss-sc-table-container` > `table.gloss-sc-table` | (`:336`) |
| `thead`,`tbody`,`tfoot`,`tr` | `.gloss-sc-<tag>` | children (`:305`) |
| `th`,`td` | `.gloss-sc-<tag>` | `colSpan`/`rowSpan` applied (`:310,:362`) |
| `div`,`span`,`ol`,`ul`,`li`,`details`,`summary` | `.gloss-sc-<tag>` | styled (`:313`) |
| `img` | anchor `a.gloss-image-link` | see image contract |
| `a` | anchor `a.gloss-link` | see links |

Every element: class is exactly `gloss-sc-${tag}` (`:354`); a `data` object is copied to `data-sc-<Key>`
with the key camel-cased (`_setElementDataset`, `:253-265`; e.g. `data.content="glossary"` →
`data-sc-content="glossary"`); `lang` → `lang`; `title` → title; `details open` → `open` (`:357-377`).
`style` maps through `_setStructuredContentElementStyle` (`:389-469`; full set:
`types/ext/structured-content.d.ts:53-86`; numeric margins → `em`). Default styling:
`ext/css/structured-content.css` / `ext/data/structured-content-style.json`.

**img**: `a.gloss-image-link` > `span.gloss-image-container` > `.gloss-image-sizer` +
`.gloss-image-background` + `.gloss-image-container-overlay`, plus `span.gloss-image-link-text` and a
display `canvas.gloss-image` (`:97-116,:152`). Attributes: `data-path`, `data-dictionary`,
`data-image-load-state` (`not-loaded`/`loaded`/`unloaded`/`load-error`), `data-has-aspect-ratio=true`,
`data-image-rendering`, `data-appearance`, `data-background`, `data-collapsed`, `data-collapsible`,
optional `data-vertical-align`, `data-size-units` (`:125-139`). Sizer `padding-top` = aspect×100%
(`:141`).

**a**: `a.gloss-link` > `span.gloss-link-text`; `data-external` true/false; external also
`span.gloss-link-external-icon.icon[data-icon=external-link]` (`:484-501`).

## (b) Required `data-*` + classes

| Attribute | Element | Meaning |
|---|---|---|
| `data-index` | `.entry`,`.headword`,`.gloss-item` | ordinal |
| `data-type` | `.entry` | `term`/`kanji` |
| `data-format` | `.entry` | dictionary entry type |
| `data-*-count` | `.entry`,`.headword-list`,`.definition-list`,`.gloss-list`, disambiguation | drive CSS hiding |
| `data-dictionary` | `.kanji-entry`,`.definition-item`,`.pronunciation-group`,`.frequency-item`,`.gloss-image-link` | CSS scope + lookup |
| `data-category`,`data-details`,`data-redundant` | `.tag` | tag metadata |
| `data-headwords`,`data-total/matched/unmatched-headword-count` | headword tag | disambiguation |
| `data-is-primary`,`data-reading-is-same`,`data-frequency`,`data-match-types`,`data-match-sources`,`data-pronunciation-categories`,`data-word-classes` | `.headword` | headword meta |
| `data-pitch-accent-downstep-position`,`data-pronunciation-type`,`data-nasal-mora-position`,`data-devoice-mora-position`,`data-tag-count` | `.pronunciation` | pitch meta |
| `data-pronunciations-multi`,`data-pronunciations-count` | `.pronunciation-group` | |
| `data-frequency-type` (term/kanji), `data-frequency` | `.frequency-tag`,`.frequency-value` | |
| `data-display-value` | `.frequency-value` | display text when ≠ raw |
| `data-term`,`data-reading`,`data-has-reading`,`data-reading-is-same`,`data-dictionary` | `.frequency-item` | |
| `data-character` | kanji `.frequency-item` | |
| `data-sc-*` | structured-content elements | dictionary-provided |
| `data-external` | `.gloss-link` | internal vs external |
| `data-reason` | `.inflection` | |
| `data-icon` | `.icon` | icon font key |

Root `<html>` options: `data-language`, `data-result-output-mode`, `data-glossary-layout-mode`,
`data-term-display-mode`, `data-frequency-display-mode`, `data-average-frequency`, `data-compact-tags`,
`data-enable-search-tags`, `data-show-pronunciation-*`, `data-popup-*`, `data-debug`, `data-browser`
(`display.js:326,1256-1274`).

## (c) Pitch accent & frequency

**Pitch.** `_createPronunciationPitchAccent` (`display-generator.js:873`) emits `li.pronunciation` with
`.pronunciation-tag-list`, `.pronunciation-disambiguation-list`,
`.pronunciation-downstep-notation-container`, `.pronunciation-text-container`,
`.pronunciation-graph-container` (template `templates-display.html:97`). Morae from
`getKanaMorae(reading)` (`:876`).

- Text: `createPronunciationText` (`pronunciation-generator.js:38`) →
  `span.pronunciation-text` > `span.pronunciation-mora[data-position][data-pitch=high|low][data-pitch-next]`
  > `span.pronunciation-character` + `span.pronunciation-mora-line`; nasal adds
  `.pronunciation-character-group`/`.pronunciation-nasal-diacritic`/`.pronunciation-nasal-indicator` and
  `data-nasal`/`data-original-text`; devoice adds `.pronunciation-devoice-indicator` + `data-devoice`
  (`:66-99`).
- Downstep notation: `span.pronunciation-downstep-notation[data-downstep-position]` with `-prefix` `[`,
  `-number`, `-suffix` `]` (`:169-192`).
- Graph: `svg.pronunciation-graph` (`focusable=false`), `viewBox="0 0 50*(n+1) 100"`; high y=25, low
  y=75, x=i*50+25 (`:115-163`). Paths `.pronunciation-graph-line` (solid) +
  `.pronunciation-graph-line-tail` (dashed); circles `.pronunciation-graph-dot`,
  `.pronunciation-graph-dot-downstep1/2`; terminal `.pronunciation-graph-triangle`. `currentColor` via
  `ext/data/pronunciation-style.json` / `ext/css/display-pronunciation.css`.

**Frequency.** `_createFrequencyGroup` (`display-generator.js:937`) emits `.frequency-group-item` >
`.tag.tag-has-body.frequency-group-tag` with `.tag-label-content` (alias) and
`.tag-body-content.frequency-list` (template `templates-display.html:74`). Per-dictionary groups plus a
synthetic `Average` group (`dictionary-data-util.js:120-125`). Term items: `.frequency-item` >
`.frequency-tag[data-frequency-type=term]` > `.frequency-body` with `.frequency-disambiguation` (ruby
`.frequency-disambiguation-term/reading`) then `.frequency-value-list` (`:974-997`). Values: each
`.frequency-value[data-frequency]` shows `displayValue ?? frequency`; `data-display-value` + `title=raw`
added when they differ; joined by a `.frequency-value` containing `, `; the list's `data-frequency` holds
the concatenated string (`_populateFrequencyValueList`, `:1029-1060`). Kanji variant omits term/reading
(`:1007`). Grouping is per dictionary→term/reading and **not numeric-sorted** (insertion order;
`dictionary-data-util.js:60-105`). `headword.dataset.frequency` = `popular|rare|normal` from summed tag
scores (`getTermFrequency`, `:264-276`); frequencies section hides via `data-frequency-count='0'`, and
per-value visibility follows `data-frequency-display-mode` (`display.css:1374-1427`).

## (d) Styles scoping

A dictionary's `styles.css` is stored as `styles` (`dictionary-importer.js:297`) and injected per
profile: wrapped by `addScopeToCss(styles, '[data-dictionary="<title>"]')` →
`[data-dictionary="TITLE"] { …css… }` using **native CSS nesting** (`display.js:1311-1318`;
`core/utilities.js:285-287`). Title quotes/backslashes escaped (`display.js:1316`). An element must
carry `data-dictionary="<exact title>"` for that CSS to apply — set on `.kanji-entry`,
`.definition-item`, `.pronunciation-group`, both frequency items, `.gloss-image-link`. Legacy flat form
`addScopeToCssLegacy` (`utilities.js:298`) used only for Anki notes (`anki-note-data-creator.js:618,631`).

`ext/data/structured-content-style.json` / `pronunciation-style.json` are the global default style
tables; `CssStyleApplier` (`ext/js/dom/css-style-applier.js:78`) converts class-based rules to inline
`style` and **strips the class** for Anki output, while the display uses
`ext/css/structured-content.css` and `ext/css/display-pronunciation.css`.

## (e) Must-match checklist

1. `.entry[data-type=term|kanji]` with the `data-*-count` set; hide empty `.entry-body-section` via counts.
2. `data-dictionary` (exact title) on every per-dictionary node in §d, so scoped CSS matches.
3. Tag element exactly: `.tag > .tag-label > .tag-label-content`, with `data-category`, `data-details`, `data-redundant`.
4. Gloss item = `li.gloss-item.click-scannable > span.gloss-content` (+ `.gloss-separator`); structured glosses add `.structured-content`.
5. Structured elements use `gloss-sc-<tag>`; `data` → `data-sc-<CamelKey>`; images use the `gloss-image-*` tree + `data-image-load-state`/`data-appearance`/`data-collapsible`.
6. Pitch: `.pronunciation-mora[data-pitch][data-pitch-next]`, downstep `[n]`, SVG `viewBox 0 0 50*(n+1) 100`, y=25/75 (or the JJ variant).
7. Frequency: `.frequency-value[data-frequency]` with `data-display-value`/`title`, comma separators, `Average` group, `data-frequency-type`.
8. Links: internal → same-origin `search.html?…` with `data-external=false`; external `target=_blank rel="noreferrer noopener"` with `data-external=true`. Kanji use `.headword-kanji-link`.
9. Set root `<html data-*>` options (language, layout modes, display modes) that CSS keys off.
10. Preserve `dataset.index` on entries/headwords/items and the scan selectors `.click-scannable` (include) / `.scan-disable` (exclude).
