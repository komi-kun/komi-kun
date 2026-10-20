#!/usr/bin/env python3
"""Post the schema-shaped review written by the OpenCode review agent.

The agent writes ``build/opencode-review/findings.json`` (gitignored) with a
``summary`` and a list of ``findings``. This script validates every finding
against the pull request diff, then posts one review whose body is the summary
and whose comments are threads on the exact changed lines. It removes the
action's own short status comment so the PR shows a single review entry.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from pathlib import Path

FINDINGS_PATH = Path("build/opencode-review/findings.json")
DIFF_PATH = Path("build/opencode-review/pr.diff")
VALID_SEVERITIES = {"low", "medium", "high", "critical"}
SEVERITY_ORDER = {"critical": 0, "high": 1, "medium": 2, "low": 3}
MAX_COMMENTS = 50
DEFAULT_SUMMARY = (
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
    # A removed line whose content starts with "-- " is emitted as "--- ", so
    # only treat "---"/"+++" as file headers in the two lines after "diff --git".
    state = "outside"

    for raw in diff.splitlines():
        if raw.startswith("diff --git "):
            path = None
            new_line = None
            state = "diff"
        elif state == "diff" and raw.startswith("--- "):
            state = "minus"
        elif state == "minus" and raw.startswith("+++ "):
            target = raw[4:]
            if target.startswith("b/"):
                path = target[2:]
                valid.setdefault(path, set())
            else:  # /dev/null for deleted files
                path = None
            state = "header"
        elif raw.startswith("@@"):
            new_line = _hunk_start(raw)
            state = "hunk"
        elif state == "hunk" and path is not None and new_line is not None:
            if raw.startswith("+") or raw.startswith(" "):
                valid.get(path, set()).add(new_line)
                new_line += 1
            elif raw.startswith("-") or raw.startswith("\\"):
                continue
            else:
                new_line = None
        elif state not in ("diff", "minus"):
            new_line = None

    return valid


def _hunk_start(header: str) -> int | None:
    match = re.search(r"\+(\d+)(?:,\d+)?", header)
    return int(match.group(1)) if match else None


def load_review() -> tuple[str, list]:
    if not FINDINGS_PATH.is_file():
        print(f"No {FINDINGS_PATH}; skipping review.")
        return "", []

    try:
        data = json.loads(FINDINGS_PATH.read_text())
    except (OSError, json.JSONDecodeError) as exc:
        print(f"Could not read findings: {exc}")
        return "", []

    if not isinstance(data, dict):
        print("Findings file is not an object; skipping review.")
        return "", []

    summary = data.get("summary")
    if not isinstance(summary, str):
        summary = ""
    findings = data.get("findings")
    if not isinstance(findings, list):
        findings = []
    return summary.strip(), findings


def describe_comment(finding: object, valid: dict[str, set[int]]) -> dict | None:
    if not isinstance(finding, dict):
        return None

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
        "severity": severity,
        "body": f"**[{severity}]** {body.strip()}",
    }


def existing_comments(repo: str, pr: str) -> set[tuple[str | None, int | None, str | None]] | None:
    result = gh("api", "--paginate", f"repos/{repo}/pulls/{pr}/comments", "--jq", ".[]")
    if result.returncode != 0:
        return None

    seen: set[tuple[str | None, int | None, str | None]] = set()
    for line in result.stdout.splitlines():
        try:
            comment = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(comment, dict):
            seen.add(
                (
                    comment.get("path"),
                    comment.get("line") or comment.get("original_line"),
                    comment.get("body"),
                )
            )
    return seen


def review_comment(comment: dict) -> dict:
    return {
        "path": comment["path"],
        "line": comment["line"],
        "side": "RIGHT",
        "body": comment["body"],
    }


def post_summary(repo: str, pr: str, summary: str) -> None:
    if not summary:
        return
    result = gh(
        "api",
        "--method",
        "POST",
        f"repos/{repo}/issues/{pr}/comments",
        "--input",
        "-",
        stdin=json.dumps({"body": summary}),
    )
    if result.returncode != 0:
        print(f"Could not post the summary comment: {result.stderr.strip()}")


def post_individually(repo: str, pr: str, head: str, comments: list[dict]) -> int:
    posted = 0
    for comment in comments:
        result = gh(
            "api",
            "--method",
            "POST",
            f"repos/{repo}/pulls/{pr}/comments",
            "--input",
            "-",
            stdin=json.dumps({"commit_id": head, **review_comment(comment)}),
        )
        if result.returncode == 0:
            posted += 1
        else:
            print(f"Could not comment on {comment['path']}:{comment['line']}: {result.stderr.strip()}")
    return posted


def delete_action_comment(repo: str, pr: str) -> None:
    """Remove the action's own short status comment for this run."""
    run_id = os.environ.get("GITHUB_RUN_ID")
    if not run_id:
        return

    result = gh("api", "--paginate", f"repos/{repo}/issues/{pr}/comments", "--jq", ".[]")
    if result.returncode != 0:
        return

    for line in result.stdout.splitlines():
        try:
            comment = json.loads(line)
        except json.JSONDecodeError:
            continue
        if not isinstance(comment, dict):
            continue
        user = comment.get("user", {})
        if not isinstance(user, dict) or user.get("login") != "github-actions[bot]":
            continue
        if f"/actions/runs/{run_id}" not in str(comment.get("body", "")):
            continue
        gh("api", "--method", "DELETE", f"repos/{repo}/issues/comments/{comment['id']}")


def main() -> int:
    summary, findings = load_review()
    if not summary and not findings:
        return 0

    pr = os.environ.get("PR_NUMBER")
    repo = os.environ.get("GITHUB_REPOSITORY")
    if not pr or not repo:
        print("PR_NUMBER and GITHUB_REPOSITORY are required.")
        return 1

    diff = DIFF_PATH.read_text() if DIFF_PATH.is_file() else gh("pr", "diff", pr).stdout
    if not diff:
        print("Could not read the pull request diff; skipping review.")
        return 0

    valid = parse_diff(diff)
    seen = existing_comments(repo, pr)
    comments: list[dict] = []
    if seen is None:
        print("Could not list existing comments; skipping inline comments to avoid duplicates.")
    else:
        for finding in findings:
            comment = describe_comment(finding, valid)
            if comment is None:
                continue
            key = (comment["path"], comment["line"], comment["body"])
            if key in seen:
                continue
            seen.add(key)
            comments.append(comment)

    comments.sort(key=lambda c: SEVERITY_ORDER[c["severity"]])
    comments = comments[:MAX_COMMENTS]

    if not summary and not comments:
        print("Nothing new to post.")
        return 0

    head = gh("pr", "view", pr, "--json", "headRefOid", "--jq", ".headRefOid").stdout.strip()
    if not head:
        print("Could not resolve the pull request head commit.")
        return 0

    payload = {
        "commit_id": head,
        "event": "COMMENT",
        "body": summary or DEFAULT_SUMMARY,
        "comments": [review_comment(comment) for comment in comments],
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
        print(f"Posted review with {len(comments)} inline comment(s).")
        delete_action_comment(repo, pr)
        return 0

    # A single rejected line fails the whole review. Fall back to a summary
    # comment plus individual threads so a bad line only loses that finding.
    print(f"Review submission failed, falling back: {result.stderr.strip()}")
    post_summary(repo, pr, summary or DEFAULT_SUMMARY)
    posted = post_individually(repo, pr, head, comments)
    print(f"Posted {posted}/{len(comments)} inline comment(s) individually.")
    delete_action_comment(repo, pr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
