# Yomitan spec — Dictionary package format & import

Source: `yomidevs/yomitan` @ `e7334a9` (GPL-3.0). A package is a ZIP whose **root** holds `index.json`,
zero or more bank files, optional `styles.css`, and media files (all banks at the archive root;
`docs/making-yomitan-dictionaries.md:40`).

> Provenance note: derived from Yomitan's GPL-3.0 source; treat as a behavioral reference. If the port
> stays Apache-2.0, implement from these behavioral facts, not by transcribing Yomitan code.

## (a) File-by-file format table

| File (regex) | Schema | Version use |
|---|---|---|
| `index.json` (exact) | dictionary-index-schema.json | required, root only |
| `term_bank_(\d+)\.json` | dictionary-term-bank-v1/v3-schema.json | V1 only if `version===1`, else V3 |
| `term_meta_bank_(\d+)\.json` | dictionary-term-meta-bank-v3-schema.json | always V3 |
| `kanji_bank_(\d+)\.json` | dictionary-kanji-bank-v1/v3-schema.json | V1 or V3 |
| `kanji_meta_bank_(\d+)\.json` | dictionary-kanji-meta-bank-v3-schema.json | always V3 |
| `tag_bank_(\d+)\.json` | dictionary-tag-bank-v3-schema.json | always V3 |
| `styles.css` | (none; must be non-empty text if present) | — |
| media (`*.png`/`.gif`/…) | (none) | referenced by glossary `path` |

Discovery regexes `dictionary-importer.js:132-137`; schema selection `_getDataBankSchemas` (`:507-523`).
Files in subdirectories never match (`^…$`), so ignored; bank files read in ZIP-entry order (not sorted)
(`:899-919,929-951`).

**`index.json`** (dictionary-index-schema.json:17-112): required `title`, `revision` (`:13-16`);
`format` **or** `version` (alias, enum 1/2/3; `:114-121`). Optional: `minimumYomitanVersion`,
`sequenced`, `author`, `isUpdatable` (const `true`), `indexUrl`, `downloadUrl`, `url`, `description`,
`attribution`, `sourceLanguage`/`targetLanguage` (`^[a-z]{2,3}$`), `frequencyMode`
(`occurrence-based`|`rank-based`), and the **obsolete** `tagMeta` map.

> ⚠️ **There is no `priority` and no `prefix` field** — an earlier assumption (from the Chimahon
> survey) was wrong. `isUpdatable` requires `indexUrl`+`downloadUrl` (`:122-124`; http(s) validated
> `dictionary-importer.js:461-490`).

`format`/`version`: 1 = legacy V1 banks; 2 and 3 both use V3 banks (importer only branches
`version===1` vs else; resolved `format ?? version`). `sequenced` (default false): term rows carry
sequence numbers, used to pick the main dictionary and merge related terms (`:430-436`; merge
`translator.js:818`).

## (b) Bank row spec (index-by-index)

**term_bank V1** (`minItems=maxItems=5` + extra strings; `term-bank-v1-schema.json:9-36`):
`[0]` expression, `[1]` reading (`""` ⇒ expression), `[2]` definitionTags (string|`null`), `[3]` rules
(space-sep ids, e.g. `v1`,`v5`,`vs`,`vz`,`vk`,`adj-i`), `[4]` score, `[5..]` one definition **string** each.

**term_bank V3** (exactly 8; `:406-591`): `[0]` expression, `[1]` reading, `[2]` definitionTags,
`[3]` rules, `[4]` score, `[5]` glossary **array** (each: string | `{type:'text',text}` |
`{type:'image',…}` | `{type:'structured-content',content}` | `[uninflected, ruleChain[]]` pair),
`[6]` sequence (int), `[7]` termTags (string).

**term_meta_bank** (exactly 3; `term-meta-bank-v3-schema.json:30-49`): `[0]` expression, `[1]` mode ∈
{`freq`,`pitch`,`ipa`}, `[2]` value:
- `freq` (`:57-83`): number | string, or `{value:number, displayValue?:string}`, or
  `{reading, frequency}` wrapping either.
- `pitch` (`:91-174`): `{reading, pitches:[{position: int≥0 | "^[HL]+$", nasal?:int|int[],
  devoice?:int|int[], tags?:string[]}]}`.
- `ipa` (`:183-221`): `{reading, transcriptions:[{ipa:string, tags?:string[]}]}`.

**kanji_bank V1** (≥4 + meanings; `kanji-bank-v1-schema.json:9-33`):
`[character, onyomi, kunyomi, tags, ...meanings:string]`.
**kanji_bank V3** (exactly 6; `:9-45`): `[character, onyomi, kunyomi, tags, meanings:string[],
stats:{[k]:string}]`.
**kanji_meta_bank** (exactly 3; `kanji-meta-bank-v3-schema.json:33-50`): `[character, "freq",
number|string|{value,displayValue?}]` (single mode).
**tag_bank** (exactly 5; `tag-bank-v3-schema.json:9-33`): `[name, category, order:number, notes:string,
score:number]`. Categories: `name, expression, popular, frequent, archaism, dictionary, frequency,
partOfSpeech, search, pronunciation-dictionary` (`docs:89-98`).

## (c) Import normalization (pseudo-algorithms)

Per-row conversion (`dictionary-importer.js:813-878`):

```
convert_term_v1(row): (expr, reading, defTags, rules, score, ...gloss)
    reading = reading=="" ? expr : reading
    -> {expression:expr, reading, definitionTags:defTags, rules, score, glossary:gloss, dictionary}
convert_term_v3(row): same but glossary=row[5]; + {sequence:row[6], termTags:row[7]}
kanji v1: meanings=rest;  v3: {meanings:row[4], stats:row[5]}
term_meta/kanji_meta: {expression|character, mode, data:verbatim, dictionary}
tag: {name,category,order,notes,score,dictionary}
```

> ⚠️ **No `forms` expansion exists** at this commit — no `forms` field anywhere, and no
> `index.json.prefix`. Do **not** invent it. The only term-time transforms:

```
if import_details.prefixWildcardsSupported:      # a user option, not index.json
    entry.expressionReverse = reverse(expression)  # code-unit reverse
    entry.readingReverse    = reverse(reading)
```

(`:141,190-195`; option source `dictionary-import-controller.js:558`.) Reading `""`→expression is the
only canonicalization. `definitionTags`/`termTags`/`rules`/`onyomi`/`kunyomi`/`tags` stay
space-separated strings in the DB and are split on `" "` at query time (`_splitField`,
`dictionary-database.js:852-854`). **Tag ids are names**: term/kanji rows reference tag *names*,
resolved against the `tagMeta` store by (name, dictionary) (`findTagMetaBulk`, `:374-378`). Obsolete
`index.tagMeta` appended as tag rows (`_addOldIndexTags`, `dictionary-importer.js:885-892`).

Glossary object lowering / media (`:198-226,540-806`): each glossary object is replaced in-place, then
requirements resolved; already-seen paths resolved but not re-added:

```
for each glossary object g:
   text               -> g.text (string)
   image              -> push requirement; target={type:'image',path:''} (filled later)
   structured-content -> recurse; img tag -> requirement
resolve not-yet-added requirements:
   path = requirement source.path (archive-root-relative, exact filename)
   content = zip[path]; mediaType = inferFromExtension(path)
   {content,width,height} = mediaLoader.getImageDetails(content, mediaType)
   media store row = {dictionary, path, mediaType, width, height, content}
```

Errors: `Could not find image`, `Could not determine media type…`, `Could not load image` (`:756-792`).

DB stores vs banks (`dictionary-database.js:89-156`): `terms`(indices dictionary,expression,
reading,+sequence,+expressionReverse,+readingReverse), `termMeta`(dictionary,expression),
`kanji`(dictionary,character), `kanjiMeta`(dictionary,character), `tagMeta`(dictionary,name),
`media`(dictionary,path), `dictionaries`(title,version). Inserted in 1000-row transactions (`:72,87-98`).

## (d) Must-match checklist

1. Accept `index.json` only at ZIP root; reject nested (`:358-366`).
2. Validate `index.json` against the schema; require `title`,`revision`, and `format`|`version` ∈{1,2,3} (`:372-384`).
3. `version 1` ⇒ V1 term/kanji schemas; 2 & 3 ⇒ V3; meta/tag banks always V3 (`:507-523`).
4. Validate **every** bank file before ingesting; JSON/schema errors carry filename + details (`:145-149,497-501,958-980`).
5. Reject duplicate dictionary `title` (skip + error) (`:118-123`).
6. Reproduce exact row layouts, incl. V1 variadic glossary vs V3 glossary array, V3 `sequence`/`termTags`.
7. `reading==""` ⇒ expression; store tags/rules space-separated; split on `" "` at query time.
8. Resolve tags by name against `tagMeta`; append legacy `index.tagMeta`.
9. `prefixWildcardsSupported` (external option) ⇒ add `expressionReverse`/`readingReverse` by reversing code units.
10. `sequenced` from index; `format`/`version` recorded as `version`.
11. `frequencyMode`, `sourceLanguage`, `targetLanguage`, `author`, `url`, `description`, `attribution`, `minimumYomitanVersion` copied to summary; version-gate on min via numeric revision compare (`:445-452`; `dictionary-data-util.js:350-370`).
12. `isUpdatable` requires http(s) `indexUrl`+`downloadUrl`.
13. Read `styles.css` if present; empty ⇒ fatal `No styles found`; store raw text; scope with `[data-dictionary="<title>"]` (`:297-309,529-531`; `display.js:1314-1317`).
14. Media: exact-path archive lookup, dedupe by path per import, mediaType from extension, dimensions measured, stored with content (`:748-806`). No `forms`/`prefix` handling.
15. Counts: totals per store + per-mode `freq`/`pitch`/`ipa` counts (`_getMetaCounts`, `:986-1001`); return `{result:summary, errors:Error[]}`; errors collected, not fatal, except index/CSS failures.
