"""Fakes for blinkpy and ffmpeg. No network, no subprocesses."""
import asyncio
from typing import List, Optional

from blinkbridge.config import Settings
from blinkbridge.errors import AuthError


class Clock:
    def __init__(self, t: float = 1000.0):
        self.t = t

    def __call__(self) -> float:
        return self.t

    def advance(self, dt: float) -> None:
        self.t += dt


def fast_settings(**kw) -> Settings:
    """Real-time timeouts are tiny so hung-step tests finish fast; durations on the fake clock stay realistic."""
    s = Settings(
        username="u", password="p", warmup=0.0,
        refresh_timeout=0.2, login_timeout=0.2, open_timeout=0.2,
        teardown_timeout=0.1, cycle_timeout=3.0, inline_open_timeout=1.0,
    )
    for k, v in kw.items():
        setattr(s, k, v)
    return s


class FakeProc:
    """Stands in for asyncio.subprocess.Process."""

    def __init__(self, ignore_terminate=False, unkillable=False):
        self.stdout = asyncio.StreamReader()
        self.stderr = asyncio.StreamReader()
        self.returncode: Optional[int] = None
        self.ignore_terminate = ignore_terminate
        self.unkillable = unkillable
        self.terminated = False
        self.killed = False
        self.reaped = False
        self._exited = asyncio.Event()

    def progress(self):
        self.stdout.feed_data(b"progress=continue\n")

    def die(self, rc: int, stderr: str = ""):
        if stderr:
            self.stderr.feed_data(stderr.encode() + b"\n")
        self.returncode = rc
        self.stdout.feed_eof()
        self.stderr.feed_eof()
        self._exited.set()

    def terminate(self):
        self.terminated = True
        if not self.ignore_terminate:
            self.die(-15)

    def kill(self):
        self.killed = True
        if not self.unkillable:
            self.die(-9)

    async def wait(self):
        await self._exited.wait()
        self.reaped = True
        return self.returncode


class FakeSpawner:
    def __init__(self, **proc_kw):
        self.procs: List[FakeProc] = []
        self.argv: List[list] = []
        self.proc_kw = proc_kw

    async def __call__(self, *args, **kwargs):
        self.argv.append(list(args))
        p = FakeProc(**self.proc_kw)
        self.procs.append(p)
        return p


class FakeSession:
    def __init__(self, port=40001, stop_raises=False, feed_ignores_cancel=False):
        self.port = port
        self.stopped = False
        self.stop_raises = stop_raises
        self.feed_ignores_cancel = feed_ignores_cancel
        self.end = asyncio.Event()
        self.feed_error: Optional[Exception] = None

    async def feed(self):
        while True:
            try:
                await self.end.wait()
            except asyncio.CancelledError:
                if self.feed_ignores_cancel:
                    await asyncio.sleep(60)
                raise
            if self.feed_error:
                raise self.feed_error
            return

    def stop(self):
        self.stopped = True
        if self.stop_raises:
            raise ConnectionResetError("teardown boom")


class FakeClient:
    def __init__(self, cams=("Outdoor 4 - DHEE", "AldrichFront")):
        self.cams = list(cams)
        self.login_calls = 0
        self.refresh_calls = 0
        self.login_errors: list = []     # popped per call; None = success
        self.refresh_errors: list = []
        self.open_errors: list = []
        self.refresh_hangs = False
        self.motion_state = {}           # name -> (bool, token)
        self.raw = {}                    # name -> raw camera record dict
        self.sessions: List[FakeSession] = []
        self.session_kw: dict = {}
        self.closed = False

    async def login(self):
        self.login_calls += 1
        if self.login_errors:
            err = self.login_errors.pop(0)
            if err:
                raise err

    async def refresh(self):
        self.refresh_calls += 1
        if self.refresh_hangs:
            await asyncio.sleep(60)
        if self.refresh_errors:
            err = self.refresh_errors.pop(0)
            if err:
                raise err

    def camera_names(self):
        return list(self.cams)

    def motion(self, name):
        return self.motion_state.get(name, (False, None))

    def raw_camera(self, name):
        return dict(self.raw.get(name, {}))

    async def open_livestream(self, name):
        if self.open_errors:
            err = self.open_errors.pop(0)
            if err:
                raise err
        s = FakeSession(**self.session_kw)
        self.sessions.append(s)
        return s

    async def close(self):
        self.closed = True


def auth_error():
    return AuthError("token expired")
