"""Entry point: python -m blinkbridge.main"""
import asyncio
import logging
import signal
import sys

from aiohttp import web

from . import config, patches
from .blink_client import BlinkpyClient
from .health import Watchdog
from .server import make_app
from .supervisor import Supervisor

log = logging.getLogger("blinkbridge")


async def amain() -> None:
    settings = config.load()
    logging.getLogger().setLevel(settings.log_level)
    patches.apply()
    if not settings.username or not settings.password:
        log.warning("BLINK_USERNAME/BLINK_PASSWORD not set; relying on saved credentials in %s", settings.cred_path)

    sup = Supervisor(settings, BlinkpyClient(settings))
    watchdog = Watchdog(sup.hb, settings.watchdog_exit_after)
    watchdog.start()

    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop.set)

    runner = web.AppRunner(make_app(sup))
    await runner.setup()
    await web.TCPSite(runner, "0.0.0.0", settings.http_port).start()
    log.info("blinkbridge up: control API + /healthz on :%d", settings.http_port)

    try:
        await sup.run(stop)
    finally:
        log.info("shutting down")
        watchdog.stop()
        await sup.shutdown()
        await runner.cleanup()


def main() -> None:
    logging.basicConfig(stream=sys.stdout, level=logging.INFO,
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    asyncio.run(amain())


if __name__ == "__main__":
    main()
