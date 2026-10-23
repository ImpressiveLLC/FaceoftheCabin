"""Liveness: loop heartbeat, last successful Blink poll, and a dead-man watchdog thread."""
import logging
import os
import threading
from typing import Callable, Dict, Optional, Tuple

log = logging.getLogger(__name__)


class Heartbeat:
    def __init__(self, clock: Callable[[], float], settings):
        self.clock = clock
        self.s = settings
        self.started_at = clock()
        self.loop_tick_at = self.started_at
        self.poll_ok_at: Optional[float] = None
        self.last_error: Optional[str] = None

    def tick(self) -> None:
        self.loop_tick_at = self.clock()

    def poll_ok(self) -> None:
        self.poll_ok_at = self.clock()
        self.last_error = None

    def poll_failed(self, err: str) -> None:
        self.last_error = err

    def status(self) -> Tuple[bool, Dict]:
        now = self.clock()
        loop_age = now - self.loop_tick_at
        ok = loop_age <= self.s.loop_stale
        if self.poll_ok_at is None:
            poll_age = None
            if now - self.started_at > self.s.startup_grace:
                ok = False
        else:
            poll_age = now - self.poll_ok_at
            if poll_age > self.s.poll_stale:
                ok = False
        return ok, {
            "ok": ok,
            "loop_age_s": round(loop_age, 1),
            "last_poll_ok_age_s": None if poll_age is None else round(poll_age, 1),
            "last_error": self.last_error,
            "uptime_s": round(now - self.started_at, 1),
        }


class Watchdog(threading.Thread):
    """Dead-man switch on its own thread: if the event loop stops ticking entirely, exit so Docker restarts us."""

    def __init__(self, hb: Heartbeat, exit_after: float, exit_fn: Callable[[int], None] = os._exit,
                 period: float = 30.0):
        super().__init__(daemon=True, name="bb-watchdog")
        self.hb, self.exit_after, self.exit_fn, self.period = hb, exit_after, exit_fn, period
        self._stop_evt = threading.Event()

    def check(self) -> bool:
        """True if healthy; calls exit_fn(70) and returns False otherwise."""
        age = self.hb.clock() - self.hb.loop_tick_at
        if age > self.exit_after:
            log.critical("event loop silent for %.0fs (limit %.0fs): exiting for restart", age, self.exit_after)
            self.exit_fn(70)
            return False
        return True

    def run(self) -> None:
        while not self._stop_evt.wait(self.period):
            self.check()

    def stop(self) -> None:
        self._stop_evt.set()
