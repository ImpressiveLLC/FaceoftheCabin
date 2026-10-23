import asyncio
import logging
import time
import unittest

from blinkbridge.camera import State
from blinkbridge.errors import ThrottledError, TransientError
from blinkbridge.supervisor import Supervisor

from .fakes import Clock, FakeClient, FakeSpawner, auth_error, fast_settings

DRIVEWAY = "Outdoor 4 - DHEE"


def build(client=None, spawner=None, **settings):
    clock = Clock()
    client = client or FakeClient()
    spawner = spawner or FakeSpawner()
    sup = Supervisor(fast_settings(**settings), client, clock=clock, rng=lambda: 0.5, spawn=spawner)
    return sup, client, spawner, clock


class Base(unittest.IsolatedAsyncioTestCase):
    async def ticks(self, sup, clock, n, dt=5.0):
        for _ in range(n):
            await sup.tick()
            clock.advance(dt)


class ThrottleTests(Base):
    async def test_throttle_blocks_blink_calls_and_backs_off(self):
        sup, client, _, clock = build()
        client.refresh_errors = [ThrottledError("possibly down or throttled")]
        await sup.tick()
        self.assertEqual(client.refresh_calls, 1)
        self.assertGreaterEqual(sup.gate(), sup.s.throttle_floor * 0.5)
        first_gate = sup.gate()
        # inside the throttle window: no more refresh calls no matter how often we tick
        await self.ticks(sup, clock, 20, dt=5.0)  # 100 s
        self.assertEqual(client.refresh_calls, 1)
        # a camera request during the throttle does not open a session either
        sup.runners[DRIVEWAY].request(60)
        await sup.runners[DRIVEWAY].step()
        self.assertEqual(client.sessions, [])
        # after the window we poll again; a second throttle backs off longer
        clock.advance(first_gate + 1)
        client.refresh_errors = [ThrottledError("throttled again")]
        await sup.tick()
        self.assertEqual(client.refresh_calls, 2)
        self.assertGreater(sup.gate(), first_gate)

    async def test_camera_open_throttle_pauses_everything(self):
        sup, client, _, clock = build()
        await sup.tick()
        client.open_errors = [ThrottledError("throttled")]
        r = sup.runners[DRIVEWAY]
        r.request(120)
        await r.step()
        self.assertEqual(r.state, State.BACKOFF)
        self.assertGreater(sup.gate(), 0)
        calls = client.refresh_calls
        clock.advance(40)
        await sup.tick()
        self.assertEqual(client.refresh_calls, calls)  # poll skipped while throttled


class TeardownTests(Base):
    async def go_live(self, **kw):
        sup, client, spawner, clock = build(**kw)
        await sup.tick()
        r = sup.runners[DRIVEWAY]
        r.request(300)
        await r.step()
        self.assertEqual(r.state, State.LIVE)
        return sup, client, spawner, clock, r

    async def test_publishes_to_slugified_mediamtx_path(self):
        _, _, spawner, _, _ = await self.go_live()
        self.assertEqual(spawner.argv[0][-1], "rtsp://mediamtx:8554/outdoor_4_-_dhee")
        self.assertIn("tcp://127.0.0.1:40001", spawner.argv[0])

    async def test_broken_pipe_tears_down_reaps_and_retries(self):
        sup, client, spawner, clock, r = await self.go_live()
        proc = spawner.procs[0]
        proc.die(1, "av_interleaved_write_frame(): Broken pipe")
        await asyncio.sleep(0)
        await r.step()
        self.assertEqual(r.state, State.BACKOFF)
        self.assertTrue(client.sessions[0].stopped)
        self.assertTrue(proc.reaped)
        self.assertIn("Broken pipe", r.last_error)
        # not retried before the backoff elapses
        await r.step()
        self.assertEqual(len(client.sessions), 1)
        clock.advance(r.next_retry_at - clock() + 1)
        await r.step()
        self.assertEqual(r.state, State.LIVE)
        self.assertEqual(len(client.sessions), 2)

    async def test_teardown_errors_never_wedge_the_loop(self):
        sup, client, spawner, clock = build(ffmpeg_stall=20.0)
        client.session_kw = {"stop_raises": True, "feed_ignores_cancel": True}
        spawner.proc_kw = {"ignore_terminate": True, "unkillable": True}
        await sup.tick()
        r = sup.runners[DRIVEWAY]
        r.request(300)
        await r.step()
        self.assertEqual(r.state, State.LIVE)
        client.sessions[0].end.set()  # blink ends the session
        await asyncio.sleep(0.01)
        t0 = time.monotonic()
        await asyncio.wait_for(sup.tick(), 5)  # would hang forever without bounded teardown
        self.assertLess(time.monotonic() - t0, 3)
        self.assertEqual(r.state, State.BACKOFF)
        self.assertTrue(spawner.procs[0].terminated and spawner.procs[0].killed)
        self.assertIsNone(r.relay)
        self.assertIsNone(r.session)

    async def test_stuck_ffmpeg_is_killed(self):
        sup, client, spawner, clock, r = await self.go_live(ffmpeg_stall=20.0)
        proc = spawner.procs[0]
        proc.progress()
        await asyncio.sleep(0.01)
        clock.advance(15)
        await r.step()
        self.assertEqual(r.state, State.LIVE)  # progress seen recently enough
        clock.advance(10)  # 25 s with no progress
        await r.step()
        self.assertEqual(r.state, State.BACKOFF)
        self.assertTrue(proc.terminated)
        self.assertTrue(proc.reaped)
        self.assertIn("stalled", r.last_error)

    async def test_hung_open_times_out_into_backoff(self):
        sup, client, _, clock = build()
        await sup.tick()

        async def hang(name):
            await asyncio.sleep(60)

        client.open_livestream = hang
        r = sup.runners[DRIVEWAY]
        r.request(60)
        await asyncio.wait_for(r.step(), 3)
        self.assertEqual(r.state, State.BACKOFF)
        self.assertIsNotNone(r.last_error)

    async def test_expiry_stops_live_session(self):
        sup, client, spawner, clock, r = await self.go_live()
        clock.advance(301)
        await r.step()
        self.assertEqual(r.state, State.IDLE)
        self.assertTrue(client.sessions[0].stopped)
        self.assertTrue(spawner.procs[0].reaped)


class AuthTests(Base):
    async def test_auth_expiry_triggers_relogin(self):
        sup, client, _, clock = build()
        await sup.tick()
        self.assertEqual(client.login_calls, 1)
        client.refresh_errors = [auth_error()]
        clock.advance(40)
        await sup.tick()  # poll fails with auth error
        self.assertFalse(sup.ready)
        await sup.tick()  # next tick logs in again
        self.assertTrue(sup.ready)
        self.assertEqual(client.login_calls, 2)
        clock.advance(40)
        await sup.tick()
        self.assertIsNotNone(sup.hb.poll_ok_at)
        self.assertIsNone(sup.hb.last_error)

    async def test_login_failure_backs_off_not_hammers(self):
        sup, client, _, clock = build()
        client.login_errors = [auth_error()] * 5
        await sup.tick()
        self.assertFalse(sup.ready)
        await self.ticks(sup, clock, 8, dt=5.0)  # 40 s, below the 45 s jittered backoff
        self.assertEqual(client.login_calls, 1)


class MotionTests(Base):
    async def test_motion_opens_liveview_for_enabled_camera_and_expires(self):
        sup, client, spawner, clock = build(motion_liveview=["AldrichFront"])
        client.motion_state["AldrichFront"] = (True, "t1")
        client.motion_state[DRIVEWAY] = (True, "d1")
        await sup.tick()
        self.assertEqual(sup.runners["AldrichFront"].state, State.LIVE)
        self.assertEqual(sup.runners[DRIVEWAY].state, State.IDLE)  # not opted in
        clock.advance(91)
        await sup.tick()
        self.assertEqual(sup.runners["AldrichFront"].state, State.IDLE)


class NeverSilentTests(Base):
    async def test_loop_logs_at_least_every_40s_even_when_everything_fails(self):
        sup, client, _, clock = build()
        client.login_errors = [TransientError("down")] * 100
        stamps = []

        class H(logging.Handler):
            def emit(self, record):
                stamps.append(clock())

        h = H()
        logging.getLogger("blinkbridge").addHandler(h)
        logging.getLogger("blinkbridge").setLevel(logging.INFO)
        try:
            for _ in range(400):  # ~33 minutes of 5 s ticks
                await sup.tick()
                clock.advance(5)
        finally:
            logging.getLogger("blinkbridge").removeHandler(h)
        self.assertGreater(len(stamps), 20)
        gaps = [b - a for a, b in zip(stamps, stamps[1:])]
        self.assertLessEqual(max(gaps), 40.0, f"silent gap {max(gaps)}s")

    async def test_hung_refresh_cannot_wedge_tick(self):
        sup, client, _, clock = build()
        await sup.tick()
        client.refresh_hangs = True
        clock.advance(40)
        await asyncio.wait_for(sup.tick(), 3)  # refresh_timeout (0.2 s real) bounds it
        self.assertIn("TimeoutError", sup.hb.last_error)
        self.assertLess(clock() - sup.hb.loop_tick_at, 1)

    async def test_poll_line_cadence_is_30_to_40s(self):
        sup, client, _, clock = build(poll_jitter=10.0)
        await sup.tick()
        first = client.refresh_calls
        times = []
        for _ in range(60):
            before = client.refresh_calls
            clock.advance(5)
            await sup.tick()
            if client.refresh_calls != before:
                times.append(clock())
        gaps = [b - a for a, b in zip(times, times[1:])]
        self.assertEqual(first, 1)
        for g in gaps:
            self.assertTrue(30 <= g <= 45, g)


if __name__ == "__main__":
    unittest.main()
