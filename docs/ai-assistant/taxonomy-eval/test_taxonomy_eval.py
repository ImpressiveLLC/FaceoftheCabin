import copy
import json
import re
import unittest
from pathlib import Path

import extract_elements as ee
import questions_check as qc
import verdicts as v

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]


def load(name):
    return json.loads((HERE / name).read_text(encoding="utf-8"))


TOPICS = v.load_topics()
TOPIC_IDS = {t["id"] for t in TOPICS}
ELEMENT_IDS = v.load_element_ids()
QUESTIONS = qc.load_questions()
DRAFTED = qc.edge_tags_drafted()


class Topics(unittest.TestCase):
    def test_six_topics_two_views_unique_ids(self):
        self.assertEqual([t["kind"] for t in TOPICS].count("topic"), 6)
        self.assertEqual([t["kind"] for t in TOPICS].count("view"), 2)
        self.assertEqual(len(TOPIC_IDS), len(TOPICS))
        self.assertEqual({t["id"] for t in TOPICS if t["kind"] == "view"}, {"alert_history", "occupancy"})

    def test_descriptions_carry_the_distinction_and_nothing_a_probe_must_not_see(self):
        for t in TOPICS:
            self.assertTrue(10 < len(t["description"]) <= 220, t["id"])
            self.assertNotRegex(t["description"], r"\bR[0-9]+\b|ruling|edge test|rationale", t["id"])
        views = [t for t in TOPICS if t["kind"] == "view"]
        self.assertTrue(all("computed view" in t["description"] for t in views))


class Elements(unittest.TestCase):
    def test_snapshot_is_internally_consistent(self):
        data = load("elements.json")
        self.assertEqual(sum(data["counts"].values()), data["total"])
        self.assertEqual(len(data["elements"]), data["total"])
        self.assertEqual(len({e["id"] for e in data["elements"]}), data["total"])
        self.assertRegex(data["sha256"], r"^[0-9a-f]{64}$")
        self.assertRegex(data["git_blob"], r"^[0-9a-f]{40}$")
        for cls in ("raw", "derived", "composite", "conceptual"):
            self.assertIn(cls, data["counts"])

    def test_reader_handles_crlf_folded_definitions_and_quoted_values(self):
        sample = ("version: 1\r\nelements:\r\n  - id: alpha\r\n    layer: protocol\r\n    data_class: raw\r\n    primitive: true\r\n"
                  "    ui_display_name: \"Alpha\"\r\n    definition: >\r\n      First line\r\n      second line.\r\n    notes: ignored\r\n"
                  "  - id: beta\r\n    data_class: derived\r\n    definition: single line\r\n")
        found = ee.parse(sample)
        self.assertEqual([e["id"] for e in found], ["alpha", "beta"])
        self.assertEqual(found[0]["definition"], "First line second line.")
        self.assertEqual(found[0]["ui_display_name"], "Alpha")
        self.assertEqual(found[1]["data_class"], "derived")

    def test_reader_agrees_with_pyyaml_when_it_is_installed(self):
        try:
            import yaml
        except ImportError:
            self.skipTest("PyYAML not installed")
        text = (REPO / "docs" / "ontology.yaml").read_text(encoding="utf-8")
        reference = yaml.safe_load(text)["elements"]
        ours = ee.parse(text)
        self.assertEqual([e["id"] for e in ours], [e["id"] for e in reference])
        for mine, theirs in zip(ours, reference):
            self.assertEqual(mine.get("data_class"), theirs.get("data_class"), mine["id"])
            self.assertEqual(mine.get("layer"), theirs.get("layer"), mine["id"])

    def test_snapshot_function_hashes_lf_normalized_text(self):
        a = ee.snapshot("elements:\n  - id: x\n    data_class: raw\n")
        b = ee.snapshot("elements:\r\n  - id: x\r\n    data_class: raw\r\n")
        self.assertEqual(a["sha256"], b["sha256"])
        self.assertEqual(a["git_blob"], b["git_blob"])
        with self.assertRaises(ValueError):
            ee.snapshot("elements:\n  - id: x\n  - id: x\n")


class QuestionSet(unittest.TestCase):
    def check(self, data):
        return qc.check(data, TOPICS, ELEMENT_IDS, DRAFTED)[0]

    def test_the_shipped_set_meets_every_section_5_1_rule(self):
        problems, coverage = qc.check(QUESTIONS, TOPICS, ELEMENT_IDS, DRAFTED)
        self.assertEqual(problems, [])
        self.assertTrue(25 <= coverage["questions"] <= 30)
        self.assertTrue(all(n >= 3 for n in coverage["by_topic"].values()))
        self.assertEqual(set(coverage["by_topic"]), TOPIC_IDS)
        self.assertGreaterEqual(coverage["hard_edge"] * 3, coverage["questions"])
        self.assertGreaterEqual(coverage["unanswerable_by_design"], 3)

    def test_every_question_is_a_persons_problem_with_a_real_expected_home(self):
        for q in QUESTIONS["questions"]:
            self.assertIn(q["expected_topic"], TOPIC_IDS, q["id"])
            self.assertTrue(q["expected_element"] == v.NO_DATA or q["expected_element"] in ELEMENT_IDS, q["id"])
            self.assertLessEqual(len(q["start_context"]), 240, q["id"])

    def test_rulings_are_recorded_and_the_questions_follow_them(self):
        ruled = {q["id"]: q["ruled"] for q in QUESTIONS["questions"] if q.get("ruled")}
        self.assertEqual(ruled, {"Q005": ["R4"], "Q011": ["R4"], "Q018": ["R3"]})
        rulings = qc.load_rulings()
        self.assertEqual(rulings["R3"]["homes"], {"Q018": "device_health"})
        self.assertEqual(rulings["R4"]["homes"], {"Q005": "air_quality", "Q011": "security_presence"})
        for r in rulings.values():
            self.assertEqual((r["ruled_by"], r["ruled_on"]), ("Nate", "2026-10-17"))
        expected = {q["id"]: q["expected_topic"] for q in QUESTIONS["questions"]}
        for r in rulings.values():
            for qid, home in r["homes"].items():
                self.assertEqual(expected[qid], home, qid)

    def test_a_question_that_disagrees_with_its_ruling_is_caught(self):
        data = copy.deepcopy(QUESTIONS)
        next(q for q in data["questions"] if q["id"] == "Q018")["expected_topic"] = "energy"
        self.assertTrue(any("ruled home" in p for p in self.check(data)))
        data = copy.deepcopy(QUESTIONS)
        next(q for q in data["questions"] if q["id"] == "Q001")["ruled"] = ["R9"]
        self.assertTrue(any("not recorded" in p for p in self.check(data)))

    def test_the_three_candidate_groupings_are_in_the_set(self):
        tags = {t for q in QUESTIONS["questions"] for t in q["edge_tags"]}
        self.assertTrue({"candidate-access-perimeter", "candidate-energy-wording", "candidate-dim-lights-view"} <= tags)

    def test_a_question_that_names_a_topic_or_an_element_is_caught(self):
        for field, text in (("problem", "Is the Connectivity ok?"), ("problem", "How is device health today?"),
                            ("start_context", "Open the Air Quality tab."), ("problem", "Why is battery_percent low?"),
                            ("problem", "Obviously the internet is down, right?")):
            data = copy.deepcopy(QUESTIONS)
            data["questions"][0][field] = text
            self.assertTrue(self.check(data), text)

    def test_structural_rules_fail_when_broken(self):
        data = copy.deepcopy(QUESTIONS); data["questions"] = data["questions"][:20]
        self.assertTrue(any("25 to 30" in p for p in self.check(data)))
        data = copy.deepcopy(QUESTIONS)
        for q in data["questions"]:
            if q["expected_topic"] == "occupancy":
                q["expected_topic"] = "energy"
        self.assertTrue(any("occupancy" in p and "at least 3" in p for p in self.check(data)))
        data = copy.deepcopy(QUESTIONS)
        for q in data["questions"]:
            q["edge_tags"] = []
        self.assertTrue(any("hard edge" in p for p in self.check(data)))
        data = copy.deepcopy(QUESTIONS)
        for q in data["questions"]:
            if q["expected_element"] == v.NO_DATA:
                q["expected_element"] = "temperature_celsius"
        self.assertTrue(any("unanswerable" in p for p in self.check(data)))
        data = copy.deepcopy(QUESTIONS); data["questions"][1]["expected_element"] = "made_up_element"
        self.assertTrue(any("made_up_element" in p for p in self.check(data)))
        data = copy.deepcopy(QUESTIONS); data["questions"][2]["edge_tags"] = ["no-such-edge"]
        self.assertTrue(any("no-such-edge" in p for p in self.check(data)))
        data = copy.deepcopy(QUESTIONS); data["questions"][3]["source"] = "invented"
        self.assertTrue(any("source" in p for p in self.check(data)))
        data = copy.deepcopy(QUESTIONS); data["questions"][4]["id"] = data["questions"][5]["id"]
        self.assertTrue(any("duplicate" in p for p in self.check(data)))
        data = copy.deepcopy(QUESTIONS); del data["questions"][6]["start_context"]
        self.assertTrue(any("missing" in p for p in self.check(data)))

    def test_every_listed_edge_has_a_drafted_test(self):
        for tag in ("th-aq", "conn-dh", "dh-energy", "presence-conn", "life-safety", "derived-state", "view-topic"):
            self.assertIn(tag, DRAFTED)
        used = {t for q in QUESTIONS["questions"] for t in q["edge_tags"]}
        self.assertTrue(used <= DRAFTED, used - DRAFTED)


class VerdictSchema(unittest.TestCase):
    def test_schema_enums_and_required_fields_match_the_code(self):
        schema = load("verdict.schema.json")
        props = schema["properties"]
        self.assertEqual(set(props["verdict"]["enum"]), set(v.VERDICTS))
        self.assertEqual(set(props["reason"]["enum"]) - {None}, set(v.WRONG_REASONS) | set(v.CANNOT_REASONS))
        self.assertEqual(set(schema["required"]), set(v.HARNESS_FIELDS) | set(v.PROBE_FIELDS))
        self.assertEqual(set(props), set(schema["required"]))
        self.assertFalse(schema["additionalProperties"])
        self.assertTrue(len(schema["x-cross-field-rules"]) >= 9)

    def test_ten_dry_run_records_all_validate_and_none_is_unparseable(self):
        lines = (HERE / "fixtures" / "dry-run-verdicts.jsonl").read_text(encoding="utf-8").splitlines()
        valid, defects = v.ingest(lines, TOPIC_IDS, ELEMENT_IDS)
        self.assertEqual(defects, [])
        self.assertEqual(len(valid), 10)

    def test_the_dry_run_records_are_honest_about_found(self):
        expected = {q["id"]: q["expected_topic"] for q in QUESTIONS["questions"]}
        for line in (HERE / "fixtures" / "dry-run-verdicts.jsonl").read_text(encoding="utf-8").splitlines():
            r = json.loads(line)
            self.assertIn(r["qid"], expected)
            self.assertEqual(r["found"], v.topic_of(r["path"][-1]) == expected[r["qid"]], r["run_id"])

    def test_the_dry_run_records_exercise_every_verdict_reason_and_cell(self):
        recs = [json.loads(l) for l in (HERE / "fixtures" / "dry-run-verdicts.jsonl").read_text(encoding="utf-8").splitlines()]
        self.assertEqual({r["verdict"] for r in recs}, set(v.VERDICTS))
        self.assertTrue({"missing_or_broken_here", "belongs_in", "duplicate_of", "cannot_tell_apart_from"} <= {r["reason"] for r in recs})
        self.assertTrue(any(r["reason"] in v.CANNOT_REASONS for r in recs))
        self.assertEqual({v.cell(r) for r in recs}, {"found_asserted", "missed_asserted", "missed_unasserted"})

    def test_every_negative_fixture_is_rejected_for_the_rule_it_names(self):
        cases = load("fixtures/invalid-verdicts.json")
        self.assertGreaterEqual(len(cases), 14)
        for case in cases:
            problems = v.validate_record(case["record"], TOPIC_IDS, ELEMENT_IDS)
            self.assertTrue(any(case["rule"] in p for p in problems), f"{case['rule']!r} not in {problems}")

    def test_a_bad_line_is_discarded_and_counted_never_repaired(self):
        good = (HERE / "fixtures" / "dry-run-verdicts.jsonl").read_text(encoding="utf-8").splitlines()[0]
        valid, defects = v.ingest([good, "", "{not json", json.dumps({"qid": "Q001"})], TOPIC_IDS, ELEMENT_IDS)
        self.assertEqual(len(valid), 1)
        self.assertEqual([d["line"] for d in defects], [3, 4])
        self.assertIn("not JSON", defects[0]["problems"][0])

    def test_non_objects_and_wrong_types_are_defects_not_crashes(self):
        for junk in (None, 5, [], "x", {"qid": "Q001"}):
            self.assertTrue(v.validate_record(junk, TOPIC_IDS, ELEMENT_IDS))
        base = json.loads((HERE / "fixtures" / "dry-run-verdicts.jsonl").read_text(encoding="utf-8").splitlines()[3])
        for field, value in (("found", "yes"), ("hops", True), ("path", "connectivity"), ("note", 5), ("run_id", ""), ("verdict", "maybe")):
            r = dict(base); r[field] = value
            self.assertTrue(v.validate_record(r, TOPIC_IDS, ELEMENT_IDS), field)


class HarnessSide(unittest.TestCase):
    question = {"id": "Q019", "expected_topic": "connectivity"}

    def build(self, **probe):
        raw = {"path": ["connectivity"], "verdict": "right_here", "reason": None, "target": None, "runner_up": None, "note": None, **probe}
        return v.build_record(raw, self.question, "r1", "p1", "t1", TOPIC_IDS)

    def test_found_and_hops_are_computed_not_reported(self):
        r = self.build(path=["device_health", "connectivity/household-paths"])
        self.assertTrue(r["found"])
        self.assertEqual(r["hops"], 2)
        r = self.build(path=["connectivity", "device_health"], verdict="wrong_here", reason="missing_or_broken_here", runner_up="connectivity")
        self.assertFalse(r["found"], "found is where the verdict was given")
        self.assertEqual(v.validate_record(r, TOPIC_IDS, ELEMENT_IDS), [])

    def test_a_probe_that_reports_found_or_extra_fields_is_an_instrument_defect(self):
        for extra in ({"found": True}, {"hops": 1}, {"expected_topic": "connectivity"}):
            with self.assertRaises(ValueError):
                self.build(**extra)
        with self.assertRaises(ValueError):
            v.build_record({"path": ["connectivity"]}, self.question, "r", "p", "t", TOPIC_IDS)
        with self.assertRaises(ValueError):
            v.build_record("text", self.question, "r", "p", "t", TOPIC_IDS)
        with self.assertRaises(ValueError):
            self.build(path=[])

    def test_the_four_cells(self):
        base = {"found": True, "verdict": "right_here"}
        self.assertEqual(v.cell(base), "found_asserted")
        self.assertEqual(v.cell({"found": True, "verdict": "cannot_tell"}), "found_unasserted")
        self.assertEqual(v.cell({"found": False, "verdict": "wrong_here"}), "missed_asserted")
        self.assertEqual(v.cell({"found": False, "verdict": "cannot_tell"}), "missed_unasserted")

    def test_routing_follows_the_plan_table_and_an_unasserted_miss_never_demotes(self):
        wrong = lambda reason, target=None: {"verdict": "wrong_here", "reason": reason, "target": target, "found": False}
        self.assertEqual(v.route(wrong("missing_or_broken_here"))["object"], "implementation")
        self.assertIn("promote connectivity", v.route(wrong("belongs_in", "connectivity"))["action"])
        self.assertEqual(v.route(wrong("belongs_in", "connectivity"))["object"], "taxonomy")
        self.assertEqual(v.route(wrong("duplicate_of", "cabin_event"))["object"], "taxonomy")
        self.assertEqual(v.route(wrong("cannot_tell_apart_from", "linkquality"))["object"], "taxonomy edge")
        for reason in v.CANNOT_REASONS:
            routed = v.route({"verdict": "cannot_tell", "reason": reason, "found": False})
            self.assertEqual(routed["object"], "instrument")
            self.assertNotIn("demote", routed["action"])
        self.assertEqual(v.route({"verdict": "right_here", "reason": None, "found": True})["action"], "promote")


class Targets(unittest.TestCase):
    def test_targets_are_proposals_until_nate_ratifies_them(self):
        t = load("targets.json")
        self.assertEqual(bool(t["ratified_by"]), bool(t["ratified_on"]), "ratified_by and ratified_on go together")
        if not t["ratified_by"]:
            self.assertIn("NOT ratified", t["status"])
        for key in ("first_click_find_rate", "inter_labeler_kappa", "top_two_margin", "hops_median"):
            self.assertIn(key, t["metrics"])
        self.assertEqual(t["metrics"]["inter_labeler_kappa"]["target"], 0.80)

    def test_nate_ratified_the_targets_as_written_on_2026_10_17(self):
        t = load("targets.json")
        self.assertEqual((t["ratified_by"], t["ratified_on"]), ("Nate", "2026-10-17"))
        self.assertIn("R2", t["ratified_scope"])
        self.assertNotIn("NOT ratified", t["status"])
        plan = (REPO / "docs" / "governance" / "proposals" / "2026-10-17-topic-taxonomy-evaluation.md").read_text(encoding="utf-8")
        for key, text in (("first_click_find_rate", "85% or better"), ("top_two_margin", "0.30 or better"),
                          ("inter_labeler_kappa", "0.80 or better"), ("audit_agreement", "90%")):
            self.assertIn(text, plan, f"the plan no longer says {text!r} for {key}")


if __name__ == "__main__":
    unittest.main()
