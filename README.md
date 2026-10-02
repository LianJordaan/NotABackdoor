# NotABackdoor

A small web panel that runs inside a Paper server. It is for server owners who need to edit a configuration, look at a log, or moderate a player without navigating a full hosting control panel.

The [published 1.0.0-beta.4 release](https://modrinth.com/plugin/notabackdoor/version/VxLRdFSS) adds an in-game setup guide, opt-in public HTTP, the full Minecraft console, performance history, backup progress, and bulk file actions. Its [exact JAR](docs/TESTING.md#published-combined-panel-and-setup-100-beta4) passed checks on four pinned Paper releases and Java 17 tests. On a direct online-mode server, LianJordaan confirmed that setup, Console Reload, and a backup worked; the authenticated join is corroborated by a redacted server log, while the menu and browser outcomes are the player's report. The [experimental outbound relay connector](relay/README.md) is not part of the supported setup.

![Desktop overview captured from the earlier beta.3 panel](docs/screenshots/overview-desktop.png)

[See the file editor, Backups screen, and mobile views](docs/TESTING.md#browser-checks-and-screenshots).

## What you can do

- Browse server files; create folders and files; upload, download, edit, rename, move, or delete them.
- Select several files or folders to create one ZIP, download one TAR, or delete them after review. Zip a single item and extract an archive into a new folder. Extraction rejects paths that escape the chosen destination and limits archive expansion.
- Read the server's actual `logs/latest.log`, including startup and plugin output, and run a server command. The view follows new lines while you are at the bottom; you can turn following off to inspect older output.
- See current process CPU usage, TPS, average milliseconds per tick, Java heap use, and online player count. Review history from a scrolling 60-second realtime view through 1 minute, 5 minutes, 10 minutes, 30 minutes, 1 hour, 12 hours, 1 day, and 1 week. Minute summaries are saved for a week across restarts.
- See online players and manage operator, ban, and whitelist state by exact player name.
- Create, download, or delete a live archive of the server process folder, with progress through world saving, file scanning, and archiving. The plugin saves loaded worlds first and omits Minecraft's live `session.lock` files. Files may change during copying, and world folders outside the process folder are excluded. For a strictly consistent full-server backup, stop Paper and use your host's snapshot or backup tool; the in-panel archive requires the plugin to be running.

## Set up beta.4

1. Copy the JAR into `plugins/` and start Paper. Use a compatible Paper and Java combination from the [exact-JAR test record](docs/TESTING.md); beta.3 results do not establish beta.4 compatibility.
2. On a direct `online-mode=true` server, an OP with `notabackdoor.admin` receives a private reminder when joining an unconfigured server. Run `/nab` to open the 27-slot guide. It shows panel access, setup code, browser setup, and connection check. It does not open automatically. `/nab status` gives the same diagnostic summary in chat.
3. Choose panel access. The default is local HTTP at `http://127.0.0.1:8127`. From another computer, run `ssh -L 8127:127.0.0.1:8127 user@your-server`, then open `http://localhost:8127` locally. Public HTTP requires a separate in-game warning and confirmation, followed by an exact `http://host:port` address. The server console can instead run `nab access local` or `nab access public http://host:port confirm`; the final `confirm` is required to avoid accidental exposure. The public URL's port becomes the panel listener port.
4. In the guide, select **Setup code**. The copyable first-run code lasts 15 minutes. Multiple eligible operators see the same unexpired code, so asking again does not invalidate another operator's setup. On `online-mode=false` servers, including Velocity backends, only the server console may change access or issue the code: run `nab setup`. The in-game guide still shows status and diagnostics.
5. Open the panel address and enter the code with a new password of 12–128 characters. Do not enter the password in Minecraft chat. Sign in, then use **Connection check** in `/nab`: it checks the local listener and offers a short-lived link to verify that your browser reached the panel. A successful browser sign-in completes that check.

**Public HTTP is unencrypted.** People on the network path may read panel passwords, sessions, files, and commands. It is disabled by default; use localhost or an SSH tunnel for private access. The configured public address must have an exact host and explicit port; applying it updates and saves the listener port. Public mode accepts only that Host and Origin, while preserving local health access. The browser check proves only the browser that opened its link reached the panel; it does not prove reachability from every network. Change `panel.port` in `plugins/NotABackdoor/config.yml` if 8127 is in use.

The file manager and backups require a Java filesystem provider with `SecureDirectoryStream`, which keeps operations bound to open directory handles during symlink swaps. If the provider lacks it, the plugin refuses to start and logs `This filesystem has no race-safe directory handles; panel file access is disabled`. The tested Linux filesystem supports it; the default Windows JDK provider does not. The earlier beta.4 setup candidate has its own [pinned live test record](docs/TESTING.md#guided-setup-100-beta4-candidate), separate from beta.3 and from the combined candidate.

On Paper 1.21.11, a scripted offline-mode Minecraft client also verified that panel operator, console, whitelist, and ban actions affect a connected player and subsequent joins. The [beta.3 test record](docs/TESTING.md#backup-copy-100-beta3-candidate) distinguishes this 30/30 end-to-end result from the four-version panel API checks. LianJordaan later confirmed an authenticated online-mode setup walkthrough against the separate setup-only beta.4 JAR.

If your host does not provide SSH access, the [outbound relay candidate](relay/README.md) remains experimental with no deployed service. The explicit public HTTP mode is available for owners who accept its unencrypted transport. If you separately configure the experimental relay, it stays connected to the panel port selected when Paper started; restart Paper after changing that port before using the relay.

After a password exists, an in-game operator cannot issue another setup code. If you forget the password, run `nab setup` in the server console and set a new one. This revokes existing sessions. Changing the panel access mode also revokes sessions and any pending setup code; request a fresh code if setup was still in progress. The password is stored as a salted PBKDF2-HMAC-SHA256 hash in `plugins/NotABackdoor/auth.properties`. Keep that file and your SSH account private.

### Upgrade from the old panel

The first start with a pre-v2 configuration copies the old `config.yml` to a timestamped `.legacy-*.bak` file and writes a safe configuration. Old public HTTP, password-in-URL, and `clearFiles` settings are ignored. On the first beta.4 startup, an existing v2 configuration is backed up as `config.yml.v2.bak` and migrated to v3 with local access; the existing panel password remains valid. V3 adds access-mode and advertised-origin settings. Review backups privately and remove them when no longer needed. The plugin does not delete server files on startup.

## Design and safeguards

The browser UI uses the same-origin API. Every authenticated state-changing request requires a session-specific request token, and requests from a foreign browser origin are denied. Sessions expire after two hours and are invalidated when the password or access mode changes. Login and first-run setup attempts are throttled by source address. The listener alone restarts when access changes; if the new address cannot bind, the previous listener and settings are restored. Panel responses use no-store, a restrictive content security policy, and frame protection.

File paths stay under the server process directory, including `plugins/` and `server.properties` even if worlds live elsewhere. The in-panel archive has the same root and does not include world folders outside it. Absolute paths and traversal are rejected; symlinks are never followed. File reads, writes, ZIP/TAR operations and backups use open directory handles so a concurrent path replacement cannot redirect them outside the root. The text editor checks the file hash before saving so a stale tab cannot silently replace newer work. Text edits are limited to 2 MiB, uploads to 128 MiB, ZIP/TAR source data and extracted archives to 1 GiB each. An archive excludes its own backup directory and is capped at 20 GiB.

The console reads `logs/latest.log` and keeps at most 1,000 rendered lines in the browser. Metrics begin collecting after startup: raw one-second samples cover the most recent hour, while one-minute summaries cover up to a week and survive restarts. The CPU value is this Java process's CPU time as a percentage of its available cores, not whole-host load. TPS is Paper's one-minute average; a sudden drop may take a little time to appear. If the server host removes the metrics history file, older charts start fresh.

**The panel has full server authority after sign-in.** Treat its password, console access, and SSH tunnel as administrator credentials. Public HTTP removes the transport protection that localhost with SSH forwarding provides; the login and request protections cannot encrypt network traffic.

## Development

Build with `mvn package`; run checks with `mvn test`. The shaded JAR is `target/NotABackdoor-1.0.0-beta.4.jar`. Java sources target 17; the API baseline is Paper 1.18.2. Browser assets live in `src/main/resources/panel/`, separate from the HTTP, file, authentication, backup, and relay services.

The old implementation and pages were removed because several file endpoints could escape the intended directory and one handler ignored an authentication return value. The replacement has no route to those handlers.
