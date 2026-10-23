"""The supervisor loop: login, poll Blink for motion, reconcile every camera. Nothing in tick() may raise or hang."""
import asyncio
import logging
import random
import time
from typing import Callable, Dict, Optional

from .backoff import Backoff
from .camera import CameraRunner, Env, State
from .config import Settings, slug
from .errors import AuthError, ThrottledError, classify
from .health import Heartbeat

log = logging.getLogger(__name__)


class Supervisor:
    def __init__(self, settings: Settings, client, clock: Callable[[], float] = time.monotonic,
                 rng: Callable[[], float] = random.random, spawn=None):
        self.s = settings
        self.client = client
        self.clock = clock
        self.rng = rng
        self.hb = Heartbeat(clock, settings)
        self.runners: Dict[str, CameraRunner] = {}
        self.ready = False
        self.blocked_until = 0.0
        self.next_poll_at = 0.0
        self.next_login_at = 0.0
        self.last_log_at = clock()
        self._motion_active: Dict[str, bool] = {}
        self._motion_token: Dict[str, Optional[str]] = {}
        self.poll_backoff = Backoff(settings.poll_backoff_base, settings.poll_backoff_cap, rng)
        self.throttle_backoff = Backoff(settings.throttle_floor, settings.throttle_cap, rng)
        self.auth_backoff = Backoff(settings.auth_backoff_base, settings.auth_backoff_cap, rng)
        self.env = Env(settings, client, clock, rng, spawn, self.gate, self.on_throttle)

    # ---- throttle gate (shared by polling, login and every camera) --------------------
    def gate(self) -> float:
        return max(0.0, self.blocked_until - self.clock())

    def on_throttle(self, delay: float, reason: str) -> None:
        self.blocked_until = max(self.blocked_until, self.clock() + delay)
        log.warning("blink throttled (%s): leaving Blink alone for %.0fs", reason, delay)

    # ---- logging: the loop is never silent --------------------------------------------
    def _log(self, level: int, msg: str, *args) -> None:
        self.last_log_at = self.clock()
        log.log(level, msg, *args)

    def _status_line(self, prefix: str) -> None:
        live = [n for n, r in self.runners.items() if r.state == State.LIVE]
        self._log(logging.INFO, "motion poll %s: ready=%s cameras=%d live=%s throttled_for=%.0fs",
                  prefix, self.ready, len(self.runners), live, self.gate())

    # ---- find -------------------------------------------------------------------------
    def find(self, name: str) -> Optional[CameraRunner]:
        if name in self.runners:
            return self.runners[name]
        low = name.lower()
        for n, r in self.runners.items():
            if n.lower() == low or slug(n) == low:
                return r
        return None

    # ---- one tick ---------------------------------------------------------------------
    async def tick(self) -> None:
        self.hb.tick()
        try:
            await asyncio.wait_for(self._cycle(), self.s.cycle_timeout)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            self._log(logging.ERROR, "supervisor cycle error: %r", exc)
        finally:
            self.hb.tick()
            if self.clock() - self.last_log_at >= self.s.max_log_gap:
                self._status_line("status")

    async def _cycle(self) -> None:
        if not self.ready:
            await self._login()
            if not self.ready:
                return
        await self._poll_if_due()
        for runner in list(self.runners.values()):
            try:
                await runner.step()  # bounded and non-raising; guard anyway
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                self._log(logging.ERROR, "%s: step error: %r", runner.name, exc)

    # ---- login ------------------------------------------------------------------------
    async def _login(self) -> bool:
        now = self.clock()
        if now < self.next_login_at or self.gate() > 0:
            return False
        try:
            await asyncio.wait_for(self.client.login(), self.s.login_timeout)
            names = list(self.client.camera_names())
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            err = classify(exc)
            self.hb.poll_failed(str(err))
            if isinstance(err, ThrottledError):
                delay = self.throttle_backoff.next_delay()
                self.on_throttle(delay, str(err))
            else:
                delay = self.auth_backoff.next_delay()
            self.next_login_at = self.clock() + delay
            self._log(logging.WARNING, "motion poll: blink login failed (%s); retry in %.0fs", err, delay)
            return False
        self.auth_backoff.reset()
        enabled = set(self.s.enabled) if self.s.enabled else set(names)
        enabled -= set(self.s.disabled)
        old = self.runners
        self.runners = {}
        for n in names:
            if n in enabled:
                self.runners[n] = old.get(n) or CameraRunner(n, self.env)
        self.ready = True
        self.next_poll_at = 0.0
        self._log(logging.INFO, "blink ready: enabled cameras=%s", sorted(self.runners))
        return True

    # ---- poll -------------------------------------------------------------------------
    async def _poll_if_due(self) -> None:
        now = self.clock()
        if now < self.next_poll_at:
            return
        if self.gate() > 0:
            self.next_poll_at = self.blocked_until
            self._status_line("skipped (throttled)")
            return
        try:
            await asyncio.wait_for(self.client.refresh(), self.s.refresh_timeout)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            await self._poll_failed(classify(exc))
            return
        self.hb.poll_ok()
        self.poll_backoff.reset()
        self.throttle_backoff.reset()
        self.next_poll_at = self.clock() + self.s.poll_interval + self.rng() * self.s.poll_jitter
        self._detect_motion()
        self._status_line("ok")

    async def _poll_failed(self, err) -> None:
        self.hb.poll_failed(str(err))
        if isinstance(err, ThrottledError):
            delay = self.throttle_backoff.next_delay(floor=self.s.throttle_floor)
            self.on_throttle(delay, str(err))
        elif isinstance(err, AuthError):
            delay = self.s.poll_interval
            self.ready = False  # force a fresh login on the next tick
            self.next_login_at = 0.0
        else:
            delay = self.poll_backoff.next_delay(floor=self.s.poll_interval)
        self.next_poll_at = self.clock() + delay
        self._log(logging.WARNING, "motion poll failed (%s: %s); next in %.0fs", type(err).__name__, err, delay)

    def _detect_motion(self) -> None:
        for name, runner in self.runners.items():
            try:
                active, token = self.client.motion(name)
            except Exception as exc:
                log.warning("%s: cannot read motion: %r", name, exc)
                continue
            was_active = self._motion_active.get(name, False)
            prev_token = self._motion_token.get(name)
            new = active and (not was_active or (token is not None and token != prev_token))
            self._motion_active[name] = active
            if active and token is not None:
                self._motion_token[name] = token
            if new:
                self._log(logging.INFO, "%s: new Blink motion event", name)
                if name in self.s.motion_liveview:
                    runner.request(self.s.motion_max)

    # ---- long-running -----------------------------------------------------------------
    async def run(self, stop: asyncio.Event) -> None:
        while not stop.is_set():
            await self.tick()
            try:
                await asyncio.wait_for(stop.wait(), self.s.step_interval)
            except asyncio.TimeoutError:
                pass

    async def shutdown(self) -> None:
        for runner in list(self.runners.values()):
            try:
                await asyncio.wait_for(runner.stop(), self.s.teardown_timeout * 4)
            except Exception as exc:
                log.warning("%s: shutdown error: %r", runner.name, exc)
        try:
            await asyncio.wait_for(self.client.close(), self.s.teardown_timeout)
        except Exception as exc:
            log.warning("client close error: %r", exc)
