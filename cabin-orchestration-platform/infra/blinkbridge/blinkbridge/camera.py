"""Per-camera state machine: IDLE -> STARTING -> LIVE -> (BACKOFF | IDLE).

step() reconciles desired state (a viewer/motion request with a deadline) with reality
(blinkpy session + ffmpeg child). It never raises, every await inside is bounded, and
teardown() is best-effort per resource so one failing step cannot strand the others.
"""
import asyncio
import enum
import inspect
import logging
from dataclasses import dataclass
from typing import Callable, Optional

from .backoff import Backoff
from .config import slug
from .errors import ThrottledError, classify
from .relay import RelayProcess, Spawn, relay_args

log = logging.getLogger(__name__)


class State(str, enum.Enum):
    IDLE = "idle"
    STARTING = "starting"
    LIVE = "live"
    BACKOFF = "backoff"


@dataclass
class Env:
    settings: object
    client: object
    clock: Callable[[], float]
    rng: Callable[[], float]
    spawn: Optional[Spawn]
    gate: Callable[[], float]                 # seconds Blink must be left alone (throttle), 0 if clear
    on_throttle: Callable[[float, str], None]  # (delay, reason) -> supervisor blocks all Blink calls


class CameraRunner:
    def __init__(self, name: str, env: Env):
        self.name = name
        self.env = env
        s = env.settings
        self.state = State.IDLE
        self.deadline: Optional[float] = None
        self.next_retry_at = 0.0
        self.last_error: Optional[str] = None
        self.backoff = Backoff(s.cam_backoff_base, s.cam_backoff_cap, env.rng)
        self.session = None
        self.feed_task: Optional[asyncio.Future] = None
        self.relay: Optional[RelayProcess] = None
        self.live_since = 0.0
        self.lock = asyncio.Lock()

    # ---- requests -------------------------------------------------------------------
    def request(self, duration: float) -> None:
        end = self.env.clock() + duration
        self.deadline = end if self.deadline is None else max(self.deadline, end)

    def wanted(self) -> bool:
        return self.deadline is not None and self.env.clock() < self.deadline

    async def stop(self) -> bool:
        """Viewer is done. True if there was anything to stop (legacy /stop contract)."""
        had = self.deadline is not None or self.state != State.IDLE
        self.deadline = None
        async with self.lock:
            await self.teardown()
            self.state = State.IDLE
        return had

    def status(self) -> dict:
        now = self.env.clock()
        mode = "live" if self.state == State.LIVE else ("reconnecting" if self.wanted() else "idle")
        out = {"camera": self.name, "mode": mode, "live": self.state == State.LIVE, "state": self.state.value}
        if mode == "reconnecting":
            out["last_error"] = self.last_error
            out["next_retry_in_s"] = max(0, round(self.next_retry_at - now, 1))
        return out

    # ---- reconcile ------------------------------------------------------------------
    async def step(self) -> None:
        s = self.env.settings
        async with self.lock:
            bound = s.open_timeout + s.warmup + 4 * s.teardown_timeout + 5
            try:
                await asyncio.wait_for(self._step(), bound)
            except asyncio.CancelledError:
                await self.teardown()
                raise
            except Exception as exc:  # includes TimeoutError: a stuck step becomes a normal failure
                log.error("%s: reconcile error: %r", self.name, exc)
                await self.teardown()
                self._fail(exc)

    async def _step(self) -> None:
        now = self.env.clock()
        if self.state == State.LIVE:
            reason = self._dead_reason()
            if reason:
                log.warning("%s: live session ended: %s", self.name, reason)
                if now - self.live_since >= self.env.settings.healthy_session:
                    self.backoff.reset()
                await self.teardown()
                if self.wanted():
                    self._fail(reason)
                else:
                    self.state = State.IDLE
            elif not self.wanted():
                log.info("%s: liveview expired, stopping", self.name)
                self.deadline = None
                await self.teardown()
                self.state = State.IDLE
            return

        if not self.wanted():
            self.deadline = None
            if self.state != State.IDLE:
                self.state = State.IDLE
            return
        if now < self.next_retry_at or self.env.gate() > 0:
            return
        await self._open()

    def _dead_reason(self) -> Optional[str]:
        if self.feed_task is not None and self.feed_task.done():
            if self.feed_task.cancelled():
                return "feed cancelled"
            exc = self.feed_task.exception()
            return f"blink feed failed: {exc!r}" if exc else "blink ended the session"
        if self.relay is None:
            return "no relay process"
        if not self.relay.alive:
            return f"ffmpeg exited rc={self.relay.returncode}: {self.relay.last_error()}"
        if self.relay.stalled(self.env.settings.ffmpeg_stall):
            return f"ffmpeg stalled (no progress for {self.env.settings.ffmpeg_stall:.0f}s)"
        return None

    async def _open(self) -> None:
        s = self.env.settings
        self.state = State.STARTING
        log.info("%s: starting liveview", self.name)
        try:
            self.session = await asyncio.wait_for(self.env.client.open_livestream(self.name), s.open_timeout)
            self.feed_task = asyncio.ensure_future(self.session.feed())
            await asyncio.sleep(s.warmup)  # let the Blink auth handshake finish before ffmpeg connects
            out = f"{s.rtsp_base}/{slug(self.name)}"
            args = relay_args(s.ffmpeg_bin, f"tcp://127.0.0.1:{self.session.port}", out)
            self.relay = await asyncio.wait_for(
                RelayProcess.spawn(args, self.name, self.env.clock, self.env.spawn), s.open_timeout)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            await self.teardown()
            self._fail(exc)
            return
        self.state = State.LIVE
        self.live_since = self.env.clock()
        self.last_error = None
        log.info("%s: live, publishing %s", self.name, out)

    def _fail(self, why) -> None:
        err = classify(why) if isinstance(why, BaseException) else None
        text = str(err or why)
        floor = 0.0
        if isinstance(err, ThrottledError):
            floor = self.env.settings.throttle_floor
        delay = self.backoff.next_delay(floor=floor)
        if isinstance(err, ThrottledError):
            self.env.on_throttle(delay, text)
        self.last_error = text
        self.state = State.BACKOFF
        self.next_retry_at = self.env.clock() + delay
        log.warning("%s: liveview failed (%s); retry in %.0fs", self.name, text, delay)

    # ---- teardown -------------------------------------------------------------------
    async def teardown(self) -> None:
        """Release session, feed task and ffmpeg. Each part bounded and isolated; never raises."""
        s = self.env.settings
        session, feed, relay = self.session, self.feed_task, self.relay
        self.session = self.feed_task = self.relay = None
        if session is not None:
            try:
                res = session.stop()
                if inspect.isawaitable(res):
                    await asyncio.wait_for(res, s.teardown_timeout)
            except Exception as exc:
                log.warning("%s: error stopping blink session: %r", self.name, exc)
        if feed is not None:
            try:
                feed.cancel()
                await asyncio.wait_for(asyncio.gather(feed, return_exceptions=True), s.teardown_timeout)
            except Exception as exc:
                log.warning("%s: error cancelling feed task: %r", self.name, exc)
        if relay is not None:
            await relay.stop(s.teardown_timeout)  # swallows its own errors
