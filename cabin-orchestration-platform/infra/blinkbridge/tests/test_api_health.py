import json
import logging
import tempfile
import unittest
import urllib.parse
from pathlib import Path

from aiohttp.test_utils import TestClient, TestServer

from blinkbridge import config
from blinkbridge.backoff import Backoff
from blinkbridge.blink_client import BlinkpyClient
from blinkbridge.errors import AuthError, ThrottledError, TransientError, classify
from blinkbridge.health import Watchdog
from blinkbridge.server import make_app

from .fakes import Clock, FakeClient, FakeSpawner, fast_settings
from .test_supervisor import DRIVEWAY, build


class ApiTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.sup, self.fake, self.spawner, self.clock = build()
        self.http = TestClient(TestServer(make_app(self.sup)))
        await self.http.start_server()

    async def asyncTearDown(self):
        await self.http.close()

    async def test_start_stop_contract_matches_cabin_backend(self):
        # not ready (never logged in) -> 503, backend treats any non-200 as failure
        r = await self.http.post("/liveview/x/start")
        self.assertEqual(r.status, 503)
        await self.sup.tick()
        # BlinkLiveviewService URL-encodes spaces as %20
        enc = urllib.parse.quote(DRIVEWAY, safe="")
        r = await self.http.post(f"/liveview/{enc}/start")
        self.assertEqual(r.status, 200)
        body = await r.json()
        self.assertTrue(body["ok"] and body["live"])
        r = await self.http.get(f"/liveview/{enc}/status")
        self.assertEqual((await r.json())["mode"], "live")
        r = await self.http.post(f"/liveview/{enc}/stop")
        self.assertEqual(r.status, 200)
        self.assertTrue(self.fake.sessions[0].stopped)
        r = await self.http.post(f"/liveview/{enc}/stop")
        self.assertEqual(r.status, 404)  # nothing to stop, as the legacy bridge answered
        r = await self.http.post("/liveview/Nope/start")
        self.assertEqual(r.status, 404)
        r = await self.http.get("/liveview/Nope/status")
        self.assertEqual((await r.json())["mode"], "unavailable")

    async def test_start_while_blink_throttled_is_accepted_not_live(self):
        await self.sup.tick()
        self.sup.on_throttle(300, "test")
        r = await self.http.post("/liveview/AldrichFront/start")
        self.assertEqual(r.status, 200)
        body = await r.json()
        self.assertEqual(body["mode"], "reconnecting")
        self.assertFalse(body["live"])

    async def test_healthz_fails_when_loop_goes_stale(self):
        await self.sup.tick()
        r = await self.http.get("/healthz")
        self.assertEqual(r.status, 200)
        self.clock.advance(self.sup.s.loop_stale + 1)  # loop stopped ticking
        r = await self.http.get("/healthz")
        self.assertEqual(r.status, 503)
        await self.sup.tick()
        r = await self.http.get("/healthz")
        self.assertEqual(r.status, 200)

    async def test_healthz_fails_when_polls_never_succeed(self):
        self.fake.login_errors = [AuthError("no")] * 50
        await self.sup.tick()
        self.clock.advance(self.sup.s.startup_grace - 1)
        await self.sup.tick()
        self.assertEqual((await self.http.get("/healthz")).status, 200)  # still in grace
        self.clock.advance(5)
        await self.sup.tick()
        self.assertEqual((await self.http.get("/healthz")).status, 503)

    async def test_healthz_fails_when_last_good_poll_is_old(self):
        await self.sup.tick()
        self.fake.refresh_errors = [TransientError("x")] * 100
        for _ in range(400):
            self.clock.advance(5)
            await self.sup.tick()
        ok, body = self.sup.hb.status()
        self.assertFalse(ok)
        self.assertGreater(body["last_poll_ok_age_s"], self.sup.s.poll_stale)


class WatchdogTests(unittest.TestCase):
    def test_exits_when_loop_dead(self):
        clock = Clock()
        sup, *_ = build()
        sup.hb.clock = clock
        sup.hb.loop_tick_at = clock()
        codes = []
        wd = Watchdog(sup.hb, exit_after=600, exit_fn=codes.append)
        self.assertTrue(wd.check())
        clock.advance(601)
        self.assertFalse(wd.check())
        self.assertEqual(codes, [70])


class ClientTests(unittest.IsolatedAsyncioTestCase):
    async def test_blinkpy_throttle_log_becomes_error(self):
        client = BlinkpyClient(fast_settings())

        class FakeBlink:
            cameras = {}

            async def refresh(self):
                logging.getLogger("blinkpy.blinkpy").warning(
                    "Endpoint https://x/y possibly down or throttled. 429 returned")

        client.blink = FakeBlink()
        with self.assertRaises(ThrottledError):
            await client.refresh()

    async def test_refresh_without_login_is_auth_error(self):
        with self.assertRaises(AuthError):
            await BlinkpyClient(fast_settings()).refresh()


class PureTests(unittest.TestCase):
    def test_backoff_grows_caps_and_jitters(self):
        b = Backoff(5, 60, rng=lambda: 1.0)
        self.assertEqual([b.next_delay() for _ in range(6)], [5, 10, 20, 40, 60, 60])
        lo = Backoff(5, 60, rng=lambda: 0.0)
        self.assertEqual(lo.next_delay(), 2.5)
        self.assertEqual(lo.next_delay(floor=300), 300)
        b.reset()
        self.assertEqual(b.next_delay(), 5)

    def test_classify(self):
        self.assertIsInstance(classify(RuntimeError("endpoint possibly down or throttled")), ThrottledError)
        self.assertIsInstance(classify(RuntimeError("401 unauthorized")), AuthError)
        self.assertIsInstance(classify(ConnectionResetError("reset")), TransientError)

    def test_config_legacy_shape_and_env_override(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "config.json"
            p.write_text(json.dumps({
                "paths": {"config": d},
                "cameras": {"enabled": [], "disabled": ["x"], "motion_liveview_enabled": ["AldrichFront"]},
                "blink": {"login": {"username": "a@b", "password": "pw"}, "poll_interval": 5},
                "rtsp_server": {"address": "mediamtx", "port": 8554},
            }))
            s = config.load(str(p), env={"BLINK_PASSWORD": "envpw"})
            self.assertEqual((s.username, s.password), ("a@b", "envpw"))
            self.assertEqual(s.poll_interval, 30.0)  # floor
            self.assertEqual(s.rtsp_base, "rtsp://mediamtx:8554")
            self.assertEqual(s.motion_liveview, ["AldrichFront"])
            s2 = config.load(str(p), env={"MEDIAMTX_URL": "rtsp://other:8554/"})
            self.assertEqual(s2.rtsp_base, "rtsp://other:8554")

    def test_slug(self):
        self.assertEqual(config.slug("Outdoor 4 - DHEE"), "outdoor_4_-_dhee")
        self.assertEqual(config.slug("AldrichFront"), "aldrichfront")


if __name__ == "__main__":
    unittest.main()
