# Launcher update proof handoff

2026-09-12. Launcher auto-update 0.9.9 -> 0.10.0 investigation. Stage:
`VERIFYING` (proof not yet captured).

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

## Verified so far

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
