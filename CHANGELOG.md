# Changelog

## 1.0.0-beta.4 — combined panel and setup candidate

- Replaced the console's partial logger capture with a live view of Minecraft's `logs/latest.log`, including startup output. Added incremental cursors, log rotation handling, a follow-output control, and bounded browser rendering.
- Fixed console refresh requests that arrived during an in-flight log fetch, and kept backup progress in sync while the Backups page stays open.
- Added overview cards for process CPU, TPS, milliseconds per tick, Java heap use, and online players. Realtime shows a scrolling 60-second window; selectable history extends to one week with persistent minute summaries.
- Added live backup progress for world saving, source scanning, and archive copying. The archive runs in a background job so the panel remains usable while it is created.
- Added file and folder multi-selection with ZIP creation, TAR download, and reviewed bulk delete. Archive operations remain bounded and use race-safe directory handles.
- Added a private 27-slot `/nab` guide with panel access, setup code, browser setup, and connection checks. An operator with `notabackdoor.admin` receives a clickable reminder on joining an unconfigured server; the menu does not open automatically. `/nab status` remains available after setup.
- On direct online-mode servers, permitted operators can share one unexpired 15-minute first-run code. On offline-mode servers and Velocity backends, code issuance and access changes remain console-only. Password resets remain console-only everywhere.
- Added explicit public HTTP access with a warning and exact advertised `http://host:port` address, alongside the default localhost mode. Changing modes restarts only the panel listener, revokes sessions and pending setup codes, and restores the prior listener and settings if the new one fails.
- Added versioned access settings, source-specific first-run attempt limits, exact public Host/Origin checks, and a short-lived browser connection check. The browser warns that public HTTP exposes passwords and sessions in transit; its check proves only that the browser opening its link reached the panel.
- The current UI-fix JAR passed two exact-JAR startup rounds in both direct online and offline modes on Paper 1.18.2, 1.21.11, 26.2, and experimental 26.3. Its Linux Java 17 suite passed 47/47 tests. LianJordaan reported that the final candidate's direct online-mode setup walkthrough, Console Reload, and backup worked. This manual confirmation is separate from the automated checks and the previous combined JAR's test history.

## 1.0.0-beta.3 — public beta release

- The supported setup uses the panel's localhost HTTP listener, with SSH forwarding for remote access. The packaged outbound relay connector remains experimental and is not provided as a public service for this release.
- Corrected the Backups screen and confirmation: in-panel archives are created while Paper runs and cover only the server process folder; worlds stored elsewhere are excluded.
- Clarified that a strictly consistent full-server backup requires stopping Paper and using the host's snapshot or backup tool. This release changes the guidance, not the archive implementation.

## 1.0.0-beta.2 — candidate, not published

- Added an opt-in outbound HTTPS relay connector for hosts with plugin upload and console access; the panel remains localhost-only and SSH forwarding remains available.
- Added console pairing, persistent reconnect and revocation, exact Host/Origin checks, Secure browser cookies, bounded streaming, and durable rate limits to the separate relay service.
- Added four concurrent transfer slots so a backup download need not block ordinary panel requests, plus review-only least-privilege systemd and dedicated-hostname Nginx templates.
- Kept the relay unconfigured by default. A public relay URL, DNS, certificate, deployment review, and owner authorization are still required before claiming remote access on plugin-only hosts.

## 1.0.0-beta.1 — pending release

- Rebuilt the panel interface around Files, Console, Players, and Backups views.
- Added create, move, delete, upload, zip, and bounded unzip operations.
- Added console output and command dispatch; operator, ban, and whitelist controls; and downloadable server backups.
- Replaced password links and public HTTP binding with console-issued setup codes, localhost access, salted password hashing, short-lived sessions, request tokens, and login throttling.
- Added backup migration for legacy settings and removed the old file handlers and pages.
- Bound file and backup operations to race-safe directory handles, including ZIP reads and extraction; startup refuses filesystems without the required Java provider support.
- Kept unsaved edits through delayed navigation and expired-session downloads; bounded ZIP creation and corrected the file root for custom world-container layouts.
- Enforced the backup size cap against bytes actually copied, including files that grow during a live backup.
- Verified the exact secure-file JAR on pinned Paper 1.18.2, 1.21.11, 26.2, and experimental 26.3 builds, plus an online-mode 26.2 server; see `docs/TESTING.md` for the limits of those checks.
- Passed a separate 30/30 real-client panel probe on offline-mode Paper 1.21.11: operator, console, whitelist, ban, pardon, and reconnect outcomes matched the panel actions.
