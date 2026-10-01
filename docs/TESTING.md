# NotABackdoor exact-JAR test record

## 1.0.0-beta.1 candidate

The frozen beta is `testing/notabackdoor/candidates/NotABackdoor-beta.1-957e24e59991.jar` in the parent workspace, built from production-code commit `d4ef773fb95a1e7b6032793993acc7efe6a6f2e9`. Its SHA-512 is:

```text
957e24e59991fc6b2bdf69cf4d31ebe3834e9c64d0df37ab952869101735b9637c1ccf89c5b0e104ca251e54ed331b4e09d81ae08bc6123adca786a1349ab72e
```

`mvn clean package` passed all 12 unit tests; `node --check` passed for the browser script. The exact installed beta JAR passed the isolated HTTP/API probe after a restart on each pinned Paper build below. Each test world was retained and its server was stopped afterward. The 26.3 Paper build is experimental.

| Minecraft | Paper build | Java | Offline-mode result |
| --- | ---: | ---: | --- |
| 1.18.2 | 388 | 17 | 41/41 checks passed |
| 1.21.11 | 132 | 21 | 41/41 checks passed |
| 26.2 | 129 | 25 | 41/41 checks passed |
| 26.3 (experimental) | 140 | 25 | 41/41 checks passed |

Paper 26.2 build 129 also passed 41/41 checks with `online-mode=true`. The checks cover setup, password persistence, session and request-token enforcement, Host/Origin/traversal denial, file actions, ZIP/unzip, logs, player-list API, command dispatch, backup create/download/delete, and logout. These are automated panel checks; no Minecraft player joined. The online-mode result proves the panel runs while server authentication is enabled, but does not prove real account login.

Machine-readable receipts and server logs are under `testing/notabackdoor/live-beta1/paper-<version>-b<build>-<mode>/` in the parent workspace. On Paper 1.18.2, headless Edge also passed desktop/mobile navigation, text-editor interaction, and eight unedited screenshots with no page errors. Four separate browser checks for unsaved edits, delayed navigation, and expired-session download recovery passed. Their receipts and captures are in `testing/notabackdoor/live-beta1/screenshots/`; selected captures are embedded in this repository's README and testing page.

The beta has not been tested on other Minecraft versions, Purpur, Spigot, Bukkit, or a live player login. The panel binds to localhost; the documented remote route requires SSH access. A host offering only plugin upload and console access cannot use this remote route.

## Historical 1.0.0-dev candidate

The following earlier receipts apply only to the development JAR named below. Its hash differs from the beta and its passes must not be counted as beta passes.

The frozen candidate is `NotABackdoor-90d246db0a3d.jar`, SHA-512:

```text
90d246db0a3d6345f64cd90d4037cf584653bd898ec47a153396d6326a6d11c7a84858d5a277f819a18cfb1e0192b1a3c264fe410269cf80d3566acaf2f1d1e0
```

This exact JAR passed the same isolated-server HTTP/API probe on these pinned Paper builds. Each server was stopped after its probe and its world was retained. `26.3` is an experimental Paper build.

| Minecraft | Paper build | Java | Result |
| --- | ---: | ---: | --- |
| 1.18.2 | 388 | 17 | 41/41 checks passed |
| 1.21.11 | 132 | 21 | 41/41 checks passed |
| 26.2 | 129 | 25 | 41/41 checks passed |
| 26.3 (experimental) | 140 | 25 | 41/41 checks passed |

The probes checked startup, one-time console setup, password storage and restart persistence, session and request-token enforcement, Host/Origin/traversal rejection, file creation/edit/conflict/upload/download/move/delete, ZIP and unzip, logs, player listing, console dispatch, backups, and logout. They also confirmed the SHA-512 of the installed JAR. The four machine-readable offline-mode receipts are kept under `testing/notabackdoor/live/paper-<version>-b<build>/http-probe-result.json` in the parent workspace.

Paper 26.2 build 129 also passed the same 41/41 checks with `online-mode=true` using that exact JAR. Its distinct receipt is `testing/notabackdoor/live/paper-26.2-b129/http-probe-online-result.json`; the instance was returned to offline mode and stopped afterward. This establishes that the panel functions while a server has online authentication enabled, but does not prove real account login.

The pinned Paper JAR SHA-256 values were `0578f18f4d632b494b468ec56b3b414b5b56fea087ee7d39cf6dcdf4c9d01f05` (1.18.2), `5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba` (1.21.11), `b1d8f6bfa1b6101fa8e947b53041cb3bdf5540e7b83b6547ca19ba7edefeb083` (26.2), and `98aabc113a80b9b5e183475e839a17cf99c39c915a1f46b8f35a5e89fd5de0f1` (26.3).

## Browser checks and screenshots

Headless Edge opened the real panel on Paper 1.18.2, signed in, navigated all views, opened and inspected a file, and rendered desktop and mobile layouts without page errors. A separate browser regression used delayed responses to check that new edits survive file and folder navigation. It also invalidated the session during a download, confirmed the browser stayed on the panel, and confirmed the unsaved text survived sign-in. All four regression checks passed. Receipts are in `testing/notabackdoor/live/screenshots/` in the parent workspace.

![File editor on desktop](screenshots/files-editor-desktop.png)

![Overview on a narrow mobile viewport](screenshots/overview-mobile.png)

The screenshots are unedited captures of the frozen candidate. The browser checks did not join the Minecraft server as a player. All test servers were loopback-bound; the four-version sweep used `online-mode=false`, and the extra Paper 26.2 check used `online-mode=true`. These checks do not establish real player login behavior or compatibility on untested Paper builds, Spigot, Purpur, or other loaders.
