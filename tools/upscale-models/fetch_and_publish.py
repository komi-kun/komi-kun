#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# komi-kun upscale model publisher (not an Android module; no GPL header rule applies to this file,
# but the release it produces carries CC BY-NC 4.0 weights, see ATTRIBUTION.md).
"""Republish the MangaJaNai / IllustrationJaNai ONNX weights as per-file release assets.

Reads `models.json` (the one source of truth for which catalog id lives in which upstream zip as
which member), downloads the upstream `v3.0.0-onnx` zips, verifies every zip and every extracted
member against upstream's `sha256sums_fp16.txt`, and writes `dist/`:

    dist/manifest.json                 the app's release manifest (see ModelDownloader's DTOs)
    dist/<memberName>.onnx             the weights, byte-identical to the upstream zip member
    dist/ATTRIBUTION.md                the CC BY-NC 4.0 attribution the licence requires
    dist/LICENSE-CC-BY-NC-4.0.txt      the full licence text of the weights
    dist/RELEASE_NOTES.md              the body for `gh release create`

`dist/` deliberately lives outside the app repository (default
`/tmp/opencode/upscale-models/dist`): the weights are CC BY-NC 4.0 and must never be committed to a
GPL-3.0 tree nor bundled into an APK.

Stdlib only. Idempotent (a re-run over an intact `dist/` downloads nothing and writes identical
bytes) and resumable (a truncated `.part` download continues with an HTTP Range request, and a
member that is already extracted with the right SHA-256 is not re-extracted).

Usage:
    python3 tools/upscale-models/fetch_and_publish.py                # cache+dist in /tmp/opencode
    python3 tools/upscale-models/fetch_and_publish.py --print-only    # table from models.json only
    python3 tools/upscale-models/fetch_and_publish.py --dist /tmp/x   # another output directory

Then publish (from `tools/upscale-models/` after a **full** run — a `--only` run suffixes every
artifact it writes with `.partial`, the `.onnx` files included, so the command below can never upload
a subset of the release; the asset list is explicit so `RELEASE_NOTES.md` — which the release body
already carries — is not uploaded as an asset):

    gh release create upscale-models-v1 -R komi-kun/komi-kun-upscale-models \
        --title "Upscale models v1" --notes-file <dist>/RELEASE_NOTES.md \
        <dist>/*.onnx <dist>/manifest.json <dist>/ATTRIBUTION.md <dist>/LICENSE-CC-BY-NC-4.0.txt

and copy the manifest into the app tree so the catalog/release drift guard keeps running:

    cp <dist>/manifest.json core/upscale/src/test/resources/upscale-manifest.json
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

DEFAULT_CACHE = Path("/tmp/opencode/upscale-models/cache")
DEFAULT_DIST = Path("/tmp/opencode/upscale-models/dist")

CHUNK = 1 << 20
USER_AGENT = "komi-kun-upscale-models/1 (+https://github.com/komi-kun/komi-kun-upscale-models)"


# --------------------------------------------------------------------------- helpers


def sha256_file(path: Path) -> str:
    """SHA-256 of `path`, lower-case hex, streamed."""
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(CHUNK), b""):
            digest.update(block)
    return digest.hexdigest()


def human_bytes(count: int) -> str:
    for unit in ("B", "KiB", "MiB", "GiB"):
        if count < 1024 or unit == "GiB":
            return f"{count:.0f} {unit}" if unit == "B" else f"{count:.1f} {unit}"
        count /= 1024.0
    raise AssertionError("unreachable")


def fail(message: str) -> None:
    """Print `message` and exit non-zero."""
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def open_url(url: str, offset: int = 0):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    if offset:
        request.add_header("Range", f"bytes={offset}-")
    return urllib.request.urlopen(request)  # noqa: S310 - fixed https URLs from models.json


def download(url: str, target: Path, expected_sha256: str) -> None:
    """Fetch `url` into `target`, resuming a `.part` when one is present, then verify the hash."""
    if target.is_file():
        actual = sha256_file(target)
        if actual == expected_sha256:
            print(f"  cached  {target.name} ({human_bytes(target.stat().st_size)})")
            return
        print(f"  stale   {target.name}: sha256 {actual[:12]} != {expected_sha256[:12]}, re-fetching")
        target.unlink()

    part = target.with_suffix(target.suffix + ".part")
    offset = part.stat().st_size if part.is_file() else 0
    try:
        with open_url(url, offset) as response:
            resuming = offset and response.status == 206
            if offset and not resuming:
                print(f"  no range support for {target.name}, restarting")
                offset = 0
            print(
                f"  get     {target.name}"
                + (f" (resuming at {human_bytes(offset)})" if resuming else ""),
            )
            with part.open("ab" if resuming else "wb") as out:
                shutil.copyfileobj(response, out, CHUNK)
    except urllib.error.URLError as error:
        fail(f"download failed: {url}: {error}")

    actual = sha256_file(part)
    if actual != expected_sha256:
        part.unlink(missing_ok=True)
        fail(f"sha256 mismatch for {target.name}: {actual} != {expected_sha256}")
    part.replace(target)
    print(f"  ok      {target.name} ({human_bytes(target.stat().st_size)})")


def extract_member(archive: Path, member: str, target: Path, expected_sha256: str) -> None:
    """Extract `member` of `archive` to `target`, verifying the member's SHA-256 before it lands."""
    if target.is_file() and sha256_file(target) == expected_sha256:
        print(f"  present {target.name} ({human_bytes(target.stat().st_size)})")
        return

    part = target.with_suffix(target.suffix + ".part")
    digest = hashlib.sha256()
    try:
        with zipfile.ZipFile(archive) as zipped:
            with zipped.open(member) as source, part.open("wb") as sink:
                for block in iter(lambda: source.read(CHUNK), b""):
                    digest.update(block)
                    sink.write(block)
    except KeyError:
        part.unlink(missing_ok=True)
        fail(f"{archive.name} has no member {member}")
    except zipfile.BadZipFile as error:
        part.unlink(missing_ok=True)
        fail(f"{archive.name} is not a readable zip: {error}")

    actual = digest.hexdigest()
    if actual != expected_sha256:
        part.unlink(missing_ok=True)
        fail(f"sha256 mismatch for {member}: {actual} != {expected_sha256}")
    part.replace(target)
    print(f"  extract {target.name} ({human_bytes(target.stat().st_size)})")


# --------------------------------------------------------------------------- spec


def load_spec(path: Path) -> dict:
    try:
        spec = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        fail(f"no model spec at {path}")
    except json.JSONDecodeError as error:
        fail(f"{path} is not valid JSON: {error}")

    models = spec.get("models") or []
    if not models:
        fail(f"{path} lists no models")
    for key in ("upstream", "release"):
        if key not in spec:
            fail(f"{path} is missing the {key!r} block")

    ids = [entry["catalogId"] for entry in models]
    names = [entry["memberName"] for entry in models]
    if len(set(ids)) != len(ids):
        fail("models.json has a duplicate catalogId")
    if len(set(names)) != len(names):
        fail("models.json has a duplicate memberName")
    for entry in models:
        for key in ("catalogId", "upstreamZip", "memberName", "scale", "family"):
            if key not in entry:
                fail(f"model {entry.get('catalogId', '?')} is missing {key!r}")
        if not entry["memberName"].endswith(".onnx"):
            fail(f"{entry['catalogId']}: memberName is not an .onnx file")
        if entry["family"] not in ("MANGA_JANAI", "ILLUSTRATION_JANAI"):
            fail(f"{entry['catalogId']}: unknown family {entry['family']!r}")
        has_bucket = entry.get("bucket") is not None
        if (entry["family"] == "MANGA_JANAI") != has_bucket:
            fail(f"{entry['catalogId']}: bucket is manga-only")
    return spec


def parse_sums(text: str) -> dict:
    """`sha256sum` output (hash name per line) as a name -> hash dict."""
    sums = {}
    for line in text.splitlines():
        fields = line.split()
        if len(fields) != 2:
            continue
        digest, name = fields
        sums[name.lstrip("*")] = digest.lower()
    return sums


def table(spec: dict, sizes: dict, hashes: dict) -> str:
    """The `id / file / scale / family / bucket / bytes / sha256(short)` table."""
    rows = [("id", "file", "scale", "family", "bucket", "bytes", "sha256")]
    for entry in spec["models"]:
        name = entry["memberName"]
        rows.append(
            (
                entry["catalogId"],
                name,
                f"{entry['scale']}x",
                "manga" if entry["family"] == "MANGA_JANAI" else "illustration",
                f"{entry['bucket']}p" if entry.get("bucket") else "-",
                str(sizes.get(name, "")),
                hashes.get(name, "")[:12],
            ),
        )
    widths = [max(len(row[column]) for row in rows) for column in range(len(rows[0]))]
    return "\n".join(
        "  ".join(cell.ljust(widths[column]) for column, cell in enumerate(row)).rstrip()
        for row in rows
    )


# --------------------------------------------------------------------------- documents


def manifest_for(spec: dict, sizes: dict, hashes: dict) -> dict:
    """The manifest exactly as `mihon.core.upscale.model.UpscaleModelManifest` parses it."""
    release = spec["release"]
    return {
        "release": release["tag"],
        "baseUrl": release["baseUrl"],
        "files": [
            {
                "name": entry["memberName"],
                "path": entry["memberName"],
                "sha256": hashes[entry["memberName"]],
                "size": sizes[entry["memberName"]],
                "id": entry["catalogId"],
                "scale": entry["scale"],
                "family": entry["family"],
                "bucket": int(entry.get("bucket") or 0),
            }
            for entry in spec["models"]
        ],
    }


def attribution_for(spec: dict, sizes: dict, hashes: dict) -> str:
    upstream = spec["upstream"]
    release = spec["release"]
    total = sum(sizes.values())
    lines = [
        "# Attribution — komi-kun upscale models",
        "",
        "This repository only **re-hosts** the ONNX files below as per-file release assets, so the",
        "komi-kun Android app can download them on device at runtime. No model is trained, altered,",
        "converted or re-exported here.",
        "",
        "## What the files are",
        "",
        f"* **Original weights / project:** MangaJaNai, by the-database — <{upstream['project']}>",
        "* **ONNX (fp16) conversion:** Lolle2000la — "
        f"<{upstream['onnxRelease']}>",
        f"* **Upstream release used:** `{upstream['release']}` (`{upstream['sumsFile']}` is the hash list)",
        "",
        "## Bytes: unmodified, only re-packed",
        "",
        "Each `.onnx` file here is **byte-for-byte the zip member** of the upstream release zip it came",
        "from: the files are extracted with `zipfile` and published unchanged (no re-export, no",
        "re-quantisation, no renaming, no metadata rewrite). Their SHA-256 values are the ones in",
        f"upstream's `{upstream['sumsFile']}`, which this repository's publisher verifies both for the",
        "zip (container) and for every member (bytes). The container is the only thing that changed:",
        "one shared zip became one asset per model.",
        "",
        "## Licence",
        "",
        "The weights are licensed **CC BY-NC 4.0 — Creative Commons Attribution-NonCommercial 4.0",
        "International** (the full text is `LICENSE-CC-BY-NC-4.0.txt` in the same release, and upstream",
        f"at <{upstream['licenseFile']}>).",
        "",
        "* **Non-commercial use only.** You may not use these files or their outputs for commercial",
        "  advantage or monetary compensation.",
        "* Attribution to the-database (MangaJaNai) for the original weights and to Lolle2000la for the",
        "  ONNX conversion is required and given above.",
        "* These files are **not** part of the komi-kun application and are **not** covered by its",
        "  GPL-3.0-or-later licence; they are separate works under CC BY-NC 4.0, downloaded at the",
        "  user's request and never bundled in the APK.",
        "",
        "## Files in this release",
        "",
        f"{len(sizes)} ONNX files, {human_bytes(total)} in total ({release['tag']}).",
        "",
        "| file | bytes | sha256 |",
        "| --- | ---: | --- |",
    ]
    for entry in spec["models"]:
        name = entry["memberName"]
        lines.append(f"| `{name}` | {sizes[name]} | `{hashes[name]}` |")
    lines.append("")
    return "\n".join(lines)


def release_notes_for(spec: dict, sizes: dict, hashes: dict) -> str:
    upstream = spec["upstream"]
    release = spec["release"]
    manga = sum(1 for entry in spec["models"] if entry["family"] == "MANGA_JANAI")
    illustration = len(spec["models"]) - manga
    total = sum(sizes.values())
    return "\n".join(
        [
            f"# {release['title']}",
            "",
            f"The upscaling models komi-kun's reader downloader pins: {len(sizes)} per-file ONNX",
            f"assets, {human_bytes(total)} total ({manga} MangaJaNai bucket models, {illustration}",
            "IllustrationJaNai models), plus `manifest.json`, `ATTRIBUTION.md` and",
            "`LICENSE-CC-BY-NC-4.0.txt`.",
            "",
            f"* **Upstream:** MangaJaNai `{upstream['release']}` fp16 ONNX release by Lolle2000la,",
            f"  weights by the-database — <{upstream['project']}>",
            "* **Unmodified:** every `.onnx` file is byte-identical to its upstream zip member (same",
            f"  SHA-256 as upstream's `{upstream['sumsFile']}`). Only the container changed — one zip",
            "  became one asset per model.",
            "* **Licence:** the weights are **CC BY-NC 4.0** (non-commercial only). See",
            "  `ATTRIBUTION.md` and `LICENSE-CC-BY-NC-4.0.txt`. They are not GPL, not part of the app,",
            "  and never bundled in the APK — the app downloads them on device.",
            "",
            "`manifest.json` lists each file with its `sha256` and `size`; the app verifies every byte",
            "before installing it.",
            "",
        ],
    )


def license_text(spec: dict, cache: Path) -> str:
    """The CC BY-NC 4.0 text upstream ships as the weights' LICENSE (fetched once, cached)."""
    cached = cache / "LICENSE-CC-BY-NC-4.0.txt"
    if cached.is_file() and cached.stat().st_size > 1000:
        return cached.read_text(encoding="utf-8")
    url = spec["upstream"]["licenseFile"]
    print(f"  get     license text from {url}")
    with open_url(url) as response:
        text = response.read().decode("utf-8")
    if "Attribution-NonCommercial 4.0 International" not in text:
        fail(f"the licence text at {url} does not look like CC BY-NC 4.0")
    cached.parent.mkdir(parents=True, exist_ok=True)
    cached.write_text(text, encoding="utf-8")
    return text


# --------------------------------------------------------------------------- main


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--models",
        type=Path,
        default=Path(__file__).resolve().parent / "models.json",
        help="the model spec (default: models.json beside this script)",
    )
    parser.add_argument("--cache", type=Path, default=DEFAULT_CACHE, help="upstream zip cache")
    parser.add_argument("--dist", type=Path, default=DEFAULT_DIST, help="output directory")
    parser.add_argument(
        "--only",
        default="",
        help="comma-separated catalog ids to publish (default: every model in the spec)",
    )
    parser.add_argument(
        "--print-only",
        action="store_true",
        help="print the planned model table from the spec and exit (no network, no writes)",
    )
    args = parser.parse_args()

    spec = load_spec(args.models)
    wanted = [part.strip() for part in args.only.split(",") if part.strip()]
    if wanted:
        unknown = sorted(set(wanted) - {entry["catalogId"] for entry in spec["models"]})
        if unknown:
            fail(f"--only names unknown catalog ids: {', '.join(unknown)}")
        spec["models"] = [entry for entry in spec["models"] if entry["catalogId"] in wanted]

    # A `--only` run is a fetch, not a release: it produces a set the catalog does not describe, which
    # is exactly the drift the app's manifest test exists to catch. So every artifact it writes —
    # **including the `.onnx` files** — carries a `.partial` suffix, and the documented publish
    # command (`<dist>/*.onnx`, `manifest.json`, …) can never pick one up by accident.
    suffix = ".partial" if wanted else ""

    if args.print_only:
        print(table(spec, {}, {}))
        return 0

    args.cache.mkdir(parents=True, exist_ok=True)
    args.dist.mkdir(parents=True, exist_ok=True)

    upstream = spec["upstream"]
    sums_url = f"{upstream['baseUrl']}/{upstream['sumsFile']}"
    print(f"upstream {upstream['release']}: {sums_url}")
    sums_path = args.cache / upstream["sumsFile"]
    if not sums_path.is_file():
        try:
            with open_url(sums_url) as response:
                sums_path.write_bytes(response.read())
        except urllib.error.URLError as error:
            fail(f"cannot read {sums_url}: {error}")
    sums = parse_sums(sums_path.read_text(encoding="utf-8"))
    if not sums:
        fail(f"{sums_path} lists no checksums")

    zips: dict[str, list[dict]] = {}
    for entry in spec["models"]:
        zips.setdefault(entry["upstreamZip"], []).append(entry)

    sizes: dict[str, int] = {}
    hashes: dict[str, str] = {}
    for zip_name, entries in zips.items():
        expected_zip_sha = sums.get(zip_name)
        if not expected_zip_sha:
            fail(f"{upstream['sumsFile']} has no line for {zip_name}")
        archive = args.cache / zip_name
        print(f"{zip_name}: {len(entries)} member(s) wanted")
        download(f"{upstream['baseUrl']}/{zip_name}", archive, expected_zip_sha)
        with zipfile.ZipFile(archive) as zipped:
            present = set(zipped.namelist())
        for entry in entries:
            member = entry["memberName"]
            if member not in present:
                fail(f"{zip_name} has no member {member} (needed for {entry['catalogId']})")
            expected_member_sha = sums.get(member)
            if not expected_member_sha:
                fail(f"{upstream['sumsFile']} has no line for {member}")
            target = args.dist / f"{member}{suffix}"
            extract_member(archive, member, target, expected_member_sha)
            sizes[member] = target.stat().st_size
            hashes[member] = expected_member_sha

    if wanted:
        print(
            "warning: --only writes every artifact with a `.partial` suffix — the .onnx files, "
            "manifest.json%s, ATTRIBUTION.md%s and RELEASE_NOTES.md%s — so a partial set can never be "
            "published under the names the release command uploads (%s)"
            % (suffix, suffix, suffix, spec["release"]["tag"]),
        )

    manifest = manifest_for(spec, sizes, hashes)
    manifest_path = args.dist / f"manifest.json{suffix}"
    manifest_path.write_text(
        json.dumps(manifest, indent=2, sort_keys=False) + "\n",
        encoding="utf-8",
    )

    (args.dist / f"ATTRIBUTION.md{suffix}").write_text(
        attribution_for(spec, sizes, hashes),
        encoding="utf-8",
    )
    (args.dist / "LICENSE-CC-BY-NC-4.0.txt").write_text(
        license_text(spec, args.cache),
        encoding="utf-8",
    )
    (args.dist / f"RELEASE_NOTES.md{suffix}").write_text(
        release_notes_for(spec, sizes, hashes),
        encoding="utf-8",
    )

    print()
    print(table(spec, sizes, hashes))
    print()
    print(f"{len(sizes)} model file(s), {human_bytes(sum(sizes.values()))} -> {args.dist}")
    print(f"release {spec['release']['tag']} on {spec['release']['repo']}")
    print(f"baseUrl {spec['release']['baseUrl']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
