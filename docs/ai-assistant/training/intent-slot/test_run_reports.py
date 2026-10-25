import json
import tempfile
import unittest
from pathlib import Path

import run_reports as rr


def gold_file(tmp):
    rows = [{"id": "gold-01", "target": {"intent": "set_device_state"}},
            {"id": "gold-02", "target": {"intent": "clarify"}},
            {"id": "gold-03", "target": {"intent": "unsupported"}}]
    path = Path(tmp) / "gold.jsonl"
    path.write_text("".join(json.dumps(r) + "\n" for r in rows), encoding="utf-8")
    return path


def baseline(**over):
    report = {"model": "cabin-intent-slot-prompt-r1", "count": 3, "valid_output_rate": 1.0, "intent_accuracy": 2 / 3,
              "slot_micro_f1": 0.5, "slot_counts": {"tp": 1, "fp": 1, "fn": 1}, "critical_fixture_failures": 1,
              "offline_gates": {"structured_output": True, "intent_accuracy": False, "slot_f1": False,
                                "critical_fixture_failures": False},
              "integration_gates": {"accepted_invented_entities": "NOT_MEASURED", "rag_regression": "NOT_MEASURED"},
              "release_eligible": False, "gold_sha256": "abc",
              "timings_seconds": {"gold-01": 30.0, "gold-02": 2.0, "gold-03": 4.0},
              "transport_errors": {},
              "details": [{"id": "gold-01", "intent_correct": True}, {"id": "gold-02", "intent_correct": True},
                          {"id": "gold-03", "intent_correct": False}]}
    report.update(over)
    return report


class BaselineEvidence(unittest.TestCase):
    def test_percentile_is_nearest_rank(self):
        self.assertEqual(rr.percentile([1, 2, 3, 4], 50), 2)
        self.assertEqual(rr.percentile([1, 2, 3, 4], 95), 4)
        self.assertIsNone(rr.percentile([], 50))

    def test_the_first_request_is_kept_apart_from_the_warm_percentiles(self):
        with tempfile.TemporaryDirectory() as tmp:
            text = rr.baseline_evidence(baseline(), gold_file(tmp), peak_rss_mib=4096, digest="sha256:feed")
        self.assertIn("First request (includes model load) | 30.00", text)
        self.assertIn("p50 (excluding first) | 2.00", text)
        self.assertIn("p95 (excluding first) | 4.00", text)
        self.assertIn("Peak RSS during the run: 4096 MiB", text)
        self.assertIn("sha256:feed", text)

    def test_it_never_calls_the_run_quality_or_release_evidence(self):
        with tempfile.TemporaryDirectory() as tmp:
            text = rr.baseline_evidence(baseline(), gold_file(tmp))
        self.assertIn("**not** a quality result", text)
        self.assertIn("pending-human-review", text)
        self.assertIn("`release_eligible` is false", text)
        self.assertIn("NOT_MEASURED", text)
        self.assertIn("No SFT training ran", text)

    def test_failed_gates_and_per_intent_slices_are_shown_as_they_are(self):
        with tempfile.TemporaryDirectory() as tmp:
            text = rr.baseline_evidence(baseline(), gold_file(tmp))
        self.assertIn("| Intent accuracy | 66.7% | 90% | False |", text)
        self.assertIn("clarify 1/1", text)
        self.assertIn("unsupported 0/1", text)

    def test_timeouts_are_counted_and_missing_peak_memory_is_not_invented(self):
        with tempfile.TemporaryDirectory() as tmp:
            text = rr.baseline_evidence(baseline(transport_errors={"gold-03": "TimeoutError"}), gold_file(tmp))
        self.assertIn("Timeouts / transport errors | 1 / 1", text)
        self.assertIn("Peak RSS during the run: n/a", text)
        self.assertIn("not recorded", text)


def entry(**over):
    base = {"requestId": "r", "at": "2026-10-17T10:00:00Z", "principal": "a@example.test", "householdRole": "ADMINISTRATOR",
            "model": "cabin-intent-slot-prompt-r1", "input": "turn on the heater", "rawModelOutput": "{}",
            "modelLatencyMillis": 4000, "validation": "OK", "intent": "set_device_state", "status": "PROPOSAL", "code": None,
            "decisions": [{"role": "ACTION", "outcome": "RESOLVED", "entityId": "heater_mech_room", "reasons": ["matched"]}],
            "proposalId": "p", "planHash": "f" * 64, "totalLatencyMillis": 4100}
    base.update(over)
    return base


class ShadowReport(unittest.TestCase):
    def test_lines_with_a_log_prefix_are_parsed_and_noise_is_skipped(self):
        lines = ["2026-10-17 10:00:00 INFO assistant.proposal.audit : " + json.dumps(entry()),
                 "some other log line", "{not json", json.dumps({"unrelated": True}), ""]
        self.assertEqual(len(rr.parse_audit_lines(lines)), 1)

    def test_counts_by_intent_status_code_and_resolver_outcome(self):
        entries = [entry(),
                   entry(requestId="2", status="REFUSED", code="RESOLUTION_FAILED",
                         decisions=[{"role": "ACTION", "outcome": "AMBIGUOUS", "entityId": None, "reasons": ["2 devices fit"]}]),
                   entry(requestId="3", intent=None, status="REFUSED", code="MALFORMED_MODEL_OUTPUT", decisions=[],
                         validation="INVALID: model output is not JSON")]
        text = rr.shadow_report(entries)
        self.assertIn("3 propose call(s)", text)
        self.assertIn("| AMBIGUOUS | 1 |", text)
        self.assertIn("| RESOLUTION_FAILED | 1 |", text)
        self.assertIn("| MALFORMED_MODEL_OUTPUT | 1 |", text)
        self.assertIn("Model failures (1)", text)
        self.assertIn("nothing ran", text)

    def test_every_resolved_target_is_listed_for_a_person_to_judge(self):
        text = rr.shadow_report([entry(), entry(requestId="2", input="turn on the lights", status="TARGET",
                                                decisions=[{"role": "ACTION", "outcome": "RESOLVED", "entityId": "light_hall", "reasons": []}])])
        self.assertIn("Every target that resolved (2)", text)
        self.assertIn("heater_mech_room", text)
        self.assertIn("light_hall", text)
        self.assertIn("a person marks each right or wrong", text)

    def test_every_refusal_is_listed_with_its_reason(self):
        text = rr.shadow_report([entry(status="REFUSED", code="RESOLUTION_FAILED",
                                       decisions=[{"role": "ACTION", "outcome": "WRONG_SITE", "entityId": None,
                                                   "reasons": ["exists, but not at site home"]}])])
        self.assertIn("Every refused or ambiguous target (1)", text)
        self.assertIn("WRONG_SITE | exists, but not at site home", text)

    def test_since_filters_and_an_empty_log_still_reports(self):
        entries = [entry(at="2026-10-16T10:00:00Z"), entry(requestId="2", at="2026-10-18T10:00:00Z")]
        self.assertIn("1 propose call(s)", rr.shadow_report(entries, since="2026-10-17T00:00:00Z"))
        self.assertIn("0 propose call(s)", rr.shadow_report([]))

    def test_latency_percentiles_use_model_and_whole_request_times(self):
        entries = [entry(requestId=str(i), modelLatencyMillis=1000 * i, totalLatencyMillis=1000 * i + 100) for i in range(1, 11)]
        text = rr.shadow_report(entries)
        self.assertIn("| Model | 5.00 | 10.00 | 10.00 |", text)
        self.assertIn("| Whole request | 5.10 | 10.10 | 10.10 |", text)


if __name__ == "__main__":
    unittest.main()
