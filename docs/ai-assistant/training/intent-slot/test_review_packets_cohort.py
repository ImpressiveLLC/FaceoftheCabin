"""Codex re-review of #263: an audit must be bound to the exact cohort it audits, and the preparer must check the linkage."""
import json
import tempfile
import unittest
from pathlib import Path

from contract import read_jsonl
from grading import review_packets as rp
from prepare_sft import split_rows

ROOT = Path(__file__).resolve().parent

LINKED = {"audited_by": "Nate", "attribution_only": True, "rate": 0.95, "sample_n": 2, "agreed_n": 2, "cohort_n": 20, "track": "A",
          "cohort_sha256": "c" * 64, "audit_ids_sha256": "a" * 64}


def target(**slots):
    return {"intent": "set_device_state", "slots": slots, "requires_confirmation": True, "clarification": None, "automation": None}


def row(rid, text, tgt):
    return {"id": rid, "text": text, "target": tgt, "critical": False, "group": "fam-" + rid, "suspect_source_label": False,
            "provenance": {"source": "enfuse", "revision": "r" * 40, "sha256": "s" * 64, "license": "Apache-2.0",
                           "review_status": "unreviewed", "purpose": "training-candidate"}}


def write(path, items):
    Path(path).write_text("".join(json.dumps(i, sort_keys=True) + "\n" for i in items), encoding="utf-8")


def gold():
    return read_jsonl(ROOT / "gold.jsonl")


class PreparerChecksTheLinkage(unittest.TestCase):
    def agent_rows(self, n, audit=None):
        rows = []
        for i in range(n):
            r = row(f"x{i}", f"flip on the unit{i} lamp now", target(device_name=f"unit{i} lamp", state="on"))
            r.pop("suspect_source_label")
            r["provenance"].update(review_status="approved", reviewer="codex-proxy", reviewed_at="2026-10-26T00:00:00Z",
                                   reviewer_kind="agent", reviewer_model="m", agreement="agreed", track="A",
                                   agreed_row_sha256=rp.agreed_row_sha(r))
            if audit:
                r["provenance"]["audit_result"] = audit
            rows.append(r)
        return rows

    def test_weak_or_unlinked_audits_do_not_count(self):
        bad = (dict(LINKED, rate=0.8), dict(LINKED, audited_by=""), dict(LINKED, sample_n=0),
               {k: v for k, v in LINKED.items() if k != "cohort_sha256"}, {k: v for k, v in LINKED.items() if k != "audit_ids_sha256"},
               dict(LINKED, track="B"), dict(LINKED, cohort_n=100, sample_n=3), dict(LINKED, cohort_sha256="short"))
        for audit in bad:
            with self.assertRaises(ValueError, msg=str(audit)):
                split_rows(self.agent_rows(5, audit), gold())

    def test_a_row_edited_after_the_audit_is_refused(self):
        rows = self.agent_rows(20, LINKED)
        rows[3]["target"] = target(device_name="something else", state="off")
        with self.assertRaisesRegex(ValueError, "changed after its audit"):
            split_rows(rows, gold())

    def test_a_properly_linked_audit_lets_rows_through(self):
        result = split_rows(self.agent_rows(20, LINKED), gold())
        self.assertEqual(sum(len(v) for v in result.values()), 20)


class AuditIsBoundToItsCohort(unittest.TestCase):
    """An audit of one unrelated id once promoted 100 rows. Each way of reusing or shrinking an audit must now fail."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def cohort(self, name, prefix="r", n=100):
        d = self.dir / name
        d.mkdir()
        rows = [row(f"{prefix}{i:03d}", f"flip on the {prefix}{i} lamp now", target(device_name=f"{prefix}{i} lamp", state="on")) for i in range(n)]
        sample = d / "sample.jsonl"
        write(sample, rows)
        rp.make_packet(sample, "A", d / "packet")
        labels = d / "l.jsonl"
        write(labels, [{"id": r["id"], "target": r["target"]} for r in rows])
        _, _, _, audit = rp.import_labels(sample, d / "packet", labels, "codex-proxy", "m", "A", out_dir=d / "out")
        decisions = d / "dec.jsonl"
        write(decisions, [{"id": a["id"], "agree": True} for a in audit])
        return d / "out", decisions

    def promote(self, out, decisions, agreed=None, audit=None, manifest=None):
        return rp.record_audit(agreed or out / "agreed.jsonl", audit or out / "audit.jsonl", decisions, "Nate",
                               self.dir / "promoted.jsonl", manifest or out / "audit.manifest.json")

    def test_the_genuine_audit_promotes_with_identities_linked(self):
        out, decisions = self.cohort("a")
        summary = self.promote(out, decisions)
        rows = read_jsonl(self.dir / "promoted.jsonl")
        self.assertEqual((summary["promoted"], summary["sample_n"]), (100, 10))
        self.assertEqual({r["provenance"]["audit_result"]["cohort_sha256"] for r in rows}, {summary["cohort_sha256"]})
        self.assertTrue(rows[0]["provenance"]["audit_result"]["attribution_only"])
        split_rows(rows, gold())

    def test_an_audit_of_one_unrelated_id_cannot_approve_a_cohort(self):
        out, _ = self.cohort("a")
        stray = self.dir / "stray-audit.jsonl"
        write(stray, [row("not-in-cohort", "something unrelated", target(device_name="x", state="on"))])
        stray_dec = self.dir / "stray-dec.jsonl"
        write(stray_dec, [{"id": "not-in-cohort", "agree": True}])
        with self.assertRaisesRegex(ValueError, "not in the agreed cohort"):
            self.promote(out, stray_dec, audit=stray)
        self.assertFalse((self.dir / "promoted.jsonl").exists())

    def test_an_undersized_audit_is_refused_even_with_real_ids(self):
        out, _ = self.cohort("a")
        audit = read_jsonl(out / "audit.jsonl")[:1]
        small = self.dir / "small-audit.jsonl"
        write(small, audit)
        small_dec = self.dir / "small-dec.jsonl"
        write(small_dec, [{"id": audit[0]["id"], "agree": True}])
        with self.assertRaisesRegex(ValueError, "10 are required"):
            self.promote(out, small_dec, audit=small)

    def test_a_sample_other_than_the_drawn_one_is_refused(self):
        out, _ = self.cohort("a")
        drawn = {a["id"] for a in read_jsonl(out / "audit.jsonl")}
        other = [r for r in read_jsonl(out / "agreed.jsonl") if r["id"] not in drawn][:10]
        swapped = self.dir / "swapped-audit.jsonl"
        write(swapped, other)
        swapped_dec = self.dir / "swapped-dec.jsonl"
        write(swapped_dec, [{"id": r["id"], "agree": True} for r in other])
        with self.assertRaisesRegex(ValueError, "not the sample the importer drew"):
            self.promote(out, swapped_dec, audit=swapped)

    def test_changed_agreed_targets_after_import_are_refused(self):
        out, decisions = self.cohort("a")
        rows = read_jsonl(out / "agreed.jsonl")
        rows[0]["target"] = target(device_name="something else", state="off")
        edited = self.dir / "edited-agreed.jsonl"
        write(edited, rows)
        with self.assertRaisesRegex(ValueError, "changed since import"):
            self.promote(out, decisions, agreed=edited)

    def test_a_cohort_with_a_row_dropped_no_longer_matches_its_audit(self):
        out, decisions = self.cohort("a")
        shorter = self.dir / "shorter-agreed.jsonl"
        write(shorter, read_jsonl(out / "agreed.jsonl")[1:])
        with self.assertRaises(ValueError):
            self.promote(out, decisions, agreed=shorter)

    def test_stale_audit_reuse_on_another_cohort_is_refused(self):
        out_a, dec_a = self.cohort("a", prefix="a")
        out_b, dec_b = self.cohort("b", prefix="b")
        with self.assertRaises(ValueError):
            rp.record_audit(out_b / "agreed.jsonl", out_a / "audit.jsonl", dec_a, "Nate", self.dir / "x.jsonl", out_a / "audit.manifest.json")
        with self.assertRaisesRegex(ValueError, "stale or foreign audit"):
            rp.record_audit(out_b / "agreed.jsonl", out_b / "audit.jsonl", dec_b, "Nate", self.dir / "x.jsonl", out_a / "audit.manifest.json")
        self.assertFalse((self.dir / "x.jsonl").exists())

    def mutated_audit(self, out, change):
        rows = read_jsonl(out / "audit.jsonl")
        for r in rows:
            change(r)
        path = self.dir / "mutated-audit.jsonl"
        write(path, rows)
        return path

    def test_audit_content_must_match_the_agreed_rows_even_with_unchanged_ids(self):
        out, decisions = self.cohort("a")
        other = target(device_name="different lamp", state="off")

        def both(r):
            r["text"] = "turn off a different synthetic lamp"
            r["target"] = other
        for change, message in ((both, "shows a different text or label"),
                                (lambda r: r.__setitem__("text", r["text"] + " tonight"), "shows a different text or label"),
                                (lambda r: r.__setitem__("target", other), "shows a different text or label"),
                                (lambda r: r.pop("text"), "missing a displayed field"),
                                (lambda r: r.pop("target"), "missing a displayed field")):
            path = self.mutated_audit(out, change)
            with self.assertRaisesRegex(ValueError, message):
                self.promote(out, decisions, audit=path)
            self.assertFalse((self.dir / "promoted.jsonl").exists())

    def test_the_manifest_pins_the_audit_content(self):
        out, decisions = self.cohort("a")
        manifest = json.loads((out / "audit.manifest.json").read_text(encoding="utf-8"))
        self.assertRegex(manifest["audit_content_sha256"], "^[0-9a-f]{64}$")
        manifest["audit_content_sha256"] = "0" * 64
        tampered = self.dir / "tampered.manifest.json"
        tampered.write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "does not match the audit manifest"):
            self.promote(out, decisions, manifest=tampered)
        summary = self.promote(out, decisions)
        self.assertEqual(summary["audit_content_sha256"], json.loads((out / "audit.manifest.json").read_text(encoding="utf-8"))["audit_content_sha256"])

    def test_an_agent_cannot_be_the_auditor_and_below_90_percent_promotes_nothing(self):
        out, decisions = self.cohort("a")
        with self.assertRaisesRegex(ValueError, "not an agent"):
            rp.record_audit(out / "agreed.jsonl", out / "audit.jsonl", decisions, "codex-proxy", self.dir / "p.jsonl", out / "audit.manifest.json")
        no = self.dir / "no.jsonl"
        write(no, [{"id": a["id"], "agree": False} for a in read_jsonl(out / "audit.jsonl")])
        with self.assertRaisesRegex(ValueError, "below 0.9"):
            self.promote(out, no)
        self.assertFalse((self.dir / "promoted.jsonl").exists())


if __name__ == "__main__":
    unittest.main()
