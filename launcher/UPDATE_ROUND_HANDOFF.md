# HANDOFF: launcher update round (branch `launcher-auto-update`)

Written 2026-09-13 from the user's main Mac account (`.ai/` is gitignored, so it
lives here). Agents on the agent account pick up from here.

## Goal

Ship a real launcher update through the auto-updater, in two parts:

1. DONE - center the dashboard's "Not Available" note (shown when the mod
   reports `dashboardEligible: false`) on the window at every size.
2. DONE (2026-09-13, cycle `.ai/cycles/2026-09-13-session-stats`) - Session
   Stats HUD, a Lunar-style game + session tally in the mod on both Forge and
   Lunar. Shipped as mod + launcher **0.11.0** (the launcher bundle carries the
   mod jars, so a mod feature is a launcher release). Not play-tested on live
   Hypixel before release by the user's decision; the module is default-off.
3. DONE (2026-09-14) - mod + launcher **0.12.0**: Session Stats HUD redrawn in
   Lunar's 13-row layout with KDR / BBLR / winstreak / games (cycle
   `.ai/cycles/2026-09-13-session-hud-lunar-layout`, PR #7) and the Height
   Limit HUD (PR #6). Right-anchored HUD defaults moved on screen with a
   stamped `settingsVersion` config migration. Not play-tested before release
   by the user's decision (the user tests through auto-update; no external
   users are on the launcher yet).

## What part 1 changed

- `launcher/src/style.css`: `.sheet.bare` is shifted up by half the 140px
  header band (`transform: translateY(calc(var(--header-band) / -2))`) so the
  note centers on the window rather than on the strip under the wordmark, and
  `.sheet.bare .empty-note` is `text-align: center`.
- `launcher/src/main.js`: new browser preview fixture `?ctx=unsupported`
  (`PREVIEW_LOBBY.unsupported`, a LOBBY snapshot with
  `dashboardEligible: false`). Before this the state had no preview.

## Verification done

- `cd launcher && npm test`: 164 pass, 0 fail.
- Browser preview (`npx vite --port 5173`, open
  `http://localhost:5173/?ctx=unsupported`, resize `#preview-window`):
  note center vs stage center measured at 640x640, 640x480, 900x620, 1300x700,
  700x900, 1300x1100. Horizontal exact; vertical 8px above center at every
  size (the dash's 16px bottom padding). Judged not worth compensating.
- Not run: Windows build, in-app check. Build on the Windows box per
  `launcher/README.md` "Build on Windows" if part 2 needs a hands-on test.

## Release notes for whoever ships this

- Updater state of play and the three past root causes are in
  `launcher/UPDATE_PROOF_HANDOFF.md`. The user manually confirmed the update
  path works end to end on 2026-09-13 with a locally built exe (env vars
  `COBBLIFY_UPDATE_URL`, `COBBLIFY_UPDATE_TOKEN`, `COBBLIFY_UPDATER_PUBKEY`
  set at build time; the token is the `token=` value in
  `cobblify-backend.properties` inside the mod jar - never print or commit it).
- Release exes come from CI (`.github/workflows/launcher-update.yml`). GitHub
  Actions minutes were exhausted on 2026-09-08; check they have reset before
  relying on CI for the release build.
- Version bump for the release: every source `tools/check-release-version.sh`
  pins (`gradle.properties`, `lunar/build.gradle.kts`, Lunar `BedwarsQol.VERSION`,
  `launcher/package.json`, `package-lock.json`, `Cargo.toml`, `tauri.conf.json`)
  plus the `cobblify-launcher` entry in `Cargo.lock`. 0.14.0 as of 2026-09-20
  (0.13.0: overlay unification, lobby.json v2; 0.14.0: opt-in dev update
  channel - "Test dev builds (unstable)" checkbox, `publish_dev` on the
  Candidate workflow, see `UPDATE_RELEASE.md` "Dev channel").
- The release pair is now `Launcher Update Candidate` (`launcher-update.yml`)
  then `Promote Launcher Update` (`launcher-promote.yml`), both manual.
