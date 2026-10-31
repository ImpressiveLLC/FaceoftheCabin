"""Tests for the boundary candidates and the false-action scoring. Run from this folder: python -m unittest test_boundary -v"""
import json
import re
import unittest
from collections import Counter
from pathlib import Path

import evaluate_boundary as eb

HERE = Path(__file__).resolve().parent
ROWS = eb.read_rows(HERE / "boundary-candidates.jsonl")
AREAS = {"rules_alerts", "workflows", "device_manager", "presence_security", "maintenance"}
INTENTS = {"get_device_state", "set_device_state", "set_device_attribute", "unsupported", "clarify",
           "answer_question", "propose_automation", "express_need"}


class Candidates(unittest.TestCase):
    def test_every_row_is_unreviewed_and_shaped_the_same(self):
        for r in ROWS:
            self.assertEqual(set(r), {"id", "group", "kind", "area", "text", "expected_intents", "contrast_of", "review_status", "source"}, r["id"])
            self.assertEqual(r["review_status"], "UNREVIEWED", r["id"])
            self.assertIn(r["kind"], {"question", "command"})
            self.assertTrue(set(r["expected_intents"]) <= INTENTS, r["id"])

    def test_ids_are_unique_and_texts_are_distinct(self):
        self.assertEqual(len({r["id"] for r in ROWS}), len(ROWS))
        texts = [re.sub(r"\s+", " ", r["text"]).casefold() for r in ROWS]
        self.assertEqual(len(set(texts)), len(texts))

    def test_questions_expect_answer_question_and_commands_never_do(self):
        for r in ROWS:
            if r["kind"] == "question":
                self.assertEqual(r["expected_intents"], ["answer_question"], r["id"])
            else:
                self.assertNotIn("answer_question", r["expected_intents"], r["id"])

    def test_every_area_the_refocus_order_names_is_covered_with_both_kinds(self):
        counts = Counter((r["area"], r["kind"]) for r in ROWS)
        for area in AREAS:
            self.assertGreaterEqual(counts[(area, "question")], 8, area)
            self.assertGreaterEqual(counts[(area, "command")], 4, area)
        self.assertEqual({r["area"] for r in ROWS}, AREAS)

    def test_contrast_commands_point_at_a_real_question_in_the_same_area(self):
        by_id = {r["id"]: r for r in ROWS}
        pairs = [r for r in ROWS if r["contrast_of"]]
        self.assertGreaterEqual(len(pairs), 25)
        for r in pairs:
            q = by_id[r["contrast_of"]]
            self.assertEqual(q["kind"], "question")
            self.assertEqual(q["area"], r["area"])
            self.assertEqual(q["group"], r["group"], "a pair shares its group, so a split never separates it")

    def test_plain_words_no_secrets_no_addresses(self):
        text = (HERE / "boundary-candidates.jsonl").read_text(encoding="utf-8")
        self.assertNotRegex(text, r"([0-9a-f]{2}[:-]){5}[0-9a-f]{2}", "MAC address")
        self.assertNotRegex(text, r"\b\d{1,3}(\.\d{1,3}){3}\b", "IP address")
        self.assertNotRegex(text, r"(?i)password|secret|bearer|sk-[a-z0-9]{8}")
        for r in ROWS:
            self.assertNotRegex(r["text"], r"\b(MQTT|entity_id|payload|UUID|JSON)\b", r["id"])


class Scoring(unittest.TestCase):
    def predictions(self, mapping, default="answer_question"):
        return {r["id"]: {"intent": mapping.get(r["id"], default if r["kind"] == "question" else "unsupported")} for r in ROWS}

    def test_a_perfect_route_has_a_zero_false_action_rate(self):
        report = eb.score(ROWS, self.predictions({}))
        self.assertEqual(report["false_action_count"], 0)
        self.assertEqual(report["false_action_rate"], 0.0)
        self.assertEqual(report["missed_command_count"], 0)

    def test_a_question_routed_to_any_command_or_proposal_intent_is_a_false_action(self):
        q = [r["id"] for r in ROWS if r["kind"] == "question"][:4]
        got = {q[0]: "set_device_state", q[1]: "set_device_attribute", q[2]: "propose_automation", q[3]: "express_need"}
        report = eb.score(ROWS, self.predictions(got))
        self.assertEqual(report["false_action_count"], 4)
        self.assertAlmostEqual(report["false_action_rate"], 4 / report["questions"])
        self.assertEqual({c["predicted"] for c in report["false_action_cases"]}, set(got.values()))

    def test_reading_a_state_clarifying_or_refusing_is_not_a_false_action_but_is_reported(self):
        q = [r["id"] for r in ROWS if r["kind"] == "question"][:3]
        got = {q[0]: "get_device_state", q[1]: "clarify", q[2]: "unsupported"}
        report = eb.score(ROWS, self.predictions(got))
        self.assertEqual(report["false_action_count"], 0)
        self.assertEqual(report["questions_not_answered_as_question"], 3)

    def test_a_command_answered_as_a_question_is_a_missed_command(self):
        c = next(r["id"] for r in ROWS if r["kind"] == "command")
        report = eb.score(ROWS, self.predictions({c: "answer_question"}))
        self.assertEqual(report["missed_command_count"], 1)
        self.assertEqual(report["false_action_count"], 0)

    def test_a_missing_or_failed_output_is_counted_not_hidden(self):
        preds = self.predictions({})
        victim = ROWS[0]["id"]
        preds[victim] = None
        report = eb.score(ROWS, preds)
        self.assertEqual(report["rows_without_output"], [victim])
        self.assertEqual(report["false_action_count"], 0)
        self.assertFalse(report["complete"])
        self.assertEqual(report["false_action_rate"], "NOT_MEASURED", "a rate over a partial run is not a measurement")
        self.assertEqual(report["missed_command_rate"], "NOT_MEASURED")

    def test_when_every_query_failed_the_rate_is_not_a_reassuring_zero(self):
        report = eb.score(ROWS, {r["id"]: None for r in ROWS})
        self.assertFalse(report["complete"])
        self.assertEqual(report["false_action_rate"], "NOT_MEASURED")
        self.assertEqual(len(report["rows_without_output"]), len(ROWS))

    def test_a_complete_run_reports_real_rates(self):
        report = eb.score(ROWS, self.predictions({}))
        self.assertTrue(report["complete"])
        self.assertEqual(report["false_action_rate"], 0.0)

    def test_by_area_adds_up(self):
        q = next(r["id"] for r in ROWS if r["kind"] == "question" and r["area"] == "workflows")
        report = eb.score(ROWS, self.predictions({q: "set_device_state"}))
        self.assertEqual(report["by_area"]["workflows"]["false_actions"], 1)
        self.assertEqual(sum(a["questions"] for a in report["by_area"].values()), report["questions"])
        self.assertEqual(sum(a["commands"] for a in report["by_area"].values()), report["commands"])


if __name__ == "__main__":
    unittest.main()
