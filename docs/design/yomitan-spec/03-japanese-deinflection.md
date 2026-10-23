# Yomitan spec — Japanese deinflection

Source: `yomidevs/yomitan` @ `e7334a9` (HEAD: "[ja] add -やしない and -やしません form (#2500)").
Paths relative to repo root.

> Provenance note: derived from Yomitan's GPL-3.0 source; treat as a behavioral reference. If the port
> stays Apache-2.0 clean-room, implement the *behavior/grammar* from this and a Japanese-grammar
> reference — do not transcribe these rule tables. If the work is GPL-3.0, this caveat does not apply.

## 1. Rule data model & grammar

`ext/js/language/language-transformer.js` + `types/ext/language-transformer.d.ts:18-78`.

```
LanguageTransformDescriptor = { language, conditions: {[id]: Condition}, transforms: {[id]: Transform} }  // ja: japanese-transforms.js:240-1932
Condition = { name, isDictionaryForm: bool, i18n?: [{language,name}], subConditions?: string[] }
Transform = { name, description?, i18n? , rules: Rule[] }
Rule      = { type, isInflected: RegExp, deinflect: string=>string, conditionsIn: string[], conditionsOut: string[] }
```

Field names are `conditionsIn`/`conditionsOut` (compiled to `number` bitflags internally,
`language-transformer-internal.d.ts:26-32`). There is **no `condition`/`rulesOut`** field; `type`
(`suffix`|`prefix`|`wholeWord`) is metadata only — the engine uses only `isInflected` + `deinflect`
(`:126-143`). Constructors (`language-transforms.js`): `suffixInflection` → `isInflected=/<inf>$/`,
`deinflect = text.slice(0,-inf.length)+deinf` (`:27-37`); `wholeWordInflection` → `/^<word>$/`,
`deinflect = ()=>deinf` (`:66-75`); `prefixInflection` (unused by ja).

**Condition compilation** (`:189-259`): each leaf condition gets a bit `1<<n` (`:200-206`); a
condition with `subConditions` is the OR of children (`:207-214`). Append order = object insertion
order; **≤32 leaves** or throws (`:201-204`). An expression is `string[]` → OR of member flags
(`_getConditionFlagsStrict`, `:232-242`); unknown ids throw (`:59-61`); empty → `0`.
`isDictionaryForm` conditions also populate `_partOfSpeechToConditionFlagsMap` (`:80-87`).

**Evaluation** (`:172-181`):

```
conditionsMatch(current, next) = current === 0 || (current & next) !== 0
```

Root `current=0` ⇒ **every** rule eligible. A rule with `conditionsIn=[]` fires **only** at the root.
A rule with `conditionsOut=[]` resets `current=0`. `conditions` on a result is **replaced** by
`rule.conditionsOut` each step, never accumulated (`:138-142`).

## 2. Japanese conditions (`japanese-transforms.js:66-237`)

Dictionary-form (POS) ids → `v1`(一段), `v5`(五段), `vk`(くる), `vs`(する), `vz`(ずる), `adj-i`
(`:86,117,169,179,189,199`). Composite: `v={v1,v5,vk,vs,vz}` (`:76`), `v1={v1d,v1p}` (`:87`),
`v5={v5d,v5s}` (`:118`), `v5s={v5ss,v5sp}` (`:139`). 18 leaves. Non-POS intermediates: `-ます -ません
-て -ば -く -た -ん -なさい -ゃ` (`:201-236`). DB entries carry POS strings matched against a
deinflection's final `conditions` via `getConditionFlagsFromPartsOfSpeech` + `conditionsMatch`
(`translator.js:509-511`).

## 3. Transform catalog (57 named transforms)

Notation: **In→Out** are condition expressions; **P** = recurring full verb paradigm
`{る→る/v1; {い,ひ}→う, き→く, ぎ→ぐ, し→す, ち→つ, に→ぬ, び→ぶ, み→む, り→る /v5; じ→ずる/vz,
し→する/vs, 為→為る/vs, き|こ→くる, 来→来る, 來→來る /vk}` (`:1098-1111` etc.). Bodies are otherwise
`suffixInflection(inf, deinf, In, Out)`; only deviating mappings are written out.

| # | name (`:line`) | In → Out | inflected → deinflected |
|---|---|---|---|
| 1 | `-ば` `:244` | `-ば` → adj-i, v5, v1/v5/vk/vs/vz, -ます | ければ→い; {え,へ,け,げ,せ,て,ね,べ,め}ば→{う,う,く,ぐ,す,つ,ぬ,ぶ,む}; れば→る; れば→∅(→-ます) |
| 2 | `-ゃ` `:270` | `-ゃ` → -ば | contractions |
| 3 | `-ちゃ` `:294` | v5 → v1/v5/vz/vs/vk | P-ish + contractions |
| 4 | `-ちゃう` `:329` | v5 → same | as #3 with ちゃう |
| 5 | `-ちまう` `:360` | v5 → same | as #3 with ちまう |
| 6 | `-しまう` `:391` | v5 → -て | てしまう→て; でしまう→で |
| 7 | `-なさい` `:407` | `-なさい` → v1/v5/vz/vs/vk | P (なさい form) |
| 8 | `-そう` `:438` | `[]` → adj-i/v1/v5/vz/vs/vk | そう|さう→い, →る; P |
| 9 | `-すぎる` `:486` | v1 → adj-i/v1/v5/vz/vs/vk | すぎる→い (adj-i), →る; P |
| 10 | `-過ぎる` `:518` | v1 → same | as #9 |
| 11 | `-たい` `:550` | adj-i → v1/v5/vz/vs/vk | たい→る; P |
| 12 | `-たら` `:582` | `[]` → adj-i/v1/v5/vz/vs/vk, -ます | かったら→い; たら→る; P; irregular; ましたら→ます |
| 13 | `-たり` `:616` | `[]` → same | as #12 with たり |
| 14 | `-て` `:649` | `-て` → same | くて→い; て→る; P; irregular('て'); まして→ます |
| 15 | `-ず` `:682` | `[]` → v1/v5/vz/vs/vk | P (ず-form) |
| 16 | `-ぬ` `:714` | `[]` → same | P |
| 17 | `-ん` `:746` | `-ん` → same | P |
| 18 | `-んばかり` `:778` | `[]` → same | P |
| 19 | `-んとする` `:810` | **vs** → same | P |
| 20 | `-む` `:843` | `[]` → same | P (archaic) |
| 21 | `-ざる` `:877` | `[]` → same | P |
| 22 | `-ねば` `:909` | `-ば` → same | P |
| 23 | `-く` `:942` | `-く` → adj-i | く→い |
| 24 | `causative` `:956` | v1 → v1/v5/vz/vs/vk | させる→る; かせる→く…ませる→む; じ/ぜさせる→ずる; させる/せさせる→する; こさせる→くる |
| 25 | `short causative` `:992` | v5ss/v5sp → same | さす→る; かす→く…らす→る; ざ/ぜさす→ずる; さす→する; こさす→くる |
| 26 | `imperative` `:1028` | `[]` → same | ろ/よ→る; え/へ/…/れ→{う…る}; じろ/ぜよ→ずる; しろ/せよ→する; こい→くる |
| 27 | `continuative` `:1064` | `[]` → v1d/v5/vk/vs | い/え/き/…/れ→+る; P (v5); き→くる,し→する |
| 28 | `negative` `:1114` | adj-i → adj-i/v1/v5/vz/vs/vk; -ません→-ます | くない→い; ない→る; P; ません→ます |
| 29 | `-さ` `:1148` | `[]` → adj-i | さ→い |
| 30 | `passive` `:1163` | v1 → v5 / v5d+v5sp / vz/vs/vk | かれる→く…われる→う; される→す; られる→る; じ/ぜされる→ずる; される→する; こられる→くる |
| 31 | `-た` `:1193` | `-た` → adj-i/v1/v5/vz/vs/vk, -ます, -ません, -ん | かった→い; た→る; P; irregular('た'); ました→ます; でした→∅(→-ません); かった→∅(→-ません,-ん) |
| 32 | `-ます` `:1232` | `-ます` → v1/v5d/vz/vs/vk/adj-i | ます→る; special honorific wholeWord (いらっしゃいます…); P; くあります→い |
| 33 | `potential` `:1264` | v1 → v1/v5d/vs/vk | れる→る; える→う…める→む; できる/出来る→する; これる→くる |
| 34 | `potential or passive` `:1293` | v1 → v1/vz/vs/vk | られる→る; ざ/ぜられる→ずる; せられる→する; こられる→くる |
| 35 | `volitional` `:1316` | `[]` → v1/v5/vz/vs/vk/-ます/adj-i | よう→る; おう/…/ろう→{う…る}; ましょう→ます; かろう→い |
| 36 | `volitional slang` `:1371` | `[]` → v1/v5/vz/vs/vk/-ます | よっか→る; …; ましょっか→ます |
| 37 | `-まい` `:1405` | `[]` → **v**/v1/vz/vs/vk/-ます | まい→∅(→v), →る; …; →∅(→-ます) |
| 38 | `-おく` `:1433` | v5 → -て/adj-i | ておく→て; でおく→で; とく→て; どく→で; ないでおく/ないどく→ない |
| 39 | `-いる` `:1454` | v1/v1p/v5 → -て/adj-i | ている/てゐる→て; ておる→て; **てる→て (In v1p)**; …; ないでいる→ない |
| 40 | `-ふ` `:1482` | `[]` → v5 | ふ→う (歴史的仮名遣い) |
| 41 | `-き` `:1496` | `[]` → adj-i | き→い |
| 42 | `-げ` `:1510` | `[]` → adj-i | げ→い, 気→い |
| 43 | `-がる` `:1526` | v5 → adj-i | がる→い |
| 44 | `-やがる` `:1542` | v5 → v1/v5/vz/vs/vk | P |
| 45 | `-やしない` `:1573` | **adj-i** → same | P — HEAD addition |
| 46 | `-やしません` `:1607` | **-ません** → same | P — HEAD addition |
| 47 | `-え` `:1642` | `[]` → adj-i | ねえ/ねぇ→ない; {め,み}{え,ぃ}→むい; …slangs |
| 48 | `n-slang` `:1699` | `[]`/adj-i → -なさい/adj-i/-ゃ | んなさい→りなさい; らんない→られない; んない→らない; んなきゃ→… |
| 49 | `imperative negative slang` `:1716` | `[]` → v | んな→る |
| 50 | `kansai-ben negative` `:1728` | `[]`/`-た` → adj-i/-た | へん/ひん→ない; せえへん→しない; … |
| 51 | `kansai-ben -て` `:1747` | -て → -て | うて→って… |
| 52 | `kansai-ben -た` `:1775` | -た → -た | うた→った… |
| 53 | `kansai-ben -たら` `:1803` | `[]` → `[]` | うたら→ったら… |
| 54 | `kansai-ben -たり` `:1831` | `[]` → `[]` | うたり→ったり… |
| 55 | `kansai-ben -く` `:1859` | `[]` → -く | う→く, こう→かく… |
| 56 | `kansai-ben adjective -て` `:1883` | -て → -て | うて→くて… |
| 57 | `kansai-ben adjective negative` `:1907` | adj-i → adj-i | うない→くない… |

Helpers: `irregularVerbSuffixInflections` (`:43-55`) expands いく/行く/逝く/往く, godan-u specials
(こう,とう,請う,乞う,恋う,問う,訪う,宣う,曰う,給う,賜う,揺蕩う), and fu-verbs (`:27-34`).
`specialHonorificMasuInflections` (`:62-64`) = wholeWord for いらっしゃる,ござる,なさる,くださる,
おっしゃる,仰る,仰有る.

## 4. Trace, chaining, displayed process/rules

`transform(text)` (`:118-147`): worklist seeded `{text, conditions:0, trace:[]}`; for each result try
every transform whose joined `heuristic` regex (`:70-71`) matches, every rule with
`conditionsMatch(conditions, conditionsIn)` and `isInflected.test(text)`, push `deinflect(text)` with
`conditions=rule.conditionsOut` and `trace=[{transform:id, ruleIndex:j, text}, ...old]` (`:132-142`,
`_extendTrace :266-272`). **No max depth** — iterate to fixpoint; unbounded chaining is prevented only
by the **cycle guard** (a frame repeating the same `(transform,ruleIndex,text)` is logged+skipped
`:132-136`). Chain length = `trace.length`; sorting prefers the shortest chain (`translator.js:2198`,
`:2394-2403`). "Reasons" run outermost→innermost: `愛しくありませんでした` →
`['-ます','negative','-た']` (fixture `test/language/japanese-transforms.test.js:34`).

Display: `translator.js:550-553` sets `inflectionRuleChainCandidate = {source:'algorithm',
inflectionRules: trace.map(f => f.transform)}`. `getUserFacingInflectionRules` (`:153-160`) resolves
each id to `{name, description}` (unknown → `{name:id}`). Web UI `_createInflectionRuleChain`
(`display-generator.js:507-553`) → source icon (`algorithm|dictionary|both`) + `<span class="inflection">
{name}</span>` with `title=description`, separator `" "` (`templates-display.html:70-71`). Anki
`{conjugation}` joins names with `«` (`default-anki-field-templates.handlebars:338-360`).

## 5. Helpers & preprocessing pipeline

Predicates (`ext/js/language/ja/japanese.js`): `isCodePointKanji:291`, `isCodePointKana:299`,
`isCodePointJapanese:307`, `isStringEntirelyKana:318`, `isStringPartiallyJapanese:332`;
`SMALL_KANA_SET:59`; `getKanaMorae/getKanaMoraCount:406,423`. **No `isHiragana`/`isKatakana`/`isKanji`/
`isSmallKana`** at this commit; ranges `HIRAGANA_RANGE=[3040,309f]`, `KATAKANA_RANGE=[30a0,30ff]`
(`:28-30`), kanji via `CJK_IDEOGRAPH_RANGES` (`CJK-util.js:44`). Normalization:
`convertKatakanaToHiragana:441` (ー → previous vowel via `getProlongedHiragana:155`),
`convertHiraganaToKatakana:472`, `convertHalfWidthKanaToFullWidth:530`,
`convertAlphanumericToFullWidth:489`, `convertFullWidthAlphanumericToNormal:509`,
`normalizeCombiningCharacters:608`, `normalizeCJKCompatibilityCharacters:642`,
`collapseEmphaticSequences:776`.

Pipeline for `ja` (`language-descriptors.js:361-379`; processors `japanese-text-preprocessors.js`),
in order: `convertHalfWidthCharacters`(32) → `alphabeticToHiragana`(39) →
`normalizeCombiningCharacters`(79) → `normalizeCJKCompatibilityCharacters`(86) →
`normalizeRadicalCharacters` (CJK-util) → `alphanumericWidthVariants`(46) →
`convertHiraganaToKatakana`(57) → `collapseEmphaticSequences`(68) → `standardizeKanji`(93). Each
`process(str)` returns `[str, variant…]`; combos composed with rule-chain tracking and deduped
(`translator.js:576-637`), capped at `MAX_PROCESS_VARIANTS=4096` (`text-processors.js:18`).

## 6. Must-match checklist

- [ ] Conditions compile to ≤32 leaf bitflags; `subConditions` = OR of children; expressions = OR; empty = 0.
- [ ] `conditionsMatch(c,n) = c===0 || (c&n)!==0`; result `conditions` = `rule.conditionsOut` (replaced, not OR-accumulated).
- [ ] `conditionsIn:[]` fires **only** at root (current=0); `conditionsOut:[]` resets to root.
- [ ] Engine ignores `type`; only `isInflected` regex + `deinflect` matter; suffix = `slice(0,-len)+deinf`; wholeWord = constant.
- [ ] Heuristic = regex `(a)|(b)|…` of all a transform's `isInflected.source`; pure prefilter.
- [ ] Worklist BFS to fixpoint; **no depth cap**; only cycle guard skips repeated `(transform,ruleIndex,text)`.
- [ ] Trace frames `{transform, ruleIndex, text}` newest-first → `trace.map(f=>f.transform)` is outermost→innermost.
- [ ] User-facing rule = `{name, description}` by id; unknown id → `{name:id}`.
- [ ] POS matching: entry POS strings → flags via `isDictionaryForm` conditions; matched with `conditionsMatch(deinflection.conditions, posFlags)`.
- [ ] All 57 transform names and their `In→Out` condition expressions reproduced; `-やしない` (adj-i) and `-やしません` (-ません) present (HEAD).
- [ ] Japanese preprocessor order and 9 processors exactly as listed; katakana→hiragana and small-kana set semantics preserved.
- [ ] Reference behaviors: `食べさせられない`→`['causative','potential or passive','negative']`, `愛しくありませんでした`→`['-ます','negative','-た']`, `行った`→`行く` (irregular), `食べさします`→`['short causative','-ます']`.
