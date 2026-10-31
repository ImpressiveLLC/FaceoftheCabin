"""Camera set shrinks (Blink homescreen errors drop a camera): detect, warn, re-discover, report unhealthy."""
import logging
import unittest

from blinkbridge.camera import State

from .fakes import FakeClient
from .test_supervisor import DRIVEWAY, Base, build


class CameraSetTests(Base):
    async def test_shrink_warns_rediscovers_and_recovers(self):
        sup, client, _, clock = build()
        await sup.tick()
        self.assertEqual(sup.missing_since, {})
        self.assertEqual(client.login_calls, 1)

        client.cams = ["AldrichFront"]      # Blink answers with a shorter list
        clock.advance(40)
        with self.assertLogs("blinkbridge", level="WARNING") as cm:
            await sup.tick()
        self.assertTrue(any("camera set shrank" in m and DRIVEWAY in m for m in cm.output))
        self.assertIn(DRIVEWAY, sup.missing_since)
        self.assertFalse(sup.ready)          # re-discovery requested

        client.cams = ["AldrichFront", DRIVEWAY]   # fresh login sees both again
        with self.assertLogs("blinkbridge", level="INFO") as cm:
            await sup.tick()               # logs in, polls, notices the camera is back
        self.assertEqual(client.login_calls, 2)
        self.assertTrue(sup.ready)
        self.assertTrue(any("back in Blink response" in m for m in cm.output))
        self.assertEqual(sup.missing_since, {})
        ok, body = sup.health()
        self.assertTrue(ok)
        self.assertEqual(body["live"], sorted(["AldrichFront", DRIVEWAY]))

    async def test_health_goes_unhealthy_when_camera_stays_missing(self):
        sup, client, _, clock = build()
        await sup.tick()
        client.cams = ["AldrichFront"]
        for _ in range(60):                  # 5 min of ticks
            clock.advance(5)
            await sup.tick()
        ok, body = sup.health()
        self.assertTrue(ok)                  # missing, but inside the 900 s allowance
        self.assertIn(DRIVEWAY, body["missing"])
        self.assertEqual(body["expected"], sorted(["AldrichFront", DRIVEWAY]))
        for _ in range(140):                 # past 900 s
            clock.advance(5)
            await sup.tick()
        ok, body = sup.health()
        self.assertFalse(ok)
        self.assertFalse(body["ok"])
        self.assertIn(DRIVEWAY, body["last_error"])

    async def test_rediscovery_is_backed_off_not_hammered(self):
        sup, client, _, clock = build()
        await sup.tick()
        client.cams = ["AldrichFront"]
        for _ in range(240):                 # 20 minutes, camera never returns
            clock.advance(5)
            await sup.tick()
        # 60 s base doubling to a 600 s cap: far fewer than one login per poll (~30 polls)
        self.assertLessEqual(client.login_calls, 8)
        self.assertGreaterEqual(client.login_calls, 3)

    async def test_rediscovery_waits_while_another_camera_is_live(self):
        sup, client, _, clock = build()
        await sup.tick()
        other = sup.runners["AldrichFront"]
        other.request(300)
        await other.step()
        self.assertEqual(other.state, State.LIVE)
        client.cams = ["AldrichFront"]
        clock.advance(40)
        await sup.tick()
        self.assertTrue(sup.ready)           # a re-login would kill the live session: deferred
        self.assertEqual(client.login_calls, 1)
        self.assertIn(DRIVEWAY, sup.missing_since)

    async def test_request_for_missing_camera_backs_off_without_opening(self):
        sup, client, _, clock = build()
        await sup.tick()
        client.cams = ["AldrichFront"]
        clock.advance(40)
        await sup.tick()
        client.cams = ["AldrichFront"]       # still gone after the re-login
        await sup.tick()
        runner = sup.runners[DRIVEWAY]
        runner.request(60)
        await runner.step()
        self.assertEqual(runner.state, State.BACKOFF)
        self.assertIn("missing", runner.last_error)
        self.assertEqual(client.sessions, [])

    async def test_configured_camera_missing_at_first_login_is_reported(self):
        sup, client, _, clock = build(client=FakeClient(cams=["AldrichFront"]),
                                      enabled=["AldrichFront", DRIVEWAY])
        await sup.tick()
        clock.advance(40)
        await sup.tick()
        self.assertIn(DRIVEWAY, sup.missing_since)
        self.assertIn(DRIVEWAY, sup.runners)  # runner exists so state survives re-discovery
        ok, body = sup.health()
        self.assertEqual(body["missing"].keys(), {DRIVEWAY})

    async def test_health_reports_last_liveview_request_age(self):
        sup, client, _, clock = build()
        await sup.tick()
        r = sup.runners["AldrichFront"]
        r.request(30)
        clock.advance(12)
        _, body = sup.health()
        self.assertEqual(body["last_liveview_request_age_s"]["AldrichFront"], 12.0)
        self.assertIsNone(body["last_liveview_request_age_s"][DRIVEWAY])

    async def test_startup_warns_that_polled_motion_cannot_fire_for_no_clip_cameras(self):
        sup, client, _, clock = build(motion_liveview=["AldrichFront"])
        with self.assertLogs("blinkbridge", level="WARNING") as cm:
            await sup.tick()
        self.assertTrue(any("never fires for cameras without cloud clips" in m for m in cm.output))


if __name__ == "__main__":
    unittest.main()
