#!/usr/bin/env python3
"""Post the schema-shaped review returned by the OpenCode review agent.

The agent is asked to answer with one JSON object (``summary`` plus a list of
``findings``); the GitHub action posts that final message as a comment. This
script reads the comment, validates every finding against the pull request
diff, then posts one review whose body is the summary and whose comments are
threads on the exact changed lines. It removes the action's comment so the PR
shows a single review entry.

If the final message is not valid JSON (for example because the agent hit its
step budget and replied with prose), a cheap repair model turns it into the
expected shape before posting.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

DIFF_PATH = Path("build/opencode-review/pr.diff")
VALID_SEVERITIES = {"low", "medium", "high", "critical"}
SEVERITY_ORDER = {"critical": 0, "high": 1, "medium": 2, "low": 3}
MAX_COMMENTS = 50
DEFAULT_SUMMARY = (
    "Automated review by [OpenCode](https://opencode.ai/docs/github/). "
    "These findings are advisory: verify them before changing code."
)
OPENCODE_API_URL = os.environ.get(
    "OPENCODE_API_URL",
    "https://opencode.ai/inference/go/openai/v1/chat/completions",
)
REPAIR_HINTS = re.compile(r"finding|summary|review|severity|path:", re.IGNORECASE)
REPAIR_INSTRUCTION = """\
Convert the code-review text below into one JSON object. Output only the JSON object.

Schema:
{"summary": "markdown summary, keep any Mermaid block", "findings": [{"path": "repo/relative/path", "line": 123, "severity": "low|medium|high|critical", "body": "concise finding and fix"}]}

Use only findings present in the text. Drop findings without a concrete path and line.
Do not invent code or findings. If the text is not a review, return {"summary": "", "findings": []}.
"""


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

    # Split on "\n" rather than splitlines(): the latter also splits on
    # vertical tabs, form feeds and U+2028, which can occur inside a source line.
    for raw in diff.split("\n"):
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


def _json_objects(text: str):
    """Yield balanced {...} substrings, ignoring braces inside JSON strings."""
    start = None
    depth = 0
    in_string = False
    escaped = False

    for index, char in enumerate(text):
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue

        if char == '"':
            in_string = True
        elif char == "{":
            if depth == 0:
                start = index
            depth += 1
        elif char == "}" and depth > 0:
            depth -= 1
            if depth == 0 and start is not None:
                yield text[start : index + 1]
                start = None


def extract_review(text: str) -> tuple[str, list] | None:
    """Recover a review object from prose or a ```json fenced block.

    Prefer the last matching object: a step-limited agent may restate the
    schema before emitting the real review, so the example would otherwise win.
    """
    for candidate in reversed(list(_json_objects(text))):
        try:
            data = json.loads(candidate)
        except json.JSONDecodeError:
            continue
        if not isinstance(data, dict):
            continue
        summary = data.get("summary")
        if not isinstance(summary, str):
            continue
        findings = data.get("findings")
        if not isinstance(findings, list):
            findings = []
        return summary.strip(), findings
    return None


def repair_review(text: str) -> tuple[str, list] | None:
    """Ask a cheap model to turn a malformed final message into review JSON."""
    api_key = os.environ.get("OPENCODE_API_KEY")
    model = os.environ.get("OPENCODE_REPAIR_MODEL", "mimo-v2.6-flash")
    if not api_key:
        print("OPENCODE_API_KEY is not set; cannot repair the review.")
        return None

    payload = {
        "model": model,
        "temperature": 0,
        "messages": [
            {
                "role": "system",
                "content": "You convert code-review text into strict JSON. Output only the JSON object.",
            },
            {"role": "user", "content": f"{REPAIR_INSTRUCTION}\n\nReview text:\n{text}"},
        ],
    }
    request = urllib.request.Request(
        OPENCODE_API_URL,
        data=json.dumps(payload).encode(),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )

    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            data = json.loads(response.read())
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
        print(f"Repair request failed: {exc}")
        return None

    try:
        content = data["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError):
        print("Repair response did not contain an assistant message.")
        return None
    if isinstance(content, list):
        content = "".join(
            str(part.get("text", "")) for part in content if isinstance(part, dict)
        )
    if not isinstance(content, str):
        return None

    review = extract_review(content)
    if review is None:
        print("Repair model did not return valid review JSON.")
    return review


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


def find_action_comment(repo: str, pr: str) -> dict | None:
    """Return the action's comment for this run, if it exists."""
    run_id = os.environ.get("GITHUB_RUN_ID")
    if not run_id:
        return None

    result = gh("api", "--paginate", f"repos/{repo}/issues/{pr}/comments", "--jq", ".[]")
    if result.returncode != 0:
        return None

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
        if f"/actions/runs/{run_id}" in str(comment.get("body", "")):
            return comment
    return None


def delete_action_comment(repo: str, pr: str) -> None:
    """Remove the action's own comment for this run."""
    comment = find_action_comment(repo, pr)
    if comment is not None:
        gh("api", "--method", "DELETE", f"repos/{repo}/issues/comments/{comment['id']}")


def main() -> int:
    pr = os.environ.get("PR_NUMBER")
    repo = os.environ.get("GITHUB_REPOSITORY")
    if not pr or not repo:
        print("PR_NUMBER and GITHUB_REPOSITORY are required.")
        return 1

    comment = find_action_comment(repo, pr)
    if comment is None:
        print("::error::No action comment found for this run; the review was not posted.")
        return 1

    body = str(comment.get("body", ""))
    review = extract_review(body)
    if review is None and REPAIR_HINTS.search(body):
        print("Final message is not valid review JSON; asking the repair model.")
        review = repair_review(body)
    if review is None:
        print("::error::Could not parse or repair the review; leaving the action comment in place.")
        return 1

    summary, findings = review
    if not summary and not findings:
        print("::warning::Recovered review is empty; leaving the action comment in place.")
        return 0

    diff = DIFF_PATH.read_text() if DIFF_PATH.is_file() else gh("pr", "diff", pr).stdout
    if not diff:
        print("::error::Could not read the pull request diff; the review was not posted.")
        return 1

    valid = parse_diff(diff)
    seen = existing_comments(repo, pr)
    comments: list[dict] = []
    if seen is None:
        print("Could not list existing comments; skipping inline comments to avoid duplicates.")
    else:
        for finding in findings:
            parsed = describe_comment(finding, valid)
            if parsed is None:
                continue
            key = (parsed["path"], parsed["line"], parsed["body"])
            if key in seen:
                continue
            seen.add(key)
            comments.append(parsed)

    comments.sort(key=lambda c: SEVERITY_ORDER[c["severity"]])
    comments = comments[:MAX_COMMENTS]

    if not summary and not comments:
        print("Nothing new to post; leaving the action comment in place.")
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
