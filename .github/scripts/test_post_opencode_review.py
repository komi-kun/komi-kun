#!/usr/bin/env python3
"""Tests for the OpenCode review helper. Run with `python3 <this file>`."""

from __future__ import annotations

import importlib.util
import json
import os
import unittest
import urllib.error
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


class FakeResponse:
    def __init__(self, body: bytes) -> None:
        self._body = body

    def __enter__(self) -> FakeResponse:
        return self

    def __exit__(self, *args: object) -> bool:
        return False

    def read(self) -> bytes:
        return self._body


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


class ExtractReviewTest(unittest.TestCase):
    def test_fenced_json_with_mermaid_braces(self) -> None:
        text = f"Review:\n```json\n{json.dumps(REVIEW)}\n```\n\n[github run](/x)"
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


class RepairReviewTest(unittest.TestCase):
    def test_repair_returns_review(self) -> None:
        payload = {"choices": [{"message": {"content": json.dumps(REVIEW)}}]}
        response = FakeResponse(json.dumps(payload).encode())
        with mock.patch.dict(os.environ, {"OPENCODE_API_KEY": "test"}), mock.patch.object(
            review.urllib.request, "urlopen", return_value=response
        ):
            self.assertEqual(review.repair_review("broken text"), (REVIEW["summary"], REVIEW["findings"]))

    def test_repair_handles_content_parts(self) -> None:
        payload = {
            "choices": [
                {"message": {"content": [{"type": "text", "text": json.dumps(REVIEW)}]}}
            ]
        }
        response = FakeResponse(json.dumps(payload).encode())
        with mock.patch.dict(os.environ, {"OPENCODE_API_KEY": "test"}), mock.patch.object(
            review.urllib.request, "urlopen", return_value=response
        ):
            self.assertEqual(review.repair_review("broken text"), (REVIEW["summary"], REVIEW["findings"]))

    def test_repair_failure_is_not_fatal(self) -> None:
        with mock.patch.dict(os.environ, {"OPENCODE_API_KEY": "test"}), mock.patch.object(
            review.urllib.request, "urlopen", side_effect=urllib.error.URLError("offline")
        ):
            self.assertIsNone(review.repair_review("broken text"))

    def test_repair_without_api_key(self) -> None:
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
