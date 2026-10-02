# Experimental HTTPS relay

This directory is a **tested local release candidate**, not a deployed service or a published plugin feature. The earlier secure beta and its test evidence remain separate. The panel still binds only to `127.0.0.1`; SSH forwarding remains available even if the relay is down.

The plugin makes outbound, certificate-verified HTTPS requests to a relay origin. Set `relay.origin` in `config.yml` after choosing a deployed operator, then `nab relay pair` needs no argument; `nab relay pair <https-origin>` remains available for self-hosted relays. A browser opens the one-time URL and enters the separate code printed in the same server console. The link and code expire after 15 minutes and are each needed to claim the route. The browser then receives a signed, 30-day, Secure, HttpOnly route cookie. Its requests to `/`, assets, and `/api/*` use the same origin; the relay streams them to the plugin over authenticated long polling and the plugin forwards them to its loopback panel. `nab relay status` shows pairing state; `nab relay revoke` immediately stops local serving and retries server-side revocation across disconnects. Device state survives Minecraft restarts in the private plugin data directory.

The relay stores hashes of device, pending, and pairing secrets in SQLite, plus a private HMAC signing key. It does not receive Modrinth, SSH, or dashboard-management credentials. It **does** terminate browser HTTPS and handle panel passwords, session cookies, console commands, files, and backups in transit. The relay operator can observe those requests. Use an SSH tunnel for the higher-privacy arrangement. The relay process does not intentionally log or persist browser request bodies, panel passwords, session cookies, or pairing URLs; upload and download streams use four 64 KiB chunks per direction with backpressure. The browser receives panel sessions as Secure, HttpOnly, SameSite=Strict cookies. A stolen route cookie expires after 30 days or immediately on server-side revocation; run `nab relay revoke` and pair again after expiry.

One browser profile has one route and one panel session cookie for this relay origin. Pairing with a second server replaces the browser's route cookie; it does not give the browser a server switcher. The previous panel session is sent to the second server and rejected, so sign in again. Returning to the first server currently requires revoking and re-pairing it, or using a separate browser profile from the start. A multi-server selector and route-specific sessions are future UX work.

## Local verification

Use Python 3.11+ and Java 17+:

```text
python -m pip install -r relay/requirements.txt
mvn package
python -m unittest discover -s relay -p 'test_*.py' -v
```

The integration test generates a short-lived localhost certificate and Java trust store. It verifies that Java rejects the untrusted certificate, then tests pairing, real Java-to-Python HTTPS streaming, Secure session cookies, restart reconnect, and revocation. The unit tests cover Host/Origin/CSRF, device authentication, route signing, expiry, rate limits, and request caps. They use no public server. The integration test needs `openssl` and `keytool`; on Windows it finds the local JDK 17 and Git OpenSSL paths used in this workspace.

For a manually managed relay process, install the pinned aiohttp version and run:

```text
python relay/service.py --public-origin https://relay.example.org --data /private/notabackdoor-relay --cert /private/fullchain.pem --key /private/privkey.pem --listen 127.0.0.1 --port 9443 --trusted-loopback-proxy
```

This command serves **TLS even on loopback**. A deployment proxy would need to forward HTTPS to that listener, preserve the exact external `Host`, disable access and body logging, disable request/response buffering, and use verified upstream TLS. It must set exactly one client IP in `X-Forwarded-For`; the service trusts that header only from a loopback peer when `--trusted-loopback-proxy` is set. Without the flag, it ignores supplied client-IP headers. Public DNS, a dedicated certificate, network policy, proxy configuration, service supervision, and restore/backups for the registry are still needed. No such deployment is part of this prototype.

The service enforces an exact HTTPS Host and Origin, a CSRF header on panel mutations, separate per-IP and per-route login/setup limits, five pairing guesses, a 64-job global and four-job per-device cap, 2 MiB JSON/editor bodies, 128 MiB uploads, and 21 GiB responses. The response ceiling allows the panel's 20 GiB source-bounded backups plus ZIP overhead. Uploads and downloads stream through small bounded queues; a 20 GiB backup is not loaded into relay or plugin memory. The plugin can serve four jobs at once; a long backup can use one slot while ordinary panel requests use the other three. A fifth concurrent browser request queues and can time out after 120 seconds; a transfer has a three-hour Java deadline.

Pair enrollment, login/setup, and pairing-claim limits use SQLite token buckets that survive service restart. Pair start is limited globally to a burst of five and a refill of five per minute, plus 12 per hour per client IP and three pending links per IP. Under a stable clock, fewer than 100 pairings can start within the 15-minute lifetime, so one client or a botnet cannot fill the 100-pending-pair cap. Durable buckets are pruned after 24 hours and capped at 50,000 keys. This still permits a distributed attacker to consume the small global enrollment budget and temporarily deny legitimate pairing; the deployment needs monitoring and an owner-approved public endpoint.

**Remaining public-deployment blockers:** A dedicated hostname and matching browser/upstream TLS certificates are not yet chosen. The existing `za.bytebuilders.co.za` host serves phpMyAdmin and cannot be reused, even on another port because browser cookies ignore ports. The exact Nginx/TLS path, public pairing/login/revocation, certificate rotation, and the browser's shared-origin server-switch experience need live verification. The exact beta.3 JAR passed four pinned Paper versions plus a 26.2 online-mode startup, local HTTPS relay bridge checks on 1.18.2 and 26.2, and a 30/30 offline real-client probe; see [the test record](../docs/TESTING.md#backup-copy-100-beta3-candidate). Publication stays gated until deployment and owner review.
