"""No external relay host or Minecraft server is contacted by these tests."""

from __future__ import annotations

import asyncio
from contextlib import suppress
import hashlib
import json
import io
import logging
from pathlib import Path
import re
import secrets
import tempfile
import time
import unittest
from unittest.mock import patch

from aiohttp.test_utils import TestClient, TestServer

import service
from storage import Registry


ORIGIN = "https://relay.example.test"
HOST = "relay.example.test"


class RelayTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.settings = service.Settings(ORIGIN, Path(self.temp.name), allow_insecure_test=True)
        self.client = TestClient(TestServer(service.create_app(self.settings), handler_cancellation=True))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()

    def headers(self, **extra):
        return {"Host": HOST, **extra}

    async def pair(self):
        pair_id = secrets.token_urlsafe(18)
        code = secrets.token_urlsafe(24)
        pending = secrets.token_urlsafe(32)
        device = secrets.token_urlsafe(32)
        hashed = lambda value: hashlib.sha256(value.encode("ascii")).hexdigest()
        response = await self.client.post("/relay/v1/pair/start", headers=self.headers(), json={
            "pair_id": pair_id, "code_hash": hashed(code),
            "pending_hash": hashed(pending), "device_hash": hashed(device),
        })
        self.assertEqual(201, response.status)
        self.assertEqual(ORIGIN + "/pair/" + pair_id, (await response.json())["url"])
        page = await self.client.get("/pair/" + pair_id, headers=self.headers())
        self.assertEqual(200, page.status)
        text = await page.text()
        csrf = re.search(r"name='csrf' value='([^']+)'", text).group(1)
        pair_cookie = page.cookies[service.PAIR_COOKIE].value
        claim = await self.client.post("/pair/" + pair_id, allow_redirects=False,
                                       headers=self.headers(Origin=ORIGIN,
                                                            Cookie=service.PAIR_COOKIE + "=" + pair_cookie),
                                       data={"code": code, "csrf": csrf})
        self.assertEqual(303, claim.status)
        self.assertIn("Secure", " ".join(claim.headers.getall("Set-Cookie", [])))
        route = claim.cookies[service.ROUTE_COOKIE].value
        return pair_id, pending, device, route

    async def test_pairing_route_cookie_reconnect_and_revoke(self):
        pair_id, pending, device, route = await self.pair()
        answer = await self.client.post("/relay/v1/pair/status", headers=self.headers(),
                                        json={"pair_id": pair_id, "pending_token": pending})
        self.assertEqual({"status": "claimed", "device_id": pair_id}, await answer.json())
        self.assertTrue(self.client.server.app[service.RELAY_KEY].registry.authenticate(pair_id, device))
        db_bytes = (Path(self.temp.name) / "registry.sqlite3").read_bytes()
        self.assertNotIn(device.encode("ascii"), db_bytes)
        self.assertNotIn(pending.encode("ascii"), db_bytes)
        await self.client.close()
        self.client = TestClient(TestServer(service.create_app(self.settings), handler_cancellation=True))
        await self.client.start_server()
        relay = self.client.server.app[service.RELAY_KEY]
        self.assertEqual(pair_id, relay.route(type("Request", (), {"cookies": {
            service.ROUTE_COOKIE: route}})()))
        self.assertTrue(relay.registry.authenticate(pair_id, device))
        revoke = await self.client.post("/relay/v1/device/revoke", headers=self.headers(
            Authorization="Bearer " + pair_id + "." + device))
        self.assertEqual(200, revoke.status)
        self.assertFalse(relay.registry.authenticate(pair_id, device))
        no_route = await self.client.get("/api/status", headers=self.headers(
            Cookie=service.ROUTE_COOKIE + "=" + route))
        self.assertEqual(401, no_route.status)

    async def test_host_origin_pair_csrf_and_pair_attempt_cap(self):
        response = await self.client.get("/", headers={"Host": "attacker.example"})
        self.assertEqual(421, response.status)
        pair_id = secrets.token_urlsafe(18)
        code = secrets.token_urlsafe(24)
        pending = secrets.token_urlsafe(32)
        device = secrets.token_urlsafe(32)
        hashed = lambda value: hashlib.sha256(value.encode("ascii")).hexdigest()
        response = await self.client.post("/relay/v1/pair/start", headers=self.headers(), json={
            "pair_id": pair_id, "code_hash": hashed(code),
            "pending_hash": hashed(pending), "device_hash": hashed(device)})
        self.assertEqual(201, response.status)
        duplicate = await self.client.post("/relay/v1/pair/start", headers=self.headers(), json={
            "pair_id": pair_id, "code_hash": hashed(code),
            "pending_hash": hashed(pending), "device_hash": hashed(device)})
        self.assertEqual(409, duplicate.status)
        page = await self.client.get("/pair/" + pair_id, headers=self.headers())
        csrf = re.search(r"name='csrf' value='([^']+)'", await page.text()).group(1)
        cookie = service.PAIR_COOKIE + "=" + page.cookies[service.PAIR_COOKIE].value
        invalid_origin = await self.client.post("/pair/" + pair_id,
                                                headers=self.headers(Origin="https://attacker.example", Cookie=cookie),
                                                data={"csrf": csrf, "code": code})
        self.assertEqual(403, invalid_origin.status)
        wrong_csrf = await self.client.post("/pair/" + pair_id,
                                             headers=self.headers(Origin=ORIGIN, Cookie=cookie),
                                             data={"csrf": "wrong", "code": code})
        self.assertEqual(403, wrong_csrf.status)
        duplicated = await self.client.post("/pair/" + pair_id,
            headers=self.headers(Origin=ORIGIN, Cookie=cookie,
                                 **{"Content-Type": "application/x-www-form-urlencoded"}),
            data=f"csrf={csrf}&csrf={csrf}&code={code}")
        self.assertEqual(400, duplicated.status)
        for _ in range(5):
            wrong_code = await self.client.post("/pair/" + pair_id,
                                                headers=self.headers(Origin=ORIGIN, Cookie=cookie),
                                                data={"csrf": csrf, "code": secrets.token_urlsafe(24)})
            self.assertEqual(403, wrong_code.status)
        locked = await self.client.post("/pair/" + pair_id,
                                        headers=self.headers(Origin=ORIGIN, Cookie=cookie),
                                        data={"csrf": csrf, "code": code})
        self.assertEqual(403, locked.status)

    async def test_browser_proxy_streaming_headers_csrf_and_limits(self):
        pair_id, _, device, route = await self.pair()
        bearer = "Bearer " + pair_id + "." + device
        route_header = self.headers(Cookie=service.ROUTE_COOKIE + "=" + route
                                    + "; nab_session=SessionToken")
        relay = self.client.server.app[service.RELAY_KEY]
        relay.last_poll[pair_id] = time.monotonic()
        foreign = await self.client.post("/api/console", headers={**route_header,
                                             "Origin": "https://attacker.example",
                                             "X-CSRF-Token": "token"}, data=b"x")
        self.assertEqual(403, foreign.status)
        missing_csrf = await self.client.post("/api/console", headers={**route_header,
                                                "Origin": ORIGIN}, data=b"x")
        self.assertEqual(403, missing_csrf.status)
        tampered = await self.client.get("/api/status", headers=self.headers(
            Cookie=service.ROUTE_COOKIE + "=" + route[:-1] + ("0" if route[-1] != "0" else "1")))
        self.assertEqual(401, tampered.status)

        async def fake_device():
            poll = await self.client.post("/relay/v1/device/poll", headers=self.headers(
                Authorization=bearer))
            self.assertEqual(200, poll.status)
            job = await poll.json()
            self.assertEqual("PUT", job["method"])
            self.assertEqual("/api/file?path=config.yml", job["path"])
            self.assertEqual("nab_session=SessionToken", job["headers"]["Cookie"])
            self.assertEqual("validated", job["headers"]["Origin"])
            request_body = await self.client.get("/relay/v1/device/jobs/" + job["id"] + "/request",
                                                 headers=self.headers(Authorization=bearer))
            self.assertEqual(b"new config", await request_body.read())
            response = await self.client.post("/relay/v1/device/jobs/" + job["id"] + "/response",
                                              headers=self.headers(Authorization=bearer,
                                                  **{"X-Panel-Status": "200",
                                                     "X-Panel-Content-Type": "application/json",
                                                     "X-Panel-Set-Cookie": "nab_session=NewSession; HttpOnly; Path=/"}),
                                              data=b'{"ok":true}')
            self.assertEqual(200, response.status)

        backend = asyncio.create_task(fake_device())
        browser = await self.client.put("/api/file?path=config.yml", headers={**route_header,
                                         "Origin": ORIGIN, "X-CSRF-Token": "token"},
                                        data=b"new config")
        self.assertEqual(200, browser.status)
        self.assertEqual(b'{"ok":true}', await browser.read())
        self.assertIn("Secure", browser.headers.get("Set-Cookie", ""))
        self.assertIn("HttpOnly", browser.headers.get("Set-Cookie", ""))
        self.assertEqual("no-store", browser.headers.get("Cache-Control"))
        await backend

        with patch.object(service, "MAX_UPLOAD", 32):
            too_large = await self.client.post("/api/upload", headers={**route_header,
                                              "Origin": ORIGIN, "X-CSRF-Token": "token"},
                                               data=b"x" * 33)
            self.assertEqual(413, too_large.status)

    async def test_device_auth_isolated_and_login_attempt_limits(self):
        pair_id, _, device, route = await self.pair()
        unauthorized = await self.client.post("/relay/v1/device/poll", headers=self.headers(
            Authorization="Bearer " + pair_id + "." + secrets.token_urlsafe(32)))
        self.assertEqual(401, unauthorized.status)
        relay = self.client.server.app[service.RELAY_KEY]
        relay.last_poll[pair_id] = time.monotonic()
        for _ in range(8):
            relay.attempts.allow("login:ip:127.0.0.1", 8, 900)
        login = await self.client.post("/api/login", headers=self.headers(
            Cookie=service.ROUTE_COOKIE + "=" + route, Origin=ORIGIN), data=b"password")
        self.assertEqual(429, login.status)
        self.assertEqual("127.0.0.1", relay.client_ip(type("Request", (), {
            "remote": "127.0.0.1", "headers": {"X-Forwarded-For": "203.0.113.4"}})()))

    async def test_plain_http_is_rejected_outside_test_mode(self):
        await self.client.close()
        settings = service.Settings(ORIGIN, Path(self.temp.name), allow_insecure_test=False)
        self.client = TestClient(TestServer(service.create_app(settings), handler_cancellation=True))
        await self.client.start_server()
        response = await self.client.get("/", headers=self.headers())
        self.assertEqual(403, response.status)

    async def test_pending_pair_cancel_and_expired_route(self):
        pair_id = secrets.token_urlsafe(18)
        code = secrets.token_urlsafe(24)
        pending = secrets.token_urlsafe(32)
        device = secrets.token_urlsafe(32)
        hashed = lambda value: hashlib.sha256(value.encode("ascii")).hexdigest()
        response = await self.client.post("/relay/v1/pair/start", headers=self.headers(), json={
            "pair_id": pair_id, "code_hash": hashed(code),
            "pending_hash": hashed(pending), "device_hash": hashed(device)})
        self.assertEqual(201, response.status)
        wrong = await self.client.post("/relay/v1/pair/cancel", headers=self.headers(), json={
            "pair_id": pair_id, "pending_token": secrets.token_urlsafe(32)})
        self.assertEqual(401, wrong.status)
        canceled = await self.client.post("/relay/v1/pair/cancel", headers=self.headers(), json={
            "pair_id": pair_id, "pending_token": pending})
        self.assertEqual(200, canceled.status)
        gone = await self.client.get("/pair/" + pair_id, headers=self.headers())
        self.assertEqual(404, gone.status)

        expires_id = secrets.token_urlsafe(18)
        expires_token = secrets.token_urlsafe(32)
        created = await self.client.post("/relay/v1/pair/start", headers=self.headers(), json={
            "pair_id": expires_id, "code_hash": hashed(code),
            "pending_hash": hashed(expires_token), "device_hash": hashed(device)})
        self.assertEqual(201, created.status)
        registry = self.client.server.app[service.RELAY_KEY].registry
        registry.db.execute("UPDATE pairings SET expires_at = 0 WHERE id = ?", (expires_id,))
        registry.db.commit()
        expired = await self.client.post("/relay/v1/pair/status", headers=self.headers(), json={
            "pair_id": expires_id, "pending_token": expires_token})
        self.assertEqual("expired", (await expired.json())["status"])
        expired_page = await self.client.get("/pair/" + expires_id, headers=self.headers())
        self.assertEqual(404, expired_page.status)

        active_id, _, _, _ = await self.pair()
        relay = self.client.server.app[service.RELAY_KEY]
        expired = relay.route_cookie(active_id, int(time.time()) - 1)
        self.assertIsNone(relay.route(type("Request", (), {"cookies": {
            service.ROUTE_COOKIE: expired}})()))

    async def test_bounded_upload_download_and_no_payload_on_disk(self):
        pair_id, _, device, route = await self.pair()
        bearer = "Bearer " + pair_id + "." + device
        relay = self.client.server.app[service.RELAY_KEY]
        relay.last_poll[pair_id] = time.monotonic()
        secret = b"do-not-persist-panel-password-or-body"
        session = b"private-panel-session-token"
        payload = secret + b"u" * (128 * 1024 - len(secret))

        async def fake_device():
            poll = await self.client.post("/relay/v1/device/poll", headers=self.headers(Authorization=bearer))
            job = await poll.json()
            self.assertEqual(len(payload), job["body_length"])
            incoming = await self.client.get(f"/relay/v1/device/jobs/{job['id']}/request",
                                             headers=self.headers(Authorization=bearer))
            self.assertEqual(payload, await incoming.read())
            self.assertLessEqual(job["body_length"], 128 * 1024)
            outgoing = await self.client.post(f"/relay/v1/device/jobs/{job['id']}/response",
                headers=self.headers(Authorization=bearer, **{"X-Panel-Status": "200"}),
                data=b"d" * (128 * 1024))
            self.assertEqual(200, outgoing.status)

        with patch.object(service, "MAX_UPLOAD", 128 * 1024), patch.object(service, "MAX_RESPONSE", 128 * 1024):
            backend = asyncio.create_task(fake_device())
            browser = await self.client.post("/api/upload", data=payload,
                headers=self.headers(Cookie=service.ROUTE_COOKIE + "=" + route
                                     + "; nab_session=" + session.decode("ascii"),
                                     Origin=ORIGIN, **{"X-CSRF-Token": "token"}))
            self.assertEqual(200, browser.status)
            self.assertEqual(128 * 1024, len(await browser.read()))
            await backend
            too_large = await self.client.post("/api/upload", data=payload + b"x",
                headers=self.headers(Cookie=service.ROUTE_COOKIE + "=" + route,
                                     Origin=ORIGIN, **{"X-CSRF-Token": "token"}))
            self.assertEqual(413, too_large.status)

        self.assertFalse(relay.jobs)
        self.assertFalse(any(secret in path.read_bytes() or session in path.read_bytes()
                             for path in Path(self.temp.name).rglob("*")
                             if path.is_file()))

    async def test_disconnected_browser_backend_and_relay_restart(self):
        pair_id, _, device, route = await self.pair()
        relay = self.client.server.app[service.RELAY_KEY]
        relay.last_poll[pair_id] = time.monotonic()
        headers = self.headers(Cookie=service.ROUTE_COOKIE + "=" + route)
        browser = asyncio.create_task(self.client.get("/api/status", headers=headers))
        for _ in range(50):
            if relay.jobs:
                break
            await asyncio.sleep(0.02)
        self.assertTrue(relay.jobs)
        browser.cancel()
        with suppress(asyncio.CancelledError):
            await browser
        for _ in range(50):
            if not relay.jobs:
                break
            await asyncio.sleep(0.02)
        self.assertFalse(relay.jobs)

        with patch.object(service, "JOB_WAIT_SECONDS", 0.3):
            unanswered = await self.client.get("/api/status", headers=headers)
            self.assertEqual(504, unanswered.status)
        self.assertFalse(relay.jobs)

        pending = asyncio.create_task(self.client.get("/api/status", headers=headers))
        for _ in range(50):
            if relay.jobs:
                break
            await asyncio.sleep(0.02)
        relay.stop_jobs()
        restarted = await pending
        self.assertEqual(503, restarted.status)
        self.assertFalse(relay.jobs)
        await self.client.close()
        self.client = TestClient(TestServer(service.create_app(self.settings), handler_cancellation=True))
        await self.client.start_server()
        # The signed route persists, but requests wait for the device to reconnect.
        offline = await self.client.get("/api/status", headers=headers)
        self.assertEqual(503, offline.status)
        self.assertTrue(self.client.server.app[service.RELAY_KEY].registry.authenticate(pair_id, device))

    async def test_chunked_upload_and_response_limit_abort(self):
        pair_id, _, device, route = await self.pair()
        bearer = "Bearer " + pair_id + "." + device
        relay = self.client.server.app[service.RELAY_KEY]
        relay.last_poll[pair_id] = time.monotonic()
        payload = b"a" * (64 * 1024) + b"b" * (64 * 1024)

        async def upload_chunks():
            yield payload[:64 * 1024]
            await asyncio.sleep(0.05)
            yield payload[64 * 1024:]

        async def download_chunks():
            for _ in range(3):
                yield b"x" * (64 * 1024)
                await asyncio.sleep(0.05)

        async def fake_device():
            poll = await self.client.post("/relay/v1/device/poll",
                                          headers=self.headers(Authorization=bearer))
            job = await poll.json()
            self.assertEqual(-1, job["body_length"])
            incoming = await self.client.get(f"/relay/v1/device/jobs/{job['id']}/request",
                                             headers=self.headers(Authorization=bearer))
            self.assertEqual(payload, await incoming.read())
            sent = await self.client.post(f"/relay/v1/device/jobs/{job['id']}/response",
                headers=self.headers(Authorization=bearer, **{"X-Panel-Status": "200"}),
                data=download_chunks())
            self.assertEqual(413, sent.status)

        with patch.object(service, "MAX_UPLOAD", 128 * 1024), patch.object(service, "MAX_RESPONSE", 128 * 1024):
            backend = asyncio.create_task(fake_device())
            browser = await self.client.post("/api/upload", data=upload_chunks(),
                headers=self.headers(Cookie=service.ROUTE_COOKIE + "=" + route,
                                     Origin=ORIGIN, **{"X-CSRF-Token": "token"}))
            with self.assertRaises(Exception):
                await browser.read()
            await backend
            async def oversized_chunks():
                yield b"u" * (64 * 1024)
                yield b"v" * (64 * 1024)
                yield b"x"
            too_large = await self.client.post("/api/upload", data=oversized_chunks(),
                headers=self.headers(Cookie=service.ROUTE_COOKIE + "=" + route,
                                     Origin=ORIGIN, **{"X-CSRF-Token": "token"}))
            self.assertEqual(413, too_large.status)
        self.assertFalse(relay.jobs)

    async def test_header_spoofing_and_sanitized_errors(self):
        pair_id, _, _, route = await self.pair()
        relay = self.client.server.app[service.RELAY_KEY]
        wrong_host = await self.client.get("/api/status", headers={"Host": "attacker.test",
            "Cookie": service.ROUTE_COOKIE + "=" + route})
        self.assertEqual(421, wrong_host.status)
        wrong_origin = await self.client.get("/api/status", headers=self.headers(
            Origin="https://attacker.test", Cookie=service.ROUTE_COOKIE + "=" + route))
        self.assertEqual(403, wrong_origin.status)
        self.assertEqual("127.0.0.1", relay.client_ip(type("Request", (), {
            "remote": "127.0.0.1", "headers": {"X-Forwarded-For": "203.0.113.77"}})()))
        trusted = service.Relay(service.Settings(ORIGIN, Path(self.temp.name) / "trusted",
                                                 trusted_loopback_proxy=True, allow_insecure_test=True))
        try:
            self.assertEqual("203.0.113.77", trusted.client_ip(type("Request", (), {
                "remote": "127.0.0.1", "headers": {"X-Forwarded-For": "203.0.113.77"}})()))
            self.assertEqual("203.0.113.9", trusted.client_ip(type("Request", (), {
                "remote": "203.0.113.9", "headers": {"X-Forwarded-For": "203.0.113.77"}})()))
            self.assertEqual("127.0.0.1", trusted.client_ip(type("Request", (), {
                "remote": "127.0.0.1", "headers": {"X-Forwarded-For": "203.0.113.9, 203.0.113.77"}})()))
        finally:
            trusted.close()

        stream = io.StringIO()
        handler = logging.StreamHandler(stream)
        service.LOG.addHandler(handler)
        try:
            with patch.object(relay.registry, "active", side_effect=RuntimeError("secret-session-body")):
                failed = await self.client.get("/api/status", headers=self.headers(
                    Cookie=service.ROUTE_COOKIE + "=" + route + "; nab_session=secret-session-body"))
            self.assertEqual(500, failed.status)
            self.assertNotIn("secret-session-body", stream.getvalue())
            self.assertIn("RuntimeError", stream.getvalue())
        finally:
            service.LOG.removeHandler(handler)

    async def test_backend_reports_failure_without_waiting_for_timeout(self):
        pair_id, _, device, route = await self.pair()
        relay = self.client.server.app[service.RELAY_KEY]
        relay.last_poll[pair_id] = time.monotonic()
        browser = asyncio.create_task(self.client.get("/api/status", headers=self.headers(
            Cookie=service.ROUTE_COOKIE + "=" + route)))
        poll = await self.client.post("/relay/v1/device/poll", headers=self.headers(
            Authorization="Bearer " + pair_id + "." + device))
        job = await poll.json()
        failed = await self.client.post(f"/relay/v1/device/jobs/{job['id']}/fail",
            headers=self.headers(Authorization="Bearer " + pair_id + "." + device))
        self.assertEqual(200, failed.status)
        self.assertEqual(502, (await browser).status)
        self.assertFalse(relay.jobs)


class DurableAbuseTests(unittest.TestCase):
    def test_global_pair_rate_survives_restart_and_cannot_fill_pending_cap(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "registry.sqlite3"
            first = Registry(path)
            try:
                self.assertTrue(all(first.allow_attempt("pair-start:global", 5, 60, now=1000)
                                    for _ in range(5)))
                self.assertFalse(first.allow_attempt("pair-start:global", 5, 60, now=1000))
            finally:
                first.close()
            second = Registry(path)
            try:
                self.assertFalse(second.allow_attempt("pair-start:global", 5, 60, now=1000))
                self.assertTrue(second.allow_attempt("pair-start:global", 5, 60, now=1012))
                accepted = sum(second.allow_attempt("pair-start:global", 5, 60, now=1012 + second_no)
                               for second_no in range(900))
                self.assertLess(accepted + 6, 100)
            finally:
                second.close()

    def test_per_ip_pending_pair_cap(self):
        with tempfile.TemporaryDirectory() as directory:
            registry = Registry(Path(directory) / "registry.sqlite3")
            try:
                ids = [secrets.token_urlsafe(18) for _ in range(4)]
                code = secrets.token_urlsafe(24)
                hashed = hashlib.sha256(code.encode("ascii")).hexdigest()
                for pair_id in ids[:3]:
                    registry.start_pair(pair_id, hashed, hashed, hashed, "203.0.113.7", now=1000)
                with self.assertRaises(ValueError):
                    registry.start_pair(ids[3], hashed, hashed, hashed, "203.0.113.7", now=1000)
                self.assertTrue(registry.claim(ids[0], code, now=1000))
                registry.start_pair(ids[3], hashed, hashed, hashed, "203.0.113.7", now=1000)
            finally:
                registry.close()


if __name__ == "__main__":
    unittest.main()
