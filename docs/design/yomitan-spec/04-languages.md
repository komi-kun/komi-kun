# Yomitan spec — Language support & preprocessing

Source: `yomidevs/yomitan` @ `e7334a9` (GPL-3.0). Captured by the swarm for the komi-kun port.
Scope: everything about language handling **except** Japanese deinflection (see `03-japanese-deinflection.md`).

> Provenance note: this spec was derived by reading Yomitan's GPL-3.0 source. It is a **behavioral
> reference**. If the port stays Apache-2.0, implement from the format/grammar and this spec's
> behavioral facts, not by transcribing Yomitan code/tables. If the work is GPL-3.0, this caveat
> does not apply.

## Per-language registry

Languages live in one array, `languageDescriptors` (`ext/js/language/language-descriptors.js:82`),
indexed into `languageDescriptorMap` by ISO code (`:626-630`). Each entry: `iso`, `iso639_3`, `name`,
`exampleText`, and optionally `textPreprocessors`, `textPostprocessors`, `languageTransforms`,
`readingNormalizer`, `isTextLookupWorthy`. There is **no per-language class** for most languages —
only processors/transforms are factored into subdirectories.

| iso | deinflector | preprocessors | notes |
|---|---|---|---|
| ja | yes | 9 | custom `isTextLookupWorthy` (`:366`) |
| zh | **no** | `normalizeRadicalCharacters` | `readingNormalizer: normalizePinyin` (`:619`) |
| yue | no | `normalizeRadicalCharacters` | Cantonese (`:605`) |
| ko | **yes** | `disassembleHangul` (+ post `reassembleHangul`) | 468 transforms |
| ar / arz | yes | diacritics/tatweel/hamza/alif-maqsura | 76 transforms |
| de | yes | capitalize/decapitalize, `eszettPreprocessor` | 12 |
| el | yes | capitalize/decapitalize, `removeDoubleAcuteAccents` | 2 |
| en | yes | capitalize/decapitalize | 24 |
| eo | yes | capitalize/decapitalize | 28 |
| es | yes | capitalize/decapitalize | 23 |
| eu | yes | capitalize/decapitalize | 79 |
| fr | yes | capitalize/decapitalize, `apostropheVariants` | 14 |
| ga | yes | capitalize/decapitalize | 8 |
| grc | yes | + `removeAlphabeticDiacritics`, `convertLatinToGreek` | 24 |
| ka | yes | — | 9 |
| la | yes | + `removeAlphabeticDiacritics`, `processDiphtongs` | 27 |
| sga | yes | + `removeAlphabeticDiacritics` | 15 |
| sq | yes | capitalize/decapitalize | 37 |
| tl | yes | + `removeAlphabeticDiacritics` | 63 |
| uk | yes | + Ukrainian diacritics/apostrophes | 41; custom lookup (`:564`) |
| yi | yes | diacritics/ligatures (+ post final-letter/ligature conversion) | 14 |
| aii | no | `removeSyriacScriptDiacritics` | |
| fa | no | `removeArabicScriptDiacritics` | |
| id, it, ro, vi | no | `removeAlphabeticDiacritics`; it also `removeApostrophedWords`; vi `normalizeDiacritics` | |
| ru | no | capitalize/decapitalize, `yoToE`, `removeRussianDiacritics` | |
| sh | no | capitalize/decapitalize, add/remove Serbo-Croatian accents | |
| be,bg,br,cs,da,et,fi,gd,gv,haw,hu,kw,mn,mt,hy,nl,no,pl,pt,sv,tr,tok,cy | no | capitalize/decapitalize (some none) | |
| he,hi,lo,th,kn,km | no | none | |
| `xxx` | no | — | "Any / Unknown" (`:83-88`) |

Non-Japanese deinflectors are **declarative suffix/prefix rewrite rules**
(`ext/js/language/language-transforms.js:27,47` — `suffixInflection`/`prefixInflection`) compiled
into the generic `LanguageTransformer`. Korean is the largest (particle/ending chains over jamo).
Chinese has **no** rule-based deinflector — preprocessing + a reading normalizer only.

## Preprocessor catalog

A `TextProcessor` is `{name, description, process(str) -> string[]}`. `process` returns the original
plus transformed variants; the translator tries **all** variants (`translator.js:600-609`), capped at
`MAX_PROCESS_VARIANTS = 4096` (`text-processors.js:18`, `translator.js:628-632`).

**Generic** (`ext/js/language/text-processors.js`):
- `decapitalize` → `[s, s.toLowerCase()]` (`:21-25`)
- `capitalizeFirstLetter` → `[s, S…]` (`:28-32`)
- `removeAlphabeticDiacritics` → `[s, s.normalize('NFD').replace(/[\u0300-\u036f]/g,'')]` (`:41-45`;
  explicitly unsafe for Han)

**CJK** (`ext/js/language/CJK-util.js`):
- `normalizeRadicalCharacters` → NFKD each codepoint inside radical ranges (`:121-135`)

**Japanese** (`ext/js/language/ja/japanese-text-preprocessors.js`) — each `[orig, …transformed]`:
- `convertHalfWidthCharacters` (`:32-36`)
- `alphabeticToHiragana` (`:39-43`)
- `alphanumericWidthVariants` (3 outputs, `:46-54`)
- `convertHiraganaToKatakana` (3 outputs, `:57-65`)
- `collapseEmphaticSequences` (3 outputs: partial/full, `:68-76`)
- `normalizeCombiningCharacters` (`:79-83`)
- `normalizeCJKCompatibilityCharacters` (`:86-90`)
- `standardizeKanji` (`:93-97`)

**Others:** `removeSyriacScriptDiacritics` · Arabic `removeArabicScriptDiacritics`, `removeTatweel`,
`normalizeUnicode` = **NFKC**, `addHamzaTop/Bottom`, `convertAlifMaqsuraToYaa` (`/ى$/`),
`convertHaToTaMarbuta` (`/ه$/`) · German `eszettPreprocessor` (`ß↔ss`) · Greek `removeDoubleAcuteAccents`
· French `apostropheVariants` · `convertLatinToGreek` · Italian `removeApostrophedWords` · Latin
`processDiphtongs` (`æ→ae`) · Russian `removeRussianDiacritics`/`yoToE` · Serbo-Croatian accent add/remove
· Ukrainian diacritics/apostrophes · Vietnamese `normalizeDiacritics` · Yiddish ligature/diacritic ·
Korean `disassembleHangul`/`reassembleHangul`.

Note: at this commit there is **no** standalone `NFC`/`toRomaji`/`toHiragana`/`lowercase` processor;
NFC/NFKC only appear inside `normalizeUnicode`, `normalizePinyin`, and reading normalizers.

## Wiring: descriptor → transformer → lookup

1. `ext/js/language/languages.js` enumerates the registry: `getAllLanguageTransformDescriptors`
   (`:85-93`), `getAllLanguageTextProcessors` (`:47-69`), `getAllLanguageReadingNormalizers`
   (`:34-41`).
2. `Translator.prepare()` builds a `MultiLanguageTransformer`, then maps `iso→{pre,post}` and
   `iso→readingNormalizer` (`translator.js:56-68`).
3. `MultiLanguageTransformer.prepare()` compiles each descriptor into a `LanguageTransformer` keyed
   by `descriptor.language` (`multi-language-transformer.js:28-35`); unknown ⇒ identity (`:72-76`).
4. `LanguageTransformer.addDescriptor` compiles `conditions` into bit-flags (max 32,
   `language-transformer.js:201-204`) and each rule's `isInflected` into a join regex heuristic
   (`:70-72`). `transform()` BFS-expands: skip on heuristic fail, require `conditionsMatch`, cycle-guard
   via trace (`:118-147`); `conditionsMatch` = current==0 OR overlap (`:179-181`).
5. Lookup (`translator.js:526-567`): for each raw substring → `_getTextVariants` applies
   **preprocessors** in order (`:576-609`) → `transform(language, source)` per variant →
   **postprocessors** → one `Deinflection{originalText, transformedText, deinflectedText, conditions,
   chains}`.
6. Substring stepping: `_getNextSubstring` trims by `\p{Letter}` for `word` resolution else one char
   (`:645-651`). For ja/zh/yue/ko, `removeNonJapaneseCharacters` truncates at the first non-CJK/JP/KR
   codepoint (`:244`, `:665-679`). Reading normalization applied when matching readings (`:803`).
7. Language selection: options supply `general.language`; `getLanguageFromText`
   (`text-utilities.js:28-36`) overrides with `ja`/`zh` via `isStringPartiallyJapanese`/
   `isStringPartiallyChinese` unless current is zh/yue. `isTextLookupWorthy` (`languages.js:76-80`)
   filters text (default true; ja/uk/zh use range tests).

## Character classification

`CJK-util.js`: codepoint ranges — `CJK_IDEOGRAPH_RANGES` (extensions A–I + compatibility,
`:19-57`), `FULLWIDTH_CHARACTER_RANGES` (`:60-70`), `CJK_PUNCTUATION_RANGE` (`:73`), radical/stroke
ranges (`:101-115`), `isCodePointInRange(s)` (`:83-99`). `ja/japanese.js` builds
`JAPANESE_RANGES`/`KANA_RANGES`; exposes `isCodePointKanji/Kana/Japanese`, `isStringPartiallyJapanese`,
`isStringEntirelyKana`. `zh/chinese.js` `isStringPartiallyChinese`; `ko/korean.js`
`isCodePointKorean`. `text-scanner.js` uses classes for scan gating only:
`SCAN_RESOLUTION_EXCLUDED_LANGUAGES = {'ja','zh','yue','ko'}` (`:29`, used `:502`) and
sentence/quote terminator maps (`:129-132`, built `:322-346`).

## Must-match checklist

Primary target **Japanese** (mandatory):
- [ ] 9 JP preprocessors with exact variant lists & order (`language-descriptors.js:367-377`).
- [ ] Per-variant lookup + `MAX_PROCESS_VARIANTS=4096` truncation.
- [ ] `getLanguageFromText` ja-override + `isStringPartiallyJapanese` lookup-worthiness.
- [ ] CJK-util ranges/radicals, `JAPANESE_RANGES`, half-width/kana conversions.
- [ ] Word-boundary substring stepping + `removeNonJapaneseCharacters` for ja.

Lower priority (non-Japanese):
- [ ] Generic `decapitalize`/`capitalizeFirstLetter`/`removeAlphabeticDiacritics`, `normalizeRadicalCharacters`.
- [ ] Registry completeness and `getLanguageSummaries`.
- [ ] Non-JP preprocessors and deinflectors — only if non-Japanese dictionaries are in scope.
- [ ] `readingNormalizer` (zh pinyin only).
- [ ] zh/yue/ko scan-exclusion and postprocessors.
