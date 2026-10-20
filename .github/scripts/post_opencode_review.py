#!/usr/bin/env python3
"""Post the findings written by the OpenCode review agent as inline PR comments.

The agent writes ``build/opencode-review/findings.json`` (gitignored, so the
OpenCode GitHub action does not try to commit it). This script validates every
finding against the pull request diff and posts one review whose comments point
at the exact changed lines, so they show up in the "Files changed" view.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from pathlib import Path

FINDINGS_PATH = Path("build/opencode-review/findings.json")
VALID_SEVERITIES = {"low", "medium", "high", "critical"}
REVIEW_BODY = (
    "Automated review by [OpenCode](https://opencode.ai/docs/github/). "
    "These findings are advisory: verify them before changing code."
)


def gh(*args: str, stdin: str | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["gh", *args],
        input=stdin,
        text=True,
        capture_output=True,
        check=False,
    )


def parse_diff(diff: str) -> dict[str, set[int]]:
    """Return the new-file line numbers that GitHub will accept for comments."""
    valid: dict[str, set[int]] = {}
    path: str | None = None
    new_line: int | None = None

    for raw in diff.splitlines():
        if raw.startswith("diff --git "):
            path = None
            new_line = None
        elif raw.startswith("+++ "):
            target = raw[4:]
            if target.startswith("b/"):
                path = target[2:]
                valid.setdefault(path, set())
            else:  # /dev/null for deleted files
                path = None
        elif raw.startswith("--- "):
            continue
        elif raw.startswith("@@"):
            match = re.search(r"\+(\d+)(?:,\d+)?", raw)
            new_line = int(match.group(1)) if match else None
        elif path is not None and new_line is not None:
            if raw.startswith("+") or raw.startswith(" "):
                valid[path].add(new_line)
                new_line += 1
            elif raw.startswith("-") or raw.startswith("\\"):
                continue
            else:
                new_line = None

    return valid


def load_findings() -> list[dict]:
    if not FINDINGS_PATH.is_file():
        print(f"No {FINDINGS_PATH}; skipping inline comments.")
        return []

    try:
        data = json.loads(FINDINGS_PATH.read_text())
    except (OSError, json.JSONDecodeError) as exc:
        print(f"Could not read findings: {exc}")
        return []

    findings = data.get("findings") if isinstance(data, dict) else None
    if not isinstance(findings, list):
        print("Findings file has no 'findings' list; skipping.")
        return []
    return findings


def describe_comment(finding: dict, valid: dict[str, set[int]]) -> dict | None:
    path = finding.get("path")
    body = finding.get("body")
    line = finding.get("line")

    if not isinstance(path, str) or not path.strip():
        return None
    if not isinstance(body, str) or not body.strip():
        return None
    try:
        line = int(line)
    except (TypeError, ValueError):
        return None

    if line not in valid.get(path, set()):
        print(f"Skipping {path}:{line}: line is not part of the pull request diff.")
        return None

    severity = str(finding.get("severity", "medium")).lower()
    if severity not in VALID_SEVERITIES:
        severity = "medium"

    return {
        "path": path,
        "line": line,
        "side": "RIGHT",
        "body": f"**[{severity}]** {body.strip()}",
    }


def main() -> int:
    findings = load_findings()
    if not findings:
        return 0

    pr = os.environ.get("PR_NUMBER")
    repo = os.environ.get("GITHUB_REPOSITORY")
    if not pr or not repo:
        print("PR_NUMBER and GITHUB_REPOSITORY are required.")
        return 1

    diff = gh("pr", "diff", pr).stdout
    if not diff:
        print("Could not read the pull request diff; skipping inline comments.")
        return 0

    valid = parse_diff(diff)
    comments = [c for f in findings if (c := describe_comment(f, valid))]
    if not comments:
        print("No findings mapped to changed lines; skipping inline comments.")
        return 0

    head = gh("pr", "view", pr, "--json", "headRefOid", "--jq", ".headRefOid").stdout.strip()
    if not head:
        print("Could not resolve the pull request head commit.")
        return 0

    payload = {
        "commit_id": head,
        "event": "COMMENT",
        "body": REVIEW_BODY,
        "comments": comments,
    }
    result = gh(
        "api",
        "--method",
        "POST",
        f"repos/{repo}/pulls/{pr}/reviews",
        "--input",
        "-",
        stdin=json.dumps(payload),
    )
    if result.returncode == 0:
        print(f"Posted {len(comments)} inline comment(s).")
        return 0

    # A single rejected line fails the whole review. Retry one by one so a bad
    # line only loses that finding.
    print(f"Review submission failed, retrying individually: {result.stderr.strip()}")
    posted = 0
    for comment in comments:
        single = {"commit_id": head, **comment}
        res = gh(
            "api",
            "--method",
            "POST",
            f"repos/{repo}/pulls/{pr}/comments",
            "--input",
            "-",
            stdin=json.dumps(single),
        )
        if res.returncode == 0:
            posted += 1
        else:
            print(f"Could not comment on {comment['path']}:{comment['line']}: {res.stderr.strip()}")
    print(f"Posted {posted} inline comment(s) individually.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
