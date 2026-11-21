"""Synthetic-only checks; no operational data or packet files."""
import copy
from dataclasses import FrozenInstanceError
import hashlib
import unittest
from unittest.mock import patch
from jsonschema.exceptions import ValidationError
from grading import blind_packet as bp


def sources():
    return [{"id": "semantic-source-" + str(n), "text": "  lumière café\n" + str(n),
             "group": "private-family", "suspect_source_label": True,
             "provenance": {"source": "synthetic", "revision": "r1", "sha256": "0" * 64, "license": "synthetic"},
             "target": {"intent": "set_device_state", "slots": {"state": "on"},
                        "requires_confirmation": True, "clarification": None, "automation": None}}
            for n in range(3)]


class BlindPacketTests(unittest.TestCase):
    def setUp(self):
        self.rows = sources()
        self.rows[0]["card_target"] = {"device_name": "synthetic lamp", "location": "test room", "site": "test"}
        self.families = {"private-family": [r["id"] for r in self.rows]}

    def build(self, **kwargs):
        return bp.build_packet(self.rows, recipient_id="a", family_map=self.families, **kwargs)

    def test_projection_and_commitments(self):
        result = self.build()
        private = bp.strict_loads(result.protected_bytes)
        crosswalk = {e["opaque_id"]: e for e in private["crosswalk"]}
        sources_by_id = {r["id"]: r for r in self.rows}
        for line in result.packet_bytes.splitlines():
            item = bp.strict_loads(line)
            self.assertEqual(set(item), {"id", "text", "card_target"})
            self.assertRegex(item["id"], r"^[0-9a-f]{32}$")
            row = sources_by_id[crosswalk[item["id"]]["source_id"]]
            self.assertEqual(item["text"], row["text"])
            self.assertEqual(item["card_target"], row.get("card_target"))
        for hidden in (b"semantic-source", b"private-family", b"suspect_source_label", b"provenance", b'"target"'):
            self.assertNotIn(hidden, result.packet_bytes)
        self.assertEqual(private["packet_bytes_sha256"], hashlib.sha256(result.packet_bytes).hexdigest())
        self.assertEqual(private["mapping_sha256"], hashlib.sha256(result.frozen_mapping).hexdigest())
        self.assertEqual(private["status"], "BUILT_NOT_DELIVERED")
        self.assertTrue(result.packet_bytes.endswith(b"\n"))
        self.assertNotIn(b"\r", result.packet_bytes)
        self.assertEqual(len(result.packet_bytes.splitlines()), 3)

    def test_frozen_rebuild_immutable_and_null(self):
        result = self.build()
        self.rows[1]["card_target"] = None
        self.rows.reverse()
        with patch.object(bp.secrets, "token_hex", side_effect=AssertionError("randomness")):
            self.assertEqual(result, self.build(frozen_mapping=result.frozen_mapping))
        with self.assertRaises(FrozenInstanceError):
            result.packet_bytes = b"changed"
        self.rows[0]["text"] = "changed"
        with self.assertRaises(ValueError):
            self.build(frozen_mapping=result.frozen_mapping)
        self.assertNotIn(b"changed", result.packet_bytes)

    def test_recipient_ids(self):
        first = self.build()
        second = bp.build_packet(self.rows, recipient_id="b", family_map=self.families)
        ids = lambda result: {e["opaque_id"] for e in bp.strict_loads(result.frozen_mapping)["entries"]}
        self.assertFalse(ids(first) & ids(second))
        with self.assertRaises(ValueError):
            self.build(frozen_mapping=second.frozen_mapping)

    def test_changed_sources_rejected(self):
        original = self.build()
        for field in ("text", "card_target", "target", "provenance", "group"):
            with self.subTest(field=field):
                self.setUp()
                if field == "text": self.rows[0][field] += " changed"
                elif field == "card_target": self.rows[0][field]["location"] = "another room"
                elif field == "target": self.rows[0][field]["slots"]["state"] = "off"
                elif field == "provenance": self.rows[0][field]["revision"] = "r2"
                else:
                    for row in self.rows: row["group"] = "renamed"
                    self.families = {"renamed": self.families["private-family"]}
                with self.assertRaises(ValueError):
                    self.build(frozen_mapping=original.frozen_mapping)

    def test_target_changes_hidden_only_card_changes_projection(self):
        def controlled():
            with patch.object(bp.secrets, "token_hex", side_effect=[f"{n:032x}" for n in range(4)]), \
                    patch.object(bp.secrets.SystemRandom, "shuffle", lambda self, entries: None):
                return self.build()
        original = controlled()
        self.rows[0]["target"]["slots"]["state"] = "off"
        target_changed = controlled()
        self.assertEqual(original.packet_bytes, target_changed.packet_bytes)
        cohort = lambda result: bp.strict_loads(result.protected_bytes)["cohort_sha256"]
        self.assertNotEqual(cohort(original), cohort(target_changed))
        self.rows[0]["card_target"]["location"] = "new room"
        card_changed = controlled()
        self.assertNotEqual(target_changed.packet_bytes, card_changed.packet_bytes)
        self.assertNotEqual(cohort(target_changed), cohort(card_changed))

    def test_invalid_sources(self):
        changes = [lambda r: r.append(copy.deepcopy(r[0])), lambda r: r[0].pop("group"),
                   lambda r: r[0].pop("target"), lambda r: r[0].update(id=""),
                   lambda r: r[0].update(text=""), lambda r: r[0].update(card_target={}),
                   lambda r: r[0]["target"]["slots"].update(location="wrong room"),
                   lambda r: r[0]["target"].update(requires_confirmation=False)]
        for change in changes:
            self.setUp()
            change(self.rows)
            with self.subTest(change=change), self.assertRaises((ValueError, ValidationError)):
                self.build()
        self.setUp()
        for families in ({}, {"private-family": [self.rows[0]["id"]]},
                         {"private-family": self.families["private-family"] * 2}):
            with self.assertRaises(ValueError):
                bp.build_packet(self.rows, recipient_id="a", family_map=families)

    def test_whitespace_only_text_rejected_without_trimming_valid_text(self):
        for text in (" ", "\t\r\n", "\u2003\u00a0"):
            self.rows[0]["text"] = text
            with self.subTest(text=text), self.assertRaisesRegex(ValueError, "nonblank source text"):
                self.build()
        self.rows[0]["text"] = " \t valid synthetic text \n"
        shown = [bp.strict_loads(line)["text"] for line in self.build().packet_bytes.splitlines()]
        self.assertIn(self.rows[0]["text"], shown)

    def test_malformed_objects_raise_validation_errors(self):
        for value in (None, [], 123, {"unexpected": True}):
            for field in ("id", "group", "text", "provenance", "card_target", "target"):
                self.setUp()
                self.rows[0][field] = value
                # Null card is explicitly supported.
                if field == "card_target" and value is None:
                    continue
                with self.subTest(field=field, value=value), self.assertRaises((ValueError, ValidationError)):
                    self.build()
        self.setUp()
        for value in (None, [], 123, {}):
            with self.subTest(mapping=value), self.assertRaises(ValueError):
                self.build(frozen_mapping=bp.canonical_bytes(value))

    def test_mapping_collision_and_corruption(self):
        with patch.object(bp.secrets, "token_hex", return_value="a" * 32), self.assertRaises(ValueError):
            self.build()
        original = bp.strict_loads(self.build().frozen_mapping)
        changes = [lambda m: m["entries"].pop(),
                   lambda m: m["entries"][0].update(source_id="invented"),
                   lambda m: m["entries"][0].update(source_id=m["entries"][1]["source_id"]),
                   lambda m: m["entries"][0].update(opaque_id=m["entries"][1]["opaque_id"]),
                   lambda m: m["entries"][0].update(family="changed"),
                   lambda m: m["entries"][0].update(row_sha256="0" * 64),
                   lambda m: m["entries"][0].update(target={}), lambda m: m.update(version=True)]
        for change in changes:
            mapping = copy.deepcopy(original)
            change(mapping)
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.build(frozen_mapping=bp.canonical_bytes(mapping))

    def test_inputs_not_mutated_and_nested_nonfinite_rejected(self):
        before_rows, before_families = copy.deepcopy(self.rows), copy.deepcopy(self.families)
        result = self.build()
        self.build(frozen_mapping=result.frozen_mapping)
        self.assertEqual(self.rows, before_rows)
        self.assertEqual(self.families, before_families)
        for value in (float("nan"), float("inf"), float("-inf")):
            self.rows[0]["provenance"]["nested"] = {"items": [value]}
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.build()

    def test_strict_json_and_canonical_bytes(self):
        value = {"é": "e\u0301", "a": {"z": None, "b": [True, 1, 1.5]}}
        self.assertEqual(bp.canonical_bytes(value), '{"a":{"b":[true,1,1.5],"z":null},"é":"e\u0301"}'.encode())
        self.assertEqual(bp.strict_loads(bp.canonical_bytes(value)), value)
        for raw in ('{"a":1,"a":2}', '{"nested":{"a":1,"a":2}}', 'NaN', 'Infinity',
                    '-Infinity', '1e999', '{"x":1e999}', '\ufeff{}', b'\xff', '"\\ud800"'):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                bp.strict_loads(raw)
        for value in ({1: "key"}, (1, 2), {"nested": float("nan")}, float("inf")):
            with self.subTest(value=value), self.assertRaises(ValueError):
                bp.canonical_bytes(value)
        with self.assertRaises(ValueError):
            bp.canonical_bytes({}, version=2)


if __name__ == "__main__":
    unittest.main()
