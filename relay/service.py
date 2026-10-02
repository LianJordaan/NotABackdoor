"""Opt-in, outbound-only HTTPS relay for the local NotABackdoor panel.

The relay is a separate service. It never reads a Minecraft server directory or
receives a Modrinth, SSH, or workspace-management token. Browser panel traffic
does pass through it, including panel login credentials; operate it accordingly.
"""

from __future__ import annotations

import argparse
import asyncio
from collections import defaultdict
from dataclasses import dataclass, field
import hashlib
import hmac
import html
import ipaddress
import json
import logging
import os
from pathlib import Path
import re
import secrets
import sqlite3
import ssl
import time
from urllib.parse import urlsplit
from urllib.parse import parse_qs

from aiohttp import web

from storage import Registry, load_signing_key


ID = re.compile(r"[A-Za-z0-9_-]{22,64}\Z")
HEX = re.compile(r"[a-f0-9]{64}\Z")
TOKEN = re.compile(r"[A-Za-z0-9_-]{32,128}\Z")
SESSION = re.compile(r"(?:^|;\s*)nab_session=([A-Za-z0-9_-]{0,128})(?:;|$)")
MAX_FRAME = 64 * 1024
MAX_JSON = 2 * 1024 * 1024
MAX_UPLOAD = 128 * 1024 * 1024
# A 20 GiB backup may gain ZIP metadata; streaming keeps this cap off the heap.
MAX_RESPONSE = 21 * 1024 * 1024 * 1024
JOB_WAIT_SECONDS = 120
POLL_SECONDS = 20
PAIR_COOKIE = "__Host-nab_pair"
ROUTE_COOKIE = "__Host-nab_route"
LOG = logging.getLogger("notabackdoor.relay")


@dataclass(frozen=True)
class Settings:
    origin: str
    data_dir: Path
    trusted_loopback_proxy: bool = False
    allow_insecure_test: bool = False

    def __post_init__(self):
        parsed = urlsplit(self.origin)
        if (parsed.scheme != "https" or not parsed.hostname or parsed.path
                or parsed.query or parsed.fragment or parsed.username or parsed.password
                or self.origin.rstrip("/") != self.origin):
            raise ValueError("Relay origin must be one HTTPS origin without a path or credentials")

    @property
    def host(self) -> str:
        return urlsplit(self.origin).netloc


@dataclass
class Job:
    id: str
    device_id: str
    method: str
    path: str
    headers: dict[str, str]
    request_size: int
    response_ready: asyncio.Future
    request_chunks: asyncio.Queue = field(default_factory=lambda: asyncio.Queue(maxsize=4))
    chunks: asyncio.Queue = field(default_factory=lambda: asyncio.Queue(maxsize=4))
    request_pump: asyncio.Task | None = None
    responding: bool = False
    closed: bool = False

    def close(self) -> None:
        self.closed = True
        if self.request_pump is not None:
            self.request_pump.cancel()
        for queue in (self.request_chunks, self.chunks):
            if queue.full():
                queue.get_nowait()
            queue.put_nowait(ConnectionAbortedError("Relay request ended"))


class Attempts:
    """Durable pre-auth rate limits; panel auth still validates passwords."""

    def __init__(self, registry: Registry):
        self.registry = registry

    def allow(self, key: str, limit: int, window: int) -> bool:
        return self.registry.allow_attempt(key, limit, window)


class Relay:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.registry = Registry(settings.data_dir / "registry.sqlite3")
        self.signing_key = load_signing_key(settings.data_dir / "route-signing.key")
        self.attempts = Attempts(self.registry)
        self.queues: dict[str, asyncio.Queue[Job]] = defaultdict(lambda: asyncio.Queue(maxsize=4))
        self.last_poll: dict[str, float] = {}
        self.jobs: dict[str, Job] = {}

    def stop_jobs(self) -> None:
        for job in self.jobs.values():
            if not job.response_ready.done():
                job.response_ready.set_exception(web.HTTPServiceUnavailable(text="Relay is restarting"))
            job.close()

    def close(self) -> None:
        self.stop_jobs()
        self.registry.close()

    def client_ip(self, request: web.Request) -> str:
        peer = request.remote or "unknown"
        if self.settings.trusted_loopback_proxy and peer in ("127.0.0.1", "::1"):
            supplied = request.headers.get("X-Forwarded-For", "")
            if supplied and "," not in supplied:
                try:
                    return str(ipaddress.ip_address(supplied))
                except ValueError:
                    pass
        return peer

    def route_cookie(self, device_id: str, expires_at: int | None = None) -> str:
        expires_at = int(time.time()) + 30 * 24 * 3600 if expires_at is None else expires_at
        signed = device_id + "." + str(expires_at)
        signature = hmac.new(self.signing_key, signed.encode("ascii"), hashlib.sha256).hexdigest()
        return signed + "." + signature

    def route(self, request: web.Request) -> str | None:
        cookie = request.cookies.get(ROUTE_COOKIE, "")
        parts = cookie.split(".")
        if len(parts) != 3:
            return None
        device_id, expiry, signature = parts
        if (not ID.fullmatch(device_id) or not expiry.isascii() or not expiry.isdigit()
                or len(expiry) > 12 or int(expiry) < int(time.time())
                or not HEX.fullmatch(signature)):
            return None
        expected = self.route_cookie(device_id, int(expiry)).rsplit(".", 1)[1]
        if not hmac.compare_digest(signature, expected) or not self.registry.active(device_id):
            return None
        return device_id

    def require_origin(self, request: web.Request) -> None:
        if request.headers.get("Origin") != self.settings.origin:
            raise web.HTTPForbidden(text="Cross-site requests are blocked")

    def reject_foreign_origin(self, request: web.Request) -> None:
        origin = request.headers.get("Origin")
        if origin is not None and origin != self.settings.origin:
            raise web.HTTPForbidden(text="Cross-site requests are blocked")

    def device(self, request: web.Request) -> str:
        authorization = request.headers.get("Authorization", "")
        if not authorization.startswith("Bearer "):
            raise web.HTTPUnauthorized(text="Device authentication required")
        device_id, separator, secret = authorization[7:].partition(".")
        if (not separator or not ID.fullmatch(device_id) or not TOKEN.fullmatch(secret)
                or not self.registry.authenticate(device_id, secret)):
            raise web.HTTPUnauthorized(text="Device authentication required")
        return device_id

    async def small_json(self, request: web.Request) -> dict:
        raw = bytearray()
        async for chunk in request.content.iter_chunked(MAX_FRAME):
            raw.extend(chunk)
            if len(raw) > MAX_FRAME:
                raise web.HTTPRequestEntityTooLarge(max_size=MAX_FRAME, actual_size=len(raw))
        try:
            value = json.loads(raw)
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise web.HTTPBadRequest(text="Expected JSON") from None
        if not isinstance(value, dict):
            raise web.HTTPBadRequest(text="Expected JSON object")
        return value

    async def pair_start(self, request: web.Request) -> web.Response:
        row = await self.small_json(request)
        pair_id = row.get("pair_id")
        hashes = (row.get("code_hash"), row.get("pending_hash"), row.get("device_hash"))
        if not isinstance(pair_id, str) or not ID.fullmatch(pair_id) or any(
                not isinstance(value, str) or not HEX.fullmatch(value) for value in hashes):
            raise web.HTTPBadRequest(text="Invalid pairing request")
        ip = self.client_ip(request)
        if not self.attempts.allow("pair-start:global", 5, 60):
            raise web.HTTPTooManyRequests(text="Relay pairing capacity reached; retry shortly")
        if not self.attempts.allow("pair-start:" + ip, 12, 3600):
            raise web.HTTPTooManyRequests(text="Pairing rate limit reached")
        try:
            expires = self.registry.start_pair(pair_id, *hashes, ip)
        except (ValueError, sqlite3.IntegrityError):
            raise web.HTTPConflict(text="Pairing cannot be started") from None
        return web.json_response({"url": self.settings.origin + "/pair/" + pair_id,
                                  "expires_at": expires}, status=201)

    async def pair_status(self, request: web.Request) -> web.Response:
        row = await self.small_json(request)
        pair_id, pending = row.get("pair_id"), row.get("pending_token")
        if (not isinstance(pair_id, str) or not ID.fullmatch(pair_id)
                or not isinstance(pending, str) or not TOKEN.fullmatch(pending)):
            raise web.HTTPUnauthorized(text="Pairing authentication required")
        status = self.registry.pair_status(pair_id, pending)
        if status == "unknown":
            raise web.HTTPUnauthorized(text="Pairing authentication required")
        return web.json_response({"status": status, "device_id": pair_id if status == "claimed" else None})

    async def pair_cancel(self, request: web.Request) -> web.Response:
        row = await self.small_json(request)
        pair_id, pending = row.get("pair_id"), row.get("pending_token")
        if (not isinstance(pair_id, str) or not ID.fullmatch(pair_id)
                or not isinstance(pending, str) or not TOKEN.fullmatch(pending)
                or not self.registry.cancel_pair(pair_id, pending)):
            raise web.HTTPUnauthorized(text="Pairing authentication required")
        return web.json_response({"revoked": True})

    async def pair_page(self, request: web.Request) -> web.Response:
        pair_id = request.match_info["pair_id"]
        if not ID.fullmatch(pair_id) or not self.registry.pair_available(pair_id):
            raise web.HTTPNotFound(text="Pairing link unavailable")
        csrf = secrets.token_urlsafe(24)
        page = ("<!doctype html><html lang='en'><meta charset='utf-8'>"
                "<meta name='viewport' content='width=device-width, initial-scale=1'>"
                "<title>Connect server · NotABackdoor</title>"
                "<link rel='stylesheet' href='/relay/pair.css'>"
                "<main><div class='mark'>N<span>·</span>B</div><p class='eyebrow'>Server connection</p>"
                "<h1>Connect your server</h1><p>Enter the one-time code shown by "
                "<code>nab relay pair</code> in your server console. The code expires after 15 minutes.</p>"
                "<form method='post' action='/pair/" + html.escape(pair_id) + "'>"
                "<input type='hidden' name='csrf' value='" + csrf + "'>"
                "<label>Pairing code<input name='code' autocomplete='off' required autofocus></label>"
                "<button type='submit'>Connect server</button></form>"
                "<p class='fine'>This relay operator can observe panel traffic. "
                "Use a local SSH tunnel if you need the higher-privacy option.</p></main></html>")
        response = web.Response(text=page, content_type="text/html")
        response.set_cookie(PAIR_COOKIE, csrf, secure=True, httponly=True,
                            samesite="Strict", path="/", max_age=900)
        return response

    async def pair_claim(self, request: web.Request) -> web.Response:
        self.require_origin(request)
        pair_id = request.match_info["pair_id"]
        if not ID.fullmatch(pair_id):
            raise web.HTTPNotFound()
        ip = self.client_ip(request)
        if not self.attempts.allow("pair-claim:" + ip, 20, 900):
            raise web.HTTPTooManyRequests(text="Pairing rate limit reached")
        raw = bytearray()
        async for chunk in request.content.iter_chunked(1024):
            raw.extend(chunk)
            if len(raw) > 1024:
                raise web.HTTPRequestEntityTooLarge(max_size=1024, actual_size=len(raw))
        try:
            form = parse_qs(raw.decode("ascii"), keep_blank_values=True, strict_parsing=True)
            if len(form.get("csrf", [])) != 1 or len(form.get("code", [])) != 1:
                raise ValueError("Pairing form fields must be unique")
            csrf = form.get("csrf", [""])[0]
            code = form.get("code", [""])[0]
        except (UnicodeDecodeError, ValueError):
            raise web.HTTPBadRequest(text="Invalid pairing form") from None
        cookie = request.cookies.get(PAIR_COOKIE, "")
        if (not isinstance(csrf, str) or not isinstance(code, str) or not cookie
                or not hmac.compare_digest(csrf, cookie) or not TOKEN.fullmatch(code)):
            raise web.HTTPForbidden(text="Invalid or expired pairing")
        if not self.registry.claim(pair_id, code):
            raise web.HTTPForbidden(text="Invalid or expired pairing")
        response = web.HTTPSeeOther(location="/")
        response.set_cookie(ROUTE_COOKIE, self.route_cookie(pair_id), secure=True,
                            httponly=True, samesite="Strict", path="/", max_age=30 * 24 * 3600)
        response.set_cookie(PAIR_COOKIE, "", secure=True, httponly=True,
                            samesite="Strict", path="/", max_age=0)
        security_headers(response)
        raise response

    async def pair_css(self, request: web.Request) -> web.Response:
        css = (Path(__file__).parent / "pair.css").read_text(encoding="utf-8")
        return web.Response(text=css, content_type="text/css")

    async def device_poll(self, request: web.Request) -> web.Response:
        device_id = self.device(request)
        self.last_poll[device_id] = time.monotonic()
        queue = self.queues[device_id]
        try:
            while True:
                job = await asyncio.wait_for(queue.get(), timeout=POLL_SECONDS)
                if not self.registry.active(device_id):
                    raise web.HTTPUnauthorized(text="Device authentication required")
                if not job.closed:
                    return web.json_response({"id": job.id, "method": job.method,
                                              "path": job.path, "headers": job.headers,
                                              "body_length": job.request_size})
        except asyncio.TimeoutError:
            return web.Response(status=204)

    def owned_job(self, request: web.Request) -> Job:
        device_id = self.device(request)
        job = self.jobs.get(request.match_info["job_id"])
        if job is None or job.device_id != device_id or job.closed:
            raise web.HTTPNotFound(text="Request expired")
        return job

    async def device_request(self, request: web.Request) -> web.StreamResponse:
        job = self.owned_job(request)
        headers = {"Content-Type": "application/octet-stream"}
        if job.request_size >= 0:
            headers["Content-Length"] = str(job.request_size)
        response = web.StreamResponse(status=200, headers=headers)
        await response.prepare(request)
        while True:
            chunk = await asyncio.wait_for(job.request_chunks.get(), timeout=JOB_WAIT_SECONDS)
            if chunk is None:
                break
            if isinstance(chunk, Exception):
                raise ConnectionResetError("Browser upload failed")
            await response.write(chunk)
        await response.write_eof()
        return response

    async def pump_request(self, request: web.Request, job: Job, cap: int) -> None:
        """Forward upload bytes with backpressure; never spool panel bodies to disk."""
        total = 0
        failure: Exception | None = None
        try:
            async for chunk in request.content.iter_chunked(MAX_FRAME):
                total += len(chunk)
                if total > cap:
                    raise web.HTTPRequestEntityTooLarge(max_size=cap, actual_size=total)
                await asyncio.wait_for(job.request_chunks.put(chunk), timeout=15)
            if job.request_size >= 0 and total != job.request_size:
                raise web.HTTPBadRequest(text="Incomplete browser upload")
        except asyncio.CancelledError:
            return
        except Exception as error:
            failure = error
            if not job.response_ready.done():
                job.response_ready.set_exception(error)
        finally:
            if not job.closed:
                try:
                    if failure is not None:
                        await asyncio.wait_for(job.request_chunks.put(failure), timeout=2)
                    await asyncio.wait_for(job.request_chunks.put(None), timeout=2)
                except asyncio.TimeoutError:
                    pass

    async def device_response(self, request: web.Request) -> web.Response:
        job = self.owned_job(request)
        if job.responding:
            raise web.HTTPConflict(text="Response already started")
        try:
            status = int(request.headers.get("X-Panel-Status", ""))
        except ValueError:
            raise web.HTTPBadRequest(text="Invalid response status") from None
        if status < 200 or status > 599:
            raise web.HTTPBadRequest(text="Invalid response status")
        content_type = request.headers.get("X-Panel-Content-Type", "application/octet-stream")
        disposition = request.headers.get("X-Panel-Disposition", "")
        set_cookie = request.headers.get("X-Panel-Set-Cookie", "")
        if any("\r" in value or "\n" in value or len(value) > 512
               for value in (content_type, disposition, set_cookie)):
            raise web.HTTPBadRequest(text="Invalid response header")
        if not (content_type.startswith(("application/", "text/", "image/"))
                or content_type == "application/octet-stream"):
            content_type = "application/octet-stream"
        job.responding = True
        if not job.response_ready.done():
            job.response_ready.set_result((status, content_type, disposition, set_cookie))
        total = 0
        try:
            async for chunk in request.content.iter_chunked(MAX_FRAME):
                total += len(chunk)
                if total > MAX_RESPONSE:
                    raise web.HTTPRequestEntityTooLarge(max_size=MAX_RESPONSE, actual_size=total)
                if job.closed:
                    raise web.HTTPGone(text="Browser request ended")
                await asyncio.wait_for(job.chunks.put(chunk), timeout=15)
        except Exception as failure:
            if not job.closed:
                try:
                    await asyncio.wait_for(job.chunks.put(failure), timeout=2)
                except asyncio.TimeoutError:
                    pass
            raise
        finally:
            if not job.closed:
                try:
                    await asyncio.wait_for(job.chunks.put(None), timeout=2)
                except asyncio.TimeoutError:
                    pass
        return web.json_response({"ok": True})

    async def device_fail(self, request: web.Request) -> web.Response:
        job = self.owned_job(request)
        if not job.response_ready.done():
            job.response_ready.set_exception(web.HTTPBadGateway(text="Panel request failed"))
        else:
            job.close()
        return web.json_response({"failed": True})

    async def device_revoke(self, request: web.Request) -> web.Response:
        device_id = self.device(request)
        self.registry.revoke(device_id)
        self.last_poll.pop(device_id, None)
        for job in list(self.jobs.values()):
            if job.device_id == device_id:
                if not job.response_ready.done():
                    job.response_ready.set_exception(web.HTTPServiceUnavailable(text="Server disconnected"))
                job.close()
        return web.json_response({"revoked": True})

    def _public_headers(self, request: web.Request) -> dict[str, str]:
        headers = {}
        for name in ("Accept", "Content-Type", "X-CSRF-Token", "X-Confirm-Path"):
            value = request.headers.get(name)
            if value is not None and len(value) <= 2048:
                headers[name] = value
        cookie = request.headers.get("Cookie", "")
        match = SESSION.search(cookie)
        if match and match.group(1):
            headers["Cookie"] = "nab_session=" + match.group(1)
        if request.method not in ("GET", "HEAD"):
            headers["Origin"] = "validated"
        return headers

    def _rate_limit_panel(self, request: web.Request, device_id: str) -> None:
        if request.method != "POST" or request.path not in ("/api/login", "/api/setup"):
            return
        kind = "login" if request.path.endswith("login") else "setup"
        ip = self.client_ip(request)
        ip_limit = 8 if kind == "login" else 5
        route_limit = 30 if kind == "login" else 15
        if not self.attempts.allow(f"{kind}:ip:{ip}", ip_limit, 900):
            raise web.HTTPTooManyRequests(text="Too many sign-in attempts")
        if not self.attempts.allow(f"{kind}:route:{device_id}", route_limit, 900):
            raise web.HTTPTooManyRequests(text="Too many sign-in attempts")

    async def proxy(self, request: web.Request) -> web.StreamResponse:
        self.reject_foreign_origin(request)
        device_id = self.route(request)
        if device_id is None:
            if request.path == "/" and request.method == "GET":
                return web.Response(text="<h1>Connect a server</h1><p>Run nab relay pair in your server console and open its one-time link.</p>",
                                    content_type="text/html")
            raise web.HTTPUnauthorized(text="Connect a server using its console pairing link")
        if request.method not in ("GET", "HEAD", "POST", "PUT", "DELETE"):
            raise web.HTTPMethodNotAllowed(request.method, ("GET", "HEAD", "POST", "PUT", "DELETE"))
        if request.method not in ("GET", "HEAD"):
            self.require_origin(request)
            if request.path not in ("/api/login", "/api/setup") and not request.headers.get("X-CSRF-Token"):
                raise web.HTTPForbidden(text="Request token required")
        self._rate_limit_panel(request, device_id)
        if self.last_poll.get(device_id, 0) < time.monotonic() - 30:
            raise web.HTTPServiceUnavailable(text="Server is offline; retry when it reconnects")
        path = request.raw_path
        if len(path) > 2048 or not path.startswith("/") or path.startswith("//") or "\\" in path:
            raise web.HTTPBadRequest(text="Invalid panel path")
        if len(self.jobs) >= 64 or self.queues[device_id].full() or sum(
                job.device_id == device_id for job in self.jobs.values()) >= 4:
            raise web.HTTPServiceUnavailable(text="Panel is busy; retry shortly")
        cap = MAX_UPLOAD if request.path == "/api/upload" else MAX_JSON
        size = request.content_length
        if size is not None and size > cap:
            raise web.HTTPRequestEntityTooLarge(max_size=cap, actual_size=size)
        job = Job(secrets.token_urlsafe(18), device_id, request.method, path,
                  self._public_headers(request), size if size is not None else -1,
                  asyncio.get_running_loop().create_future())
        self.jobs[job.id] = job
        job.request_pump = asyncio.create_task(self.pump_request(request, job, cap))
        self.queues[device_id].put_nowait(job)
        output: web.StreamResponse | None = None
        try:
            status, content_type, disposition, set_cookie = await asyncio.wait_for(
                job.response_ready, timeout=JOB_WAIT_SECONDS)
            output = web.StreamResponse(status=status)
            output.headers["Content-Type"] = content_type
            if disposition:
                output.headers["Content-Disposition"] = disposition
            if set_cookie:
                match = SESSION.search(set_cookie)
                if match:
                    value = match.group(1)
                    output.set_cookie("nab_session", value, secure=True, httponly=True,
                                      samesite="Strict", path="/",
                                      max_age=0 if not value else 7200)
            security_headers(output)
            await output.prepare(request)
            while True:
                chunk = await asyncio.wait_for(job.chunks.get(), timeout=JOB_WAIT_SECONDS)
                if chunk is None:
                    break
                if isinstance(chunk, Exception):
                    raise ConnectionResetError("Device response failed")
                await output.write(chunk)
            await output.write_eof()
            return output
        except asyncio.TimeoutError:
            if output is not None and output.prepared:
                if request.transport is not None:
                    request.transport.close()
                return output
            raise web.HTTPGatewayTimeout(text="Server action timed out; check before retrying") from None
        except Exception:
            if output is not None and output.prepared:
                if request.transport is not None:
                    request.transport.close()
                return output
            raise
        finally:
            self.jobs.pop(job.id, None)
            job.close()


RELAY_KEY = web.AppKey("relay", Relay)


def security_headers(response: web.StreamResponse) -> None:
    response.headers["Cache-Control"] = "no-store"
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["Content-Security-Policy"] = (
        "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
        "connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'")


@web.middleware
async def security_middleware(request: web.Request, handler):
    relay: Relay = request.app[RELAY_KEY]
    try:
        hosts = request.headers.getall("Host", [])
        if len(hosts) != 1 or hosts[0] != relay.settings.host:
            raise web.HTTPMisdirectedRequest(text="Unknown relay host")
        if not relay.settings.allow_insecure_test and not request.secure:
            raise web.HTTPForbidden(text="HTTPS is required")
        response = await handler(request)
    except web.HTTPException as error:
        security_headers(error)
        raise
    except Exception as error:
        # Do not put request paths, headers, cookies, bodies, or stack traces in logs.
        LOG.error("Relay handler failed (%s)", type(error).__name__)
        response = web.HTTPInternalServerError(text="Relay request failed")
        security_headers(response)
        raise response
    security_headers(response)
    return response


def create_app(settings: Settings) -> web.Application:
    relay = Relay(settings)
    app = web.Application(middlewares=[security_middleware], client_max_size=MAX_UPLOAD)
    app[RELAY_KEY] = relay
    app.router.add_post("/relay/v1/pair/start", relay.pair_start)
    app.router.add_post("/relay/v1/pair/status", relay.pair_status)
    app.router.add_post("/relay/v1/pair/cancel", relay.pair_cancel)
    app.router.add_post("/relay/v1/device/poll", relay.device_poll)
    app.router.add_get("/relay/v1/device/jobs/{job_id}/request", relay.device_request)
    app.router.add_post("/relay/v1/device/jobs/{job_id}/response", relay.device_response)
    app.router.add_post("/relay/v1/device/jobs/{job_id}/fail", relay.device_fail)
    app.router.add_post("/relay/v1/device/revoke", relay.device_revoke)
    app.router.add_get("/relay/pair.css", relay.pair_css)
    app.router.add_get("/pair/{pair_id}", relay.pair_page)
    app.router.add_post("/pair/{pair_id}", relay.pair_claim)
    app.router.add_route("*", "/{tail:.*}", relay.proxy)

    async def shutdown(_):
        relay.stop_jobs()
    async def cleanup(_):
        relay.close()
    app.on_shutdown.append(shutdown)
    app.on_cleanup.append(cleanup)
    return app


def main() -> None:
    if os.name != "nt":
        os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--public-origin", required=True, help="Dedicated HTTPS relay origin")
    parser.add_argument("--data", type=Path, required=True, help="Private relay database/key directory")
    parser.add_argument("--cert", type=Path, required=True)
    parser.add_argument("--key", type=Path, required=True)
    parser.add_argument("--listen", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=9443)
    parser.add_argument("--trusted-loopback-proxy", action="store_true")
    args = parser.parse_args()
    settings = Settings(args.public_origin, args.data,
                        trusted_loopback_proxy=args.trusted_loopback_proxy)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.minimum_version = ssl.TLSVersion.TLSv1_2
    tls.load_cert_chain(args.cert, args.key)
    web.run_app(create_app(settings), host=args.listen, port=args.port,
                ssl_context=tls, access_log=None, handler_cancellation=True)


if __name__ == "__main__":
    main()
