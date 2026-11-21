"""S7 G1 (#536 build spec): frozen-family exclusion in prepare_sft.py, tested on synthetic S6 evidence only.

No sealed set, refusal pack, real family export, screen result or freeze record exists or is read. The evidence files
here are built by the test to the formats #536 section 2.3 fixes, so the tool and the S6 build can be built against
each other. Passing these tests does not validate G1 against a real freeze.
"""
import hashlib
import io
import json
import random
import shutil
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

import prepare_sft
from contract import ROOT, proposal, read_jsonl
from prepare_sft import EMPTY_LIST_SHA256, Frozen, S6Error, list_hash, load_frozen, split_rows, split_rows_with_report


def reviewed_rows(families=100):
    rows = []
    for i in range(families):
        for j in range(2):
            rows.append({"id": f"{i}-{j}", "text": f"example {i} version {j}", "group": f"family-{i}",
                         "target": proposal("answer_question"),
                         "provenance": {"review_status": "approved", "reviewer": "test-only", "reviewed_at": "2026-11-21",
                                        "source": "test", "revision": "test", "sha256": "test", "license": "test"}})
    return rows


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


class Evidence:
    """A synthetic, internally consistent set of S6 files for one INPUT, in a temporary directory."""

    TEST_FAMILIES = ["family-90", "family-91"]
    SAFETY_FAMILIES = ["family-95"]

    def __init__(self, tmp, rows, quarantine=None):
        self.tmp = Path(tmp)
        self.input = self.tmp / "train.jsonl"
        self.write_rows(rows)
        if quarantine is None:
            quarantine = sorted(r["id"] for r in rows if r["group"] in set(self.TEST_FAMILIES + self.SAFETY_FAMILIES))
        self.gold = ROOT / "gold.jsonl"
        self.recompute = prepare_sft.recompute_inputs(self.gold)
        self.record = {"kind": "freeze", "freeze_id": "freeze-synthetic-1", "result": "FROZEN",
                       "tool": {"screen-train": "a" * 64, "round_ledger": "b" * 64},
                       "inputs": {name: sha(path) for name, path in self.recompute.items()},
                       "sets": {"test": {"family_ids_sha256": list_hash(self.TEST_FAMILIES, what="t"), "sha256": "c" * 64},
                                "safety": {"family_ids_sha256": list_hash(self.SAFETY_FAMILIES, what="s"), "sha256": "d" * 64}}}
        self.export = {"freeze_id": "freeze-synthetic-1", "test_family_ids": list(self.TEST_FAMILIES),
                       "safety_family_ids": list(self.SAFETY_FAMILIES)}
        self.screen = {"kind": "screen-train", "result": "pass", "freeze_id": "freeze-synthetic-1",
                       "train_file_sha256": sha(self.input), "quarantine_ids": quarantine,
                       "quarantine_ids_sha256": list_hash(quarantine, what="q", allow_empty=True),
                       "counts": {"quarantined": len(quarantine)}, "tool": {"screen-train": "a" * 64}}

    def write_rows(self, rows):
        self.input.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")

    def files(self):
        out = {}
        for name in ("record", "export", "screen"):
            path = self.tmp / f"{name}.json"
            path.write_text(json.dumps(getattr(self, name)), encoding="utf-8")
            out[name] = path
        return out

    def load(self):
        f = self.files()
        return load_frozen(f["record"], f["export"], f["screen"], sha(self.input), self.recompute)


class FrozenExclusionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.gold = read_jsonl(ROOT / "gold.jsonl")
        self.rows = reviewed_rows()

    def evidence(self, **kw):
        return Evidence(self.tmp, self.rows, **kw)

    # --- list hash conventions -------------------------------------------------------------------------------
    def test_list_hash_convention(self):
        self.assertEqual(list_hash(["b", "a"], what="x"), hashlib.sha256(b"a\nb").hexdigest())
        self.assertEqual(list_hash([], what="q", allow_empty=True), EMPTY_LIST_SHA256)
        self.assertEqual(EMPTY_LIST_SHA256, hashlib.sha256(b"").hexdigest())
        for bad in (["a\n"], ["a", "a"], [""], [3], ["a b"], "a"):
            with self.assertRaises(S6Error):
                list_hash(bad, what="x")
        with self.assertRaisesRegex(S6Error, "empty"):
            list_hash([], what="test_family_ids")

    # --- a consistent freeze passes and drops quarantined rows ----------------------------------------------------
    def test_verified_evidence_drops_quarantined_rows_and_reports_pass(self):
        frozen = self.evidence().load()
        self.assertEqual(frozen.exclusion, ("family-90", "family-91", "family-95"))
        splits, report, dropped = split_rows_with_report(self.rows, self.gold, frozen=frozen)
        self.assertEqual(report["s6"], "FROZEN")
        self.assertEqual(report["frozen_family_check"], "pass")
        self.assertEqual(report["quarantined"]["count"], 6)
        self.assertEqual(report["rows_in"], 200)
        self.assertEqual(report["rows_out"], 194)
        self.assertEqual(dropped, sorted(["90-0", "90-1", "91-0", "91-1", "95-0", "95-1"]))
        kept = {r["id"] for v in splits.values() for r in v}
        self.assertFalse(kept & set(dropped))
        self.assertNotIn("90-0", json.dumps(report))

    def test_without_frozen_reports_not_run_never_pass(self):
        _, report, _ = split_rows_with_report(self.rows, self.gold)
        self.assertEqual((report["s6"], report["frozen_family_check"]), ("NOT_RUN", "NOT_RUN"))
        self.assertEqual(set(split_rows(self.rows, self.gold)), {"train", "validation", "holdout"})

    def test_frozen_family_row_not_quarantined_fails_the_run(self):
        frozen = self.evidence(quarantine=["90-0", "90-1", "91-0", "91-1", "95-0"]).load()
        with self.assertRaisesRegex(S6Error, "1 row\\(s\\) in a frozen test or safety family were not quarantined by the screen: 95-1"):
            split_rows_with_report(self.rows, self.gold, frozen=frozen)

    def test_empty_quarantine_is_accepted_when_no_row_is_frozen(self):
        rows = [r for r in self.rows if r["group"] not in {"family-90", "family-91", "family-95"}]
        ev = Evidence(self.tmp, rows, quarantine=[])
        frozen = ev.load()
        self.assertEqual(frozen.quarantine_ids_sha256, EMPTY_LIST_SHA256)
        _, report, _ = split_rows_with_report(rows, self.gold, frozen=frozen)
        self.assertEqual(report["quarantined"]["count"], 0)

    # --- each identity and custody check fails closed -------------------------------------------------------------
    def assert_refused(self, ev, pattern):
        with self.assertRaisesRegex(S6Error, pattern):
            ev.load()

    def test_freeze_record_checks(self):
        ev = self.evidence(); ev.record["result"] = "FROZEN_WITH_GAPS"
        self.assert_refused(ev, "FROZEN_WITH_GAPS does not satisfy S6")
        ev = self.evidence(); ev.record["kind"] = "open"
        self.assert_refused(ev, "kind is not 'freeze'")
        ev = self.evidence(); del ev.record["sets"]["safety"]["family_ids_sha256"]
        self.assert_refused(ev, "per-set family_ids_sha256")

    def test_family_export_checks(self):
        ev = self.evidence(); ev.export["test_family_ids"] = ["family-90", "family-99"]
        self.assert_refused(ev, "test_family_ids do not hash")
        ev = self.evidence(); ev.export["safety_family_ids"] = ["family-96"]
        self.assert_refused(ev, "safety_family_ids do not hash")
        ev = self.evidence(); ev.export["freeze_id"] = "freeze-other"
        self.assert_refused(ev, "another freeze_id")
        for extra in ({"keyed_hashes": ["x"]}, {"token_hashes": []}, {"hmac": "k"}, {"text": "sealed row"}):
            ev = self.evidence(); ev.export.update(extra)
            self.assert_refused(ev, "custody-unsafe")
        ev = self.evidence(); ev.export["test_family_ids"] = ["family-90", 91]
        self.assert_refused(ev, "plain non-empty string")
        ev = self.evidence(); ev.export["test_family_ids"] = []
        self.assert_refused(ev, "empty")

    def test_overlapping_test_and_safety_sets_fail(self):
        ev = self.evidence()
        ev.export["safety_family_ids"] = ["family-91"]
        ev.record["sets"]["safety"]["family_ids_sha256"] = list_hash(["family-91"], what="s")
        self.assert_refused(ev, "overlap")

    def test_screen_result_checks(self):
        ev = self.evidence(); ev.screen["result"] = "fail"
        self.assert_refused(ev, "not pass")
        ev = self.evidence(); ev.screen["kind"] = "screen"
        self.assert_refused(ev, "kind is not 'screen-train'")
        ev = self.evidence(); ev.screen["freeze_id"] = "freeze-other"
        self.assert_refused(ev, "another freeze_id")
        ev = self.evidence(); ev.screen["tool"] = {"screen-train": "e" * 64}
        self.assert_refused(ev, "is not the one the freeze record lists")
        ev = self.evidence(); ev.screen["quarantine_ids"] = ev.screen["quarantine_ids"][:-1]
        self.assert_refused(ev, "quarantine_ids do not hash")

    def test_stale_screen_after_an_appended_row_is_refused(self):
        ev = self.evidence()
        f = ev.files()
        ev.write_rows(self.rows + [dict(self.rows[0], id="new-0", text="a new row")])
        with self.assertRaisesRegex(S6Error, "stale"):
            load_frozen(f["record"], f["export"], f["screen"], sha(ev.input), ev.recompute)

    def test_each_recompute_input_absent_unlisted_or_changed_fails(self):
        for name in list(prepare_sft.recompute_inputs(ROOT / "gold.jsonl")):
            ev = self.evidence(); del ev.record["inputs"][name]
            self.assert_refused(ev, f"recompute input {name} is not in")
            ev = self.evidence(); ev.record["inputs"][name] = "0" * 64
            self.assert_refused(ev, f"recompute input {name} has changed")
        ev = self.evidence()
        ev.recompute = dict(ev.recompute, **{"gold.jsonl": self.tmp / "absent-gold.jsonl"})
        self.assert_refused(ev, "not present here")
        ev = self.evidence(); ev.record["inputs"]["sft/s2-batch99-families.json"] = "1" * 64
        self.assert_refused(ev, "lists sft/s2-batch99-families.json but it is not found")

    def test_changed_gold_file_is_refused(self):
        changed = self.tmp / "gold-changed.jsonl"
        changed.write_bytes((ROOT / "gold.jsonl").read_bytes() + b"\n")
        ev = self.evidence()
        ev.recompute = prepare_sft.recompute_inputs(changed)
        self.assert_refused(ev, "gold.jsonl has changed")

    def test_input_with_the_bytes_of_a_frozen_set_is_refused(self):
        ev = self.evidence()
        ev.record["sets"]["test"]["sha256"] = sha(ev.input)
        self.assert_refused(ev, "frozen test set")

    def test_recompute_inputs_cover_every_committed_family_map(self):
        names = set(prepare_sft.recompute_inputs(ROOT / "gold.jsonl"))
        committed = {f"sft/{p.name}" for p in (ROOT / "sft").glob("*families*.json")}
        self.assertTrue(committed <= names)
        self.assertTrue({"gold.jsonl", "system.txt", "intent-slot.schema.json", "sft/s1/families.json"} <= names)

    # --- determinism ------------------------------------------------------------------------------------------
    def test_shuffled_input_gives_identical_splits_and_report(self):
        frozen = self.evidence().load()
        a = split_rows_with_report(self.rows, self.gold, frozen=frozen)
        shuffled = list(self.rows)
        random.Random(7).shuffle(shuffled)
        b = split_rows_with_report(shuffled, self.gold, frozen=frozen)
        self.assertEqual(json.dumps(a[1], sort_keys=True), json.dumps(b[1], sort_keys=True))
        self.assertEqual(json.dumps(a[0], sort_keys=True, ensure_ascii=False).encode("utf-8"),
                         json.dumps(b[0], sort_keys=True, ensure_ascii=False).encode("utf-8"))
        reversed_run = split_rows_with_report(list(reversed(self.rows)), self.gold, frozen=frozen)
        self.assertEqual(a[0], reversed_run[0])
        self.assertEqual(a[2], b[2])

    # --- source never reads frozen material ---------------------------------------------------------------------
    def test_source_has_no_read_of_frozen_set_or_refusal_pack(self):
        source = (ROOT / "prepare_sft.py").read_text(encoding="utf-8")
        for forbidden in ("refusal_pack", "refusal-pack", "safety80", "test170", "sealed/", "hmac", "screening_key"):
            self.assertNotIn(forbidden, source.lower())


class CliTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.rows = reviewed_rows()
        self.ev = Evidence(self.tmp, self.rows)
        self.out = self.tmp / "out"

    def run_main(self, *extra):
        buffer = io.StringIO()
        with redirect_stdout(buffer):
            report = prepare_sft.main([str(self.ev.input), str(self.out), *extra], _synthetic_test_only=True)
        return report, buffer.getvalue()

    def s6_flags(self):
        f = self.ev.files()
        return ["--freeze-record", str(f["record"]), "--family-export", str(f["export"]), "--screen-result", str(f["screen"])]

    def test_missing_or_partial_s6_flags_are_refused(self):
        with self.assertRaises(SystemExit):
            self.run_main()
        with self.assertRaises(SystemExit):
            self.run_main(*self.s6_flags()[:4])
        with self.assertRaises(SystemExit):
            self.run_main("--dry-run-no-s6", *self.s6_flags())
        self.assertFalse(self.out.exists())

    def test_dry_run_writes_only_the_report_and_says_not_run(self):
        report, output = self.run_main("--dry-run-no-s6")
        self.assertTrue(output.startswith("NOT_RUN: S6"))
        self.assertEqual((report["s6"], report["frozen_family_check"]), ("NOT_RUN", "NOT_RUN"))
        self.assertEqual(sorted(p.name for p in self.out.iterdir()), ["report.json"])

    def test_real_run_writes_holdout_splits_report_and_private_ids(self):
        report, _ = self.run_main(*self.s6_flags())
        self.assertEqual(report["frozen_family_check"], "pass")
        self.assertEqual(sorted(p.name for p in self.out.iterdir()),
                         ["holdout.jsonl", "quarantined.private.json", "report.json", "train.jsonl", "validation.jsonl"])
        private = json.loads((self.out / "quarantined.private.json").read_text(encoding="utf-8"))
        self.assertEqual(len(private["quarantined_ids"]), 6)
        public = (self.out / "report.json").read_text(encoding="utf-8")
        self.assertNotIn("90-0", public)
        first = public
        shutil.rmtree(self.out)
        self.run_main(*self.s6_flags())
        self.assertEqual((self.out / "report.json").read_text(encoding="utf-8"), first)

    def test_cli_failure_writes_nothing(self):
        self.ev.screen["train_file_sha256"] = "0" * 64
        with self.assertRaisesRegex(SystemExit, "stale"):
            self.run_main(*self.s6_flags())
        self.assertFalse(self.out.exists())

    def test_input_that_is_one_of_the_s6_files_is_refused(self):
        f = self.ev.files()
        with self.assertRaises(SystemExit):
            prepare_sft.main([str(f["export"]), str(self.out), "--freeze-record", str(f["record"]),
                              "--family-export", str(f["export"]), "--screen-result", str(f["screen"])],
                             _synthetic_test_only=True)


class G1RepairTests(unittest.TestCase):
    """Codex HOLD 7059474381: screen-tool identity, one family-map predicate, the refusal until G8 and the read order."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.rows = reviewed_rows()

    def evidence(self):
        return Evidence(self.tmp, self.rows)

    def test_screen_tool_must_be_exactly_the_screening_tool(self):
        ev = self.evidence()
        ev.screen["tool"] = {"round_ledger": ev.record["tool"]["round_ledger"]}  # matching but unrelated key
        with self.assertRaisesRegex(S6Error, "not exactly"):
            ev.load()
        ev = self.evidence()
        ev.screen["tool"] = dict(ev.screen["tool"], round_ledger=ev.record["tool"]["round_ledger"])  # extra key
        with self.assertRaisesRegex(S6Error, "not exactly"):
            ev.load()
        ev = self.evidence(); ev.screen["tool"] = {}
        with self.assertRaisesRegex(S6Error, "not exactly"):
            ev.load()
        ev = self.evidence(); ev.screen["tool"] = {"screen-train": "A" * 64}
        ev.record["tool"]["screen-train"] = "A" * 64
        with self.assertRaisesRegex(S6Error, "not a SHA-256"):
            ev.load()
        ev = self.evidence(); del ev.record["tool"]["screen-train"]
        with self.assertRaisesRegex(S6Error, "does not pin a SHA-256"):
            ev.load()

    def test_one_family_map_predicate_for_discovery_and_reverse_check(self):
        for name in ("sft/s1/families.json", "sft/s2-batch2-families.json", "sft/retired-families-map.json",
                     "sft/families.json"):
            self.assertTrue(prepare_sft.is_family_map(name), name)
        for name in ("sft/s1/other.json", "sft/nested/x-families.json", "gold.jsonl", "sft/families.txt"):
            self.assertFalse(prepare_sft.is_family_map(name), name)
        ev = self.evidence()
        ev.record["inputs"]["sft/retired-families-map.json"] = "1" * 64  # suffix after "families"
        with self.assertRaisesRegex(S6Error, "lists sft/retired-families-map.json but it is not found"):
            ev.load()

    def test_cli_refuses_frozen_aware_runs_until_g8_lands(self):
        self.assertFalse(prepare_sft.G8_BOUND_FAMILIES)
        ev = self.evidence()
        f = ev.files()
        out = self.tmp / "out"
        with self.assertRaisesRegex(SystemExit, "refused until S7 G8"):
            prepare_sft.main([str(ev.input), str(out), "--freeze-record", str(f["record"]),
                              "--family-export", str(f["export"]), "--screen-result", str(f["screen"])])
        self.assertFalse(out.exists())
        buffer = io.StringIO()
        with redirect_stdout(buffer):
            prepare_sft.main([str(ev.input), str(out), "--dry-run-no-s6"])  # the dry run still works
        self.assertTrue(buffer.getvalue().startswith("NOT_RUN: S6"))

    def test_frozen_set_bytes_are_refused_before_input_is_parsed(self):
        ev = self.evidence()
        ev.input.write_bytes(b"sealed text that is not JSON" + bytes([10]))
        ev.screen["train_file_sha256"] = sha(ev.input)
        ev.record["sets"]["safety"]["sha256"] = sha(ev.input)
        f = ev.files()
        with self.assertRaisesRegex(SystemExit, "frozen safety set"):
            prepare_sft.main([str(ev.input), str(self.tmp / "out"), "--freeze-record", str(f["record"]),
                              "--family-export", str(f["export"]), "--screen-result", str(f["screen"])],
                             _synthetic_test_only=True)


if __name__ == "__main__":
    unittest.main()
