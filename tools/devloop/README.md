# UI dev loop (`cobdev`)

Edit Cobblify's UI and see it in the real game within seconds, without building a jar or
relaunching Minecraft. Agents drive it end to end: hot-swap code, open screens, click, take
screenshots, and run the in-game smoke checks.

## How it works

- The **dev client** is the `ornithe/` project's Minecraft 1.8.9 client: Fabric Loader (Knot,
  started through DevLaunchInjector) with Pylon (LWJGL 3 + SDL3 behind the LWJGL 2 API) on
  **Java 25 arm64** (`/Library/Java/JavaVirtualMachines/temurin-25.jdk`). It runs in the **user's
  own macOS account**: the `agent` account cannot open windows.
- `/Users/agent` is private, so `cobdev stage` copies everything the game needs to
  `/Users/Shared/cobblify-dev` (owned by `agent`, world-readable). Each stage is a self-contained
  runtime: java binary, JVM flags, classpath, main class, mode and the agent jar all live in
  `runtimes/<name>/launch/`, so `start.sh` never changes when the launch does.
- `start.sh` (user's account) launches whatever is staged and relaunches it whenever an agent asks
  for a restart, including switches between dev and prod mode. The user starts it **once**.
- Two halves, both dev-only and never in the mod jar:
  - `agent/` - a Java agent (`-javaagent`): the control API on `127.0.0.1:47821`, hot-swap
    (`redefineClasses`), logs and restarts. Pure JDK: under Knot, LWJGL/Pylon/Minecraft load
    child-first, so the agent must never touch them itself.
  - `bridge/` - a small Fabric mod (`cobdev-bridge`) with everything that touches Minecraft. It is
    compiled against the ornithe project's MCP-named classpath (names are compile-checked), remapped
    to intermediary, and loaded with `-Dfabric.addMods`. The agent reaches it through the system
    property `cobdev.bridge` (a `BiFunction`, JDK types only).
- `devloop.init.gradle` is passed with `--init-script`: it adds the bridge source set, the dev
  runtime mods (`modLocalRuntime`) and `cobdevDump`. No build file is edited.
- `cobdev swap` compiles, copies changed classes into the running game's class directory, and
  redefines them in place. Changes inside existing methods apply live; new fields or methods,
  added or removed lambdas, mixin changes and `fabric.mod.json`/mixin config edits need a restart,
  which `swap` does on its own.

### Modes

| | dev (default) | prod (`--prod`) |
|---|---|---|
| Cobblify | this worktree's classes (hot-swap works) | the release jar, `ornithe/build/libs/Cobblify-1.8.9-ornithe-<ver>.jar`, built by a plain `./gradlew remapJar` |
| Names | MCP (Loom dev run) | intermediary (production Fabric layout, vanilla client jar) |
| Runtime mods | Pylon, Sarcio, Slipstream (Loom-remapped) + OSL modules from the build | mods folder: Pylon, Argentum, Sarcio, Slipstream, full OSL 0.23.1 |
| Catches | UI and behaviour | remap/refmap/mixin failures, Argentum interplay |

Argentum is prod-only: Loom cannot remap it to MCP names (`ParticleCaptureBuffer` declares
`reset()` and also overrides a vanilla method MCP names `reset()`: "unfixable conflicts").
The pinned jars (CONTRACT "Pinned runtime mods") are downloaded once by `cobdev setup` into
`/Users/Shared/cobblify-dev/cache/mods` and kept only if size and sha512 match. `stage --prod`
refuses a release jar that contains bridge classes or nested runtime mods (`cobdev jarcheck`).

## One-time setup

1. Agent: `tools/devloop/cobdev setup`, then `tools/devloop/cobdev stage`.
2. User, in a terminal pane in their own account (leave it open):

   ```
   /Users/Shared/cobblify-dev/bin/start.sh             # offline dev account
   /Users/Shared/cobblify-dev/bin/start.sh --hypixel   # DevAuth (fabric) login, to test on Hypixel
   ```

   A running `start.sh` keeps the old script; after `cobdev setup` changes it, the user restarts it
   once.

## Agent workflow

```
cobdev status                 # who holds the lock, what is staged and running (and its mode)
cobdev open settings          # or: hud, or a com.bedwarsqol.* GuiScreen class (--arg N)
cobdev shot                   # PNG of the last frame; prints the path and the GUI scale
# edit code
cobdev swap                   # ~seconds; restarts the game itself if a live swap is impossible
cobdev click 120 80           # GUI coordinates = screenshot pixels / scale factor
cobdev shot --at 120 80       # hover first, then capture
```

Also: `down`, `up`, `drag`, `scroll X Y N` (positive = down), `hover X Y` / `hover off`,
`type TEXT`, `key esc|enter|backspace|…|a-z|0-9|CODE`, `scale 0-4`, `size W H` (dev mode only),
`reinit`, `close`, `world` (flat creative world "cobdev", clouds and view bobbing off),
`refresh` (resources), `restart [--prod]`, `log [N]`.

Precise input (Edit HUD checks):

```
cobdev drag 200 90 --mods alt          # modifier keys held for this one event: alt, shift, ctrl
cobdev key z --mods ctrl               # ctrl holds Ctrl and Cmd (GuiScreen.isCtrlKeyDown reads Cmd on macOS)
cobdev down 640 360 --device           # display pixels from the top-left (down, up, drag): one screen pixel
cobdev drag 641 360 --device           #   per step at any GUI scale
cobdev keyhold key.playerlist on --raw # hold the key's keyboard state, never the binding
cobdev keyhold key.playerlist off --raw
```

`--mods` writes the modifier bytes into `Keyboard.keyDownBuffer` right before the injected event and
puts them back right after it. A raw hold writes the keyboard state every few ms (and Pylon's SDL-side
copy that `poll()` reads each frame) and never calls `setKeyBindState`, on or off, so a screen sees
exactly what a real press and release produce while it is open. Raw holds are for Cobblify's bindings,
`key.playerlist` and `key.forward/left/back/right/jump`, on keyboard keys only.

State and probes (singleplayer only):

```
cobdev field com.bedwarsqol.BedwarsQol.config.keystrokesEnabled true   # read (no value) or set
cobdev invoke com.bedwarsqol.feature.ClutchRules.damage 3.0
cobdev has com.bedwarsqol.feature.ClutchPractice                       # is it in this build?
cobdev command "/time set 6000"
cobdev keyhold                                                         # list Cobblify's key bindings
cobdev keyhold Zoom on        # held until "off" (re-applied every few ms)
cobdev probe fov              # EntityRenderer.getFOVModifier(1, true), read-only
cobdev probe hurt             # hurtTime = maxHurtTime = 10 on the local player
cobdev probe player           # health, motion, hurtResistantTime, position (client + server)
cobdev probe block 3 0 1 [minecraft:wool]          # read or place one block near the player
cobdev probe clutch-blast 3 0 0                    # Clutch Practice's own blast, server thread
cobdev probe use-item [minecraft:fire_charge]      # server-side right-click with the held item
cobdev probe hud-boxes        # Edit HUD boxes, module states and the open editor's selection/menu rects
cobdev probe sheet-node resetPositions             # where the open settings sheet draws a node (GUI + display px)
cobdev probe sheet-node keystrokes MODULE_TOGGLE   # TYPE picks between nodes sharing an id (header vs switch)
cobdev probe open-shop full                        # a chest window from bridge/resources/team-upgrades-full.json
```

`hud-boxes` returns `{scale, sw, sh, boxes, states, editor}`: every `getEditorBoxes` box (content box
and `pad`, GUI px, `shown`), every module's stored state (`x, y, fracX, fracY, anchor, scale, enabled,
background`), and, while `EditHudGui` is open, its `selectedId`, `menuId`, `mode`, `tabHeld`,
`playerListPressed` (`keyBindPlayerList.isKeyDown()`), `visibleIds` and the menu rows with GUI rects.
It also reports `gui` (`[sw, sh]`) and `scaleFactor`, which the Team Upgrades steps read.
It reads Cobblify by reflection, so a field a build does not have yet comes back as null.

Overlay items (the scoreboard, chat and tab list as HUD items), also singleplayer only:

```
cobdev probe overlay-boxes                  # scoreboard, chat, tablist boxes (GUI px), scale factor, GUI size
cobdev probe place-item chat 4 4            # put an item's box at GUI (x, y), as Edit HUD does
cobdev probe chat-fixture overlays          # prints bridge/resources/chat-overlays.json (link, hover, wrap)
cobdev probe chat-clear
cobdev probe chat-open                      # vanilla's chat screen
cobdev probe chat-at 40 300                 # what a click there hits, and what Copy Chat would copy (read-only)
cobdev probe chat-click 40 300              # a left click in vanilla's chat screen only; reports screenAfter
cobdev probe tab-header-footer "H" "F"      # or: tab-header-footer clear
cobdev probe chat-heights 1.0 1.0           # vanilla's chat height sliders this session; or: chat-heights restore
cobdev probe chat-defaults                  # vanilla's default chat width, scale, heights, opacity (restore as above)
```

`chat-click` is the one input that reaches a vanilla screen: it is refused unless vanilla's chat screen is
open, is a left click only (a right click is Copy Chat, which would overwrite the clipboard), and is refused
while Chat Links Prompt is off (a link click would open the browser).

The smoke also leaves the world through the bridge (`/world` with `leave=1`, its own `cobdev` save only)
to get a real world load; `cobdev world` opens it again.

## Smoke checks

`cobdev smoke [--prod] [--boot-only] [--only overlays|team-upgrades]` runs PLAN Verification A3-A6, the
Clutch Practice check, the HUD look step `HL`, the overlay steps `OV-*`, the Team Upgrades steps (synthetic
Bed Wars scoreboard, chat lines, titles and shop windows, then an Edit HUD sweep over window sizes, GUI scales
and HUD Size that checks Team Upgrades' default spot against every switched-on module and the hotbar, with the
profile's modules and again with all of them on, where only a screen whose other modules already overlap one
another is a note, not a failure) and the Edit HUD step `A4-edithud` (its E-checks, from E0: the probe on an
open editor) against the running game and writes every command, status, shot path and pass/fail per check to
`~/.cache/cobdev/smoke/smoke-<time>-<mode>.json`. It stages and restarts into the requested mode
first (`--no-stage` reuses a running game of that mode); `--prod` switches back to dev afterwards
unless `--keep`. Steps that need code the build does not have yet report "not available yet"
instead of failing. Exit status is 1 if any check failed.

The HUD look step (`HL`, cycle 2026-11-14-hud-look) follows the HUD check in `cobdev smoke`. In the `cobdev`
world at GUI scale 2, Minecraft font, Pale Blue accent and HUD Size Medium, it turns on one module at a time and finds
it by diffing frames. With its Background on, each module must draw Lunar's see-through panel: every pixel of a ring
just inside the panel equals the backdrop darkened by alpha 0x6F (Keystrokes: one pixel in each cap). With it off,
that ring equals the backdrop and the module's content is still drawn. Sub-checks: `HL-bg` (every module),
`HL-persist` (an Off survives a restart), `HL-potion` (rows, blink), `HL-mapinfo` (accent labels), `HL-keys`
(pressed cap in the editor), `HL-text` (FPS/CPS/Ping boxes, divider, bare text, Auto layout), `HL-edit` (drag, resize,
Delete), `HL-scale` (module scale 0.3 at GUI 1, 0.75 at GUI 2), `HL-font` (Modern font shadows) and the modern
potion icons, `HL-icons-cold` then `HL-icons-warm`. Those two restart the game and point
`EffectIconTextures.cacheDirOverride` at a new `/private/tmp/cobblify-smoke-icons-*` directory before the loader
exists, so the cold run makes one real fetch (NETWORK, 1 request, 23 icons) and the warm run none (CACHE). The real
icon cache is never used for them. The step saves every setting it touches and restores it at the end.

`cobdev smoke --only overlays` runs the boot and world steps, then only the overlay item checks (OV-scoreboard,
OV-tab, OV-tabhide, OV-chat, OV-chatclick): drawn bounding boxes against `probe overlay-boxes` at three
placements, Background fill ratios against the plain-sky backdrop, chat rows closed/open, the Tab-hides-HUD
frame comparison with `TabHudGate.mismatchFrames`, and scripted chat clicks. The full smoke runs them too.

`cobdev smoke --only rewind` runs the boot and world steps, then `RW` (Rewind, PLAN A11): with Rewind and Record
Games on it rejoins the smoke world, walks for a second, presses the Marker Key (`invoke
com.bedwarsqol.rewind.Rewind.markerKeyPressed`), leaves, and checks the newest closed recording with
`probe rewind-latest` (RewindCheck's counts only; pass = complete, no torn tail, NET and TICK records, a KEY marker,
every segment starting at its boundary packet). Every staged runtime records into its own folder,
`-Dcobblify.rewindDir=cobdev-rewind`, relative to the game's run folder in the user's account, never
`~/.cobblify/rewind`; `probe rewind-clear` deletes the closed recordings there and refuses without that flag. Both
Rewind probes work without a world (never on a server).

`cobdev selftest` runs the checks that need no game: the bridge's refusal rules
(`bridge/test/.../PolicyTest.java`), image and launch-config helpers (`test_cobdev.py`), and the
release-jar check.

Run `tools/devloop/cobdev` from any checkout with the `ornithe/` project, or
`/Users/Shared/cobblify-dev/bin/cobdev` from a branch that predates this tool. Forge-era runtimes
staged by an older cobdev still start with the new `start.sh` (Intel Java 8 and the old agent at
`/Users/Shared/cobblify-dev/devagent.jar`, which setup leaves alone).

## Rules

- **One session drives the game at a time.** The first mutating command takes a lock for its
  worktree; another worktree gets an error until the lock is released (`cobdev unlock`) or taken
  over (`--steal`, only when that session is finished).
- Input, `open`, `field`, `invoke` and `keyhold` only reach Cobblify (`com.bedwarsqol.*`, never its
  mixins, key bindings in the "Cobblify" category; a raw hold may also set the keyboard state of Tab
  and the five movement keys), and are refused while the game is connected to a multiplayer server. `command` and `probe` need the singleplayer world. Screenshots and
  hot-swap still work on a server.
- Input is injected into Pylon's mouse state (not the SDL event queue) and never moves the user's
  real pointer. Real device input, IME and focus behaviour are not exercised.
- The window shows the Ornithe build, not Lunar. Port UI changes to `lunar/` as usual
  (`tools/check-tree-drift.sh`).
- Screens and HUD elements with example data: `cobdev open hud` shows the HUD editor's preview.
