# Cobblify Launcher

A small desktop app (macOS and Windows) that installs Cobblify for Lunar
Client and for a regular Forge 1.8.9 setup, and registers it so the mod loads
however the game is started.

The two targets are set up **independently**. Either can be absent, blocked or
broken without taking the other down - a friend with Forge and no Lunar reaches
a working app, which is exactly what the previous Lunar-only version could not
do (it called `lunar_config::register` unconditionally and a missing
`launcher.json` was an app-wide error).

Both platforms are live-verified. On 2026-08-20 the owner reported roughly two
hours of real Bedwars play across macOS and Windows on 0.9.1 with no surprises:
launch, deep link, dashboard, and roster all behaved as designed on each.
`lunar/dist/Install BedwarsQOL (Lunar).bat` remains the manual fallback route on
Windows. See "Verification status" under "Ship it for Windows" for the two paths
that real play still has not touched.

## Launcher updates

The home window includes a compact updater row. Every install checks stable
metadata at startup and again every six hours (with ±10% jitter). The first run
offers an inline, non-blocking choice to enable automatic downloads; manual
**Check** and **Update** actions remain available when automatic downloads are
off. Downloads are resumable and remain cached after restart, pause while a
game is active, and are verified with both SHA-256 and the Tauri Minisign key
before **Restart to update** is offered.

The stable channel is private: authenticated metadata comes from the existing
stats Worker, artifacts are streamed from a private R2 bucket through expiring
signed URLs, and minimum-version policy is independently signed. A critical
minimum version blocks only a new game launch—it never interrupts an active
session. Ordinary releases can be deferred for the current launcher run.

Local builds deliberately compile without update credentials and stay fully
usable. `Launcher Update Candidate` builds private platform artifacts; the
separate manually started `Promote Launcher Update` workflow publishes those
exact stored files without rebuilding them. Provisioning, key backup, required
secrets, and promotion details are in [UPDATE_RELEASE.md](UPDATE_RELEASE.md).

## How it works

The launcher writes `-javaagent:<weave agent>` into Lunar's own JVM arguments in
`~/.lunarclient/settings/launcher.json` (both `settings.jvm-args` and
`settings.jvmArgs` - Lunar carries both spellings, and the field is global, not
per-profile).

That is the whole trick, and it is why the launcher is not a "you must launch
from here" tool: once Lunar's own config carries the agent, Cobblify loads from
the Dock, from Spotlight, from anywhere.

**Launch Cobblify** (Lunar) fires Lunar's official bare deep link,

    open -g "lunarclient://play"

which makes Lunar's OWN launcher boot the game on its ACTIVE version profile.
Launches never join a server: the player joins Hypixel themselves. Lunar has no
link parameter that picks a version without also joining a server, so the
version is read at launch from the fresh Weave log instead: a Lunar that is not
on 1.8.9 ends the launch with "Set Lunar to 1.8.9" + Open.

`open -g` avoids stealing focus. Cobblify never hides, minimises or re-hides a
Lunar or Prism window, on macOS or Windows. Lunar hides its own launcher when
the game starts (unless its "Launcher visibility on Game Launch" setting is Keep
Open) and shows it again when the game exits. Lunar stays open after a game;
Cobblify quits it only where it must write `launcher.json` - setup's "Closing
Lunar", a polite quit (`lunar_quit.rs`, macOS).

Before writing it:

- backs the file up to `launcher.json.bak-cobblify`, once, never overwriting an
  existing backup - that file is the uninstall path
- refuses to write while Lunar is running, because Lunar rewrites that file on
  exit and would clobber the edit
- preserves any other JVM args, and replaces a stale Weave javaagent rather than
  appending a second one
- reads the user's arguments from `settings.jvmArgs` when that key is present
  (Lunar's own UI writes it), falling back to `settings.jvm-args` only when it
  is absent, then writes both keys
- refuses to touch the file at all if the JSON is unparseable

It also installs the jars into `~/.weave/` using a same-directory temp file plus
an atomic rename, so a running game never sees a half-written jar. Weave loads
every jar in `~/.weave/mods/`, so the install deletes the older releases the new
jar replaces: any other `Cobblify-Lunar-<x.y.z>.jar` up to our version, matched
without regard to case. It deletes them only after the new jar is staged and
hash-checked. Any other `Cobblify-Lunar-*` entry is left alone and reported as a
conflict: a newer release, a `-dev` or renamed build, or a symlink. An old
release that could not be deleted (Windows, game still open) is reported too,
and the new jar is not installed beside it until the next try.

**Uninstalling on Windows undoes that registration first.** The NSIS
uninstaller's `NSIS_HOOK_PREUNINSTALL` (`src-tauri/nsis/hooks.nsh`) runs
`cobblify-launcher.exe --uninstall-lunar-integration`, which strips the Weave
`-javaagent:` from both jvm keys in `launcher.json` and deletes only the two
jars the launcher installed - `~/.weave/Weave-Loader-Agent-*.jar` and
`~/.weave/mods/Cobblify-Lunar-*.jar` - removing `mods/` and `.weave` only if
they end up empty. The exit code is the contract: `0` done, `3` Lunar is
running or cannot be determined, `4` another launcher process is alive, `1` an
I/O error. Both refusals (`3`, `4`) happen before the first write, so they
guarantee nothing was changed; a `1` can come after `launcher.json` was already
rewritten (the jar deletion failed), so it means "partially undone, look at
`~/.weave`". On any non-zero exit the hook shows a message and aborts the
uninstall so the user can retry. They are not paranoia: Lunar rewrites `launcher.json` when it exits
and would put back a javaagent whose jar had just been deleted, and the NSIS
template's own running-app check happens *after* this hook. Residual race: a
Lunar or Cobblify process started in the milliseconds between the check and
the write is not covered. `launcher.json.bak-cobblify` is deliberately left in
place - it is the user's pristine pre-Cobblify copy.

## First run and setup (0.18)

Startup only reads: it never writes into a client the player has not set up. A
client is **set up** once Cobblify is registered in it (Lunar: our javaagent in
`launcher.json` and the jar in `~/.weave/mods`; Prism: a valid saved instance in
`~/.cobblify/launcher-targets.json`). A set-up client still gets current jars
silently after an update.

- **Nothing set up:** the window shows "Choose Your Setup" alone, with no island.
  Its menu lists Lunar Client and Forge (Prism); a client that is not installed
  carries a download mark. Picking it opens its download page and shows
  "Install Lunar/Prism" + Download; detection is polled every 2 s and setup
  starts by itself once it is installed.
- **Setup** (`begin_setup` / `resume_setup`, progress on `setup://progress`)
  runs six checks, each held to 0.9 s so the island visibly builds one part per
  check: Cobblify's own files, the client, sign-in, the client closed (Lunar is
  quit politely; Prism's instance must not be running), old Cobblify files, then
  adding Cobblify. It stops at "Sign in to Lunar/Prism" + Open until an account
  exists, at "Close Minecraft" while that client's game runs, and fails with
  "Try again" (which resumes at the failed check). Launch arrives with the bed.
- **Prism:** one compatible 1.8.9 Forge instance is used; several bring up a
  pick with the most recently played preselected; none makes "Cobblify 1.8.9",
  with Java 8 for that instance only when Prism will not fetch it itself.
- **From home**, the client menu's "Add Prism/Lunar" sets the other client up the
  same way (download + "Install …" wait first when it is missing; it follows
  through only on an idle home). Settings: Lunar **Repair** re-runs Lunar's
  setup; Prism **Add** is the same add flow, **Change** re-picks the instance.
- **The window** is created hidden and shown once the island's first frame is
  drawn (at once without one, 1.5 s at most); the backend shows it after 3 s if
  it is somehow still hidden.

## Forge

The Forge build is a **pure drop-in**: Mixin is shaded into the jar and its
manifest carries `TweakClass` + `ForceLoadAsMod`, which FML auto-registers. No
JVM argument, no coremod, no bootstrap, no config edit. The launcher deliberately
supports Prism only: Prism provides a documented instance-id CLI, so Forge can match
Lunar's one-click launch, progress text and external lobby/queue/game dashboard.

`forge.rs` scans Prism's default instances directory and reads `mmc-pack.json`:

- **Confirmed** - the metadata says Minecraft 1.8.9 *and* Forge.
- **Incompatible** - the metadata positively says another version, another
  loader, or that Forge is not installed. **Never offered**.

**Nothing is ever installed speculatively.** Startup lists candidates; a user
choice installs. The choice is remembered in `~/.cobblify/launcher-targets.json`
and its Prism marker is revalidated before every install or launch. Cobblify does not
support CurseForge, the vanilla `.minecraft` folder, or a hand-picked directory.

### The install transaction

Forge raises `DuplicateModsFoundException` and refuses to boot with two jars
declaring mod id `bedwarsqol`, so a stale Cobblify jar is fatal rather than
untidy. Order is the whole safety argument:

1. **always** scan both `mods/` and `mods/1.8.9/` (FML loads the version-specific
   directory too), and finish the scan before changing anything - including when
   our jar is already current, which is the likeliest real upgrade and the case
   an early return would break;
2. install nothing if any Cobblify jar we may not touch is present: anything
   Cobblify-ish that is *not* a `Cobblify-1.8.9-forge-<x.y.z>.jar` up to our
   version (a newer release, a `-dev` build, a hand-renamed file, a symlink) is
   reported and blocks;
3. stage and hash the new jar - a failure here deletes nothing;
4. **delete** every superseded release. If one cannot be deleted (Windows
   refuses while the game holds it open), stop before the commit: our jar beside
   it would be the two-jar state;
5. commit, replacing whatever build sits at our own name (usually the dev build
   of the same version);
6. delete the `<release>.cobblify-disabled` copies that launchers before 0.16.3
   set aside instead of deleting. This step is best-effort, since FML never loads
   those files.

Classification is **caseless on both platforms**: Forge loads a jar however its
name is cased, so an exact match would miss a lower-cased stale jar on a
case-sensitive volume. The one exempt file is our own destination, identified as
a directory entry (same folder, same name under the platform's casing, plus an
inode check for a case-folding volume's single entry), so a symlink or hard link
under another name still counts as a second jar.

## Build

```sh
cd launcher
npm install
npm run tauri build -- --bundles app
```

`--bundles app` is not optional in practice: the plain `npm run tauri build`
also tries to produce a `.dmg`, and that step needs Finder automation permission
and fails with `AppleEvent timed out (-1712)`. The `.app` itself builds fine
either way; only the dmg step fails.

The build is **blank on purpose** - no jars inside it. That is what keeps the
backend token out of every build artifact. Jars are injected later, locally.

The app icon (a pocket island with a red bed) is drawn by
`node tools/make-app-icon.mjs`, which writes everything in `src-tauri/icons/`; edit
the script, never the images. `Cobblify.icon` is the macOS 26 icon with its light and
dark tiles. Tauri compiles it with actool, which has to be Xcode 26's: without it
the build logs an actool error and ships only `icon.icns` (the dark tile), which is
what older macOS shows anyway. The release workflow points actool at the runner's
Xcode 26 and fails if `Assets.car` is missing.

### Build on Windows

A Windows machine builds and tests the whole launcher locally - this is the
dev loop; releases come from `Launcher Update Candidate`. Verified 2026-08-14 on
Windows 11:

```powershell
cd launcher
npm ci
npm run build                  # vite -> dist/, which generate_context! needs
cargo test --manifest-path src-tauri/Cargo.toml
npx tauri build --no-bundle    # -> src-tauri/target/release/cobblify-launcher.exe
```

Prerequisites: Rust (rustup, MSVC toolchain), VS Build Tools 2022 with the C++
workload, a Windows 10/11 SDK, the WebView2 runtime (present on Windows 11), and
Node 22. `--no-bundle` skips the NSIS installer; the release workflow builds
that with credentials baked in.

To run a local build end to end, drop the exe next to any existing `resources/`
folder (the jars + `manifest.json` from a bundle) - resource resolution is
relative to the executable, and the file name does not matter, so a second exe
can sit beside the shipped one as a control.

This does NOT change the distribution rules: friends get the signed setup exe
published by `Promote Launcher Update`. A locally built exe is for the dev
loop, never for a friend.

## Test it without packaging

`launcher/tools/test-drive.command` builds a runnable launcher using the
current locally built Lunar and Forge jars plus the Weave agent already installed
in `~/.weave`. It runs no Gradle build itself and touches no token. It drops
the app on the Desktop.

`launcher/tools/check-state.sh` is read-only and reports what the launcher did:
whether the backup exists, whether there is exactly one javaagent per key,
whether the jars are installed, and whether the last game actually loaded
Cobblify.

`launcher/tools/test-injection.sh` proves the packaging path - injection,
manifest, signing, signature validation and the archive listing - using fake
jars and no token.

### A Next test build beside the stable launcher

`launcher/tools/next-build.sh <N> --vendor <dir>` builds "Cobblify Launcher
(Next)", version `0.18.0-next.<N>`, identifier `club.cobblify.launcher.next`.

- **Updater:** built with the three updater values unset. `strings` confirms
  the binary has no update host.
- **Jars:** the vendor dir is a released launcher's
  `Contents/Resources/resources` (three jars + `manifest.json`). The jars are
  checked against that manifest, and the Weave agent against the hash pinned in
  `launcher-update.yml`. Only the launcher carries the prerelease version; the
  jars keep their release names.
- **Output:** `/Users/Shared/CobblifyNext/<version>/`. That folder is sticky:
  copy the app out, don't move it. `--quarantine` makes Gatekeeper ask as it
  would for a download.

The identity only separates the single-instance lock and the `~/Library`
webview folders. Every other launcher file is in `$HOME`, shared with the
stable launcher, and opening Next writes there at once (`~/.weave`,
`launcher.json`, a saved Prism instance). Open it only with your setup set
aside.

### Testing as a brand-new player: fresh-mac.sh

`launcher/tools/fresh-mac.sh`, delivered next to the Next build, moves a Mac's
Lunar / Prism / Cobblify state out of the way and puts it back exactly. Run it
in your own account, with Lunar, Prism, their games and both launchers closed.

1. **`status`** is read-only. It lists every known path, other matches in
   `~/Library`, related processes, login items, and any active stash.
2. **`aside <lunar|prism|all> [--data-only]`** moves the chosen clients' state,
   plus Cobblify's own, into `~/CobblifyStash/<timestamp>/`. Before anything
   moves, it records every file's path, mode, size and sha256. Apps become
   `.app.stashed` so nothing can start them. `--data-only` leaves the apps
   installed.
3. **`restore`** first finishes any move a crash interrupted. It then moves
   anything created while testing into `<stash>/new/`, moves the originals back,
   and checks every file against the record. If something fails, run it again;
   it picks up where it stopped.

It never deletes. Delete the stash folder yourself once you are happy.
Reinstall Lunar or Prism into `/Applications` while testing: the launcher only
looks there. On macOS 15, moving apps may need Terminal to have App Management
(System Settings › Privacy & Security); the tool says so when that is the
cause.

`launcher/tools/test-fresh-mac.sh` runs the tool against a fake home and a fake
`/Applications`. It covers round trips, refusals, injected move failures,
crashes between a move and its journal entry, and a file changed while
stashed.

### A Next test build on Windows: next-build.ps1

`launcher\tools\next-build.ps1 <N> -Vendor <dir>`, run on the Windows build box, is the
twin of `next-build.sh`: "Cobblify Launcher (Next)", version `0.18.0-next.<N>`, identifier
`club.cobblify.launcher.next` (its own single-instance lock and webview folder,
`%LOCALAPPDATA%\club.cobblify.launcher.next`).

- **Updater:** the three updater values are removed from the build's environment, and the
  exe is searched for the update host (with a string that must be present, so the search is
  known to work).
- **Jars:** as on the Mac: checked against the vendor manifest and the pinned agent hash.
- **Output:** `C:\ProgramData\CobblifyNext\<version>\` holds `Cobblify Launcher (Next).exe`
  and `resources\` (resource resolution is relative to the exe), plus `fresh-win.ps1` one
  folder up. Every account on the PC can read it. `-Replace` overwrites the same version.

```powershell
git push win feat/launcher-redesign          # from the Mac; the vendor dir goes over with scp
powershell -ExecutionPolicy Bypass -File launcher\tools\next-build.ps1 11 -Vendor C:\Users\agent\cobblify-vendor
```

### Testing as a brand-new player on Windows: fresh-win.ps1

`fresh-win.ps1` is `fresh-mac.sh` for Windows (PowerShell 5.1), with the same `status`,
`aside <lunar|prism|all> [--data-only] [--yes]` and `restore [--yes]`. Run it in your own
account with Lunar (check the system tray), Prism, their games and both launchers closed:

```powershell
powershell -ExecutionPolicy Bypass -File C:\ProgramData\CobblifyNext\fresh-win.ps1 status
```

- It moves Cobblify's state (`.cobblify`, `.weave`, `.bedwarsqol`, the installed launcher, its
  webview data and shortcuts), Lunar's (both install layouts, `.lunarclient`, its AppData
  folders, shortcuts, Weave's `.minecraft\versions\1.8.9`) and Prism's (installer and Scoop
  installs, its data folders, shortcuts; a portable Prism Cobblify remembered; absolute
  `InstanceDir`/`JavaDir`) into `%USERPROFILE%\CobblifyStash\<timestamp>\`, recording every
  file first. Nothing outside your user folder is moved; a Prism folder outside it stops
  `aside` before anything moves.
- It never deletes. `restore` moves what testing created into `<stash>\new\`, returns the
  originals and checks every file. It can be re-run after a crash or a failure.
- It records login-time starters (the Run key, the Startup folder) for you to re-check, and
  never writes the registry. A reinstalled Lunar or Prism re-registers its own links.

`launcher\tools\test-fresh-win.ps1` runs it against a fake profile in `%TEMP%`.

## Ship it

```sh
tools/package-owner-bundle.sh
```

Owner-only. It bakes the real backend URL and token into the jars, so it needs
`cobblifyBackendUrl` / `cobblifyBackendToken` in `~/.gradle/gradle.properties`,
and it runs both Gradle builds. It expects a launcher build to already exist and
will tell you the exact command if one does not.

It stages the `.app` into a temp dir, injects the jars and a `manifest.json`,
ad-hoc signs **after** injection (injecting invalidates any earlier signature),
validates with `codesign --verify --deep --strict`, and asserts the finished zip
contains exactly the expected paths and nothing else.

Output lands in `dist-owner/`. **DM it. Never attach it to a public GitHub
Release.**

### Ship it for Windows

This Mac cannot build Windows binaries. The Windows setup exe (and the Mac ZIP)
come from the manually started `Launcher Update Candidate` workflow, and
`Promote Launcher Update` publishes those exact files to the private R2 bucket.
See [UPDATE_RELEASE.md](UPDATE_RELEASE.md). Same distribution rule: DM only.

### Verification status

The cargo suite (including the argv-canary privacy test) runs on the Windows
box during the dev loop and in `Launcher Update Candidate`. Beyond that, as of
2026-08-20:

- **Live-verified on macOS and Windows** by roughly two hours of real Bedwars
  play on 0.9.1 - auto-join on (launch, deep link, dashboard, roster),
  auto-join off followed by a manual join, and a mid-session disconnect
  followed by a reconnect.
- **Windows `launcher.json` has been observed** on a real Windows machine
  (2026-08-13). A fresh Windows install carries no jvm keys at all and Lunar
  writes camelCase `jvmArgs` only; the schema guard accepts that shape and
  still refuses missing-settings and non-string files. Because Lunar edits
  `jvmArgs` alone, the two spellings diverge as soon as a user changes their
  arguments after setup, so a present `jvmArgs` (empty included - that is a
  cleared setting) is authoritative and `jvm-args` is read only when the
  camelCase key is absent. The guard used to refuse that divergence outright,
  which reverted the user's flags to whatever Cobblify last wrote.
- **Not exercised by real play:** the bounded "Still Waiting for Hypixel"
  state, and the refusal to attach to a game that was already running before
  the launch. Both are covered by tests only. Still-waiting may be hard to
  reach in practice - Minecraft's own connect timeout is short enough that it
  tends to fail first.

## Gotchas worth knowing

**Never run the `.app` from `/tmp`.** Tauri refuses to resolve its bundled
resources when the executable has a symlinked ancestor on macOS, and `/tmp` is a
symlink to `/private/tmp`. The app dies with "Cannot locate the bundled
resources". `~/Applications`, `/Applications` and `~/Downloads` are fine. The
friend instructions say to move it to Applications for this reason.

**Gatekeeper blocks the first open.** The bundle is ad-hoc signed, not
notarized, so macOS blocks it once and the user clears it through
System Settings > Privacy & Security > Open Anyway. The ad-hoc signature is what
keeps this in the "cannot be checked" case, which has that override, rather than
the "damaged" case, which does not. Measured: the signature stays valid with a
quarantine attribute applied. macOS 15 removed the old right-click > Open
bypass, so the instructions name the System Settings path specifically.

**`status()` is computed once at startup and cached.** There is deliberately no
Retry button - it would report stale state. Blocked and error states tell the
user to quit and reopen. It now lives behind a `Mutex` because an *explicit* user
action - choosing a Forge instance - produces a new one; it is still never
recomputed behind the user's back, which is the invariant that mattered.

**No auto-join (0.18).** Lunar always opens through the bare `lunarclient://play` link
and Prism always launches with `--launch <instance-id>` only. The 0.17 launcher's
`auto_join_hypixel` key in `~/.cobblify/launcher-preferences.json` is still read
leniently and written back unchanged (0.17 shares the file and requires it), but
nothing acts on it. The UI does not narrate Hypixel connectivity until a verified
live lobby snapshot proves it.

**Test dev builds (update channel).** A third checkbox in the same row switches the
updater to the opt-in dev channel (`update_channel` in the same preference file). Ticked,
the launcher is offered whichever of the stable and dev channels is newer and labels a
`-dev.N` build as a dev build in the update strip; unticked, nothing about the update
request changes. See `UPDATE_RELEASE.md`, "Dev channel", for how a build gets there.

**Lobby writer identity and session binding.** Every `lobby.json` snapshot carries
`jvmPid` and `jvmStartTimeMs` from the exporting JVM (operational metadata only — never
argv). Each JVM writes through its own temp file (`lobby.<pid>.<start>.tmp`) before an
atomic replace. The launcher admits only a writer whose OS process is alive, whose birth
time is at/after the launch baseline, and whose JVM start time matches that process
within strict bounds. A pre-existing alive writer is detected before native dispatch and
surfaces **Game Already Running** with **Try Again**; stale files from prior sessions
never activate the dashboard.

**`lobby.json` is contract v2 and the launcher only draws it.** Every presentation decision
about a player - FKDR tier, cheater flag, single priority badge, gated tag chips, which mode the
numbers came from, nick reveal with the real account's stats - is made once in the mod by
`PlayerCard` (the same policy the tab list and chat use, honouring the Tag Utils (Urchin) and
Nick Utils / Auto Denick toggles, tag expiry, the identity gate and the forced `/bw mode`) and
exported
verbatim. `row-model.js` arranges those fields into classes, chips and cells; nothing in the
launcher derives a tier, a cheater flag or a badge, and `main-no-policy.test.js` pins that. The
shared fixtures under `common/src/test/resources/lobby-contract/` (`valid/`, `invalid/`,
`valid-v1/`) are consumed by the mod's DTO test, `lobby-contract.test.js` and the Rust validator
tests, so the three copies of the shape cannot drift. A well-formed v1 file from an older mod jar
still binds the session but the dashboard shows "Update Cobblify to use the overlay" instead of a
roster.
`seraphThreat` is retired with Seraph (its API shut down in October 2026): mod jars keep writing
`-1` because launchers built before the removal require the key, while the launcher accepts a player without it
(fixture `valid/no-seraph-threat.json`) and never draws it.

**The Forge dashboard uses the same writer binding as Lunar.** A launch stamps a
per-launch baseline; only snapshots from a JVM born after that baseline and still alive
at poll time can connect. Forge cosmetic progress still comes from the remembered
instance's FML log.

**`proc.rs` is the only file allowed to use `sysinfo`.** Lunar's game JVM
carries a live Minecraft access token in its command line, so process inspection
uses an exe-only refresh
(`ProcessRefreshKind::nothing().with_exe(...)`) and never reads argv. There is a
test that proves a process's `cmd()` is empty under that refresh; do not weaken
it, and do not call `new_all()` or `refresh_all()` anywhere.

**Launch Lunar limitations, stated plainly.**

- The game boots whatever version profile is ACTIVE in Lunar. A 1.8.9
  profile merely existing is not enough; it must be the selected one.
- Lunar's login and update windows appear when Lunar needs them, and
  Cobblify leaves every Lunar and Prism window where it is.
- The button disables for the rest of the session after a successful
  dispatch (a second play request mid-boot has no defined meaning);
  reopening Cobblify resets it.
- On Windows, when Lunar starts the game on `java.exe` rather than
  `javaw.exe`, the game's console window stays on screen.
- The deep link goes through ShellExecute on Windows (never `cmd /C start` -
  it breaks on `&` and flashes a console).
- The Windows exe is linked into the GUI subsystem
  (`windows_subsystem = "windows"` in `main.rs`, release builds only). Without
  it Windows opens a console window next to the launcher that the user cannot
  close - closing a console kills the process attached to it. Verified against
  the 0.9.0 bundle: PE Subsystem was 3 (CONSOLE).

## Layout

```
launcher/
  src/            UI - main.js (backend wiring), dash.css (the post-launch dashboard),
                  release-notes.json (What's New and the history)
  src/ui/         the 0.18 home: app.js (DOM), model.js (backend -> what it shows),
                  island3d.js (three.js island), kit.js, tokens.css, fonts/
  src-tauri/
    src/main.rs         startup sequence + the two commands
    src/lunar_quit.rs   setup's polite Lunar quit ("Closing Lunar"), macOS - pid-targeted
    src/proc.rs         the only sysinfo call site
    src/resources.rs    manifest verification: global fail-closed, per-target isolated
    src/install.rs      stage_verified + commit, the atomic jar primitive
    src/forge.rs        instance detection, compatibility, stale-jar cleanup, persistence
    src/lunar_config.rs the launcher.json edit
    resources/          empty in git; THREE jars + manifest injected at package time
  tools/          test-drive, check-state, test-injection, app-inject-lib
  friend/         READ ME FIRST.txt - ships to friends inside the bundle;
                  MAC INSTALL.txt - rides inside the release .dmg.
                  Lives OUTSIDE dist/ because Vite wipes dist/ on every build.
```
