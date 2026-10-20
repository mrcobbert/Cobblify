# Native GUI fixture app

This separate **Cobblify GUI Test** app runs the current production frontend in
real Tauri/WKWebView, including native macOS fullscreen and title-bar behavior.
Its identifier is `club.cobblify.gui-test`; it can coexist with the installed
launcher. It shares the production native window-chrome module and embeds the unchanged
production frontend. The production backend, credentials, installers and updater
are absent. The two ready clients and all dashboard rosters are fixtures.
Settings work in memory and reset when the app quits. Launch on Home is rejected;
selecting a dashboard test view explicitly simulates a successful launch, progress
and acknowledged lobby snapshots through the real frontend controllers. No game
process starts, no real files/settings are read, and no update can be installed.

Double-click **Cobblify GUI Test.app** in `/Users/Shared/Cobblify-GUI-Test`, or run:

```sh
open "/Users/Shared/Cobblify-GUI-Test/Cobblify GUI Test.app"
```

The small **GUI TEST • fixture data only** badge identifies the preview without
moving the production layout. The app stays open for manual testing. It does not automatically enter
fullscreen, run a diagnosis or quit. Test fullscreen → windowed → Settings,
opening/closing the client menu, both window sizes, release-note history and
moving between displays. The displayed `0.18.1-dev.0` is a fixture version,
not an update available for installation. Use the app's title/menu name to
distinguish it from the real launcher.

Use the **Test view** selector at the bottom right to switch between Home,
Join Hypixel, Lobby, Full queue, Solo game, Doubles game and Not Available. Each
selection reloads the frontend and starts only the selected in-memory scenario.
Check that Cobblify and **Preview** keep the same position across views; repeat
fullscreen → windowed in a dashboard, then resize to the smallest window and
scroll a full roster to its last rows. Home's **Preview updates** switch controls
the selected update channel and both badges, just as in the real launcher.
Changing the test view enables the in-memory External Overlay preference.

With Reduce Motion off, quit and reopen the app a few times: the island should
build on each opening, once the window is visible. Startup layout changes must
not end the build early. Clicking or pressing a key still lets you skip it.
Keyboard controls use a subtle highlight instead of a separate white frame.
Click Launch's arrow twice: the second click should close the menu without
flashing/reopening. Keyboard opening, Escape and clicking outside still dismiss
or navigate it normally.

To start the same app using the modern-font fixture:

```sh
open -n "/Users/Shared/Cobblify-GUI-Test/Cobblify GUI Test.app" --args --font=modern
```

Use `--whats-new` to show the fixture's 0.18.1 release-note modal after the intro,
including for keyboard/focus testing on Home. This simulates an updated open; it performs
no update operation:

```sh
open -n "/Users/Shared/Cobblify-GUI-Test/Cobblify GUI Test.app" --args --whats-new
```

Close an old preview before rebuilding it. From the repository root on this
Apple Silicon Mac:

```sh
python3 launcher/tools/native-preview/build.py
```

The helper builds `launcher/dist`, stages a private copy of this fixture package
and those assets under `~/.cache/cobblify-gui-test`, then builds an arm64 binary
with SDK 26.5. It assembles an ad-hoc signed app in the shared directory. The
frontend assets are embedded unchanged; `Contents/Resources/build-info.json`
records their SHA-256 hashes and the shared native window-chrome source hash. No CI, release, installed launcher or package
script changes are involved. `--skip-frontend-build` uses an already built
`launcher/dist`; `--sdk`, `--target-dir` and `--output` are optional overrides.
The helper replaces only a previous app with this fixture identifier.

For an explicitly requested automated native smoke check, run the binary
**from an active macOS GUI session**:

```sh
"/Users/Shared/Cobblify-GUI-Test/Cobblify GUI Test.app/Contents/MacOS/cobblify-gui-test" --check
```

This mode requires a real WebGL island and checks three native fullscreen
cycles, restored main/wordmark/island positions, client-menu anchoring and
full-height Settings without an island jump after a delayed focus refresh.
It prints `GUI_TEST result=PASS` and exits 0, fails with exit 1,
or reports `UNAVAILABLE` when WKWebView remains hidden/occluded (exit 2). The
90-second safety timeout also exits 2. It intentionally moves only its own
fixture window. This automated mode does not replace manual checks of clicking,
focus, multiple displays, reduced motion or actual game/updater behavior.

For native event/DOM timing without requiring visible painting, use
`--check-chrome` instead of `--check`. It verifies three fullscreen cycles:
windowed title spacing must reach the DOM during `willExit`, while Tao still
reports fullscreen, and the title must not move after exit completes. This can
run while WKWebView is occluded. A pass proves event timing and DOM geometry;
it does not prove the visible native animation is seamless. Add a scene option
to test the actual dashboard title and badge instead of hidden home elements:

```sh
"/Users/Shared/Cobblify-GUI-Test/Cobblify GUI Test.app/Contents/MacOS/cobblify-gui-test" --check-chrome --dashboard=gameDoubles
```

The same `--dashboard=join|lobby|queueFours|gameSolo|gameDoubles|unsupported`
option can open a manual test directly in a dashboard view without `--check-chrome`.

The agent account can build this app and automate a native GUI session when
one is available. It cannot guarantee a window will appear in a different
logged-in account's desktop. The shared app is readable by that account, so
the owner can open and test it there directly.

## Browser geometry and GPU regression check

Start a loopback-only frontend preview in one terminal (from `launcher/`):

```sh
npm run build
npm run preview -- --host 127.0.0.1 --port 1440
```

From the repository root in another terminal, with Playwright installed:

```sh
node launcher/tools/gui-regression.mjs http://127.0.0.1:1440/
```

If Playwright is installed outside this repo, set `PLAYWRIGHT_MODULE` to its
module entry file, for example `/path/to/node_modules/playwright/index.mjs`.
`PLAYWRIGHT_CHROMIUM` optionally selects an existing Chromium executable:

```sh
PLAYWRIGHT_MODULE=/path/to/node_modules/playwright/index.mjs \
PLAYWRIGHT_CHROMIUM=/path/to/chrome \
node launcher/tools/gui-regression.mjs http://127.0.0.1:1440/
```

This browser check complements the native check; Chromium does not exercise
AppKit fullscreen or WKWebView behavior. No Python HTTP server or local-network
discovery is needed by either check.

For startup animation checks, use `npm run dev -- --host 127.0.0.1 --port 1440`
instead of the bundled preview, then run `node launcher/tools/intro-regression.mjs`
from the repository root (the same Playwright environment overrides apply).
It observes the actual island target API in the unbundled production modules
with real WebGL, exercising repeated opens, a hidden startup window, layout
refreshes, blocked/error recovery and Reduce Motion. It does not invoke games
or the real updater. Native animation still requires the manual GUI check above.

`node launcher/tools/menu-toggle-regression.mjs` checks repeated pointer clicks
on the Launch arrow and setup chooser, a controlled button-focus-loss sequence,
touch taps, keyboard opening/Escape and outside-click dismissal. It works with
either loopback dev server or bundled preview and uses the same Playwright
environment overrides.

`node launcher/tools/dashboard-regression.mjs` checks home/dashboard title and
Preview badge parity, stable/preview channel toggles, minimum-window rosters and
scroll reachability, message centering, and repeated native-inset changes across
both fonts and 1×/2× display scales. It uses the same browser/loopback overrides
as the other regression scripts and exercises production fixture routing.
