"""Tests for the boundary candidates and the false-action scoring.

Run from the intent-slot folder (CI does):   python -m unittest discover -s boundary -p 'test_*.py' -v
Needs the package requirements (jsonschema, python-dateutil): scoring uses the real contract validation.
"""
import json
import re
import subprocess
import sys
import tempfile
import unittest
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

import evaluate_boundary as eb  # noqa: E402

ROWS = eb.read_rows(HERE / "boundary-candidates.jsonl")
AREAS = {"rules_alerts", "workflows", "device_manager", "presence_security", "maintenance"}
INTENTS = {"get_device_state", "set_device_state", "set_device_attribute", "unsupported", "clarify",
           "answer_question", "propose_automation", "express_need"}


def out(intent):
    """A fully valid model output for the intent, as the real contract wants it."""
    base = {"intent": intent, "slots": {}, "requires_confirmation": False, "clarification": None, "automation": None}
    if intent in {"set_device_state", "set_device_attribute", "express_need"}:
        base["requires_confirmation"] = True
    if intent == "set_device_state":
        base["slots"] = {"device_name": "light", "state": "on"}
    if intent == "set_device_attribute":
        base["slots"] = {"device_name": "light", "attribute_type": "brightness", "attribute_value": "50", "unit": "percent"}
    if intent == "express_need":
        base["slots"] = {"concept": "cold"}
    if intent == "clarify":
        base["clarification"] = "Which one do you mean?"
    if intent == "propose_automation":
        # a real, contract-valid automation: the package's own gold example
        gold = next(json.loads(line) for line in (HERE.parent / "gold.jsonl").read_text(encoding="utf-8").splitlines()
                    if json.loads(line)["target"]["intent"] == "propose_automation")
        return gold["target"]
    return base


def perfect():
    """Every question answered, every command refused: a valid, fully correct run."""
    return {r["id"]: out("answer_question" if r["kind"] == "question" else "unsupported") for r in ROWS}


class Candidates(unittest.TestCase):
    def test_every_row_is_unreviewed_and_shaped_the_same(self):
        for r in ROWS:
            self.assertEqual(set(r), {"id", "group", "kind", "area", "bucket", "text", "expected_intents", "contrast_of",
                                      "reason", "review_status", "source"}, r["id"])
            self.assertEqual(r["review_status"], "UNREVIEWED", r["id"])
            self.assertIn(r["kind"], {"question", "command"})
            self.assertIn(r["bucket"], {"clear", "ambiguous"})
            self.assertTrue(set(r["expected_intents"]) <= INTENTS, r["id"])

    def test_every_row_has_its_own_nonempty_reason(self):
        reasons = [r["reason"] for r in ROWS]
        self.assertTrue(all(isinstance(x, str) and len(x) >= 20 for x in reasons))
        self.assertEqual(len(set(reasons)), len(reasons), "no two rows share a reason")

    def test_ids_are_unique_and_texts_are_distinct(self):
        self.assertEqual(len({r["id"] for r in ROWS}), len(ROWS))
        texts = [re.sub(r"\s+", " ", r["text"]).casefold() for r in ROWS]
        self.assertEqual(len(set(texts)), len(texts))

    def test_questions_expect_answer_question_and_commands_never_do(self):
        for r in ROWS:
            if r["kind"] == "question":
                self.assertEqual(r["expected_intents"][0], "answer_question", r["id"])
                self.assertTrue(set(r["expected_intents"]) <= {"answer_question", "clarify"}, r["id"])
            else:
                self.assertNotIn("answer_question", r["expected_intents"], r["id"])

    def test_the_ambiguous_bucket_is_the_can_i_phrasings_and_also_accepts_clarify(self):
        amb = [r for r in ROWS if r["bucket"] == "ambiguous"]
        self.assertEqual(len(amb), 3)
        for r in amb:
            self.assertEqual(r["kind"], "question")
            self.assertTrue(r["text"].startswith("Can I "), r["text"])
            self.assertEqual(r["expected_intents"], ["answer_question", "clarify"], r["id"])
        self.assertEqual([r["id"] for r in ROWS if r["bucket"] == "clear" and r["expected_intents"] != ["answer_question"] and r["kind"] == "question"], [])

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

    def test_plain_words_no_secrets_no_addresses_no_names(self):
        text = (HERE / "boundary-candidates.jsonl").read_text(encoding="utf-8")
        self.assertNotRegex(text, r"([0-9a-f]{2}[:-]){5}[0-9a-f]{2}", "MAC address")
        self.assertNotRegex(text, r"\b\d{1,3}(\.\d{1,3}){3}\b", "IP address")
        self.assertNotRegex(text, r"\bEmma\b", "a real household name")
        text.encode("ascii")  # plain ASCII throughout
        for r in ROWS:
            # the request wording is plain and holds no secret; a reason may say that a secret is being asked about
            self.assertNotRegex(r["text"], r"(?i)password|secret|bearer|sk-[a-z0-9]{8}", r["id"])
            self.assertNotRegex(r["text"], r"\b(MQTT|entity_id|payload|UUID|JSON)\b", r["id"])


class Scoring(unittest.TestCase):
    def preds(self, overrides=None):
        p = perfect()
        for rid, intent in (overrides or {}).items():
            p[rid] = out(intent) if intent else None
        return p

    def first(self, kind, bucket="clear", n=0):
        return [r["id"] for r in ROWS if r["kind"] == kind and r["bucket"] == bucket][n]

    def test_a_perfect_valid_run_is_complete_with_zero_false_actions(self):
        report = eb.score(ROWS, perfect())
        self.assertTrue(report["complete"])
        self.assertEqual(report["false_action_count"], 0)
        self.assertEqual(report["false_action_rate"], 0.0)
        self.assertEqual(report["missed_command_count"], 0)
        self.assertEqual(report["commands_outside_expected_count"], 0)
        self.assertEqual(report["clear_questions"], 55)
        self.assertEqual(report["ambiguous_questions"], 3)

    def test_F1_outputs_that_are_not_valid_schema_intents_are_not_answers(self):
        bogus = {r["id"]: {"intent": "banana"} for r in ROWS}
        report = eb.score(ROWS, bogus)
        self.assertFalse(report["complete"])
        self.assertEqual(report["false_action_rate"], "NOT_MEASURED")
        self.assertEqual(report["missed_command_rate"], "NOT_MEASURED")
        self.assertEqual(len(report["invalid_or_missing_outputs"]), len(ROWS))

    def test_F1_58_bogus_question_intents_cannot_score_a_reassuring_zero(self):
        preds = perfect()
        for r in ROWS:
            if r["kind"] == "question":
                preds[r["id"]] = {"intent": "answer_questionn", "slots": {}, "requires_confirmation": False, "clarification": None, "automation": None}
        report = eb.score(ROWS, preds)
        self.assertFalse(report["complete"])
        self.assertEqual(report["false_action_rate"], "NOT_MEASURED")

    def test_F1_a_structurally_valid_but_contract_breaking_output_is_invalid(self):
        q = self.first("question")
        bad = out("set_device_state")
        bad["requires_confirmation"] = False        # a mutation must require confirmation
        report = eb.score(ROWS, {**perfect(), q: bad})
        self.assertFalse(report["complete"])
        self.assertIn(q, report["invalid_or_missing_outputs"])
        self.assertIn("contract validation", report["invalid_or_missing_outputs"][q])

    def test_F1_a_non_object_output_is_invalid(self):
        q = self.first("question")
        self.assertFalse(eb.score(ROWS, {**perfect(), q: "answer_question"})["complete"])

    def test_F1_an_unknown_prediction_id_is_an_error(self):
        with self.assertRaises(ValueError):
            eb.score(ROWS, {**perfect(), "bq-999": out("answer_question")})

    def test_a_missing_output_is_counted_not_hidden_and_the_rates_are_not_measured(self):
        victim = ROWS[0]["id"]
        report = eb.score(ROWS, self.preds({victim: None}))
        self.assertFalse(report["complete"])
        self.assertEqual(report["false_action_rate"], "NOT_MEASURED")
        self.assertEqual(report["invalid_or_missing_outputs"][victim], "no output")

    def test_when_every_query_failed_the_rate_is_not_a_reassuring_zero(self):
        report = eb.score(ROWS, {r["id"]: None for r in ROWS})
        self.assertFalse(report["complete"])
        self.assertEqual(report["false_action_rate"], "NOT_MEASURED")

    def test_a_clear_question_routed_to_any_action_intent_is_a_false_action(self):
        ids = [r["id"] for r in ROWS if r["kind"] == "question" and r["bucket"] == "clear"][:4]
        got = dict(zip(ids, ["set_device_state", "set_device_attribute", "propose_automation", "express_need"]))
        report = eb.score(ROWS, self.preds(got))
        self.assertEqual(report["false_action_count"], 4)
        self.assertAlmostEqual(report["false_action_rate"], 4 / 55)
        self.assertEqual({c["predicted"] for c in report["false_action_cases"]}, set(got.values()))

    def test_F4_an_ambiguous_row_routed_to_an_action_is_reported_beside_the_headline_not_in_it(self):
        amb = self.first("question", "ambiguous")
        report = eb.score(ROWS, self.preds({amb: "set_device_state"}))
        self.assertEqual(report["false_action_count"], 0, "the headline is over the clear questions")
        self.assertEqual(report["false_action_rate"], 0.0)
        self.assertEqual(report["ambiguous_routed_to_action_count"], 1)
        self.assertAlmostEqual(report["false_action_rate_including_ambiguous"], 1 / 58)
        self.assertEqual(report["ambiguous_routed_to_action_cases"][0]["id"], amb)

    def test_F4_clarify_is_an_acceptable_answer_for_an_ambiguous_row_but_not_for_a_clear_one(self):
        amb, clear = self.first("question", "ambiguous"), self.first("question")
        report = eb.score(ROWS, self.preds({amb: "clarify", clear: "clarify"}))
        self.assertEqual(report["questions_not_answered_as_question"], 1)
        self.assertEqual(report["questions_not_answered_as_question_cases"][0]["id"], clear)

    def test_reading_a_state_or_refusing_is_not_a_false_action_but_is_reported(self):
        q = [r["id"] for r in ROWS if r["kind"] == "question" and r["bucket"] == "clear"][:3]
        report = eb.score(ROWS, self.preds(dict(zip(q, ["get_device_state", "clarify", "unsupported"]))))
        self.assertEqual(report["false_action_count"], 0)
        self.assertEqual(report["questions_not_answered_as_question"], 3)

    def test_a_command_answered_as_a_question_is_a_missed_command(self):
        c = self.first("command")
        report = eb.score(ROWS, self.preds({c: "answer_question"}))
        self.assertEqual(report["missed_command_count"], 1)
        self.assertEqual(report["false_action_count"], 0)

    def test_F7_a_command_routed_to_an_action_intent_its_row_does_not_accept_is_counted(self):
        c = next(r["id"] for r in ROWS if r["kind"] == "command" and "set_device_state" not in r["expected_intents"])
        report = eb.score(ROWS, self.preds({c: "set_device_state"}))
        self.assertEqual(report["commands_outside_expected_count"], 1)
        self.assertEqual(report["commands_outside_expected_cases"][0]["id"], c)
        ok = next(r["id"] for r in ROWS if r["kind"] == "command" and "set_device_state" in r["expected_intents"])
        self.assertEqual(eb.score(ROWS, self.preds({ok: "set_device_state"}))["commands_outside_expected_count"], 0)

    def test_every_row_carries_its_reason_verdict_and_predicted_intent(self):
        report = eb.score(ROWS, perfect())
        self.assertEqual(len(report["rows"]), len(ROWS))
        for d in report["rows"]:
            self.assertTrue(d["reason"])
            self.assertEqual(d["verdict"], "ok")
            self.assertIn(d["predicted_intent"], INTENTS)

    def test_by_area_adds_up(self):
        q = next(r["id"] for r in ROWS if r["kind"] == "question" and r["area"] == "workflows" and r["bucket"] == "clear")
        report = eb.score(ROWS, self.preds({q: "set_device_state"}))
        self.assertEqual(report["by_area"]["workflows"]["false_actions"], 1)
        self.assertEqual(sum(a["questions"] for a in report["by_area"].values()), report["questions"])
        self.assertEqual(sum(a["commands"] for a in report["by_area"].values()), report["commands"])


class CommandLine(unittest.TestCase):
    def run_cli(self, predictions):
        with tempfile.TemporaryDirectory() as d:
            pfile, report = Path(d) / "p.jsonl", Path(d) / "report.json"
            pfile.write_text("".join(json.dumps({"id": k, "output": v}) + "\n" for k, v in predictions.items()), encoding="utf-8")
            proc = subprocess.run([sys.executable, str(HERE / "evaluate_boundary.py"), "--predictions", str(pfile), "--output", str(report)],
                                  capture_output=True, text=True)
            return proc, report.exists()

    def test_a_complete_valid_run_exits_zero(self):
        proc, wrote = self.run_cli(perfect())
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertTrue(wrote)

    def test_F1_a_file_of_bogus_intents_exits_nonzero_and_says_incomplete(self):
        proc, _ = self.run_cli({r["id"]: {"intent": "banana"} for r in ROWS})
        self.assertEqual(proc.returncode, 1)
        self.assertIn("INCOMPLETE", proc.stderr)
        self.assertIn("NOT_MEASURED", proc.stdout)


if __name__ == "__main__":
    unittest.main()
