# UI dev loop (`cobdev`)

Edit Cobblify's UI and see it in the real game within seconds, without building a jar or
relaunching Minecraft. Agents drive it end to end: hot-swap code, open screens, click, take
screenshots.

## How it works

- The **dev client** is Minecraft 1.8.9 + Forge with this checkout's classes, run on an Intel
  Java 8 through Rosetta (LWJGL 2 has no Apple Silicon natives). It runs in the **user's own
  macOS account**: the `agent` account cannot open windows.
- `/Users/agent` is private, so `cobdev stage` copies everything the game needs to
  `/Users/Shared/cobblify-dev` (owned by `agent`, world-readable).
- `start.sh` (user's account) launches the game with `devagent.jar`, a Java agent that serves a
  control API on `127.0.0.1:47821`. It relaunches the game whenever an agent asks for a restart.
- `cobdev swap` compiles, copies changed classes into the running game's class directory, and
  redefines them in place. Changes inside existing methods apply live; new fields or methods,
  added or removed lambdas, and mixin changes need a restart, which `swap` does on its own.

Nothing here ends up in the mod jar. No build file is edited: `devloop.init.gradle` is passed with
`--init-script`.

## One-time setup

1. Agent: `tools/devloop/cobdev setup`, then `tools/devloop/cobdev stage`.
2. User, in a terminal pane in their own account (leave it open):

   ```
   /Users/Shared/cobblify-dev/bin/start.sh             # offline dev account
   /Users/Shared/cobblify-dev/bin/start.sh --hypixel   # DevAuth login, to test on Hypixel
   ```

## Agent workflow

```
cobdev status                 # who holds the lock, what is staged and running
cobdev open settings          # or: hud, or a com.bedwarsqol.* GuiScreen class (--arg N)
cobdev shot                   # PNG of the last frame; prints the path and the GUI scale
# edit code
cobdev swap                   # ~seconds; restarts the game itself if a live swap is impossible
cobdev click 120 80           # GUI coordinates = screenshot pixels / scale factor
cobdev shot --at 120 80       # hover first, then capture
```

Also: `down`, `up`, `drag`, `scroll X Y N` (positive = down), `hover X Y` / `hover off`,
`type TEXT`, `key esc|enter|backspace|…|CODE`, `scale 0-4`, `reinit`, `close`, `world` (flat
creative world "cobdev"), `refresh` (resources), `restart`, `log [N]`.

Run `tools/devloop/cobdev` from any checkout, or `/Users/Shared/cobblify-dev/bin/cobdev` from a
branch that predates this tool.

## Rules

- **One session drives the game at a time.** The first mutating command takes a lock for its
  worktree; another worktree gets an error until the lock is released (`cobdev unlock`) or taken
  over (`--steal`, only when that session is finished).
- Input and `open` only reach Cobblify screens (`com.bedwarsqol.*`), and are refused while the game
  is connected to a multiplayer server. Screenshots and hot-swap still work there.
- The window shows Forge's code as built here, not Lunar. Port UI changes to `lunar/` as usual
  (`tools/check-tree-drift.sh`).
- Screens and HUD elements with example data: `cobdev open hud` shows the HUD editor's preview.
