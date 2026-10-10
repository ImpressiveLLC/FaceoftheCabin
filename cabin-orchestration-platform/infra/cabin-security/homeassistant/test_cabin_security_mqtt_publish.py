"""Tests for the publish allow-list (W-23's heartbeat topic and the existing safety gate).

Run from this directory, no dependencies (paho is stubbed):
    python -m unittest test_cabin_security_mqtt_publish -v
"""

import sys
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


if __name__ == "__main__":
    unittest.main()
