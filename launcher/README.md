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

The **Launch Lunar** button goes further: it fires Lunar's official deep link,

    open -g "lunarclient://play?serverAddress=play.hypixel.net"

which makes Lunar's OWN launcher boot the game on the ACTIVE version profile
and auto-join Hypixel - no Press Play, no manual server join. Never add
`forceRecommendedVersion` to that link: it can silently switch the profile
off 1.8.9 (there is a test pinning this).

`open -g` avoids stealing focus, and a background worker then hides the
launcher window outright: it polls for the launcher by exact executable path
(`proc::lunar_launcher_pid()` - the game JVM can never match), then runs an
`osascript` JXA `NSRunningApplication.hide()` against that one pid,
re-verifying the pid's bundle id before acting. One hide plus two
confirmations, at most three osascript spawns per launch. Measured on this
machine: Lunar shows its window once and never fights the hide, and
`hide()` needs no TCC permission. The whole hide layer is fail-soft - if
any of it breaks, the launch still works, just with a visible launcher.

Before writing it:

- backs the file up to `launcher.json.bak-cobblify`, once, never overwriting an
  existing backup - that file is the uninstall path
- refuses to write while Lunar is running, because Lunar rewrites that file on
  exit and would clobber the edit
- preserves any other JVM args, and replaces a stale Weave javaagent rather than
  appending a second one
- refuses to touch the file at all if the JSON is unparseable

It also installs the jars into `~/.weave/` using a same-directory temp file plus
an atomic rename, so a running game never sees a half-written jar.

## Forge

The Forge build is a **pure drop-in**: Mixin is shaded into the jar and its
manifest carries `TweakClass` + `ForceLoadAsMod`, which FML auto-registers. No
JVM argument, no coremod, no bootstrap, no config edit. The launcher deliberately
supports Prism only: Prism provides a documented instance-id CLI, so Forge can match
Lunar's one-click launch, automatic Hypixel join, hidden launcher, progress text and
external lobby/queue/game dashboard.

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

1. stage and hash the new jar - a failure here moves nothing;
2. set aside a **foreign** jar sitting at our own destination name, so a renamed
   local build or a privately shared artifact is preserved rather than
   overwritten;
3. **always** scan both `mods/` and `mods/1.8.9/` (FML loads the version-specific
   directory too) - including when step 1 found our jar already current, which is
   the likeliest real upgrade and the case an early return would break;
4. commit.

Nothing is ever deleted. A superseded release is **renamed** to
`<name>.cobblify-disabled` - FML only considers `(.+).(zip|jar)$`, so the rename
alone makes it inert, it stays in the same directory, and the user recovers it by
renaming it back. The backup name is claimed with `create_new`, which fails
atomically if taken; a stat-then-rename would race, and rename replaces its
destination on both platforms. Anything Cobblify-ish that is *not* an exact
`Cobblify-1.8.9-forge-<x.y.z>.jar` - a `-dev` build, a hand-renamed file - is
reported and blocks, untouched.

Classification is **caseless on both platforms**, unlike the Lunar path: Forge
loads a jar however its name is cased, so an exact match would miss a lower-cased
stale jar on a case-sensitive volume. The one exempt file is our own destination,
identified by `canonicalize` rather than by folding a string; a candidate whose
path cannot be resolved blocks rather than being skipped.

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
  still refuses missing-settings, non-string, or divergent files.
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

**Auto-join Hypixel (shared preference).** A compact checkbox under the launch row
defaults on and is stored at `~/.cobblify/launcher-preferences.json` (backend-owned
path and lock; the UI never supplies a filesystem location). When on, Lunar uses the
official `lunarclient://play?serverAddress=play.hypixel.net` deep link and Prism gets
`--server play.hypixel.net`. When off, Lunar opens only (`Open Lunar` / `Opening Lunar…`
/ `Lunar Opened`) and Prism launches with `--launch <instance-id>` only (no server
argument). The UI does not narrate Hypixel connectivity until a verified live lobby
snapshot proves it.

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
`PlayerCard` (the same policy the tab list and chat use, honouring the Urchin / Seraph / Nick
Utils / Auto Denick toggles, tag expiry, the identity gate and the forced `/bw mode`) and exported
verbatim. `row-model.js` arranges those fields into classes, chips and cells; nothing in the
launcher derives a tier, a cheater flag or a badge, and `main-no-policy.test.js` pins that. The
shared fixtures under `common/src/test/resources/lobby-contract/` (`valid/`, `invalid/`,
`valid-v1/`) are consumed by the mod's DTO test, `lobby-contract.test.js` and the Rust validator
tests, so the three copies of the shape cannot drift. A well-formed v1 file from an older mod jar
still binds the session but the dashboard shows "Update Cobblify to use the overlay" instead of a
roster.

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
- Lunar's login and update windows still appear when Lunar needs them.
  "Hidden" covers the ordinary launcher window, which appears once and is
  hidden within about a second of being sighted.
- The hide worker lives inside the Cobblify process. Quitting Cobblify
  right after clicking kills the worker - the launch still completes, with
  the launcher left visible.
- The button disables for the rest of the session after a successful
  dispatch (a second play request mid-boot has no defined meaning);
  reopening Cobblify resets it.
- Windows hides differently, because it has to. There is no app-level hide,
  so `hide_windows.rs` walks the top-level windows and acts per window:
  Lunar launcher windows (every process running `Lunar Client.exe` under
  `%LOCALAPPDATA%\Programs` - Electron helpers share that path) are
  MINIMIZED, and the game JVM's console window, when Lunar starts the game
  on `java.exe` rather than `javaw.exe`, is hidden outright. A console
  window is owned by `conhost.exe`, so its pid proves nothing; ownership
  comes from `AttachConsole(game pid)` + `GetConsoleWindow` instead. The
  Minecraft window is never a target. Each window is acted on at most three
  times, so restoring one from the taskbar ends the argument - that is the
  escape hatch for a login or update window caught in the sweep. The sweep
  runs for 150 s (a cold start reaches the JVM long after the click) or
  until nothing Lunar-shaped is running.
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
  src/            UI - main.js, scene.js (three.js voxel hero), style.css
  src-tauri/
    src/main.rs         startup sequence + the two commands
    src/hide.rs         post-launch launcher hiding, macOS - fail-soft, pid-targeted
    src/hide_windows.rs the same job on Windows, per top-level window
    src/proc.rs         the only sysinfo call site
    src/resources.rs    manifest verification: global fail-closed, per-target isolated
    src/install.rs      stage_verified + commit, the atomic jar primitive
    src/forge.rs        instance detection, compatibility, quarantine, persistence
    src/lunar_config.rs the launcher.json edit
    resources/          empty in git; THREE jars + manifest injected at package time
  tools/          test-drive, check-state, test-injection, app-inject-lib
  friend/         READ ME FIRST.txt - ships to friends inside the bundle.
                  Lives OUTSIDE dist/ because Vite wipes dist/ on every build.
```
