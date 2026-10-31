"""Tests for the publish allow-list (W-23's heartbeat topic and the existing safety gate).

Run from this directory, no dependencies (paho is stubbed):
    python -m unittest test_cabin_security_mqtt_publish -v
"""

import json
import os
import sys
import tempfile
import types
import unittest
from unittest import mock

# paho is only installed on the HA host; the allow-list logic does not need it.
_paho, _mqtt, _publish = types.ModuleType("paho"), types.ModuleType("paho.mqtt"), types.ModuleType("paho.mqtt.publish")
_publish.single = mock.Mock()
_mqtt.publish = _publish
_paho.mqtt = _mqtt
sys.modules.update({"paho": _paho, "paho.mqtt": _mqtt, "paho.mqtt.publish": _publish})

import cabin_security_mqtt_publish as script  # noqa: E402


def run(topic, payload):
    _publish.single.reset_mock()
    with mock.patch.object(sys, "argv", ["x", topic, payload]):
        code = script.main()
    return code, _publish.single


class HeartbeatTopic(unittest.TestCase):
    def test_a_valid_utc_timestamp_is_published_retained(self):
        code, single = run("cabin/presence/nate/last_seen", "2026-10-04T09:00:00Z")
        self.assertEqual(code, 0)
        single.assert_called_once()
        self.assertEqual(single.call_args.kwargs["retain"], True, "the heartbeat must be retained so a restart still has the last real value")
        self.assertEqual(single.call_args.kwargs["payload"], "2026-10-04T09:00:00Z")

    def test_anything_that_is_not_exactly_a_real_utc_time_is_refused(self):
        bad = [
            "", "home", "not_home", "now", "null", "0",
            "2026-10-04 09:00:00Z",          # space instead of T
            "2026-10-04T09:00:00",           # no Z
            "2026-10-04T09:00:00+00:00",     # offset form
            "2026-10-04T04:00:00-05:00",     # local time
            "2026-10-04T09:00:00.123Z",      # fractional seconds
            "2026-10-04T09:00Z",             # no seconds
            "2026-13-04T09:00:00Z",          # month 13
            "2026-10-32T09:00:00Z",          # day 32
            "2026-10-04T25:00:00Z",          # hour 25
            "2026-10-04T09:00:00Z; rm -rf /", # trailing junk
            " 2026-10-04T09:00:00Z",         # leading space
        ]
        for payload in bad:
            code, single = run("cabin/presence/nate/last_seen", payload)
            self.assertEqual(code, 3, repr(payload))
            single.assert_not_called()

    def test_the_heartbeat_pattern_does_not_open_any_other_topic(self):
        for topic in ("home/presence/nate/last_seen", "cabin/presence/emma/last_seen", "cabin/presence/nate/last_seen/x",
                      "cabin/security/enabled", "cabin/event/critical", "zigbee2mqtt/leak_alarm_fridge/set"):
            code, single = run(topic, "2026-10-04T09:00:00Z")
            self.assertEqual(code, 3, topic)
            single.assert_not_called()


class ExistingAllowListIsUnchanged(unittest.TestCase):
    def test_presence_and_arm_state_still_take_only_their_exact_values(self):
        for topic, good, bad in (
            ("cabin/presence/nate", ("home", "not_home"), ("away", "HOME", "unknown", "")),
            ("home/presence/nate", ("home", "not_home"), ("away", "on")),
            ("cabin/security/armed_away", ("ON", "OFF"), ("on", "true", "")),
            ("cabin/kidde/co_alarm", ("ON", "OFF"), ("ALARM",)),
            ("cabin/blink/motion", ("driveway", "home_aldrich_front"), ("front_door", "")),
        ):
            for payload in good:
                self.assertEqual(run(topic, payload)[0], 0, (topic, payload))
            for payload in bad:
                self.assertEqual(run(topic, payload)[0], 3, (topic, payload))

    def test_retain_behaviour_is_unchanged(self):
        self.assertEqual(run("cabin/presence/nate", "home")[1].call_args.kwargs["retain"], True)
        self.assertEqual(run("cabin/blink/motion", "driveway")[1].call_args.kwargs["retain"], False)

    def test_wrong_argument_count_and_unknown_topics_are_refused(self):
        with mock.patch.object(sys, "argv", ["x", "only-one-arg"]):
            self.assertEqual(script.main(), 2)
        self.assertEqual(run("cabin/security/disarm_everything", "ON")[0], 3)


class GeneratedPeople(unittest.TestCase):
    """W-38 slice 3: the generated roster file can only add exact per-person presence topics, and fails closed."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.path = os.path.join(self.dir.name, "presence_people_allowlist.json")
        patcher = mock.patch.object(script, "PEOPLE_FILE", self.path)
        patcher.start()
        self.addCleanup(patcher.stop)

    def write(self, content):
        with open(self.path, "w", encoding="utf-8") as handle:
            handle.write(content if isinstance(content, str) else json.dumps(content))

    def write_bytes(self, content):
        with open(self.path, "wb") as handle:
            handle.write(content)

    def good(self, *people):
        return {"version": 1, "people": [{"personId": p, "location": l} for p, l in people]}

    def test_the_checked_in_file_for_nate_adds_nothing_the_base_does_not_already_allow(self):
        real = os.path.join(os.path.dirname(os.path.abspath(script.__file__)), "presence_people_allowlist.json")
        allowed, patterns = script.load_people(real)
        self.assertEqual(set(allowed), {"cabin/presence/nate"})
        self.assertEqual(set(patterns), {"cabin/presence/nate/last_seen"})
        self.assertTrue(set(allowed) <= set(script.ALLOWED), "nate is already in the hand-reviewed base")
        self.assertTrue(set(patterns) <= set(script.PATTERNS))

    def test_a_listed_person_gets_exactly_their_two_topics_and_values(self):
        self.write(self.good(("child_1", "cabin")))
        for payload in ("home", "not_home"):
            self.assertEqual(run("cabin/presence/child_1", payload)[0], 0, payload)
        for payload in ("away", "HOME", "", "on"):
            self.assertEqual(run("cabin/presence/child_1", payload)[0], 3, payload)
        self.assertEqual(run("cabin/presence/child_1/last_seen", "2026-10-04T09:00:00Z")[0], 0)
        self.assertEqual(run("cabin/presence/child_1/last_seen", "home")[0], 3)

    def test_it_never_opens_another_person_location_or_topic(self):
        self.write(self.good(("child_1", "cabin")))
        for topic in ("home/presence/child_1", "cabin/presence/child_2", "cabin/presence/child_1/extra",
                      "cabin/presence/+", "cabin/presence/#", "cabin/security/armed_away_x", "zigbee2mqtt/x/set"):
            self.assertEqual(run(topic, "home")[0], 3, topic)

    def test_a_person_who_is_not_listed_stays_refused(self):
        self.write(self.good(("child_1", "cabin")))
        self.assertEqual(run("cabin/presence/emma", "home")[0], 3)

    def test_missing_file_adds_nothing_and_the_base_still_works(self):
        self.assertEqual(script.load_people(), ({}, {}))
        self.assertEqual(run("cabin/presence/nate", "home")[0], 0)
        self.assertEqual(run("cabin/presence/child_1", "home")[0], 3)

    def test_malformed_or_wrong_shape_files_add_nothing(self):
        for content in ("", "not json", "[]", "null", "{}", '{"version": 1}', '{"version": 2, "people": []}',
                        '{"version": 1, "people": {}}', '{"version": 1, "people": [], "extra": 1}',
                        '{"version": "1", "people": []}'):
            self.write(content)
            self.assertEqual(script.load_people(), ({}, {}), content)
            self.assertEqual(run("cabin/presence/child_1", "home")[0], 3, content)
            self.assertEqual(run("cabin/presence/nate", "home")[0], 0, "base still works: " + content)

    def test_one_bad_entry_rejects_the_whole_file(self):
        bad_entries = [
            {"personId": "Child_1", "location": "cabin"},        # uppercase
            {"personId": "child-1", "location": "cabin"},        # hyphen
            {"personId": "", "location": "cabin"},
            {"personId": "a" * 33, "location": "cabin"},         # too long
            {"personId": "child_1", "location": "garage"},       # location outside the fixed set
            {"personId": "child_1", "location": "cabin", "topic": "cabin/security/armed_away"},  # tries to name a topic
            {"personId": "child_1"},
            {"personId": 7, "location": "cabin"},
            {"personId": "child_1", "location": ["cabin"]},
            {"personId": "../x", "location": "cabin"},
            {"personId": "x/#", "location": "cabin"},
            "child_1",
        ]
        for bad in bad_entries:
            self.write({"version": 1, "people": [{"personId": "child_2", "location": "cabin"}, bad]})
            self.assertEqual(script.load_people(), ({}, {}), repr(bad))
            self.assertEqual(run("cabin/presence/child_2", "home")[0], 3, "the good entry is rejected with the bad one: " + repr(bad))

    def test_an_oversized_file_or_too_many_people_adds_nothing(self):
        self.write({"version": 1, "people": [{"personId": f"p{i}", "location": "cabin"} for i in range(33)]})
        self.assertEqual(script.load_people(), ({}, {}))
        self.write(" " * (script.MAX_PEOPLE_FILE_BYTES + 1))
        self.assertEqual(script.load_people(), ({}, {}))

    def padded(self, size, version=1, person="child_1"):
        """A VALID JSON document with a valid person, padded with whitespace to exactly `size` bytes."""
        body = json.dumps({"version": version, "people": [{"personId": person, "location": "cabin"}]})
        return body + " " * (size - len(body.encode("utf-8")))

    def test_a_valid_file_just_over_the_size_limit_adds_nothing_but_one_at_the_limit_works(self):
        self.write(self.padded(script.MAX_PEOPLE_FILE_BYTES))
        self.assertEqual(os.path.getsize(self.path), script.MAX_PEOPLE_FILE_BYTES)
        self.assertEqual(run("cabin/presence/child_1", "home")[0], 0, "exactly at the limit is accepted")
        self.write(self.padded(script.MAX_PEOPLE_FILE_BYTES + 1))
        self.assertEqual(os.path.getsize(self.path), script.MAX_PEOPLE_FILE_BYTES + 1)
        json.loads(open(self.path, encoding="utf-8").read())  # it really is valid JSON: only the size rejects it
        self.assertEqual(script.load_people(), ({}, {}))
        self.assertEqual(run("cabin/presence/child_1", "home")[0], 3)

    def test_a_wrong_version_carrying_a_valid_person_adds_nothing(self):
        for version in (2, 0, -1, "1", 1.0, True, None, [1]):
            self.write({"version": version, "people": [{"personId": "child_1", "location": "cabin"}]})
            self.assertEqual(script.load_people(), ({}, {}), repr(version))
            self.assertEqual(run("cabin/presence/child_1", "home")[0], 3, repr(version))

    def test_a_path_that_is_not_a_regular_file_adds_nothing(self):
        self.assertEqual(script.load_people(self.dir.name), ({}, {}), "a directory")

    @unittest.skipUnless(hasattr(os, "mkfifo"), "needs a POSIX FIFO (not run on Windows)")
    def test_a_fifo_is_refused_without_blocking_on_it(self):
        fifo = os.path.join(self.dir.name, "fifo.json")
        os.mkfifo(fifo)
        # Opening a FIFO for reading blocks until a writer appears; the regular-file check must refuse it before any open.
        self.assertEqual(script.load_people(fifo), ({}, {}))

    def test_a_hostile_file_cannot_break_the_base_path_or_crash_the_loader(self):
        for content in ("[" * 4000 + "]" * 4000,                 # deep nesting: RecursionError in the JSON parser
                        '{"version": 1, "people": [' + '{"personId": "x", "location": "cabin"},' * 400 + "]}",
                        b'{"version": 1, "people": "\\ud800"}',   # lone surrogate escape
                        b"\xff\xfe\x00"):                         # not UTF-8
            (self.write_bytes if isinstance(content, bytes) else self.write)(content)
            self.assertEqual(script.load_people(), ({}, {}), repr(content[:20]))
            self.assertEqual(run("cabin/presence/nate", "home")[0], 0, "base topic unaffected")
            self.assertEqual(run("cabin/presence/nate/last_seen", "2026-10-04T09:00:00Z")[0], 0, "base heartbeat unaffected")
            self.assertEqual(run("cabin/security/armed_away", "ON")[0], 0, "armed_away unaffected")

    def test_a_base_topic_is_decided_without_reading_the_generated_file(self):
        with mock.patch.object(script, "load_people", side_effect=AssertionError("must not be read")):
            self.assertEqual(run("cabin/presence/nate", "home")[0], 0)
            self.assertEqual(run("cabin/presence/nate/last_seen", "2026-10-04T09:00:00Z")[0], 0)
            self.assertEqual(run("cabin/security/armed_away", "OFF")[0], 0)

    def test_person_ids_must_be_single_underscore_words_so_two_ids_never_share_an_entity_id(self):
        for bad in ("a__b", "_a", "a_", "1a", "a-b", "A", "a b", "a.b", "_", "a" * 33):
            self.write({"version": 1, "people": [{"personId": "child_2", "location": "cabin"}, {"personId": bad, "location": "cabin"}]})
            self.assertEqual(script.load_people(), ({}, {}), repr(bad))
        for good in ("nate", "child_1", "a1_b2", "a" * 32):
            self.write({"version": 1, "people": [{"personId": good, "location": "cabin"}]})
            self.assertIn(f"cabin/presence/{good}", script.load_people()[0], good)

    def test_a_listed_person_is_retained_like_nate(self):
        self.write(self.good(("child_1", "cabin")))
        self.assertEqual(run("cabin/presence/child_1", "home")[1].call_args.kwargs["retain"], True)


if __name__ == "__main__":
    unittest.main()
