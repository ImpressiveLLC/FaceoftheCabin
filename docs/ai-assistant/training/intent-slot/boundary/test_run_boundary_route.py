import unittest

import run_boundary_route as r


class VerdictTest(unittest.TestCase):
    def test_a_question_that_becomes_a_proposal_is_a_false_action(self):
        self.assertEqual(r.verdict("question", "PROPOSAL"), "false_action")

    def test_a_question_answered_or_clarified_is_fine(self):
        self.assertEqual(r.verdict("question", "ANSWER"), "ok")
        self.assertEqual(r.verdict("question", "CLARIFY"), "ok")

    def test_a_command_answered_as_a_question_is_missed(self):
        self.assertEqual(r.verdict("command", "ANSWER"), "missed_command")

    def test_a_command_proposed_clarified_or_refused_is_fine(self):
        for status in ("PROPOSAL", "CLARIFY", "REFUSED", "TARGET"):
            self.assertEqual(r.verdict("command", status), "ok", status)

    def test_no_response_is_never_ok(self):
        self.assertEqual(r.verdict("question", None), "no_response")
        self.assertEqual(r.verdict("command", None), "no_response")


class ScoreTest(unittest.TestCase):
    rows = [{"id": "q1", "kind": "question", "area": "a", "bucket": "clear"},
            {"id": "q2", "kind": "question", "area": "a", "bucket": "clear"},
            {"id": "c1", "kind": "command", "area": "a"}]

    def test_counts_rates_and_latency(self):
        out = r.score(self.rows, {"q1": {"status": "ANSWER", "millis": 100}, "q2": {"status": "PROPOSAL", "millis": 300},
                                  "c1": {"status": "ANSWER", "millis": 200}})
        self.assertTrue(out["complete"])
        self.assertEqual(out["question_to_action_count"], 1)
        self.assertEqual(out["question_to_action_rate"], 0.5)
        self.assertEqual(out["missed_command_count"], 1)
        self.assertEqual(out["false_action_ids"], ["q2"])
        self.assertEqual(out["latency_ms"]["p50"], 200)

    def test_one_failed_call_makes_the_rates_not_measured(self):
        out = r.score(self.rows, {"q1": {"status": "ANSWER", "millis": 1}, "q2": {"status": None, "millis": 1},
                                  "c1": {"status": "PROPOSAL", "millis": 1}})
        self.assertFalse(out["complete"])
        self.assertEqual(out["question_to_action_rate"], "NOT_MEASURED")
        self.assertEqual(out["missed_command_rate"], "NOT_MEASURED")
        self.assertEqual(out["no_response_count"], 1)

    def test_the_report_never_carries_answer_text(self):
        out = r.score(self.rows, {"q1": {"status": "ANSWER", "intent": "answer_question", "code": None, "millis": 1, "answer": "SECRET"},
                                  "q2": {"status": "CLARIFY", "millis": 1}, "c1": {"status": "PROPOSAL", "millis": 1}})
        self.assertNotIn("SECRET", str(out))


if __name__ == "__main__":
    unittest.main()
