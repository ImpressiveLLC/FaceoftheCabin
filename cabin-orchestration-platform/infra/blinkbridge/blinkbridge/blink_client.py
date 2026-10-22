"""Thin wrapper over blinkpy. The only module that imports it, so tests swap in a fake with the same shape:

    await client.login()                     -> raises AuthError / TransientError
    await client.refresh()                   -> raises ThrottledError / TransientError / AuthError
    client.camera_names() -> list[str]
    client.motion(name) -> (bool, token)
    await client.open_livestream(name)       -> session with .port, async feed(), stop()
    await client.close()

Callers bound every call with asyncio.wait_for; nothing here needs to be cancel-safe beyond that.
"""
import logging
import time
from typing import List, Optional, Tuple

from .errors import AuthError, BlinkError, ThrottledError, TransientError, classify

log = logging.getLogger(__name__)


class _ThrottleSniffer(logging.Handler):
    """blinkpy logs 'Endpoint ... possibly down or throttled' and returns normally instead of raising.
    Record that so refresh() can turn it into a real ThrottledError."""

    def __init__(self):
        super().__init__(level=logging.WARNING)
        self.hits = 0

    def emit(self, record: logging.LogRecord) -> None:
        try:
            msg = record.getMessage().lower()
        except Exception:
            return
        if "possibly down or throttled" in msg or "throttled" in msg:
            self.hits += 1


class LiveSession:
    def __init__(self, live_stream, port: int):
        self._ls = live_stream
        self.port = port

    async def feed(self) -> None:
        await self._ls.feed()

    def stop(self) -> None:
        self._ls.stop()


class BlinkpyClient:
    def __init__(self, settings):
        self.s = settings
        self.session = None
        self.blink = None
        self._sniffer = _ThrottleSniffer()
        logging.getLogger("blinkpy").addHandler(self._sniffer)

    async def login(self) -> None:
        from aiohttp import ClientSession
        from blinkpy.auth import Auth, BlinkTwoFARequiredError, LoginError, TokenRefreshFailed
        from blinkpy.blinkpy import Blink
        from blinkpy.helpers.util import json_load

        await self.close()
        self.session = ClientSession()
        self.blink = Blink(session=self.session)
        cred = self.s.cred_path
        try:
            if cred.exists():
                log.info("blink login: using saved credentials")
                self.blink.auth = Auth(await json_load(cred), no_prompt=True, session=self.session)
            else:
                log.info("blink login: using configured username/password")
                self.blink.auth = Auth(self.s.login_blob, no_prompt=True, session=self.session)
            await self.blink.start()
        except BlinkTwoFARequiredError as exc:
            raise AuthError(f"2FA required (no interactive prompt in container): {exc}") from exc
        except (TokenRefreshFailed, LoginError) as exc:
            if cred.exists():
                log.warning("blink login failed with saved credentials; removing %s", cred)
                cred.unlink()
            raise AuthError(f"{type(exc).__name__}: {exc}") from exc
        except BlinkError:
            raise
        except Exception as exc:
            raise classify(exc) from exc
        try:
            await self.blink.save(cred)
        except Exception as exc:  # saving the token cache is a convenience, never fatal
            log.warning("could not save %s: %s", cred, exc)
        log.info("blink login ok: cameras=%s", self.camera_names())

    async def refresh(self) -> None:
        if self.blink is None:
            raise AuthError("not logged in")
        before = self._sniffer.hits
        try:
            result = await self.blink.refresh()
        except BlinkError:
            raise
        except Exception as exc:
            raise classify(exc) from exc
        if self._sniffer.hits != before:
            raise ThrottledError("blinkpy reported endpoint possibly down or throttled")
        if result is False:
            raise TransientError("blinkpy refresh returned False")

    def camera_names(self) -> List[str]:
        return list(self.blink.cameras.keys()) if self.blink else []

    def motion(self, name: str) -> Tuple[bool, Optional[str]]:
        attrs = self.blink.cameras[name].attributes
        return bool(attrs.get("motion_detected")), (attrs.get("last_record") or attrs.get("video"))

    async def open_livestream(self, name: str) -> LiveSession:
        camera = self.blink.cameras[name]
        try:
            ls = await camera.init_livestream()
            await ls.start(host="127.0.0.1", port=0)
            return LiveSession(ls, ls.socket.getsockname()[1])
        except BlinkError:
            raise
        except Exception as exc:
            raise classify(exc) from exc

    async def close(self) -> None:
        if self.session is not None and not self.session.closed:
            try:
                await self.session.close()
            except Exception as exc:
                log.warning("error closing blink session: %s", exc)
        self.session = None
        self.blink = None
