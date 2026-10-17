# Yomitan backup → Yomitan dictionary ZIP converter

`dexie_to_yomitan.py` turns a **Yomitan Dexie/IndexedDB backup** (`yomitan-dictionaries-*.json`,
e.g. the user's 2.3 GB `yomitan-dictionaries-2024-04-18-14-20-10.json`) into **standard Yomitan
dictionary ZIPs** that komi-kun's dictionary engine
([`hoshidicts`](../../core/dictionary/)) can import **through the app's existing
"Import dictionary" screen**. It is a pure dev tool: **no Kotlin/Android changes**, no Gradle.

Why it is needed: a Yomitan backup is *not* a term bank. It is Dexie's `export()` of Yomitan's
IndexedDB — the raw contents of the `dict` object stores, with all 25 dictionaries mixed into
one file. Importing it directly is impossible; the original per-dictionary ZIPs may be long gone.
This tool reverses Yomitan's
[`dictionary-importer.js`](https://github.com/yomidevs/yomitan/blob/master/ext/js/dictionary/dictionary-importer.js)
and rebuilds the banks, so the backup can be re-imported on the tablet without the original
downloads.

## Recommended flow

```
# On a computer (needs the 2.3 GB backup + a few GB of scratch space):
python3 tools/yomitan-import/dexie_to_yomitan.py \
    /path/to/yomitan-dictionaries-2024-04-18-14-20-10.json --all --out ./zips

# Copy ./zips/*.zip to the tablet, then in komi-kun:
#   More → Settings → Dictionary → Import dictionary → pick the ZIPs
```

The existing screen (`SettingsDictionaryScreen`) probes each dictionary's entry types, copies it
into the right `dictionaries/{term,frequency,pitch,kanji}/<title>/` directory, appends it to the
active profile and deletes its temp copy. Nothing new to build — this flow is proven end to end.

## Python vs Node

The other dev tools under `tools/` are Node (`tools/yomitan-corpus/*.mjs`), but this tool is
**Python on purpose**:

- The working, **host-verified proof of concept** is Python + [`ijson`](https://pypi.org/project/ijson/)
  (a streaming pull parser with a C/yajl2 backend). Reusing it means the proven row-decoding,
  bank-writing and interleaving logic stays identical to what was validated against the real
  backup.
- Node has no equivalent *bundled* pull parser: `JSON.parse` cannot touch a 2.3 GB file, and
  streaming alternatives (`JSONStream`, `stream-json`, `clarinet`) are extra dependencies with
  different parsing semantics — a rewrite with new failure modes for no benefit.
- The tool is offline-only and imports no GPL code, so the language choice is free; reuse wins.

## Dependency

```
pip install -r tools/yomitan-import/requirements.txt   # ijson==3.6.0
```

`ijson` is pinned. Its C backend (`yajl2_c`) is built automatically on install when a compiler is
available; if only the pure-Python backend is present the tool still works but prints a warning
and is far slower. The tool streams **one row at a time** and never loads the backup into memory.

## Decoded Dexie schema

Top level is `{formatName:"dexie", formatVersion:1, data:{…}}`. Two structures matter:

- **`data.tables`** is only a *manifest* (`name`, `schema`, `rowCount`). **The rows are not
  here.**
- **`data.data[]`** is an array of 7 table objects `{tableName, inbound, rows[]}`. Rows are
  streamed with `ijson.items(f, "data.data.item.rows.item")`.

Two row encodings, keyed on the store's primary key:

| store | Dexie schema | row form |
|---|---|---|
| `dictionaries`, `kanji`, `kanjiMeta`, `tagMeta`, `termMeta` | anonymous `++` PK | `{"$":[pk, value], "$types":{…}}` |
| `media`, `terms` | named PK `id` (`++id,…`) | flat `value` with `id` inline |

`$types` is Dexie bookkeeping (only `arrayNonindexKeys` and `arraybuffer` occur here) and is
dropped after decoding.

## Row → bank mapping (the reverse of `dictionary-importer.js`)

| store | bank file | bank entry |
|---|---|---|
| `terms` | `term_bank_N.json` (≤ 10000 entries) | `[expression, reading, definitionTags, rules, score, glossary, sequence, termTags]` |
| `termMeta` | `term_meta_bank_N.json` | `[expression, mode, data]` |
| `kanji` | `kanji_bank_N.json` | `[character, onyomi, kunyomi, tags, meanings, stats]` |
| `kanjiMeta` | `kanji_meta_bank_N.json` | `[character, mode, data]` |
| `tagMeta` | `tag_bank_N.json` | `[name, category, order, notes, score]` |
| `media` | file at `path` | raw bytes = base64-decode(`content`) |
| `dictionaries` | `index.json` | `{title, revision, format:version, sequenced, author, url, …}` |

The DB's `glossary` array is byte-for-byte the bank glossary format (strings,
`{type:"image",…}`, `{type:"structured-content",…}`), so it is passed through untouched. Banks
are always emitted in **format 3** shape (`sequence`, `termTags`, `stats` defaulted) so
`hoshidicts`' fixed-size structs never misparse a legacy V1 import.

## Usage

```
python3 tools/yomitan-import/dexie_to_yomitan.py <backup.json> --out <dir> (--all | --title T …)
python3 tools/yomitan-import/dexie_to_yomitan.py <backup.json> --list
```

| Flag | Meaning |
|---|---|
| `--all` | export every dictionary in the backup |
| `--title T` | export only dictionary `T` (repeatable) |
| `--list` | print every title + row counts and exit (reads only the manifest; instant) |
| `--out DIR` | directory for the per-dictionary ZIPs (all writes stay inside it) |
| `--force` | overwrite ZIPs that already exist |
| `--no-media` | skip the (large) media blobs |
| `--no-recover` | do not recover rows whose `dictionary` field is U+FFFD-corrupted |
| `--bank-size N` | entries per `*_bank_N.json` (default 10000) |
| `--quiet` | suppress progress and warnings |

**Progress & resumability.** While streaming, a status line is printed every ~5 s; each dictionary
is reported (`started: …` / `exported: …` with its row counts and ZIP size). A run is **resumable**:
a dictionary whose `<title>.zip` already exists is skipped (`--force` to redo). ZIPs are written to
`<title>.zip.part` and atomically renamed, so a `*.zip` on disk always means *complete* — a crashed
run leaves no half-ZIP to skip. Scratch banks live in `<out>/.tmp/` and are removed afterwards.

**Exit codes:** `0` success, `1` unexpected internal error, `2` usage error, `3` input problem
(missing/unreadable, not a Dexie backup, unparseable, output-name collision), `4` nothing to do
(no requested dictionary found).

## Correctness hazards handled (and tested)

1. **Rows live in `data.data[].rows[]`, not `data.tables`.** The reader streams
   `data.data.item.rows.item` and a cheap prefix sniff verifies `"formatName":"dexie"` first.
2. **Two row encodings.** Anonymous-`++`-PK rows are unwrapped from `{"$":[pk,value]}`; named-`id`
   rows are used flat; `$types` is dropped. Both are exercised by the fixture.
3. **`terms` rows are interleaved across dictionaries** (94 transitions in the real dump). Rows are
   **routed by their own `dictionary` field** to a writer held open for the whole stream; a
   dictionary is never finalised when another appears. The fixture interleaves Alpha/Beta and
   asserts each ZIP gets exactly its own terms in source order.
4. **`media.content` is a base64 blob** decoded to the real file at `media.path`. Paths are
   normalised and constrained to the dictionary directory (no `..`, absolute paths or NUL); the
   fixture includes a `../evil.webp` row that must be refused.
5. **`kanji` stores meanings under `meanings`** (Yomitan's name for the 5th bank slot). Asserted.
6. **No recoverable `styles.css`** in a 2024-era backup. Absence is tolerated: an empty-but-valid
   placeholder stylesheet (a CSS comment explaining this) is written so the ZIP stays
   well-formed. Restoring the stylesheet needs the original dictionary ZIP.
7. **U+FFFD-corrupted `dictionary` fields** *(found while validating this tool, not in the PoC)*.
   The 2024 backup is valid UTF-8 but literally contains 1,058 `U+FFFD` characters; in ~40 `terms`
   rows the title is corrupted (e.g. `デジタル大辞泉` → `デジタル��辞泉`), so those rows match no
   known title. The tool anchors the literal parts and treats each U+FFFD run as a wildcard, but
   accepts a match **only when it is unique**, re-routing the row to the right dictionary. On this
   backup it recovers 39 of the 40 corrupted rows; anything ambiguous or unmatchable is warned and
   skipped, never mis-filed. Verified: `新明解国語辞典　第七版` exports **83080** terms, exactly its
   manifest count.

## Known limitations (engine-side; not fixed here)

- **`tag_bank` is silently dropped** by `hoshidicts` (`importer.cpp` collects `tag_banks` but never
  writes them), so Yomitan tag colours/order/notes are not shown. Cosmetic.
- **`sequence` is parsed but not stored** by `hoshidicts`, so Yomitan's "merge by sequence" has no
  effect. Cosmetic.
- **Only the dictionary backup is converted.** Importing Yomitan *settings* into komi-kun profiles,
  and on-device conversion, are separate future phases and are out of scope.
- **Schema drift.** The reader keys off store names and row shapes, not `databaseVersion`; unknown
  stores/`$types` are skipped gracefully, but a future Yomitan schema change may need a new mapping.

## Measured reference run (whole backup)

Single-threaded, on the host that produced the proof of concept:

| metric | value |
|---|---|
| streaming JSON pass (C/yajl2) | 15.6 s |
| full convert + base64 media + deflate | **48.3 s** |
| output | 25 ZIPs = **693 MB** (largest デジタル大辞泉) |
| `hoshidicts`-imported tree | **1.3 GB** (`media.bin` 470 MB) |
| peak extra scratch while converting | ~2.1 GB (temp banks/media + ZIPs) |

Bounded re-validation of *this* productionised tool against the same 2.3 GB backup:

| run | result |
|---|---|
| `--list` | 0.05 s (stops after the manifest) |
| `--title 岩波国語辞典　第八版 --title 新明解四字熟語辞典` | 17.7 s stream / 18.1 s wall; 2 ZIPs = 8.1 MB; media 308/308 |
| `--title 新明解国語辞典　第七版` | 16.2 s stream; terms = 83080 (== manifest), 39 corrupted rows recovered |

Import the ZIPs with **More → Settings → Dictionary → Import dictionary**.

## Tests

Self-contained: builds a tiny synthetic Dexie backup (both row encodings, interleaved `terms`,
a base64 media row, a path-traversal row, a U+FFFD-corrupted title) in a temp dir, runs the
converter as a subprocess and asserts the emitted banks — **no 2.3 GB file and no network needed**.

```bash
tools/yomitan-import/run_tests.sh          # bootstraps a throwaway venv, then runs unittest
# or, with ijson already importable:
python3 -m unittest -v tools/yomitan-import/test_dexie_to_yomitan
```

## License

`Apache-2.0` (SPDX headers in each file). The tool reimplements the *format* of Yomitan's
importer and imports no GPL code.
