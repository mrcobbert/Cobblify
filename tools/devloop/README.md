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
```

`hud-boxes` returns `{scale, sw, sh, boxes, states, editor}`: every `getEditorBoxes` box (content box
and `pad`, GUI px, `shown`), every module's stored state (`x, y, fracX, fracY, anchor, scale, enabled,
background`), and, while `EditHudGui` is open, its `selectedId`, `menuId`, `mode`, `tabHeld`,
`playerListPressed` (`keyBindPlayerList.isKeyDown()`), `visibleIds` and the menu rows with GUI rects.
It reads Cobblify by reflection, so a field a build does not have yet comes back as null.

## Smoke checks

`cobdev smoke [--prod] [--boot-only]` runs PLAN Verification A3-A6 plus the Clutch Practice check
and the Edit HUD step `A4-edithud` (its E-checks, from E0: the probe on an open editor) against the
running game and writes every command, status, shot path and pass/fail per check to
`~/.cache/cobdev/smoke/smoke-<time>-<mode>.json`. It stages and restarts into the requested mode
first (`--no-stage` reuses a running game of that mode); `--prod` switches back to dev afterwards
unless `--keep`. Steps that need code the build does not have yet report "not available yet"
instead of failing. Exit status is 1 if any check failed.

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
