# Launcher update proof handoff

2026-09-12. Launcher auto-update 0.9.9 -> 0.10.0 investigation. Stage:
`PROVEN` on the Windows box as `agent` (see "Proof captured" below). Only the
D1 event rows remain to be read from an owner-authenticated wrangler.

## Goal

Prove that an installed launcher 0.9.9 auto-updates to 0.10.0 on the Windows
box and capture evidence: registry DisplayVersion 0.10.0, installed exe sha256
equal to the manifest, and update-event rows.

## Root causes found (three, all confirmed)

1. Live `channels/stable.json` policy signature was made over the policy text
   plus a trailing newline; the launcher verifies exact bytes and reports
   `untrusted_policy`, shown as "Retry". FIXED: re-signed with the owner key and
   re-uploaded to R2 (`cobblify-launcher-updates`). CI promote workflow uses
   `printf` and is correct; the bad manifest came from a manual promote.
2. GitHub env secret `COBBLIFY_BACKEND_TOKEN` (launcher-stable) had a trailing
   newline, so every CI-built exe (0.9.9 and the promoted 0.10.0) has
   `token\n` baked in. reqwest rejects the header value, the updater builder
   fails before any request, and no update events are ever posted. FIXED on
   2026-09-12 by re-setting the secret without the newline. Consequence: the
   shipped 0.9.9 and 0.10.0 exes can never self-update; a fresh build is
   required. The token itself is accepted by the worker (probed: HTTP 200).
3. The launcher had no Tauri capabilities file, so the webview is denied
   `core:event:listen` and never receives `updater://status` events. UI sticks
   at "0% / Pause" and the Restart button never appears even when native code
   reaches `ready`. FIXED in this commit: `launcher/src-tauri/capabilities/default.json`.

## Proof captured (2026-09-12, Windows box, user `agent`)

Starter: 0.9.9 built from `6ad8678` on the box (`build-starter.ps1`, token read
from the installed Lunar jar, URL and pinned pubkey compiled in; FileVersion
0.9.9, sha256 `65203f500bd4afe1daf6bddb9342d62b430759c9c10bea075204ab40bdb7f6fa`).
Installed 0.10.0 via the R2 setup exe (`/S`), then swapped the exe for the
starter (original kept as `cobblify-launcher.exe.orig-0.10.0`).

| Step | Evidence |
|---|---|
| Live manifest | `GET /launcher/update/windows/x86_64/0.9.9` -> 200, version 0.10.0, sizeBytes 15172237, sha256 `45593015fb5c206bc6238c915b912b4e42be52cb124ab4659e19246fe0d7ede6`, policy verifies |
| Consent -> auto download | Clicked Enable; `launcher-preferences.json` gained `auto_update_enabled: true`; `~/.cobblify/updates/0.10.0.bundle` written, 15172237 bytes, sha256 equal to the manifest |
| UI ready state | Screenshot: "0.10.0 downloaded / RESTART / LATER" (capabilities fix confirmed; no more stuck "0% / Pause") |
| Restart -> install | Clicked Restart at 13:10; NSIS rewrote `uninstall.exe` at 13:11:02 and relaunched pid 20072 at 13:11:02 |
| Installed exe | FileVersion 0.10.0, sha256 `d059a7092b7ee08de0100af203c0883066f4382bb38fce4ccfbcd4c89a30aed5`, byte-identical to the exe shipped inside the 0.10.0 setup |
| Registry | HKCU Uninstall `Cobblify Launcher` DisplayVersion 0.10.0, MainBinaryName cobblify-launcher.exe |
| pending-version | Written by `install_update`, gone after the relaunch (consumed by `report_completed_install`) |
| Relaunched 0.10.0 | Shows "Unavailable / Retry" - expected: the CI-built 0.10.0 carries the `token\n` secret (root cause 2) and fails `updater_build_failed`; it cannot self-update until a post-fix CI build ships |

Note: the manifest sha256 is the NSIS setup bundle, not the installed exe, so
"installed exe equals manifest" is proven via bundle hash == manifest and
installed exe == exe extracted by that bundle.

Not yet read: D1 `launcher_update_events` rows for this run (the Mac `agent`
account has no wrangler login). From an owner-authenticated shell:

```sh
cd server/stats-worker && npx wrangler d1 execute cobblify-launcher-update-events \
  --remote -c wrangler.owner.toml --command "SELECT id, datetime(occurred_at,'unixepoch') AS at, event, current_version, target_version, platform FROM launcher_update_events WHERE occurred_at > strftime('%s','now') - 86400 ORDER BY id"
```

Expected for the run around 2026-09-12 20:07-20:11 UTC: check_ok,
download_started, download_verified, install_started (current 0.9.9, target
0.10.0), then post_update_started (current 0.10.0). Nothing after that: the
relaunched CI 0.10.0 cannot post events (root cause 2).

Box state left behind: install dir holds the updated 0.10.0 exe plus
`cobblify-launcher.exe.orig-0.10.0`; `C:\Users\agent\proof\` holds the
starter, setup exe, build/launch/shot/click scripts and screenshots; scheduled
tasks `CobblifyAgentLaunch`, `CobblifyAgentShot`, `CobblifyAgentClick`
(interactive, run as `agent`; the click target is read from
`proof\click-target.txt` because `schtasks /change` prompts for a password).
The Windows clone is clean at `6ad8678`.

## Verified before this (old `human` account)

- Debug 0.9.9 build (same source as the 0.9.9 CI commit 3ffaf47, token read
  from the installed jar, built on the Windows box) with the fixed manifest:
  check_ok -> download -> download_verified -> state `ready`, events all 204.
  Not yet verified: UI shows Restart with the capabilities fix, install to
  0.10.0, post-install evidence.

## Windows box state (old `human` account, admin)

- `C:\Users\human\AppData\Local\Cobblify Launcher\cobblify-launcher.exe` is a
  debug build; original saved next to it as `cobblify-launcher.exe.orig-0.9.9`.
- Clone `C:\Users\human\Cobblify` has an uncommitted debug patch (version
  0.9.9 in tauri.conf.json/Cargo.toml, logging in updater.rs) and the
  capabilities file. Reset with `git checkout -- launcher/src-tauri`.
- Scheduled tasks `CobblifyUpdateTest`, `CobblifyShot`, `CobblifyFg`,
  `CobblifyClick` exist; helper scripts in `C:\Users\human\*.ps1`.
- Work now continues from the `agent` accounts (Mac `/Users/agent/Cobblify`,
  Windows user `agent`, logged in on the desktop). The `agent` user has no
  launcher installed yet.

## How to finish the proof (from the Mac `agent` account)

1. On Windows as `agent`: build a 0.9.9 starter from this branch with
   `COBBLIFY_UPDATE_URL=https://bedwarsqol-stats.mrcobbert.workers.dev`,
   `COBBLIFY_UPDATE_TOKEN` (read from `cobblify-backend.properties` inside any
   Cobblify-Lunar jar, never commit it) and `COBBLIFY_UPDATER_PUBKEY` (the
   pubkey pinned in tauri.conf.json), with version temporarily set to 0.9.9
   (see launcher/README.md "Build on Windows"). Do not commit the version change.
2. Install the launcher for `agent` (run the 0.10.0 setup exe from R2 once to
   get the NSIS install + registry entry, then overwrite the exe with the
   0.9.9 starter), or copy `resources/` next to the exe.
3. Launch it in the interactive session (scheduled task with
   `New-ScheduledTaskPrincipal -UserId agent -LogonType Interactive`), wait for
   auto-download, confirm the UI shows "0.10.0 downloaded / Restart", click
   Restart (user32 click via a scheduled task works), then check uninstall
   registry DisplayVersion, exe hash vs manifest sha256, and
   `~/.cobblify/updates/pending-version` handling on relaunch.
4. Screenshots from inside the session: run a `CopyFromScreen` script through
   an interactive scheduled task; direct SSH sessions have no desktop.

## Still open for real releases

- Build and promote a new launcher (0.10.1) from a CI run made AFTER the secret
  fix so shipped builds can self-update. CI minutes were exhausted on
  2026-09-08; check quota.
- Consider trimming `option_env!` values in `updater.rs` `configured()` so a
  stray newline can never break auth again (not done; needs a decision).
