# NotABackdoor

A small web panel that runs inside a Paper server. It is for server owners who need to edit a configuration, look at a log, or moderate a player without navigating a full hosting control panel.

The `1.0.0-beta.3` candidate replaces the old HTTP panel and adds an [opt-in outbound HTTPS relay](relay/README.md). It is **not released yet**. Keep using a separate server or a host backup when testing it. SSH forwarding and the relay bridge passed local beta.2 checks; the relay is not publicly deployed yet.

![Desktop overview of the NotABackdoor panel](docs/screenshots/overview-desktop.png)

[See the file editor and mobile overview](docs/TESTING.md#browser-checks-and-screenshots).

## What you can do

- Browse server files; create folders and files; upload, download, edit, rename, move, or delete them.
- Zip a file or folder and extract an archive into a new folder. Extraction rejects paths that escape the chosen destination and limits archive expansion.
- Read recent console output and run a server command.
- See online players and manage operator, ban, and whitelist state by exact player name.
- Create, download, or delete a live archive of the server process folder. The plugin saves loaded worlds first and omits Minecraft's live `session.lock` files. Files may change during copying, and world folders outside the process folder are excluded. For a strictly consistent full-server backup, stop Paper and use your host's snapshot or backup tool; the in-panel archive requires the plugin to be running.

## Install and sign in

1. Use a Paper and Java combination listed in [the exact live test record](docs/TESTING.md). The beta.2 JAR passed on Paper 1.18.2, 1.21.11, 26.2, and experimental 26.3; this beta.3 JAR requires its own exact checks. Copy the JAR into `plugins/` and start the server.
2. Run `nab setup` **from the server console**. This prints a one-time code that expires in 15 minutes. It is never placed in a URL.
3. On the server itself, open `http://127.0.0.1:8127`. From another computer, run `ssh -L 8127:127.0.0.1:8127 user@your-server` and open `http://localhost:8127` locally.
4. Enter the setup code and choose a password of at least 12 characters. Sign in to the panel.

The panel listens only on `127.0.0.1`. It intentionally refuses a public bind address. SSH access needs no additional service. The optional relay uses a separately operated HTTPS service and a dedicated hostname; it is disabled until paired. Change `panel.port` in `plugins/NotABackdoor/config.yml` if 8127 is in use.

The file manager and backups require a Java filesystem provider with `SecureDirectoryStream`, which keeps operations bound to open directory handles during symlink swaps. If the provider lacks it, the plugin refuses to start and logs `This filesystem has no race-safe directory handles; panel file access is disabled`. The tested Linux filesystem supports it; the default Windows JDK provider does not. The earlier beta.2 candidate passed the [pinned live checks](docs/TESTING.md#outbound-relay-100-beta2-candidate).

On Paper 1.21.11, a scripted offline-mode Minecraft client also verified that panel operator, console, whitelist, and ban actions affect a connected player and subsequent joins. The [beta.2 test record](docs/TESTING.md#outbound-relay-100-beta2-candidate) distinguishes this 30/30 end-to-end result from the four-version panel API checks. A Microsoft-authenticated player login has not been tested.

If your host does not provide SSH access, the [outbound relay candidate](relay/README.md) offers a one-time console pairing link after a relay operator deploys a dedicated HTTPS origin. Set `relay.origin` in the plugin config, run `nab relay pair`, then open the link and enter the separate console code. It is not publicly deployed, so this is not yet an available setup option. Opening a public HTTP port is not a supported shortcut.

If you forget your password, run `nab setup` again in the server console and set a new one. This revokes existing sessions. The password is stored as a salted PBKDF2-HMAC-SHA256 hash in `plugins/NotABackdoor/auth.properties`. Keep that file and your SSH account private.

### Upgrade from the old panel

The first start copies an old `config.yml` to a timestamped `.legacy-*.bak` file and writes a safe v2 configuration. Review that backup privately, then remove it when you no longer need it. Old public HTTP, password-in-URL, and `clearFiles` settings are ignored. The plugin does not delete server files on startup.

## Design and safeguards

The browser UI uses the same-origin API. Every state-changing request requires a session-specific request token, and requests from a foreign browser origin are denied. Sessions expire after two hours and are invalidated when the password changes. Login attempts are throttled. Panel responses use no-store, a restrictive content security policy, and frame protection.

File paths stay under the server process directory, including `plugins/` and `server.properties` even if worlds live elsewhere. The in-panel archive has the same root and does not include world folders outside it. Absolute paths and traversal are rejected; symlinks are never followed. File reads, writes, ZIP operations and backups use open directory handles so a concurrent path replacement cannot redirect them outside the root. The text editor checks the file hash before saving so a stale tab cannot silently replace newer work. Text edits are limited to 2 MiB, uploads to 128 MiB, ZIP source data and extracted archives to 1 GiB each. An archive excludes its own backup directory and is capped at 20 GiB.

**The panel has full server authority after sign-in.** Treat its password, console access, and SSH tunnel as administrator credentials. A localhost-only design limits accidental internet exposure; it does not make an untrusted person safe to grant panel access.

## Development

Build with `mvn package`; run checks with `mvn test`. The shaded candidate JAR is `target/NotABackdoor-1.0.0-beta.3.jar`. Java sources target 17; the API baseline is Paper 1.18.2. Browser assets live in `src/main/resources/panel/`, separate from the HTTP, file, authentication, backup, and relay services.

The old implementation and pages were removed because several file endpoints could escape the intended directory and one handler ignored an authentication return value. The replacement has no route to those handlers.
