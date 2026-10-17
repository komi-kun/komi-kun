#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Convert a Yomitan Dexie dictionary backup into standard Yomitan dictionary ZIP(s).

A Yomitan backup (`yomitan-dictionaries-*.json`) is *not* a term bank: it is Dexie's
`export()` of Yomitan's IndexedDB, i.e. the raw contents of the `dict` object stores. This
tool reverses Yomitan's `dictionary-importer.js` and rebuilds, per dictionary, the banks that
`hoshidicts` / komi-kun expect inside a normal Yomitan dictionary ZIP:

    index.json
    styles.css                      (verbatim if the backup has it, else a placeholder)
    term_bank_N.json                [expression, reading, definitionTags, rules, score,
                                     glossary, sequence, termTags]
    term_meta_bank_N.json           [expression, mode, data]
    kanji_bank_N.json               [character, onyomi, kunyomi, tags, meanings, stats]
    kanji_meta_bank_N.json          [character, mode, data]
    tag_bank_N.json                 [name, category, order, notes, score]
    <media files>                   decoded from the `media` table (base64 `content`)

The output ZIPs are fed to the *existing* komi-kun flow: More -> Settings -> Dictionary ->
Import dictionary. This tool makes no Kotlin/Android changes.

Streaming: the backup is read once with a streaming pull parser (`ijson`, C backend when
available) at O(row) memory; rows are never accumulated. Banks and media are written to a
scratch tree under the output directory and zipped dictionary by dictionary.

Robustness: `terms` rows are interleaved across dictionaries and are routed by their own
`dictionary` field to a writer held open for the whole stream; a few rows in real backups have
a U+FFFD-corrupted `dictionary` field and are recovered to the unique matching title
(`--no-recover` disables this).

Usage:
    python3 dexie_to_yomitan.py <backup.json> --all --out <dir>
    python3 dexie_to_yomitan.py <backup.json> --title JMdict+ --title KANJIDIC+ --out <dir>
    python3 dexie_to_yomitan.py <backup.json> --list

Exit codes:
    0  success (at least one dictionary exported or already present)
    1  unexpected internal error
    2  usage error (bad arguments)
    3  input problem (missing/unreadable file, not a Dexie backup, unparseable, name collision)
    4  nothing to do (no requested dictionary found in the backup)
"""
from __future__ import annotations

import argparse
import base64
import binascii
import json
import os
import re
import shutil
import sys
import time
import zipfile

import ijson

BANK_SIZE = 10000
TMP_DIRNAME = ".tmp"
ZIP_LEVEL = 6

EXIT_OK = 0
EXIT_INTERNAL = 1
EXIT_USAGE = 2
EXIT_INPUT = 3
EXIT_NOTHING = 4


class BackupError(Exception):
    """The backup cannot be converted as-is (e.g. two titles collide on disk)."""


# Written when the backup carries no `styles` (this 2024 dump predates Yomitan persisting
# dictionary CSS, 2024-06). A CSS comment keeps styles.css valid and self-documenting.
STYLE_PLACEHOLDER = (
    "/* styles.css was not recoverable from this Yomitan backup.\n"
    "   It predates Yomitan persisting dictionary CSS. Rendering falls back to base.css;\n"
    "   re-importing the original dictionary ZIP restores the stylesheet.\n"
    "   See tools/yomitan-import/README.md. */\n"
)

INDEX_KEYS = (
    "sequenced",
    "author",
    "url",
    "description",
    "attribution",
    "frequencyMode",
    "sourceLanguage",
    "targetLanguage",
    "minimumYomitanVersion",
    "isUpdatable",
    "indexUrl",
    "downloadUrl",
)

_ROW_PATH = "data.data.item.rows.item"
_DEXIE_RE = re.compile(rb'"formatName"\s*:\s*"dexie"')
_UNSAFE_NAME_RE = re.compile(r"[\\/\x00-\x1f\x7f]")
_FFFD_RUN_RE = re.compile("\ufffd+")
_REPLACEMENT_CHAR = "\ufffd"


def warn(msg: str, quiet: bool = False) -> None:
    if not quiet:
        print(f"warning: {msg}", file=sys.stderr)


def sniff_dexie(path: str) -> bool:
    """Cheap check that the file is a Dexie export, before the expensive stream."""
    with open(path, "rb") as fh:
        head = fh.read(65536)
    return _DEXIE_RE.search(head) is not None


def sanitize_component(name: str, fallback: str = "dictionary") -> str:
    """Make `name` safe as a single path component (also used for the zip filename)."""
    safe = _UNSAFE_NAME_RE.sub("_", str(name)).strip().strip(".").strip()
    if not safe:
        safe = fallback
    if len(safe) > 200:
        safe = safe[:200].rstrip()
    return safe


def safe_media_relpath(path: str) -> str | None:
    """Normalise a media path to a relative path, or None if it would escape the zip."""
    text = str(path).replace("\\", "/")
    if "\x00" in text:
        return None
    parts: list[str] = []
    for segment in text.split("/"):
        if segment in ("", "."):
            continue
        if segment == "..":
            return None
        parts.append(segment)
    if not parts:
        return None
    return "/".join(parts)


def is_within(path: str, parent: str) -> bool:
    resolved = os.path.realpath(path)
    parent_resolved = os.path.realpath(parent)
    try:
        return os.path.commonpath([resolved, parent_resolved]) == parent_resolved
    except ValueError:  # different drives on Windows
        return False


def decode_row(row: dict) -> tuple[dict, str]:
    """Undo Dexie's two row encodings.

    Anonymous-`++`-PK stores (`dictionaries`, `kanji`, `kanjiMeta`, `tagMeta`, `termMeta`)
    serialise as `{"$": [pk, value], "$types": {...}}`; named-`id` stores (`terms`, `media`)
    serialise flat with the value inline. `$types` is Dexie bookkeeping and is dropped.
    """
    primary_key = row.get("$")
    if isinstance(primary_key, list) and len(primary_key) == 2:
        return primary_key[1], "anonymous"
    row.pop("$types", None)
    return row, "named"


def recover_title(corrupted: str, known_titles) -> str | None:
    """Match a `dictionary` field corrupted with U+FFFD to exactly one known title.

    Some rows in real backups carry a `dictionary` value in which one or more characters were
    replaced by U+FFFD (this 2024 dump has ~40 such `terms` rows). The literal (non-U+FFFD)
    parts are anchored; each run of replacement characters becomes a `.+?` wildcard. Only a
    *unique* match against a known title is accepted, so a row is never mis-routed.
    """
    if _REPLACEMENT_CHAR not in corrupted:
        return None
    pattern = ".+?".join(re.escape(part) for part in _FFFD_RUN_RE.split(corrupted))
    matches = [title for title in known_titles if re.fullmatch(pattern, title)]
    return matches[0] if len(matches) == 1 else None


def classify(value: dict) -> str | None:
    """Infer the source store from a row's shape.

    The `dictionaries` manifest is read first in the real export; everything else is routed
    by this shape test so the interleaved `terms` sequence is handled without buffering.
    """
    if "title" in value and ("counts" in value or "revision" in value):
        return "dictionaries"
    if "glossary" in value:
        return "terms"
    if "content" in value and "path" in value:
        return "media"
    if "character" in value and "mode" in value:
        return "kanjiMeta"
    if "character" in value:
        return "kanji"
    if "expression" in value and "mode" in value:
        return "termMeta"
    if "name" in value and "category" in value:
        return "tagMeta"
    return None


class BankWriter:
    """One numbered `*_bank_N.json` file, appended to as a JSON array."""

    def __init__(self, out_dir: str, prefix: str, bank_size: int):
        self.out_dir = out_dir
        self.prefix = prefix
        self.bank_size = max(1, bank_size)
        self.index = 0
        self.count = 0
        self.total = 0
        self._fh = None

    def _open(self) -> None:
        self.index += 1
        path = os.path.join(self.out_dir, f"{self.prefix}_{self.index}.json")
        self._fh = open(path, "w", encoding="utf-8")
        self._fh.write("[")

    def add(self, entry) -> None:
        if self._fh is None:
            self._open()
        assert self._fh is not None
        self._fh.write((",\n" if self.count else "\n") + json.dumps(entry, ensure_ascii=False, separators=(",", ":")))
        self.count += 1
        self.total += 1
        if self.count >= self.bank_size:
            self.close()  # next add() opens a new bank

    def close(self) -> None:
        if self._fh is not None:
            self._fh.write("\n]\n")
            self._fh.close()
            self._fh = None
            self.count = 0

    @property
    def files(self) -> int:
        return self.index


class DictWriter:
    """Accumulates one dictionary's banks/media and zips them once the stream is done."""

    def __init__(self, root: str, name: str, info: dict, bank_size: int, include_media: bool):
        self.title = info["title"]
        self.name = name  # sanitised, used for the zip filename
        self.info = info
        self.dir = os.path.join(root, name)
        os.makedirs(self.dir, exist_ok=True)
        self.include_media = include_media
        self.terms = BankWriter(self.dir, "term_bank", bank_size)
        self.term_meta = BankWriter(self.dir, "term_meta_bank", bank_size)
        self.kanji = BankWriter(self.dir, "kanji_bank", bank_size)
        self.kanji_meta = BankWriter(self.dir, "kanji_meta_bank", bank_size)
        self.tags = BankWriter(self.dir, "tag_bank", bank_size)
        self.media_count = 0
        self.media_skipped = 0

    def _banks(self) -> tuple[BankWriter, ...]:
        return (self.terms, self.term_meta, self.kanji, self.kanji_meta, self.tags)

    def add_term(self, value: dict) -> None:
        self.terms.add([
            value["expression"],
            value.get("reading", ""),
            value.get("definitionTags") or "",
            value.get("rules", ""),
            value.get("score", 0),
            value.get("glossary", []),
            value.get("sequence", 0),
            value.get("termTags") or "",
        ])

    def add_term_meta(self, value: dict) -> None:
        self.term_meta.add([value["expression"], value["mode"], value.get("data")])

    def add_kanji(self, value: dict) -> None:
        # Yomitan names the 5th bank element `definitions` but stores it as `meanings`.
        self.kanji.add([
            value["character"],
            value.get("onyomi", ""),
            value.get("kunyomi", ""),
            value.get("tags", ""),
            value.get("meanings", []),
            value.get("stats", {}),
        ])

    def add_kanji_meta(self, value: dict) -> None:
        self.kanji_meta.add([value["character"], value["mode"], value.get("data")])

    def add_tag(self, value: dict) -> None:
        self.tags.add([value["name"], value.get("category", ""), value.get("order", 0), value.get("notes", ""), value.get("score", 0)])

    def add_media(self, value: dict) -> None:
        if not self.include_media:
            return
        rel = safe_media_relpath(value.get("path", ""))
        content = value.get("content")
        if rel is None or content is None:
            self.media_skipped += 1
            return
        try:
            raw = base64.b64decode(content, validate=True)
        except (binascii.Error, ValueError):
            self.media_skipped += 1
            return
        dest = os.path.join(self.dir, rel)
        if not is_within(dest, self.dir):
            self.media_skipped += 1
            return
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, "wb") as fh:
            fh.write(raw)
        self.media_count += 1

    def _write_metadata(self) -> None:
        index = {"title": self.title, "revision": self.info.get("revision", "")}
        version = self.info.get("version")
        if version is not None:
            index["format"] = version
        for key in INDEX_KEYS:
            if key in self.info:
                index[key] = self.info[key]
        with open(os.path.join(self.dir, "index.json"), "w", encoding="utf-8") as fh:
            json.dump(index, fh, ensure_ascii=False, indent=1)
            fh.write("\n")

        styles = self.info.get("styles")
        with open(os.path.join(self.dir, "styles.css"), "w", encoding="utf-8") as fh:
            fh.write(styles if styles else STYLE_PLACEHOLDER)

    def finish(self, out_dir: str) -> tuple[str, int]:
        for writer in self._banks():
            writer.close()
        self._write_metadata()

        zip_path = os.path.join(out_dir, f"{self.name}.zip")
        part_path = f"{zip_path}.part"
        with zipfile.ZipFile(part_path, "w", zipfile.ZIP_DEFLATED, compresslevel=ZIP_LEVEL) as archive:
            for base, dirs, files in os.walk(self.dir):
                dirs.sort()
                for filename in sorted(files):
                    full = os.path.join(base, filename)
                    archive.write(full, os.path.relpath(full, self.dir))
        os.replace(part_path, zip_path)  # atomic: a *.zip only ever means "complete"
        return zip_path, os.path.getsize(zip_path)

    def stats(self) -> str:
        return (
            f"terms={self.terms.total} termMeta={self.term_meta.total} kanji={self.kanji.total} "
            f"kanjiMeta={self.kanji_meta.total} tags={self.tags.total} "
            f"media={self.media_count}"
            + (f" media_skipped={self.media_skipped}" if self.media_skipped else "")
        )


def iter_rows(stream):
    """Yield decoded `(value, encoding)` for every row under `data.data[].rows[]`.

    Reads `data.data.item.rows.item` (not `data.tables`, which is only a manifest) so the
    multi-GB file is never materialised.
    """
    for row in ijson.items(stream, _ROW_PATH, use_float=True):
        value, encoding = decode_row(row)
        yield value, encoding


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="dexie_to_yomitan.py",
        description="Convert a Yomitan Dexie dictionary backup into Yomitan dictionary ZIPs.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("backup", help="path to yomitan-dictionaries-*.json")
    parser.add_argument("--out", required=False, help="output directory for the per-dictionary ZIPs")
    parser.add_argument("--all", action="store_true", help="export every dictionary in the backup")
    parser.add_argument("--title", action="append", default=[], metavar="TITLE", help="dictionary title to export (repeatable)")
    parser.add_argument("--list", action="store_true", help="list dictionary titles and exit")
    parser.add_argument("--force", action="store_true", help="overwrite ZIPs that already exist")
    parser.add_argument("--no-media", action="store_true", help="skip the (large) media blobs")
    parser.add_argument("--no-recover", action="store_true", help="do not recover rows whose `dictionary` field is U+FFFD-corrupted")
    parser.add_argument("--bank-size", type=int, default=BANK_SIZE, help=f"entries per *_bank_N.json (default {BANK_SIZE})")
    parser.add_argument("--quiet", action="store_true", help="suppress progress and warnings")
    args = parser.parse_args(argv)

    if not args.list:
        if not args.out:
            parser.error("--out is required (except with --list)")
        if not args.all and not args.title:
            parser.error("select dictionaries with --all or --title (or use --list)")
    return args


def stream_dictionaries(stream) -> dict[str, dict]:
    """`--list` fast path: read the `dictionaries` manifest (the first table) and stop."""
    infos: dict[str, dict] = {}
    for value, _encoding in iter_rows(stream):
        if classify(value) != "dictionaries":
            if infos:
                break
            continue
        infos[value["title"]] = value
    for title, info in infos.items():
        counts = info.get("counts") or {}
        summary = " ".join(f"{key}={counts.get(key, {}).get('total', '?')}" for key in ("terms", "termMeta", "kanji", "kanjiMeta", "tagMeta", "media"))
        print(f"{title}\t{summary}")
    return infos


def convert(stream, args, quiet: bool) -> tuple[int, list[tuple[str, str, int]]]:
    wanted = set(args.title)
    tmp_root = os.path.join(args.out, TMP_DIRNAME)
    if os.path.isdir(tmp_root):
        shutil.rmtree(tmp_root)  # a crashed run's partial banks must not be reused
    os.makedirs(tmp_root, exist_ok=True)

    writers: dict[str, DictWriter] = {}
    infos: dict[str, dict] = {}
    zip_names: dict[str, str] = {}  # sanitised name -> title
    exported: list[tuple[str, str, int]] = []
    skipped: list[str] = []
    orphan_rows: dict[str, int] = {}
    recovered_titles: dict[str, str] = {}  # corrupted field -> resolved title
    recovered_rows = 0

    stream_start = time.time()
    last_tick = stream_start
    rows = 0

    try:
        for value, _encoding in iter_rows(stream):
            rows += 1
            table = classify(value)
            if table is None:
                continue

            if table == "dictionaries":
                title = value["title"]
                if title in infos:
                    warn(f"duplicate dictionary title in backup: {title!r}", quiet)
                infos[title] = value
                if not (args.all or title in wanted):
                    continue
                name = sanitize_component(title)
                other = zip_names.get(name)
                if other is not None and other != title:
                    raise BackupError(f"dictionaries {other!r} and {title!r} map to the same output name {name!r}; export them individually with --title")
                zip_names[name] = title
                zip_path = os.path.join(args.out, f"{name}.zip")
                if os.path.exists(zip_path) and not args.force:
                    skipped.append(title)
                else:
                    writers[title] = DictWriter(tmp_root, name, value, args.bank_size, not args.no_media)
                    if not quiet:
                        print(f"started: {title}", file=sys.stderr)
                continue

            # Everything below is routed by the row's own `dictionary` field; writers stay
            # open across the whole stream because `terms` rows are interleaved.
            title = value.get("dictionary")
            if title is None:
                continue
            if title not in infos:
                resolved = None if args.no_recover else recover_title(title, infos)
                if resolved is None:
                    orphan_rows[title] = orphan_rows.get(title, 0) + 1
                    continue
                recovered_titles[title] = resolved
                recovered_rows += 1
                title = resolved
            writer = writers.get(title)
            if writer is None:
                continue  # known dictionary that was not selected for export
            if table == "terms":
                writer.add_term(value)
            elif table == "termMeta":
                writer.add_term_meta(value)
            elif table == "kanji":
                writer.add_kanji(value)
            elif table == "kanjiMeta":
                writer.add_kanji_meta(value)
            elif table == "tagMeta":
                writer.add_tag(value)
            elif table == "media":
                writer.add_media(value)

            now = time.time()
            if not quiet and now - last_tick >= 5.0:
                try:
                    pos = stream.tell()
                except (OSError, ValueError):
                    pos = 0
                print(f"  ... {rows:,} rows, {pos / 1e6:.0f} MB read, {len(writers)} dictionaries open", file=sys.stderr)
                last_tick = now

        if not quiet:
            print(f"streamed {rows:,} rows in {time.time() - stream_start:.1f}s", file=sys.stderr)

        for title, writer in writers.items():
            zip_path, size = writer.finish(args.out)
            exported.append((title, zip_path, size))
            if not quiet:
                print(f"exported: {title}\n  {writer.stats()}\n  zip: {zip_path} ({size / 1e6:.1f} MB)")
    finally:
        shutil.rmtree(tmp_root, ignore_errors=True)

    for title, count in sorted(orphan_rows.items()):
        warn(f"{count} rows reference unknown dictionary {title!r} (ignored)", quiet)
    if recovered_rows:
        for corrupted, resolved in sorted(recovered_titles.items()):
            warn(f"recovered {corrupted!r} -> {resolved!r} (U+FFFD-corrupted dictionary field)", quiet)
        if not quiet:
            print(f"recovered {recovered_rows} rows from {len(recovered_titles)} corrupted title(s)", file=sys.stderr)
    for title in skipped:
        if not quiet:
            print(f"skipped (already exists): {title}")

    missing = wanted - set(infos)
    for title in sorted(missing):
        warn(f"requested dictionary not found in backup: {title!r}", quiet)

    if not exported and not skipped:
        return EXIT_NOTHING, exported
    return EXIT_OK, exported


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])
    quiet = args.quiet

    if not os.path.isfile(args.backup):
        print(f"error: no such file: {args.backup}", file=sys.stderr)
        return EXIT_INPUT
    try:
        if not sniff_dexie(args.backup):
            print(
                f"error: {args.backup} does not look like a Yomitan Dexie backup "
                '(expected "formatName":"dexie" near the start)',
                file=sys.stderr,
            )
            return EXIT_INPUT
    except OSError as exc:
        print(f"error: cannot read {args.backup}: {exc}", file=sys.stderr)
        return EXIT_INPUT

    if not quiet and getattr(ijson, "backend", "unknown") in ("python", "unknown"):
        warn("ijson is using the pure-Python backend; conversion will be slow (install a C backend)", quiet)

    started = time.time()
    try:
        with open(args.backup, "rb") as stream:
            if args.list:
                stream_dictionaries(stream)
                return EXIT_OK
            os.makedirs(args.out, exist_ok=True)
            code, exported = convert(stream, args, quiet)
    except BackupError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_INPUT
    except (OSError, ijson.JSONError, ValueError) as exc:
        print(f"error: failed to read/parse the backup: {exc}", file=sys.stderr)
        return EXIT_INPUT

    if not quiet:
        total = sum(size for _t, _p, size in exported)
        print(f"\ndone: {len(exported)} exported, {total / 1e6:.1f} MB of ZIPs in {args.out} ({time.time() - started:.1f}s)")
    return code


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("interrupted", file=sys.stderr)
        sys.exit(130)
