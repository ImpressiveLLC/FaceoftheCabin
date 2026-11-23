"""S7 G8 (#529 gap list): bind minimal-pair families into `group` before the split, tested on synthetic rows only.

No real data, S6 evidence or host is involved. `G8_BOUND_FAMILIES` stays False: these tests cover the binding and its
refusals; they do not turn a frozen-aware run on.
"""
import hashlib
import io
import json
import random
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

import prepare_sft
from contract import ROOT, proposal
from prepare_sft import (FamilyBindingError, Frozen, S6Error, bind_families, compose_family_maps, pair_base,
                         pair_relations, split_rows, split_rows_with_report)
from test_prepare_sft_frozen import reviewed_rows

GOLD = ROOT / "gold.jsonl"


def gold_rows():
    return [json.loads(line) for line in GOLD.read_text(encoding="utf-8").split("\n") if line.strip()]


def row(rid, group, text=None):
    return {"id": rid, "text": text or f"synthetic text {rid}", "group": group, "target": proposal("answer_question"),
            "provenance": {"review_status": "approved", "reviewer": "test-only", "reviewed_at": "2026-11-21",
                           "source": "test", "revision": "test", "sha256": "test", "license": "test"}}


def bucket(name):
    return int(hashlib.sha256(name.encode()).hexdigest(), 16) % 10


def find_name(wanted_bucket, prefix="fam"):
    n = 0
    while True:
        candidate = f"{prefix}-{n}"
        if bucket(candidate) == wanted_bucket:
            return candidate
        n += 1


def pair_fixture(family, half_a=("a0", "a1"), half_b=("b0", "b1")):
    """One minimal pair: template `t` and its counterpart `t-pair`, bound to a single canonical family."""
    base, pair = f"train-t-{family}", f"train-t-{family}-pair"
    rows = [row(f"{family}-{i}", base) for i in half_a] + [row(f"{family}-{i}", pair) for i in half_b]
    return rows, {family: [r["id"] for r in rows]}


def split_of(splits):
    return {item["id"]: name for name, items in splits.items() for item in items}


class PairDetection(unittest.TestCase):
    def test_pair_base_and_relations(self):
        self.assertEqual(pair_base("train-x-pair"), "train-x")
        self.assertEqual(pair_base("train-clarify-nounit-pair-3"), "train-clarify-nounit-3")
        self.assertEqual(pair_base("train-compare"), "train-compare")
        rows = [row("1", "train-a"), row("2", "train-a-pair"), row("3", "train-b-pair"), row("4", "train-c")]
        self.assertEqual(pair_relations(rows), [("train-a", "train-a-pair")])  # b-pair has no b present: not a relation


class MinimalPairsStayTogether(unittest.TestCase):
    def test_raw_groups_do_split_a_pair_which_is_why_binding_exists(self):
        # find two raw group names in different buckets, so the unbound split really separates the halves
        base = next(f"train-t-{n}" for n in range(1000) if bucket(f"train-t-{n}") == 0)
        pair = base + "-pair"
        self.assertNotEqual(bucket(base), bucket(pair))
        rows = reviewed_rows(100) + [row("x-0", base), row("x-1", pair)]
        splits = split_of(split_rows(rows, gold_rows()))
        self.assertNotEqual(splits["x-0"], splits["x-1"], "the unbound control must split the pair, else this test proves nothing")

    def test_halves_land_in_one_split_for_every_hash_bucket_and_both_orders(self):
        filler = reviewed_rows(100)
        for wanted in range(10):
            family = find_name(wanted)
            rows, family_map = pair_fixture(family)
            fam_map = dict(family_map)
            for r in filler:
                fam_map.setdefault(r["group"], []).append(r["id"])
            for order in (rows, list(reversed(rows))):
                data = filler + order
                splits = split_of(split_rows(data, gold_rows(), families=fam_map))
                placed = {splits[r["id"]] for r in rows}
                self.assertEqual(len(placed), 1, f"bucket {wanted}: a minimal pair was split: {placed}")


class RefusalsAndDeterminism(unittest.TestCase):
    def setUp(self):
        self.filler = reviewed_rows(100)
        self.filler_map = {}
        for r in self.filler:
            self.filler_map.setdefault(r["group"], []).append(r["id"])

    def test_unbound_pair_half_raises(self):
        rows, family_map = pair_fixture("fam-u")
        family_map["fam-u"].remove("fam-u-b1")  # one half-row has no family
        with self.assertRaises(FamilyBindingError) as ctx:
            split_rows(self.filler + rows, gold_rows(), families={**self.filler_map, **family_map})
        self.assertIn("not in the family map", str(ctx.exception))

    def test_pair_spread_over_two_families_raises(self):
        rows, _ = pair_fixture("fam-s")
        spread = {"fam-s-base": ["fam-s-a0", "fam-s-a1"], "fam-s-pair": ["fam-s-b0", "fam-s-b1"]}
        with self.assertRaises(FamilyBindingError) as ctx:
            bind_families(rows, spread)
        self.assertIn("spread over 2 families", str(ctx.exception))

    def test_row_in_two_families_and_duplicate_row_ids_raise(self):
        rows, family_map = pair_fixture("fam-d")
        with self.assertRaises(FamilyBindingError):
            bind_families(rows, {**family_map, "other": [rows[0]["id"]]})
        with self.assertRaises(FamilyBindingError):
            bind_families(rows + [rows[0]], family_map)

    def test_deterministic_for_shuffled_input(self):
        rows, family_map = pair_fixture("fam-r")
        data = self.filler + rows
        fam_map = {**self.filler_map, **family_map}
        reference = split_of(split_rows(data, gold_rows(), families=fam_map))
        for seed in range(5):
            shuffled = list(data)
            random.Random(seed).shuffle(shuffled)
            self.assertEqual(split_of(split_rows(shuffled, gold_rows(), families=fam_map)), reference)

    def test_binding_replaces_group_and_keeps_the_raw_value_without_mutating_input(self):
        rows, family_map = pair_fixture("fam-g")
        before = json.dumps(rows, sort_keys=True)
        bound = bind_families(rows, family_map)
        self.assertEqual({r["group"] for r in bound}, {"fam-g"})
        self.assertEqual({r["source_group"] for r in bound}, {"train-t-fam-g", "train-t-fam-g-pair"})
        self.assertEqual(json.dumps(rows, sort_keys=True), before)


class ExistingCallersUnchanged(unittest.TestCase):
    def test_no_families_gives_the_same_splits_and_report_as_before(self):
        rows = reviewed_rows(100)
        gold = gold_rows()
        self.assertEqual(split_rows(rows, gold), split_rows(rows, gold, families=None))
        splits, report, _ = split_rows_with_report(rows, gold)
        self.assertNotIn("families_bound", report)
        self.assertNotIn("pair_relations_bound", report)
        self.assertEqual(report["s6"], "NOT_RUN")
        self.assertEqual(report["frozen_family_check"], "NOT_RUN")

    def test_the_flag_that_enables_frozen_runs_is_still_off(self):
        self.assertIs(prepare_sft.G8_BOUND_FAMILIES, False)


class GoldFamilyExclusionSurvivesBinding(unittest.TestCase):
    """Codex HOLD 7094064757 on #566: a gold-family row with distinct text must stay out after its family is renamed."""

    def setUp(self):
        self.gold = gold_rows()
        self.rows = reviewed_rows(100)
        self.families = {}
        for r in self.rows:
            self.families.setdefault(r["group"], []).append(r["id"])
        self.gold_group = sorted({g["group"] for g in self.gold})[0]
        used = {r["group"] for r in self.rows} | {g["group"] for g in self.gold}
        self.fresh = next(f"renamed-family-{n}" for n in range(1000) if f"renamed-family-{n}" not in used)

    def with_extra(self, extra_group, canonical):
        extra = row("extra-1", extra_group, text="a distinct synthetic request about the cabin pump schedule")
        return self.rows + [extra], dict(self.families, **{canonical: [extra["id"]]})

    def test_gold_family_row_is_rejected_without_binding(self):
        rows, _ = self.with_extra(self.gold_group, self.fresh)
        with self.assertRaisesRegex(ValueError, "held-out example: extra-1"):
            split_rows(rows, self.gold)

    def test_gold_family_row_stays_rejected_when_bound_to_a_renamed_family(self):
        rows, families = self.with_extra(self.gold_group, self.fresh)
        with self.assertRaisesRegex(ValueError, "held-out example: extra-1"):
            split_rows(rows, self.gold, families=families)

    def test_an_ordinary_row_bound_into_a_gold_named_family_is_rejected(self):
        rows, families = self.with_extra("ordinary-synthetic-family", self.gold_group)
        with self.assertRaisesRegex(ValueError, "held-out example: extra-1"):
            split_rows(rows, self.gold, families=families)

    def test_control_an_ordinary_renamed_family_is_still_accepted(self):
        rows, families = self.with_extra("ordinary-synthetic-family", self.fresh)
        placed = split_of(split_rows(rows, self.gold, families=families))
        self.assertIn("extra-1", placed)


class OverlayComposition(unittest.TestCase):
    def test_replace_by_key_keeps_every_prior_member(self):
        base = {"hot": ["h1", "h2"], "dark": ["d1"]}
        overlay = {"hot": ["h1", "h2", "h3"]}
        self.assertEqual(compose_family_maps(base, overlay), {"hot": ["h1", "h2", "h3"], "dark": ["d1"]})

    def test_three_stacked_overlays_in_order(self):
        a = {"hot": ["h1", "h2"], "dark": ["d1"], "nap": ["n1"]}
        b = {"hot": ["h1", "h2", "h3"], "dark": ["d1", "d2"]}
        c = {"hot": ["h1", "h2", "h3", "h4"]}
        composed = compose_family_maps(a, b, c)
        self.assertEqual(sorted(i for ids in composed.values() for i in ids), ["d1", "d2", "h1", "h2", "h3", "h4", "n1"])

    def test_an_overlay_that_drops_a_member_or_double_owns_an_id_raises(self):
        with self.assertRaises(FamilyBindingError):
            compose_family_maps({"hot": ["h1", "h2"]}, {"hot": ["h1"]})
        with self.assertRaises(FamilyBindingError):
            compose_family_maps({"hot": ["h1"]}, {"cold": ["h1"]})
        with self.assertRaises(FamilyBindingError):
            compose_family_maps({"hot": ["h1", "h1"]})

    def test_concatenating_two_maps_naively_would_double_own_but_the_composition_does_not(self):
        a = {"hot": ["h1", "h2"]}
        overlay = {"hot": ["h1", "h2", "h3"]}
        naive = [i for m in (a, overlay) for ids in m.values() for i in ids]
        self.assertGreater(len(naive), len(set(naive)))
        composed = compose_family_maps(a, overlay)
        flat = [i for ids in composed.values() for i in ids]
        self.assertEqual(len(flat), len(set(flat)))


class FrozenUsesTheBoundFamily(unittest.TestCase):
    def test_frozen_exclusion_checks_the_canonical_family_not_the_raw_group(self):
        filler = reviewed_rows(100)
        filler_map = {}
        for r in filler:
            filler_map.setdefault(r["group"], []).append(r["id"])
        rows, family_map = pair_fixture("fam-f")
        fam_map = {**filler_map, **family_map}
        data = filler + rows
        frozen = Frozen("freeze-synthetic", ("fam-f",), frozenset(), hashlib.sha256(b"").hexdigest())
        with self.assertRaises(S6Error):  # raw groups are train-t-fam-f..., only the bound family id is frozen
            split_rows_with_report(data, gold_rows(), frozen=frozen, families=fam_map)
        quarantined = Frozen("freeze-synthetic", ("fam-f",), frozenset(r["id"] for r in rows), hashlib.sha256(b"x").hexdigest())
        splits, report, ids = split_rows_with_report(data, gold_rows(), frozen=quarantined, families=fam_map)
        self.assertEqual(sorted(ids), sorted(r["id"] for r in rows))
        self.assertEqual(report["families_bound"], True)


class CommandLine(unittest.TestCase):
    def run_cli(self, rows, maps):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            data = tmp / "train.jsonl"
            data.write_text("".join(json.dumps(r) + "\n" for r in rows), encoding="utf-8")
            args = [str(data), str(tmp / "out"), "--dry-run-no-s6"]
            for i, m in enumerate(maps):
                path = tmp / f"map{i}.json"
                path.write_text(json.dumps(m), encoding="utf-8")
                args += ["--family-map", str(path)]
            buffer = io.StringIO()
            with redirect_stdout(buffer):
                report = prepare_sft.main(args)
            return report, (tmp / "out" / "report.json").exists(), sorted(p.name for p in (tmp / "out").iterdir())

    def test_stacked_family_maps_bind_in_a_dry_run_and_write_only_the_report(self):
        filler = reviewed_rows(100)
        filler_map = {}
        for r in filler:
            filler_map.setdefault(r["group"], []).append(r["id"])
        rows, family_map = pair_fixture("fam-c")
        base_map = {**filler_map, **{"fam-c": family_map["fam-c"][:2]}}
        overlay = {"fam-c": family_map["fam-c"]}
        report, wrote, files = self.run_cli(filler + rows, [base_map, overlay])
        self.assertTrue(report["families_bound"])
        self.assertEqual(report["pair_relations_bound"], 1)
        self.assertEqual(files, ["report.json"])

    def test_cli_refuses_an_unbound_pair_and_a_bad_overlay(self):
        filler = reviewed_rows(100)
        filler_map = {}
        for r in filler:
            filler_map.setdefault(r["group"], []).append(r["id"])
        rows, family_map = pair_fixture("fam-x")
        with self.assertRaises(SystemExit) as ctx:
            self.run_cli(filler + rows, [filler_map])  # pair rows not in the map
        self.assertIn("family binding failed", str(ctx.exception))
        with self.assertRaises(SystemExit) as ctx:
            self.run_cli(filler + rows, [{**filler_map, "fam-x": family_map["fam-x"]}, {"fam-x": ["fam-x-a0"]}])
        self.assertIn("drops", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
