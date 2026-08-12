# Cobblify Launcher

A small macOS app that installs Cobblify for Lunar Client and registers it so
the mod loads however Lunar is started.

macOS only. Windows friends keep using
`lunar/dist/Install BedwarsQOL (Lunar).bat`, which is untouched.

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

## Test it without packaging

`launcher/tools/test-drive.command` builds a runnable launcher using the jars
already installed in `~/.weave` on this machine. No Gradle build, no token.
It drops the app on the Desktop.

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
Release** - public releases carry only the blank CI jars.

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
user to quit and reopen.

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

## Layout

```
launcher/
  src/            UI - main.js, scene.js (three.js voxel hero), style.css
  src-tauri/
    src/main.rs         startup sequence + the two commands
    src/hide.rs         post-launch launcher hiding - fail-soft, pid-targeted
    src/proc.rs         the only sysinfo call site
    src/resources.rs    manifest verification, fail-closed
    src/install.rs      atomic jar install, conflict reporting
    src/lunar_config.rs the launcher.json edit
    resources/          empty in git; jars + manifest injected at package time
  tools/          test-drive, check-state, test-injection, app-inject-lib
  friend/         READ ME FIRST.txt - ships to friends inside the bundle.
                  Lives OUTSIDE dist/ because Vite wipes dist/ on every build.
```
