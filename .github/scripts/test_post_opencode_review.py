#!/usr/bin/env python3
"""Tests for the OpenCode review helper. Run with `python3 <this file>`."""

from __future__ import annotations

import importlib.util
import json
import os
import unittest
from pathlib import Path
from unittest import mock

SCRIPT = Path(__file__).with_name("post_opencode_review.py")
SPEC = importlib.util.spec_from_file_location("post_opencode_review", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
review = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(review)

REVIEW = {
    "summary": "Looks good.\n\n```mermaid\nflowchart TD\n  A{a} --> B{b}\n```",
    "findings": [
        {
            "path": "a.kt",
            "line": 3,
            "severity": "high",
            "body": "Use {braces} carefully.",
        }
    ],
}


def jsonl(*texts: str) -> str:
    lines = [json.dumps({"type": "step_start", "part": {"type": "step-start"}})]
    for index, text in enumerate(texts):
        lines.append(
            json.dumps(
                {
                    "type": "text",
                    "part": {"type": "text", "messageID": f"msg_{index}", "text": text},
                }
            )
        )
    return "\n".join(lines)


class ParseDiffTest(unittest.TestCase):
    def test_new_and_removed_lines_that_look_like_headers(self) -> None:
        diff = """diff --git a/foo.kt b/foo.kt
--- a/foo.kt
+++ b/foo.kt
@@ -1,3 +1,4 @@
 line1
-old
+new
+++ added line whose content starts with two plus signs
diff --git a/bar.kt b/bar.kt
--- a/bar.kt
+++ b/bar.kt
@@ -10,3 +10,3 @@
 ctx
---- removed line whose content starts with two dashes
+replacement
 ctx
"""
        self.assertEqual(
            review.parse_diff(diff),
            {"foo.kt": {1, 2, 3}, "bar.kt": {10, 11, 12}},
        )

    def test_deleted_file_has_no_lines(self) -> None:
        diff = """diff --git a/gone.kt b/gone.kt
--- a/gone.kt
+++ /dev/null
@@ -1,1 +0,0 @@
-gone
"""
        self.assertEqual(review.parse_diff(diff), {})


class LoadAssistantTextTest(unittest.TestCase):
    def test_single_text_event(self) -> None:
        self.assertEqual(review.load_assistant_text(jsonl("hello")), "hello")

    def test_multiple_text_events_are_concatenated(self) -> None:
        self.assertEqual(review.load_assistant_text(jsonl("one ", "two")), "one two")

    def test_non_text_events_are_ignored(self) -> None:
        self.assertEqual(review.load_assistant_text(jsonl()), "")

    def test_malformed_lines_are_ignored(self) -> None:
        self.assertEqual(review.load_assistant_text("not json\n" + jsonl("ok")), "ok")


class ExtractReviewTest(unittest.TestCase):
    def test_fenced_json_with_mermaid_braces(self) -> None:
        text = f"Review:\n```json\n{json.dumps(REVIEW)}\n```\n"
        self.assertEqual(review.extract_review(text), (REVIEW["summary"], REVIEW["findings"]))

    def test_raw_json(self) -> None:
        text = "prefix " + json.dumps(REVIEW) + " suffix"
        self.assertEqual(review.extract_review(text), (REVIEW["summary"], REVIEW["findings"]))

    def test_summary_only_is_accepted(self) -> None:
        text = json.dumps({"summary": "No findings."})
        self.assertEqual(review.extract_review(text), ("No findings.", []))

    def test_findings_default_to_empty_when_null(self) -> None:
        text = json.dumps({"summary": "No findings.", "findings": None})
        self.assertEqual(review.extract_review(text), ("No findings.", []))

    def test_last_matching_object_wins(self) -> None:
        example = {"summary": "EXAMPLE", "findings": []}
        text = f"Format:\n```json\n{json.dumps(example)}\n```\nReview:\n```json\n{json.dumps(REVIEW)}\n```"
        self.assertEqual(review.extract_review(text), (REVIEW["summary"], REVIEW["findings"]))

    def test_prose_without_json(self) -> None:
        self.assertIsNone(review.extract_review("Maximum steps reached. No JSON here."))

    def test_placeholder_schema_is_ignored(self) -> None:
        placeholder = {
            "summary": "Markdown overarching review comment: 2-4 sentences on what the PR does.",
            "findings": [
                {
                    "path": "app/src/main/java/.../Foo.kt",
                    "line": 123,
                    "severity": "high",
                    "body": "What is wrong, why it matters, and the concrete fix.",
                }
            ],
        }
        self.assertIsNone(review.extract_review(json.dumps(placeholder)))

    def test_real_review_wins_over_placeholder(self) -> None:
        placeholder = {
            "summary": "Markdown overarching review comment: 2-4 sentences.",
            "findings": [],
        }
        text = f"{json.dumps(placeholder)}\n{json.dumps(REVIEW)}"
        self.assertEqual(review.extract_review(text), (REVIEW["summary"], REVIEW["findings"]))

    def test_fenced_review_wins_over_trailing_shape_echo(self) -> None:
        echo = {"summary": "echo summary", "findings": []}
        text = f"```json\n{json.dumps(REVIEW)}\n```\nI will output: {json.dumps(echo)}"
        self.assertEqual(review.extract_review(text), (REVIEW["summary"], REVIEW["findings"]))

    def test_short_shape_echo_is_rejected(self) -> None:
        self.assertIsNone(review.extract_review(json.dumps({"summary": "string"})))

    def test_repair_schema_placeholder_is_ignored(self) -> None:
        placeholder = {
            "summary": "markdown summary, keep any Mermaid block",
            "findings": [
                {
                    "path": "repo/relative/path",
                    "line": 123,
                    "severity": "high",
                    "body": "concise finding and fix",
                }
            ],
        }
        self.assertIsNone(review.extract_review(json.dumps(placeholder)))

    def test_real_finding_on_line_123_is_accepted(self) -> None:
        real = {
            "summary": "A real finding.",
            "findings": [
                {"path": "a.kt", "line": 123, "severity": "low", "body": "Fix it."}
            ],
        }
        self.assertEqual(review.extract_review(json.dumps(real)), (real["summary"], real["findings"]))


class RepairReviewTest(unittest.TestCase):
    def setUp(self) -> None:
        self.env = {
            "OPENCODE_BIN": "/bin/opencode",
            "OPENCODE_MODEL": "opencode-go/deepseek-v4.1-flash",
        }

    def test_repair_returns_review(self) -> None:
        result = mock.Mock(returncode=0, stdout=jsonl(json.dumps(REVIEW)), stderr="")
        with mock.patch.dict(os.environ, self.env), mock.patch.object(
            review.subprocess, "run", return_value=result
        ):
            self.assertEqual(review.repair_review("broken text"), (REVIEW["summary"], REVIEW["findings"]))

    def test_repair_failure_is_not_fatal(self) -> None:
        result = mock.Mock(returncode=1, stdout="", stderr="boom")
        with mock.patch.dict(os.environ, self.env), mock.patch.object(
            review.subprocess, "run", return_value=result
        ):
            self.assertIsNone(review.repair_review("broken text"))

    def test_repair_without_binary(self) -> None:
        with mock.patch.dict(os.environ, {}, clear=True):
            self.assertIsNone(review.repair_review("broken text"))


class DescribeCommentTest(unittest.TestCase):
    def setUp(self) -> None:
        self.valid = {"a.kt": {3, 4}}

    def test_valid_finding(self) -> None:
        comment = review.describe_comment(
            {"path": "a.kt", "line": 3, "severity": "high", "body": "Fix it."},
            self.valid,
        )
        self.assertIsNotNone(comment)
        assert comment is not None
        self.assertEqual(comment["body"], "**[high]** Fix it.")
        self.assertEqual(comment["side"], "RIGHT")

    def test_unknown_line_is_skipped(self) -> None:
        self.assertIsNone(
            review.describe_comment(
                {"path": "a.kt", "line": 99, "severity": "low", "body": "Fix it."},
                self.valid,
            )
        )

    def test_non_dict_is_skipped(self) -> None:
        self.assertIsNone(review.describe_comment("not a finding", self.valid))


if __name__ == "__main__":
    unittest.main()
