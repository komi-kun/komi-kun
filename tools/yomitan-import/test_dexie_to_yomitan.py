#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Self-contained tests for dexie_to_yomitan.py.

Builds a tiny synthetic Dexie backup (the exact `data.data[].rows[]` shape, both row
encodings, an interleaved `terms` sequence and a base64 media row) in a temporary directory,
runs the converter as a subprocess and asserts the emitted banks. Needs no real backup and no
network; only `ijson` (see requirements.txt).

Run:  ./run_tests.sh          (bootstraps a venv)
  or: python3 -m unittest test_dexie_to_yomitan -v   (with ijson importable)
"""
from __future__ import annotations

import base64
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from time import sleep

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "dexie_to_yomitan.py")

ALPHA_STYLES = ".alpha{color:red}"


def anon(pk: int, value: dict) -> dict:
    """Encode an anonymous-`++`-PK store row (`{"$":[pk,value],"$types":{...}}`)."""
    return {"$": [pk, value], "$types": {"$": {"": "arrayNonindexKeys"}}}


def flat(value: dict, extra_types: dict | None = None) -> dict:
    """Encode a named-`id`-PK store row (flat value with `$types` appended)."""
    if extra_types:
        value = {**value, "$types": extra_types}
    return value


def build_fixture(path: str) -> None:
    """Write a synthetic Dexie backup with three dictionaries and all seven stores."""
    dictionaries = [
        # Alpha: has a stylesheet (passthrough), terms/kanji/media/meta.
        anon(1, {
            "title": "Alpha",
            "revision": "rev-alpha",
            "version": 3,
            "sequenced": True,
            "author": "A. Author",
            "styles": ALPHA_STYLES,
            "importDate": 1683649417510,
            "prefixWildcardsSupported": False,
            "counts": {"terms": {"total": 2}, "kanji": {"total": 1}, "media": {"total": 2}},
        }),
        # Beta: no stylesheet (placeholder path), terms/tag/media.
        anon(2, {
            "title": "Beta",
            "revision": "rev-beta",
            "version": 3,
            "counts": {"terms": {"total": 2}, "tagMeta": {"total": 1}, "media": {"total": 1}},
        }),
        # Gamma: no rows at all.
        anon(3, {"title": "Gamma", "revision": "rev-gamma", "version": 3, "counts": {}}),
        # Delta: its single term row has a U+FFFD-corrupted `dictionary` field.
        anon(4, {"title": "Delta", "revision": "rev-delta", "version": 3, "counts": {"terms": {"total": 1}}}),
    ]

    # `terms` is deliberately interleaved across dictionaries (hazard 3).
    terms = [
        flat({"expression": "あ", "reading": "あ", "definitionTags": "n", "rules": "", "score": 1,
              "glossary": ["alpha one"], "sequence": 10, "termTags": "", "dictionary": "Alpha", "id": 1},
             {"glossary": "arrayNonindexKeys"}),
        flat({"expression": "い", "reading": "い", "definitionTags": "", "rules": "v5", "score": 2,
              "glossary": ["beta one"], "sequence": 20, "dictionary": "Beta", "id": 2}),
        flat({"expression": "う", "reading": "う", "definitionTags": "", "rules": "", "score": 3,
              "glossary": ["alpha two"], "sequence": 11, "dictionary": "Alpha", "id": 3}),
        flat({"expression": "え", "reading": "え", "definitionTags": "", "rules": "", "score": 4,
              "glossary": ["beta two"], "sequence": 21, "dictionary": "Beta", "id": 4}),
        # Delta's term: `dictionary` corrupted with U+FFFD -> must recover to "Delta".
        flat({"expression": "お", "reading": "お", "definitionTags": "", "rules": "", "score": 5,
              "glossary": ["delta one"], "sequence": 30, "dictionary": "De\ufffdta", "id": 5}),
        # Unrecoverable orphan (ambiguous: `.+?` matches every title) -> ignored.
        flat({"expression": "か", "reading": "か", "definitionTags": "", "rules": "", "score": 6,
              "glossary": ["orphan"], "sequence": 0, "dictionary": "\ufffd", "id": 6}),
    ]

    term_meta = [
        anon(1, {"expression": "あ", "mode": "pitch",
                 "data": {"reading": "あ", "pitches": [{"position": 1}]}, "dictionary": "Alpha"}),
        anon(2, {"expression": "い", "mode": "freq", "data": 1234, "dictionary": "Beta"}),
    ]

    kanji = [
        anon(1, {"character": "亜", "onyomi": "ア", "kunyomi": "つ.ぐ", "tags": "jouyou",
                 "meanings": ["Asia", "rank next"], "stats": {"strokes": "7"}, "dictionary": "Alpha"}),
    ]

    kanji_meta = [
        anon(1, {"character": "亜", "mode": "freq", "data": 99, "dictionary": "Alpha"}),
    ]

    tag_meta = [
        anon(1, {"name": "N5", "category": "name", "order": -12, "notes": "JLPT N5", "score": 0,
                 "dictionary": "Beta"}),
    ]

    media = [
        flat({"dictionary": "Alpha", "path": "images/ref/x.webp", "mediaType": "image/webp",
              "width": 1, "height": 1, "content": base64.b64encode(b"WEBPBYTES").decode("ascii"), "id": 1},
             {"content": "arraybuffer"}),
        # Path traversal: must be refused, not written anywhere.
        flat({"dictionary": "Alpha", "path": "../evil.webp",
              "content": base64.b64encode(b"EVIL").decode("ascii"), "id": 2}),
        flat({"dictionary": "Beta", "path": "images/beta.png",
              "content": base64.b64encode(b"PNGDATA").decode("ascii"), "id": 3}),
    ]

    def table(name: str, inbound: bool, rows: list) -> dict:
        return {"tableName": name, "inbound": inbound, "rows": rows}

    document = {
        "formatName": "dexie",
        "formatVersion": 1,
        "data": {
            "databaseName": "dict",
            "databaseVersion": 6,
            "tables": [
                {"name": "dictionaries", "schema": "++,title,version", "rowCount": len(dictionaries)},
                {"name": "kanji", "schema": "++,character,dictionary", "rowCount": len(kanji)},
                {"name": "kanjiMeta", "schema": "++,character,dictionary", "rowCount": len(kanji_meta)},
                {"name": "media", "schema": "++id,dictionary,path", "rowCount": len(media)},
                {"name": "tagMeta", "schema": "++,dictionary,name", "rowCount": len(tag_meta)},
                {"name": "termMeta", "schema": "++,dictionary,expression", "rowCount": len(term_meta)},
                {"name": "terms", "schema": "++id,dictionary,expression", "rowCount": len(terms)},
            ],
            # Real export order: dictionaries first, then the row stores.
            "data": [
                table("dictionaries", False, dictionaries),
                table("kanji", False, kanji),
                table("kanjiMeta", False, kanji_meta),
                table("media", True, media),
                table("tagMeta", False, tag_meta),
                table("termMeta", False, term_meta),
                table("terms", True, terms),
                {"tableName": "misc", "inbound": False, "rows": [{"foo": 1}]},  # unknown store: skipped
            ],
        },
    }
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(document, fh, ensure_ascii=False)


def load_zip(path: str) -> dict[str, object]:
    """Return {member_name: parsed_json/text_or_bytes} for a zip."""
    with zipfile.ZipFile(path) as archive:
        out: dict[str, object] = {}
        for name in archive.namelist():
            raw = archive.read(name)
            if name.endswith(".json"):
                out[name] = json.loads(raw.decode("utf-8"))
            elif name.endswith(".css"):
                out[name] = raw.decode("utf-8")
            else:
                out[name] = raw
        return out


class ConverterTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.root = tempfile.mkdtemp(prefix="yomitan-import-test-")
        cls.backup = os.path.join(cls.root, "yomitan-dictionaries-test.json")
        build_fixture(cls.backup)

    @classmethod
    def tearDownClass(cls) -> None:
        shutil.rmtree(cls.root, ignore_errors=True)

    def run_convert(self, *args: str, out: str | None = None, expect: int = 0) -> subprocess.CompletedProcess:
        if out is None:
            out = os.path.join(self.root, "out-" + self.id().rsplit(".", 1)[-1])
        cmd = [sys.executable, SCRIPT, self.backup, *args, "--out", out]
        proc = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(proc.returncode, expect, msg=f"stdout:\n{proc.stdout}\nstderr:\n{proc.stderr}")
        proc.out_dir = out  # type: ignore[attr-defined]
        return proc

    def assert_no_dexie_bookkeeping(self, zf: dict[str, object]) -> None:
        for name, value in zf.items():
            if not name.endswith(".json"):
                continue
            blob = json.dumps(value, ensure_ascii=False)
            self.assertNotIn('"$types"', blob, f"$types leaked into {name}")
            self.assertNotIn('"$"', blob, f"Dexie pk wrapper leaked into {name}")

    # -- hazard 1 + 2: rows live in data.data[].rows[] and are decoded from both encodings ----
    def test_all_exports_every_dictionary(self) -> None:
        proc = self.run_convert("--all")
        out = proc.out_dir  # type: ignore[attr-defined]
        self.assertEqual(sorted(os.listdir(out)), ["Alpha.zip", "Beta.zip", "Delta.zip", "Gamma.zip"])
        self.assertIn("recovered", proc.stderr)  # U+FFFD title recovery is reported

        alpha = load_zip(os.path.join(out, "Alpha.zip"))
        self.assert_no_dexie_bookkeeping(alpha)
        # Anonymous-PK row decoded: dictionaries/kanji/kanjiMeta/termMeta all present.
        self.assertEqual(alpha["index.json"]["title"], "Alpha")
        self.assertEqual(alpha["index.json"]["format"], 3)
        self.assertEqual(alpha["index.json"]["revision"], "rev-alpha")
        self.assertEqual(alpha["index.json"]["author"], "A. Author")
        self.assertIs(alpha["index.json"]["sequenced"], True)
        self.assertNotIn("counts", alpha["index.json"])
        self.assertNotIn("importDate", alpha["index.json"])
        self.assertEqual(alpha["styles.css"], ALPHA_STYLES)
        # Hazard 3: only Alpha's two terms, in source order.
        self.assertEqual(alpha["term_bank_1.json"], [
            ["あ", "あ", "n", "", 1, ["alpha one"], 10, ""],
            ["う", "う", "", "", 3, ["alpha two"], 11, ""],
        ])
        self.assertEqual(alpha["term_meta_bank_1.json"],
                         [["あ", "pitch", {"reading": "あ", "pitches": [{"position": 1}]}]])
        # Hazard 5: `meanings` lands in the 5th bank slot.
        self.assertEqual(alpha["kanji_bank_1.json"],
                         [["亜", "ア", "つ.ぐ", "jouyou", ["Asia", "rank next"], {"strokes": "7"}]])
        self.assertEqual(alpha["kanji_meta_bank_1.json"], [["亜", "freq", 99]])
        # Hazard 4: base64 media written to its real path; the traversal row is refused.
        self.assertEqual(alpha["images/ref/x.webp"], b"WEBPBYTES")
        self.assertFalse(any("evil" in name for name in alpha))
        self.assertFalse(os.path.exists(os.path.join(self.root, "evil.webp")))
        self.assertFalse(os.path.exists(os.path.join(out, "evil.webp")))
        self.assertNotIn("tag_bank_1.json", alpha)

        beta = load_zip(os.path.join(out, "Beta.zip"))
        self.assert_no_dexie_bookkeeping(beta)
        # Hazard 6: no styles in the backup -> placeholder, present and valid.
        self.assertTrue(beta["styles.css"].startswith("/*"))
        self.assertIn("styles.css", beta["styles.css"])
        # Hazard 3: Beta got exactly its own two terms despite interleaving.
        self.assertEqual([t[0] for t in beta["term_bank_1.json"]], ["い", "え"])
        self.assertEqual(beta["tag_bank_1.json"], [["N5", "name", -12, "JLPT N5", 0]])
        self.assertEqual(beta["images/beta.png"], b"PNGDATA")

        gamma = load_zip(os.path.join(out, "Gamma.zip"))
        self.assertEqual(set(gamma), {"index.json", "styles.css"})
        self.assertEqual(gamma["index.json"]["title"], "Gamma")

        # A U+FFFD-corrupted `dictionary` field is recovered to the unique known title.
        delta = load_zip(os.path.join(out, "Delta.zip"))
        self.assertEqual([t[0] for t in delta["term_bank_1.json"]], ["お"])
        self.assertFalse(any(t[0] == "か" for t in delta["term_bank_1.json"]))  # orphan not routed here
        # Scratch tree and partial zips are gone.
        self.assertFalse(os.path.exists(os.path.join(out, ".tmp")))
        self.assertFalse(any(name.endswith(".part") for name in os.listdir(out)))

    def test_subset_by_title(self) -> None:
        proc = self.run_convert("--title", "Alpha")
        self.assertEqual(os.listdir(proc.out_dir), ["Alpha.zip"])  # type: ignore[attr-defined]

    def test_bank_rotation(self) -> None:
        proc = self.run_convert("--all", "--bank-size", "1")
        alpha = load_zip(os.path.join(proc.out_dir, "Alpha.zip"))  # type: ignore[attr-defined]
        self.assertEqual(alpha["term_bank_1.json"][0][0], "あ")
        self.assertEqual(alpha["term_bank_2.json"][0][0], "う")

    def test_no_media(self) -> None:
        proc = self.run_convert("--all", "--no-media")
        alpha = load_zip(os.path.join(proc.out_dir, "Alpha.zip"))  # type: ignore[attr-defined]
        self.assertNotIn("images/ref/x.webp", alpha)
        self.assertEqual(alpha["term_bank_1.json"][0][0], "あ")

    # -- CLI ergonomics: resumability, listing, exit codes -----------------------------------
    def test_resumable_skips_and_force_rewrites(self) -> None:
        out = os.path.join(self.root, "out-resume")
        self.run_convert("--all", out=out)
        alpha_zip = os.path.join(out, "Alpha.zip")
        before = os.path.getmtime(alpha_zip)

        second = self.run_convert("--all", out=out)
        self.assertIn("skipped", second.stdout)
        self.assertEqual(os.path.getmtime(alpha_zip), before)

        sleep(0.05)
        self.run_convert("--all", "--force", out=out)
        self.assertGreater(os.path.getmtime(alpha_zip), before)
        self.assertEqual(sorted(os.listdir(out)), ["Alpha.zip", "Beta.zip", "Delta.zip", "Gamma.zip"])

    def test_no_recover_leaves_corrupted_rows_out(self) -> None:
        proc = self.run_convert("--all", "--no-recover")
        delta = load_zip(os.path.join(proc.out_dir, "Delta.zip"))  # type: ignore[attr-defined]
        self.assertEqual(set(delta), {"index.json", "styles.css"})  # no term recovered
        self.assertIn("unknown dictionary", proc.stderr)

    def test_list_titles(self) -> None:
        proc = subprocess.run([sys.executable, SCRIPT, self.backup, "--list"],
                              capture_output=True, text=True)
        self.assertEqual(proc.returncode, 0)
        for title in ("Alpha", "Beta", "Gamma"):
            self.assertIn(title, proc.stdout)

    def test_rejects_non_dexie_input(self) -> None:
        not_dexie = os.path.join(self.root, "not-dexie.json")
        with open(not_dexie, "w", encoding="utf-8") as fh:
            json.dump({"hello": "world"}, fh)
        proc = subprocess.run([sys.executable, SCRIPT, not_dexie, "--all", "--out",
                               os.path.join(self.root, "out-bad")], capture_output=True, text=True)
        self.assertEqual(proc.returncode, 3)

    def test_missing_input(self) -> None:
        proc = subprocess.run([sys.executable, SCRIPT, os.path.join(self.root, "nope.json"),
                               "--all", "--out", os.path.join(self.root, "out-missing")],
                              capture_output=True, text=True)
        self.assertEqual(proc.returncode, 3)

    def test_usage_error(self) -> None:
        proc = subprocess.run([sys.executable, SCRIPT, self.backup], capture_output=True, text=True)
        self.assertEqual(proc.returncode, 2)

    def test_nothing_to_do(self) -> None:
        proc = subprocess.run([sys.executable, SCRIPT, self.backup, "--title", "DoesNotExist",
                               "--out", os.path.join(self.root, "out-none")],
                              capture_output=True, text=True)
        self.assertEqual(proc.returncode, 4)


if __name__ == "__main__":
    unittest.main(verbosity=2)
