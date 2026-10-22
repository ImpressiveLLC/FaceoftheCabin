"""HTTP: the liveview control API cabin-backend calls (BlinkLiveviewService) plus /healthz."""
import asyncio
import logging

from aiohttp import web

log = logging.getLogger(__name__)


def make_app(sup) -> web.Application:
    def lookup(request: web.Request):
        return sup.find(request.match_info["camera"])  # aiohttp already URL-decodes

    async def start(request: web.Request) -> web.Response:
        if not sup.ready:
            return web.json_response({"ok": False, "error": "not ready"}, status=503)
        runner = lookup(request)
        if runner is None:
            log.warning("liveview start for unknown/disabled camera %r", request.match_info["camera"])
            return web.json_response({"ok": False}, status=404)
        runner.request(sup.s.liveview_max)
        task = asyncio.ensure_future(runner.step())
        await asyncio.wait({task}, timeout=sup.s.inline_open_timeout)  # never cancelled: it keeps going
        return web.json_response({"ok": True, **runner.status()})

    async def stop(request: web.Request) -> web.Response:
        runner = lookup(request)
        if runner is None:
            return web.json_response({"ok": False}, status=404)
        ok = await runner.stop()
        return web.json_response({"ok": ok}, status=200 if ok else 404)

    async def status(request: web.Request) -> web.Response:
        runner = lookup(request)
        if runner is None:
            return web.json_response({"camera": request.match_info["camera"], "mode": "unavailable",
                                      "live": False}, status=404)
        return web.json_response(runner.status())

    async def healthz(request: web.Request) -> web.Response:
        ok, body = sup.hb.status()
        body["ready"] = sup.ready
        body["cameras"] = {n: r.state.value for n, r in sup.runners.items()}
        return web.json_response(body, status=200 if ok else 503)

    app = web.Application()
    app.router.add_post("/liveview/{camera}/start", start)
    app.router.add_post("/liveview/{camera}/stop", stop)
    app.router.add_get("/liveview/{camera}/status", status)
    app.router.add_get("/healthz", healthz)
    return app
