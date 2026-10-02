# Changelog

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
