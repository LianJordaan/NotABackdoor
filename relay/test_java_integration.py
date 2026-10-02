"""Real Java relay client across locally generated HTTPS, with no public host."""

from __future__ import annotations

import asyncio
from contextlib import suppress
import json
import os
from pathlib import Path
import re
import shutil
import socket
import ssl
import subprocess
import tempfile
import unittest
from xml.etree import ElementTree

from aiohttp import ClientSession, ClientTimeout, CookieJar, TCPConnector, web

import service


ROOT = Path(__file__).resolve().parent.parent
JAVA_HOME = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17"))
JAVA = shutil.which("java") if not (JAVA_HOME / "bin/java.exe").exists() else str(JAVA_HOME / "bin/java.exe")
KEYTOOL = shutil.which("keytool") if not (JAVA_HOME / "bin/keytool.exe").exists() else str(JAVA_HOME / "bin/keytool.exe")
OPENSSL = shutil.which("openssl") or r"C:\Program Files\Git\usr\bin\openssl.exe"
PROJECT_VERSION = ElementTree.parse(ROOT / "pom.xml").getroot().findtext(
    "{http://maven.apache.org/POM/4.0.0}version")
JAR = ROOT / "target" / f"NotABackdoor-{PROJECT_VERSION}.jar"


@unittest.skipUnless(JAVA and KEYTOOL and Path(OPENSSL).exists() and JAR.exists(),
                     "Java, OpenSSL, and a packaged relay prototype are required")
class JavaIntegration(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        base = Path(self.temp.name)
        self.cert = base / "localhost.crt"
        key = base / "localhost.key"
        self.trust = base / "trust.p12"
        subprocess.run([OPENSSL, "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                        "-keyout", str(key), "-out", str(self.cert), "-days", "1",
                        "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost"],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run([KEYTOOL, "-importcert", "-noprompt", "-alias", "local-relay",
                        "-file", str(self.cert), "-keystore", str(self.trust),
                        "-storetype", "PKCS12", "-storepass", "test-password"],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        with socket.socket() as reserve:
            reserve.bind(("127.0.0.1", 0))
            port = reserve.getsockname()[1]
        self.origin = f"https://localhost:{port}"
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.load_cert_chain(self.cert, key)
        self.runner = web.AppRunner(service.create_app(service.Settings(
            self.origin, base / "registry")), access_log=None,
                                    handler_cancellation=True)
        await self.runner.setup()
        self.site = web.TCPSite(self.runner, "127.0.0.1", port, ssl_context=tls)
        await self.site.start()
        browser_tls = ssl.create_default_context(cafile=str(self.cert))
        self.browser = ClientSession(connector=TCPConnector(ssl=browser_tls),
                                     cookie_jar=CookieJar(unsafe=True), timeout=ClientTimeout(total=8))
        self.processes = []

    async def asyncTearDown(self):
        for process in self.processes:
            if process.returncode is None:
                process.kill()
                await process.wait()
        await self.browser.close()
        await self.runner.cleanup()

    async def java(self, *, trust: bool, resume: bool = False):
        command = [JAVA, "--add-modules", "jdk.httpserver"]
        if trust:
            command.extend(["-Djavax.net.ssl.trustStore=" + str(self.trust),
                            "-Djavax.net.ssl.trustStorePassword=test-password",
                            "-Djavax.net.ssl.trustStoreType=PKCS12"])
        command.extend(["-cp", os.pathsep.join((str(ROOT / "target/test-classes"), str(JAR))),
                        "com.lian.notabackdoor.panel.relay.RelayHarness", self.origin,
                        str(Path(self.temp.name) / "plugin")])
        if resume:
            command.append("resume")
        process = await asyncio.create_subprocess_exec(*command,
            stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE)
        self.processes.append(process)
        return process

    async def panel_request(self, path, *, method="GET", body=None):
        deadline = asyncio.get_running_loop().time() + 20
        while asyncio.get_running_loop().time() < deadline:
            try:
                async with self.browser.request(method, self.origin + path, data=body,
                        headers={"Origin": self.origin} if method != "GET" else {}) as response:
                    if response.status == 503:
                        await asyncio.sleep(0.25)
                        continue
                    return response.status, await response.text(), response.headers.copy()
            except asyncio.TimeoutError:
                await asyncio.sleep(0.25)
        self.fail("Java client did not reconnect to the relay")

    async def test_tls_pair_stream_reconnect_and_revocation(self):
        # Default Java trust rejects a private certificate; the client must not disable TLS checks.
        rejected = await self.java(trust=False)
        await asyncio.wait_for(rejected.wait(), timeout=15)
        self.assertNotEqual(0, rejected.returncode)
        self.assertFalse((Path(self.temp.name) / "plugin/relay.properties").exists())

        pending = await self.java(trust=True)
        pending_line = (await asyncio.wait_for(pending.stdout.readline(), timeout=15)).decode().strip()
        self.assertTrue(pending_line.startswith("PAIR "), "Java did not produce a pairing link")
        pending_url = pending_line.split(" ", 2)[1]
        pending.stdin.write(b"revoke\n")
        await pending.stdin.drain()
        self.assertTrue((await asyncio.wait_for(pending.stdout.readline(), timeout=10)).decode().startswith("REVOKE "))
        deadline = asyncio.get_running_loop().time() + 15
        while (Path(self.temp.name) / "plugin/relay.properties").exists() and asyncio.get_running_loop().time() < deadline:
            await asyncio.sleep(0.2)
        self.assertFalse((Path(self.temp.name) / "plugin/relay.properties").exists())
        async with self.browser.get(pending_url) as response:
            self.assertEqual(404, response.status)
        pending.stdin.write(b"stop\n")
        await pending.stdin.drain()
        await asyncio.wait_for(pending.wait(), timeout=10)

        first = await self.java(trust=True)
        line = (await asyncio.wait_for(first.stdout.readline(), timeout=15)).decode().strip()
        self.assertTrue(line.startswith("PAIR "), "Java did not produce a pairing link")
        _, url, code = line.split(" ", 2)
        self.assertTrue(url.startswith(self.origin + "/pair/"))
        async with self.browser.get(url) as response:
            self.assertEqual(200, response.status)
            csrf = re.search(r"name='csrf' value='([^']+)'", await response.text()).group(1)
        async with self.browser.post(url, data={"csrf": csrf, "code": code},
                                     headers={"Origin": self.origin}, allow_redirects=False) as response:
            self.assertEqual(303, response.status)
            self.assertIn("Secure", " ".join(response.headers.getall("Set-Cookie", [])))

        status, body, _ = await self.panel_request("/api/status")
        self.assertEqual(200, status)
        self.assertTrue(json.loads(body)["host_ok"])
        status, body, headers = await self.panel_request("/api/login", method="POST", body=b"secret")
        self.assertEqual(200, status)
        self.assertEqual(6, json.loads(body)["length"])
        self.assertTrue(json.loads(body)["origin_ok"])
        self.assertIn("Secure", headers.get("Set-Cookie", ""))

        async with self.browser.get(self.origin + "/api/slow") as slow:
            self.assertEqual(200, slow.status)
            self.assertEqual(64 * 1024, len(await slow.content.readexactly(64 * 1024)))
            started = asyncio.get_running_loop().time()
            concurrent_status, concurrent_body, _ = await self.panel_request("/api/status")
            self.assertEqual(200, concurrent_status)
            self.assertTrue(json.loads(concurrent_body)["host_ok"])
            self.assertLess(asyncio.get_running_loop().time() - started, 2.5)
            self.assertEqual(64 * 1024, len(await slow.read()))

        first.stdin.write(b"stop\n")
        await first.stdin.drain()
        await asyncio.wait_for(first.wait(), timeout=10)
        second = await self.java(trust=True, resume=True)
        ready = (await asyncio.wait_for(second.stdout.readline(), timeout=15)).decode().strip()
        self.assertTrue(ready.startswith("READY Relay paired"), ready)
        status, body, _ = await self.panel_request("/api/status")
        self.assertEqual(200, status)
        self.assertTrue(json.loads(body)["host_ok"])

        second.stdin.write(b"revoke\n")
        await second.stdin.drain()
        self.assertTrue((await asyncio.wait_for(second.stdout.readline(), timeout=10)).decode().startswith("REVOKE "))
        deadline = asyncio.get_running_loop().time() + 45
        while (Path(self.temp.name) / "plugin/relay.properties").exists() and asyncio.get_running_loop().time() < deadline:
            await asyncio.sleep(0.2)
        self.assertFalse((Path(self.temp.name) / "plugin/relay.properties").exists())
        async with self.browser.get(self.origin + "/api/status") as response:
            self.assertEqual(401, response.status)
        second.stdin.write(b"stop\n")
        await second.stdin.drain()
        await asyncio.wait_for(second.wait(), timeout=10)


if __name__ == "__main__":
    unittest.main()
