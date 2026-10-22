#!/usr/bin/env python3
"""Union-resolve conflicted moko-resources translation files during a cherry-pick.

Weblate is not connected to this fork, so upstream translation commits are ported
manually. The fork's locale files and upstream's diverge in both directions:
upstream has fresher translations and new keys, while the fork still has keys
(Komikku-era) that upstream removed. A plain "take theirs" would silently drop
translations, so this helper:

  1. takes the upstream file (the commit being cherry-picked, i.e. `theirs`),
  2. drops entries whose key is not in the module's `base/` (string removed from
     the fork's base, e.g. Komikku dropped Mihon's donation strings),
  3. re-appends every `<string>` / `<plurals>` entry that only exists in the
     fork's copy (HEAD) and is still in `base/`, with its existing translation,
  4. keeps every entry framed with the fork's `<!-- KUN -->` … `<!-- KUN -->`
     markers at HEAD's value (hand-corrected translations must not be
     overwritten by upstream), and
  5. validates the result is well-formed XML with no duplicate names.

`base/` is derived from the path (`i18n/src/<sourceSet>/moko-resources/...`), so
run it from the repo root.

With no paths it resolves every moko-resources file changed by the commit being
picked (`--theirs`, default `CHERRY_PICK_HEAD`), including files git merged without
a conflict — an automatic merge can drop a fork-only entry too.

    python3 .agents/skills/upstream-port/scripts/resolve_i18n_union.py [PATH ...]

Use `--theirs REF` if `CHERRY_PICK_HEAD` is gone (e.g. after `git cherry-pick --abort`
and a manual `git cherry-pick --no-commit`).
"""

from __future__ import annotations

import argparse
import glob
import re
import subprocess
import sys
import textwrap
import xml.etree.ElementTree as ET

ENTRY = re.compile(r'<(string|plurals)\b[^>]*\bname="([^"]+)"[^>]*>.*?</\1>', re.S)
ENTRY_SELF = re.compile(r'<(string|plurals)\b[^>]*\bname="([^"]+)"[^>]*/>', re.S)
COMMENT = re.compile(r"<!--.*?-->", re.S)
KUN_FRAME = re.compile(r"<!-- KUN -->.*?<!-- KUN -->", re.S)
MOKO_PATH = re.compile(r"^i18n[^/]*/src/.*/moko-resources/")
BASE_DIR = re.compile(r"^([^/]+/src/[^/]+/moko-resources)/")


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], text=True)


def show(ref: str, path: str) -> str | None:
    try:
        return git("show", f"{ref}:{path}")
    except subprocess.CalledProcessError:
        return None


def entries(text: str) -> dict[str, str]:
    comments = [(m.start(), m.end()) for m in COMMENT.finditer(text)]

    def in_comment(pos: int) -> bool:
        return any(start < pos < end for start, end in comments)

    out: dict[str, str] = {}
    for match in ENTRY.finditer(text):
        if not in_comment(match.start()):
            out[match.group(2)] = match.group(0)
    for match in ENTRY_SELF.finditer(text):
        if not in_comment(match.start()):
            out.setdefault(match.group(2), match.group(0))
    return out


def kun_frames(text: str) -> list[tuple[list[str], str]]:
    """Return (entry names, block) for each `<!-- KUN -->` frame, markers included."""
    frames: list[tuple[list[str], str]] = []
    for match in KUN_FRAME.finditer(text):
        block = match.group(0)
        names = list(entries(block))
        if names:
            frames.append((names, block))
    return frames


def indent_block(block: str) -> str:
    """Re-indent a frame block to the four spaces used by moko-resources files."""
    # The regex match starts at "<!--", so the opening marker has no leading
    # indentation while the following lines do; pad it before dedenting.
    lines = textwrap.dedent("    " + block).strip("\n").splitlines()
    return "\n".join("    " + line for line in lines)


def validate(text: str) -> list[str]:
    problems = []
    try:
        root = ET.fromstring(text)
    except ET.ParseError as e:
        return [f"invalid XML: {e}"]
    seen: set[str] = set()
    for element in root:
        if element.tag in ("string", "plurals"):
            name = element.get("name")
            if name in seen:
                problems.append(f"duplicate name: {name}")
            seen.add(name)
    return problems


def base_keys(path: str) -> set[str] | None:
    """Keys defined in the module's base/ files, or None when they can't be found."""
    match = BASE_DIR.match(path)
    if not match:
        return None
    keys: set[str] = set()
    for base in glob.glob(f"{match.group(1)}/base/*.xml"):
        keys |= set(entries(open(base).read()))
    return keys or None


def drop_entry(text: str, entry: str) -> str:
    new, replaced = re.subn(r"\n[ \t]*" + re.escape(entry), "", text, count=1)
    return new if replaced else text.replace(entry, "", 1)


def replace_entry(text: str, entry: str, block: str) -> str:
    new, replaced = re.subn(r"\n[ \t]*" + re.escape(entry), "\n" + block, text, count=1)
    return new if replaced else text.replace(entry, block, 1)


def resolve(path: str, theirs_ref: str) -> str:
    head = show("HEAD", path)
    theirs = show(theirs_ref, path)
    if head is None or theirs is None:
        return f"{path}: skipped (missing in HEAD or {theirs_ref})"

    ours = entries(head)
    upstream = entries(theirs)
    keys = base_keys(path)
    # Hand-corrected entries must survive the port; ignore frames whose string
    # no longer exists in base/ (same rule as any other translation).
    frames = [
        frame
        for frame in kun_frames(head)
        if keys is None or all(name in keys for name in frame[0])
    ]
    framed = {name: frame for frame in frames for name in frame[0]}

    text = theirs
    dropped = 0
    if keys is not None:
        for name, entry in list(upstream.items()):
            if name not in keys:
                text = drop_entry(text, entry)
                del upstream[name]
                dropped += 1
        missing = [name for name in ours if name in keys and name not in upstream]
    else:
        missing = [name for name in ours if name not in upstream]

    # Re-append fork-only entries. A KUN frame is appended whole so its markers
    # and explanatory comment stay attached to the value.
    appended: list[str] = []
    appended_frames: list[tuple[list[str], str]] = []
    for name in missing:
        frame = framed.get(name)
        if frame is None:
            appended.append("    " + ours[name])
        elif frame not in appended_frames:
            appended_frames.append(frame)
            appended.append(indent_block(frame[1]))
    if appended:
        text = text.replace("</resources>", "\n".join(appended) + "\n</resources>")

    for names, _ in appended_frames:
        for name in names:
            entry = upstream.get(name)
            if entry is not None:
                text = drop_entry(text, entry)

    # Frames whose entries are all present upstream are replaced in place,
    # keeping HEAD's hand-corrected value, markers and explanatory comment.
    for frame in frames:
        if frame in appended_frames:
            continue
        names, block = frame
        first = True
        for name in names:
            entry = upstream.get(name)
            if entry is None:
                continue
            if first:
                text = replace_entry(text, entry, indent_block(block))
                first = False
            else:
                text = drop_entry(text, entry)

    problems = validate(text)
    if problems:
        return f"{path}: NOT WRITTEN ({'; '.join(problems[:5])})"

    with open(path, "w") as f:
        f.write(text)
    hand_corrected = sum(len(names) for names, _ in frames)
    return (
        f"{path}: kept {len(missing)} fork-only entries, dropped {dropped} base-less entries, "
        f"kept {hand_corrected} hand-corrected entries"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("paths", nargs="*", help="files to resolve (default: all moko files changed by --theirs)")
    parser.add_argument(
        "--theirs",
        default=None,
        help="git ref of the upstream commit (default: CHERRY_PICK_HEAD, then MERGE_HEAD)",
    )
    args = parser.parse_args()

    theirs_ref = args.theirs
    if theirs_ref is None:
        for candidate in ("CHERRY_PICK_HEAD", "MERGE_HEAD"):
            try:
                git("rev-parse", "--verify", "--quiet", candidate)
                theirs_ref = candidate
                break
            except subprocess.CalledProcessError:
                continue
    if theirs_ref is None:
        print("error: no CHERRY_PICK_HEAD/MERGE_HEAD; pass --theirs REF", file=sys.stderr)
        return 2

    if not args.paths:
        changed = git("show", "--name-only", "--format=", theirs_ref).splitlines()
        args.paths = [p for p in changed if MOKO_PATH.match(p)]
        if not args.paths:
            print(f"no moko-resources files changed by {theirs_ref}")
            return 0

    failed = False
    for path in args.paths:
        result = resolve(path, theirs_ref)
        print(result)
        failed |= "NOT WRITTEN" in result
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
