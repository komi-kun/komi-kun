# Yomitan spec — Lookup algorithm

Source: `yomidevs/yomitan` @ `e7334a9`. Scope: term lookup (`Translator.findTerms`) reached from a text
scan.

> Provenance note: derived from Yomitan's GPL-3.0 source; treat as a behavioral reference. If the port
> stays Apache-2.0, implement from these behavioral facts, not by transcribing Yomitan code.

## (a) End-to-end flow

1. **Point → text source.** `TextScanner._searchAt` → `TextSourceGenerator.getRangeFromPoint`
   (`ext/js/language/text-scanner.js:1341`), yielding a `TextSourceRange`/`TextSourceElement`
   (`ext/js/dom/text-source-generator.js:190,230`). No `includeRawDomain` option at this revision.
2. **Start-offset snap (word mode).** If `scanResolution === 'word'` and language ∉ {ja,zh,yue,ko}, the
   start offset expands to a word boundary (`text-scanner.js:501-505`; excluded set `:28`).
3. **Scanned substring.** `getTextSourceContent` clones the source, sets the end offset to
   `_scanLength` (`:360-372`; default `scanning.length = 16`), applies include/exclude selectors.
4. **Terms API.** `_findTermDictionaryEntries` → `api.termsFind(searchText, {}, optionsContext)`
   (`:1262-1267`). Kanji fallback scans length 1 (`:1296`).
5. **Backend.** `_onApiTermsFind` (`ext/js/background/backend.js:554-562`) builds options via
   `_getTranslatorFindTermsOptions` (`:2744-2786`; `matchType` default `'exact'`, `deinflect` default
   `true`), calls `Translator.findTerms`, truncates with `dictionaryEntries.splice(maxResults)` (`:560`;
   default 32).
6. **Deinflections.** `_findTermsInternal` (`ext/js/language/translator.js:242-254`) strips non-JCJK
   leading chars when `removeNonJapaneseCharacters` (`:244-246`, `:667-677`), then `_getDeinflections`
   (`:391-418`). `deinflect:false` ⇒ a single identity deinflection (`:394-396`).
7. **Candidate generation.** `_getAlgorithmDeinflections` (`:526-567`) iterates **prefixes** via
   `_getNextSubstring` (`:644-649`: drop last char, or cut at last non-letter when
   `searchResolution==='word'`), builds preprocessing/text-replacement variants (`_getTextVariants`
   `:576-610`), runs `MultiLanguageTransformer.transform` (`:545`; core `language-transformer.js:118-147`),
   applies postprocessors, emits a `DatabaseDeinflection` (`:561`).
8. **DB matching.** `_addEntriesToDeinflections` (`:470-477`) groups by `deinflectedText` (`:483-496`),
   calls `database.findTermsBulk` (`:475`), attaches entries when the dictionary's `partsOfSpeechFilter`
   is off or conditions match (`_matchEntriesToDeinflections` `:504-516`; `conditionsMatch`
   `language-transformer.js:179-181`).
9. **Dictionary deinflections.** `_getDictionaryDeinflections` (`:427-462`): entries whose glossary value
   is a `[formOf, rules]` pair (and dictionary `useDeinflections`) emit extra `formOf` candidates.
10. **Filtering** (`:407-413`): drop array-valued definitions and entries with none.
11. **Entry creation.** `_getDictionaryEntries` (`:263-301`) converts each DB entry, dedup by DB `id`;
    on duplicate keeps the longest `transformedText` (`:280-291`) and merges rule chains (`:290-291`).
12. **Mode + finalize** (`findTerms` `:86-140`): `group`/`term`/`merge` (`:92-102`), meta/tags
    (`:108-121`), sort (`:126-128`), flag redundant tags + sort definitions/frequencies (`:129-134`),
    user-facing inflections (`:135`).

## (b) Candidate generation & matching

- **Deinflected record** (`translation-internal.d.ts:57-65`): `{originalText, transformedText,
  deinflectedText, conditions, textProcessorRuleChainCandidates, inflectionRuleChainCandidates,
  databaseEntries}`.
- **Lookup field:** terms are matched against **both** expression and reading. `findTermsBulk` uses
  indexes `['expression','reading']` (`dictionary-database.js:299`); `matchSource` is `'term'` when
  index 0 else `'reading'` (`:729-730`).
- **Match type:** `'exact'` → `IDBKeyRange.only` (`:301`); `'prefix'` → bound range (`:303-304`);
  `'suffix'` → reversed indexes `['expressionReverse','readingReverse']` with a bound range
  (`:299,306-307`). A prefix/suffix hit is upgraded to `'exact'` when the row value equals the query
  (`:731-733`).
- **mainDictionary/definitions merge:** only `mode==='merge'` uses `_getRelatedDictionaryEntries`
  (`:708-755`): entries from `mainDictionary` sharing `sequence >= 0` are grouped; related entries pulled
  by sequence (`findTermsBySequenceBulk` `:766`) and by `[term, reading]` (`findTermsExactBulk` `:837`).
  A disabled main dictionary is force-added and excluded from output (`backend.js:2760-2770`;
  `_removeExcludedDefinitions`).

## (c) Ranking / ordering

`_sortTermDictionaryEntries` comparator (`translator.js:2184-2234`), highest priority first:

1. `matchPrimaryReading` true first (`:2186`).
2. Larger `maxOriginalTextLength` (`:2190`).
3. Shorter shortest text-processor chain (`:2194`; `:2384-2391`).
4. Shorter shortest inflection chain (`:2198`; `:2397-2404`).
5. Larger `sourceTermExactMatchCount` (`:2202`).
6. Lower `frequencyOrder` (`:2206`; set by `_updateSortFrequencies` `:2340-2378`).
7. Lower `dictionaryIndex` (dictionary priority = options order; `_getDictionaryOrder` `:1519-1523`).
8. Higher `score` (`:2214`).
9. Headword term: longer first, then `Intl.Collator('en-US')` (`:2224-2228`).
10. More definitions (`:2232`).

**Top result = `dictionaryEntries[0]`** after this sort (e.g. `backend.js:1764`). Definitions sorted
separately (`:2247-2274`); simple data by headword/dictionary/index (`:2289-2309`).

## (d) Merge & dedup

- Same DB `id` → one entry per lookup (keep longest transformed text, merge chains) (`translator.js:263-301`).
- Group mode merges by `[term, normalizedReading, inflectionRuleChainCandidates]` (`:862-871`); term mode
  by `[term, chains]` (`:880-887`); `_createGroupedDictionaryEntry` (`:1852-1935`) unions headwords (key
  `[term, normalizedReading]` `:2056`), merges sources (`_addUniqueSources` `:2015-2041`), definitions by
  `[dictionary, ...entries]` (`:2119-2140`), tracking per-headword min `dictionaryIndex` (`:1862-1866`)
  and max `score` (`:1891`).
- `dictionaryNames` is not a field; dictionary identity is by name/alias and `dictionaryIndex`.

## (e) Result object shape

`TermDictionaryEntry` (`types/ext/dictionary.d.ts:212-274`): `type:'term'`, `isPrimary`,
`textProcessorRuleChainCandidates`, `inflectionRuleChainCandidates`
(`{source:'algorithm'|'dictionary'|'both', inflectionRules}`), `score`, `frequencyOrder`,
`dictionaryIndex`, `dictionaryAlias`, `sourceTermExactMatchCount`, `matchPrimaryReading`,
`maxOriginalTextLength`, `headwords`, `definitions`, `pronunciations`, `frequencies`.

- `TermHeadword` (`:295-311`): `index`, `headwordIndex`, `term`, `reading`, `sources`, `tags`, `wordClasses`.
- `TermSource` (`:520-546`): `originalText`, `transformedText`, `deinflectedText`, `matchType`
  (`exact|prefix|suffix`), `matchSource` (`term|reading|sequence`), `isPrimary`. Built `translator.js:1650-1652`, `:1817`.
- `TermDefinition` (`:1681-1696`): `index`, `headwordIndices`, `dictionary`, `dictionaryIndex`,
  `dictionaryAlias`, `id`, `score`, `frequencyOrder`, `sequences`, `isPrimary`, `tags`, `entries` (glossary).
- `TermFrequency` (`:1724-1726`), `TermPronunciation` (`:1707-1709`). `sourceTermExactMatchCount` counts
  `isPrimary && matchSource==='term'` (`:1987-1989`); `matchPrimaryReading` compares reading to
  `primaryReading` (`:1983`).

## (f) Limits & caching

- `maxResults` truncation after sorting (`backend.js:560`; default 32).
- Text-processor output capped `MAX_PROCESS_VARIANTS = 4096` per input (`translator.js:629-633`;
  `text-processors.js:18`).
- Prefix truncation bounds candidates to text length; transform is BFS with per-frame cycle detection
  only (`language-transformer.js:132-136`).
- Caches: tag cache (`translator.js:42,74`, `:1095-1119`), frequency-mode map (`:52-54,1731-1746`),
  per-call text-processor `sourceCache` (`:534-542`), parse-text cache (`backend.js:1750-1757`).

## Must-match checklist

1. Scan substring = first `_scanLength` chars from the (word-snapped) point; default 16.
2. Deinflect candidates are **prefixes** of that substring; `'word'` resolution snaps at last non-letter.
3. Match against **both** expression and reading; record `matchSource`.
4. `exact` = equality; `prefix`/`suffix` = bounded range; suffix uses reversed indexes; equality upgrades to `exact`.
5. Entry already matched by `id` is merged, not duplicated.
6. Dictionary deinflections emit `formOf` candidates from `[formOf, rules]` glossary arrays.
7. Apply the 10-rule comparator above, in order; best = index 0.
8. Truncate to `maxResults` **after** sorting.
9. Emit `originalTextLength` = max primary source length; reset the text source end offset to it.
10. Defaults: `resultOutputMode = 'group'`, `maxResults = 32`; `merge` mode uses sequences + secondary searches.
