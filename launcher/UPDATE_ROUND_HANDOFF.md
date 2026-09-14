# HANDOFF: launcher update round (branch `launcher-auto-update`)

Written 2026-09-13 from the user's main Mac account (`.ai/` is gitignored, so it
lives here). Agents on the agent account pick up from here.

## Goal

Ship a real launcher update through the auto-updater, in two parts:

1. DONE - center the dashboard's "Not Available" note (shown when the mod
   reports `dashboardEligible: false`) on the window at every size.
2. TODO - a larger feature change. The user will describe it to the next
   agent; nothing is known about it yet. Do not guess at it.

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
- Version bump for the release: `launcher/src-tauri/tauri.conf.json`,
  `launcher/package.json` (both `0.10.1` now) and `Cargo.toml` if it carries
  the version.
