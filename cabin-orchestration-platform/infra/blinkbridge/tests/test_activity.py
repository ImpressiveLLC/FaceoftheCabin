"""Activity detector: diff each camera's whole record per poll; trigger on signal keys; log everything that changes."""
import unittest

from blinkbridge.camera import State
from blinkbridge.server import make_app

from aiohttp.test_utils import TestClient, TestServer

from .fakes import FakeClient
from .test_supervisor import DRIVEWAY, Base, build


class ActivityTests(Base):
    async def test_baseline_poll_never_triggers(self):
        sup, client, _, clock = build(motion_liveview=["AldrichFront"])
        client.raw["AldrichFront"] = {"name": "AldrichFront", "thumbnail": "111"}
        await sup.tick()
        self.assertEqual(sup.runners["AldrichFront"].state, State.IDLE)
        self.assertEqual(sup.last_motion, {})

    async def test_signal_key_change_starts_liveview_for_enabled_camera(self):
        sup, client, _, clock = build(motion_liveview=["AldrichFront"])
        client.raw["AldrichFront"] = {"name": "AldrichFront", "thumbnail": "111"}
        await sup.tick()
        client.raw["AldrichFront"] = {"name": "AldrichFront", "thumbnail": "222"}
        clock.advance(40)
        with self.assertLogs("blinkbridge", level="INFO") as cm:
            await sup.tick()
        self.assertEqual(sup.runners["AldrichFront"].state, State.LIVE)
        self.assertTrue(any("camera record changed" in m and "thumbnail" in m for m in cm.output))
        self.assertIn("record change: thumbnail", sup.last_motion["AldrichFront"][1])

    async def test_signal_key_appearing_counts(self):
        sup, client, _, clock = build(motion_liveview=["AldrichFront"])
        client.raw["AldrichFront"] = {"name": "AldrichFront"}
        await sup.tick()
        client.raw["AldrichFront"] = {"name": "AldrichFront", "last_record": "2026-10-31 05:33:00"}
        clock.advance(40)
        await sup.tick()
        self.assertEqual(sup.runners["AldrichFront"].state, State.LIVE)

    async def test_volatile_fields_do_not_trigger(self):
        sup, client, _, clock = build(motion_liveview=["AldrichFront"])
        client.raw["AldrichFront"] = {"signals": {"wifi": 3}, "wifi_strength": -50, "battery": "ok", "thumbnail": "1"}
        await sup.tick()
        client.raw["AldrichFront"] = {"signals": {"wifi": 2}, "wifi_strength": -61, "battery": "low", "thumbnail": "1"}
        clock.advance(40)
        await sup.tick()
        self.assertEqual(sup.runners["AldrichFront"].state, State.IDLE)
        self.assertEqual(sup.last_motion, {})

    async def test_non_signal_change_is_logged_but_does_not_trigger(self):
        sup, client, _, clock = build(motion_liveview=["AldrichFront"])
        client.raw["AldrichFront"] = {"status": "online"}
        await sup.tick()
        client.raw["AldrichFront"] = {"status": "offline"}
        clock.advance(40)
        with self.assertLogs("blinkbridge", level="INFO") as cm:
            await sup.tick()
        self.assertTrue(any("camera record changed" in m and "status" in m for m in cm.output))
        self.assertEqual(sup.runners["AldrichFront"].state, State.IDLE)

    async def test_activity_on_camera_not_enabled_is_recorded_only(self):
        sup, client, _, clock = build(motion_liveview=["AldrichFront"])
        client.raw[DRIVEWAY] = {"thumbnail": "1"}
        await sup.tick()
        client.raw[DRIVEWAY] = {"thumbnail": "2"}
        clock.advance(40)
        await sup.tick()
        self.assertEqual(sup.runners[DRIVEWAY].state, State.IDLE)
        self.assertIn(DRIVEWAY, sup.last_motion)

    async def test_custom_signal_keys(self):
        sup, client, _, clock = build(motion_liveview=["AldrichFront"], motion_signal_keys=["snooze"])
        client.raw["AldrichFront"] = {"snooze": False, "thumbnail": "1"}
        await sup.tick()
        client.raw["AldrichFront"] = {"snooze": False, "thumbnail": "2"}
        clock.advance(40)
        await sup.tick()
        self.assertEqual(sup.runners["AldrichFront"].state, State.IDLE)  # thumbnail no longer a signal
        client.raw["AldrichFront"] = {"snooze": True, "thumbnail": "2"}
        clock.advance(40)
        await sup.tick()
        self.assertEqual(sup.runners["AldrichFront"].state, State.LIVE)

    async def test_healthz_shows_last_activity_and_debug_endpoint_redacts(self):
        sup, client, _, clock = build()
        client.raw["AldrichFront"] = {"thumbnail": "1", "serial": "SECRET", "network_id": 99, "video": "v", "status": "online"}
        await sup.tick()
        client.raw["AldrichFront"] = {"thumbnail": "2", "serial": "SECRET", "network_id": 99, "video": "v", "status": "online"}
        clock.advance(40)
        await sup.tick()
        _, body = sup.health()
        self.assertEqual(body["last_activity"]["AldrichFront"]["source"], "record change: thumbnail")
        http = TestClient(TestServer(make_app(sup)))
        await http.start_server()
        try:
            data = await (await http.get("/debug/cameras")).json()
        finally:
            await http.close()
        cam = data["AldrichFront"]
        self.assertEqual(cam["serial"], "<redacted>")
        self.assertEqual(cam["network_id"], "<redacted>")
        self.assertEqual(cam["video"], "v")
        self.assertEqual(cam["thumbnail"], "2")


class RealClientRecordTests(unittest.TestCase):
    def test_raw_camera_merges_homescreen_record_and_blinkpy_attributes(self):
        from blinkbridge.blink_client import BlinkpyClient
        from .fakes import fast_settings

        class Cam:
            attributes = {"last_record": "r1", "video": None, "thumbnail": "t1", "motion_detected": False}

        class FakeBlink:
            homescreen = {"owls": [{"name": "AldrichFront", "thumbnail": 123, "status": "online"},
                                   {"name": "Other", "thumbnail": 9}], "cameras": [{"name": "Outdoor 4 - DHEE", "x": 1}]}
            cameras = {"AldrichFront": Cam()}

        client = BlinkpyClient(fast_settings())
        client.blink = FakeBlink()
        raw = client.raw_camera("AldrichFront")
        self.assertEqual(raw["thumbnail"], 123)
        self.assertEqual(raw["status"], "online")
        self.assertEqual(raw["attr_last_record"], "r1")
        self.assertNotIn("x", raw)
        self.assertEqual(client.raw_camera("Outdoor 4 - DHEE"), {"name": "Outdoor 4 - DHEE", "x": 1})


if __name__ == "__main__":
    unittest.main()
