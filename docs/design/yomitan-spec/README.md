# Yomitan behavior spec (for the komi-kun port)

Behavioral spec of upstream Yomitan, captured to guide bringing dictionary lookup + Anki mining +
manga OCR into komi-kun. Source: `yomidevs/yomitan` @ `e7334a9` (GPL-3.0), read from a local clone.

> **Provenance / licensing.** These specs were derived by reading Yomitan's **GPL-3.0** source. They
> are a *behavioral reference*.
> - If komi-kun ships as **GPL-3.0** (current decision — the combined work), Yomitan/`hoshidicts` code
>   and rule tables may be reused directly.
> - If a part ever needs to stay **Apache-2.0** (e.g. a file contributed upstream), it must not be
>   derived from these specs' copied expression; implement from the format schemas + grammar instead.
> See `../yomitan-manga-ocr-port.md` §4.2.

| # | Spec | Covers |
|---|---|---|
| 01 | [Dictionary package format & import](01-dictionary-format.md) | ZIP layout, index.json, v1/v3 bank schemas + row layouts, import normalization, media |
| 02 | [Lookup algorithm](02-lookup-algorithm.md) | scan → prefix candidates → deinflect → DB match → 10-rule ranking, result shape, dedup/merge |
| 03 | [Japanese deinflection](03-japanese-deinflection.md) | rule model, all 57 transforms, trace/process, JA preprocessor order |
| 04 | [Language support & preprocessing](04-languages.md) | ~50-language registry, preprocessors, descriptor→transformer→lookup wiring |
| 05 | [Entry rendering / DOM contract](05-rendering.md) | structured-content catalog, `data-*`/CSS classes, pitch SVG, frequency, styles scoping |
| 06 | [Anki export](06-anki.md) | full marker catalog, card-building flow, media, duplicate scoping/overwrite |
| 07 | [Search UX semantics](07-search-ux.md) | query syntax, kanji search, recursive lookup, frequency modes, audio |

## Batch 2 (planned)

- Exact scoring constants and `frequencyOrder` computation edge cases.
- Import edge cases + the Yomitan test fixtures as a conformance oracle (download a
  `test/`-derived corpus of inflected→expected-headword pairs).
- Options/settings surface that affects behavior (defaults that a port must mirror).
- Performance/indexing notes (query planning, index shapes) for a native engine.
- `.mokuro` format cross-reference (not Yomitan — for the OCR track).
