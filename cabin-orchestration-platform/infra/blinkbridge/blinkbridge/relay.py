"""One ffmpeg child: spawn, watch for progress, stop and reap it. stop() can never raise or hang."""
import asyncio
import collections
import logging
from typing import Awaitable, Callable, List, Optional

log = logging.getLogger(__name__)

Spawn = Callable[..., Awaitable["asyncio.subprocess.Process"]]


def relay_args(ffmpeg_bin: str, source_url: str, output_url: str) -> List[str]:
    """mpegts relay from the local blinkpy TCP server to mediamtx. Same contract as the legacy bridge."""
    return [
        ffmpeg_bin, "-hide_banner", "-loglevel", "error", "-y", "-nostats",
        "-progress", "pipe:1",
        "-fflags", "+igndts+genpts",
        "-rw_timeout", "15000000",
        "-f", "mpegts", "-i", source_url,
        "-c:v", "copy", "-an",
        "-rtsp_transport", "tcp",
        "-f", "rtsp", output_url,
    ]


class RelayProcess:
    def __init__(self, proc, name: str, clock: Callable[[], float]):
        self.proc = proc
        self.name = name
        self.clock = clock
        self.started_at = clock()
        self.last_activity = self.started_at
        self.stderr_tail: collections.deque = collections.deque(maxlen=5)
        self._tasks = [
            asyncio.ensure_future(self._drain_progress()),
            asyncio.ensure_future(self._drain_stderr()),
        ]

    @classmethod
    async def spawn(cls, args: List[str], name: str, clock: Callable[[], float],
                    spawn: Optional[Spawn] = None) -> "RelayProcess":
        spawn = spawn or asyncio.create_subprocess_exec
        proc = await spawn(*args, stdin=asyncio.subprocess.DEVNULL,
                           stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        return cls(proc, name, clock)

    async def _drain_progress(self) -> None:
        try:
            while True:
                line = await self.proc.stdout.readline()
                if not line:
                    return
                self.last_activity = self.clock()
        except Exception:  # a drain failure must not take anything else down
            return

    async def _drain_stderr(self) -> None:
        try:
            while True:
                line = await self.proc.stderr.readline()
                if not line:
                    return
                text = line.decode("utf-8", "replace").strip()
                if text:
                    self.stderr_tail.append(text)
                    log.warning("%s: ffmpeg: %s", self.name, text)
        except Exception:
            return

    @property
    def returncode(self) -> Optional[int]:
        return self.proc.returncode

    @property
    def alive(self) -> bool:
        return self.proc.returncode is None

    def stalled(self, stall_after: float) -> bool:
        return self.alive and self.clock() - self.last_activity > stall_after

    def last_error(self) -> str:
        return self.stderr_tail[-1] if self.stderr_tail else f"ffmpeg exited rc={self.returncode}"

    async def stop(self, timeout: float) -> None:
        """terminate -> kill -> reap, each bounded. Swallows everything: teardown must not wedge callers."""
        try:
            if self.alive:
                try:
                    self.proc.terminate()
                except ProcessLookupError:
                    pass
                try:
                    await asyncio.wait_for(self.proc.wait(), timeout)
                except asyncio.TimeoutError:
                    log.warning("%s: ffmpeg ignored terminate, killing", self.name)
                    try:
                        self.proc.kill()
                    except ProcessLookupError:
                        pass
                    try:
                        await asyncio.wait_for(self.proc.wait(), timeout)
                    except asyncio.TimeoutError:
                        log.error("%s: ffmpeg did not die after kill; abandoning", self.name)
            else:
                try:
                    await asyncio.wait_for(self.proc.wait(), timeout)  # reap a child that already exited
                except asyncio.TimeoutError:
                    pass
        except Exception as exc:
            log.warning("%s: error stopping ffmpeg: %s", self.name, exc)
        finally:
            for t in self._tasks:
                t.cancel()
            await asyncio.gather(*self._tasks, return_exceptions=True)
