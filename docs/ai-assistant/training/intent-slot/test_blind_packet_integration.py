"""Synthetic-only checks of the existing pipeline's versioned entry point."""
import json
import unittest
from grading import review_packets as rp
from test_review_packets import row, target


class VersionedEntry(unittest.TestCase):
    def test_core_entry_accepts_existing_source_shape(self):
        rows = [row("synthetic-only-a", "Turn on the invented lamp",
                    target(device_name="invented lamp", state="on"))]
        result = rp.build_blind_packet_v1(
            rows, recipient_id="synthetic-reviewer", family_map={rows[0]["group"]: [rows[0]["id"]]})
        projected = json.loads(result.packet_bytes)
        self.assertEqual(set(projected), {"id", "text", "card_target"})
        self.assertNotEqual(projected["id"], rows[0]["id"])
        self.assertIsNone(projected["card_target"])
        self.assertEqual(rows[0]["text"], projected["text"])

    def test_existing_provenance_gate_remains_required(self):
        rows = [row("synthetic-only-a", "Turn on the invented lamp",
                    target(device_name="invented lamp", state="on"))]
        rows[0]["provenance"].pop("license")
        with self.assertRaisesRegex(ValueError, "provenance missing"):
            rp.build_blind_packet_v1(rows, recipient_id="synthetic-reviewer",
                                    family_map={rows[0]["group"]: [rows[0]["id"]]})
