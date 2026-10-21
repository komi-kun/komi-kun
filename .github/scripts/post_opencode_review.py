#!/usr/bin/env python3
"""Post the schema-shaped review returned by the pinned OpenCode CLI.

The workflow runs ``opencode run --format json`` and captures its JSONL event
stream. This script reads the assistant's final text from that stream, validates
every finding against the pull request diff, then posts one review whose body is
the summary and whose comments are threads on the exact changed lines. No issue
comment is created by the CLI, so there is nothing to clean up.

If the assistant text is not valid JSON (for example because the agent hit its
step budget and replied with prose), a second CLI run with the ``opencode-repair``
agent turns it into the expected shape before posting.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from pathlib import Path

DIFF_PATH = Path("build/opencode-review/pr.diff")
VALID_SEVERITIES = {"low", "medium", "high", "critical"}
SEVERITY_ORDER = {"critical": 0, "high": 1, "medium": 2, "low": 3}
MAX_COMMENTS = 50
DEFAULT_SUMMARY = (
    "Automated review by [OpenCode](https://opencode.ai/docs/github/). "
    "These findings are advisory: verify them before changing code."
)
PLACEHOLDER_SUMMARY = "markdown overarching review comment"
PLACEHOLDER_SUMMARIES = {
    "string",
    "summary",
    "...",
    "markdown summary",
    "markdown summary, keep any mermaid block",
}
PLACEHOLDER_PATHS = ("...", "repo/relative/path")
PLACEHOLDER_LINE = 123


def gh(*args: str, stdin: str | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["gh", *args],
        input=stdin,
        text=True,
        capture_output=True,
        check=False,
    )


def load_assistant_text(jsonl: str) -> str:
    """Return the final assistant message's text from an `opencode run` stream.

    Steps before the final answer are ignored, so earlier narration or quoted
    examples cannot be mistaken for the review.
    """
    messages: dict[str, list[str]] = {}
    order: list[str] = []
    for line in jsonl.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if not isinstance(event, dict) or event.get("type") != "text":
            continue
        part = event.get("part")
        if not isinstance(part, dict) or part.get("type") != "text":
            continue
        text = part.get("text")
        if not isinstance(text, str):
            continue
        message_id = str(part.get("messageID", ""))
        if message_id not in messages:
            messages[message_id] = []
            order.append(message_id)
        messages[message_id].append(text)

    if not order:
        return ""
    return "\n".join(messages[order[-1]])


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


def _fenced_objects(text: str):
    """Yield the first object after each ```json marker, not trailing objects."""
    for match in re.finditer(r"```json\s*", text, re.IGNORECASE):
        first = next(_json_objects(text[match.end() :]), None)
        if first is not None:
            yield first


def _is_placeholder(data: dict) -> bool:
    """Reject the schema example an agent may echo after hitting its limit."""
    summary = data.get("summary")
    if isinstance(summary, str):
        normalized = summary.strip().lower()
        if normalized in PLACEHOLDER_SUMMARIES or normalized.startswith(PLACEHOLDER_SUMMARY):
            return True

    findings = data.get("findings")
    if isinstance(findings, list) and findings:
        return all(
            isinstance(finding, dict)
            and (
                finding.get("line") == PLACEHOLDER_LINE
                and any(
                    placeholder in str(finding.get("path", ""))
                    for placeholder in PLACEHOLDER_PATHS
                )
            )
            for finding in findings
        )
    return False


def _score_findings(data: dict, valid: dict[str, set[int]] | None) -> int:
    """Count findings that land on a line present in the pull request diff."""
    if not valid:
        return 0
    findings = data.get("findings")
    if not isinstance(findings, list):
        return 0

    score = 0
    for finding in findings:
        if not isinstance(finding, dict):
            continue
        path = finding.get("path")
        if not isinstance(path, str):
            continue
        try:
            line = int(finding.get("line"))
        except (TypeError, ValueError):
            continue
        if line in valid.get(path, set()):
            score += 1
    return score


def _pick_review(
    candidates: list[tuple[int, int, str]], valid: dict[str, set[int]] | None
) -> tuple[str, list] | None:
    """Pick the best candidate: diff-valid findings, then substance, then order."""
    best: tuple[str, list] | None = None
    best_rank: tuple[int, int, int, int, int] | None = None

    for kind, index, candidate in candidates:
        try:
            data = json.loads(candidate)
        except json.JSONDecodeError:
            continue
        if not isinstance(data, dict) or _is_placeholder(data):
            continue
        summary = data.get("summary")
        if not isinstance(summary, str):
            continue
        findings = data.get("findings")
        if not isinstance(findings, list):
            findings = []
        summary = summary.strip()

        # A trailing shape echo or empty object must not replace a substantive
        # review, so rank by diff-valid findings, then by finding count and
        # summary length, then by fenced-over-raw and later position.
        rank = (
            _score_findings(data, valid),
            len(findings),
            len(summary),
            kind,
            index,
        )
        if best_rank is None or rank >= best_rank:
            best = (summary, findings)
            best_rank = rank
    return best


def extract_review(
    text: str, valid: dict[str, set[int]] | None = None
) -> tuple[str, list] | None:
    """Recover a review object from a ```json fence or raw JSON.

    A trailing shape echo or empty placeholder must not replace a substantive
    review, so candidates are scored against the diff and the best wins.
    """
    fenced = [(1, index, candidate) for index, candidate in enumerate(_fenced_objects(text))]
    raw = [(0, index, candidate) for index, candidate in enumerate(_json_objects(text))]
    return _pick_review(fenced + raw, valid)


def repair_review(
    text: str, valid: dict[str, set[int]] | None = None
) -> tuple[str, list] | None:
    """Ask the pinned CLI's repair agent to turn a malformed message into JSON."""
    binary = os.environ.get("OPENCODE_BIN")
    model = os.environ.get("OPENCODE_MODEL", "opencode-go/deepseek-v4.1-flash")
    if not binary or not text.strip():
        print("Cannot repair the review: missing CLI or text.")
        return None

    broken = Path("build/opencode-review/broken-review.txt")
    try:
        broken.parent.mkdir(parents=True, exist_ok=True)
        broken.write_text(text)
    except OSError as exc:
        print(f"Could not write the broken review: {exc}")
        return None

    command = [
        binary,
        "run",
        "--standalone",
        "--format",
        "json",
        "--agent",
        "opencode-repair",
        "--model",
        model,
        "--file",
        str(broken),
        "Convert the attached review text into the required JSON object.",
    ]
    try:
        result = subprocess.run(command, text=True, capture_output=True, timeout=300)
    except (OSError, subprocess.TimeoutExpired) as exc:
        print(f"Repair run failed: {exc}")
        return None

    if result.returncode != 0:
        print(f"Repair run failed: {result.stderr.strip()}")
        return None

    repaired = load_assistant_text(result.stdout)
    review = extract_review(repaired, valid) if repaired else None
    if review is None:
        print("Repair agent did not return valid review JSON.")
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


def main() -> int:
    pr = os.environ.get("PR_NUMBER")
    repo = os.environ.get("GITHUB_REPOSITORY")
    if not pr or not repo:
        print("PR_NUMBER and GITHUB_REPOSITORY are required.")
        return 1

    output = Path(os.environ.get("OPENCODE_OUTPUT", "opencode-review.jsonl"))
    raw = output.read_text() if output.is_file() else ""
    text = load_assistant_text(raw)
    if not text.strip():
        # The review is advisory: surface the failure but do not red the check.
        print("::warning::The OpenCode run produced no assistant text; no review was posted.")
        return 0

    diff = DIFF_PATH.read_text() if DIFF_PATH.is_file() else gh("pr", "diff", pr).stdout
    if not diff:
        print("::warning::Could not read the pull request diff; no review was posted.")
        return 0
    valid = parse_diff(diff)

    review = extract_review(text, valid)
    if review is None:
        print("Final message is not valid review JSON; asking the repair agent.")
        review = repair_review(text, valid)
    if review is None:
        print("::warning::Could not parse or repair the review; no review was posted.")
        return 0

    summary, findings = review
    if not summary and not findings:
        print("::warning::Recovered review is empty; no review was posted.")
        return 0
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
        print("Nothing new to post.")
        return 0

    head = gh("pr", "view", pr, "--json", "headRefOid", "--jq", ".headRefOid").stdout.strip()
    if not head:
        print("::warning::Could not resolve the pull request head commit; no review was posted.")
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
        return 0

    # A single rejected line fails the whole review. Fall back to a summary
    # comment plus individual threads so a bad line only loses that finding.
    print(f"Review submission failed, falling back: {result.stderr.strip()}")
    post_summary(repo, pr, summary or DEFAULT_SUMMARY)
    posted = post_individually(repo, pr, head, comments)
    print(f"Posted {posted}/{len(comments)} inline comment(s) individually.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
