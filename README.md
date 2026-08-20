# Cobblify

A Hypixel BedWars quality-of-life mod for **Minecraft 1.8.9** on both **Forge**
and **Lunar Client**, featuring several modules and a custom GUI.

Adheres to the [Hypixel Allowed Modifications](https://support.hypixel.net/hc/en-us/articles/6472550754962-Hypixel-Allowed-Modifications)
policy — no automation of player actions, no unfair advantages.

## Install

Official builds have the maintainer's stats backend built in and are distributed
privately by the maintainer - ask for the latest build. The public
**[Releases](../../releases)** contain blank jars with no backend baked in; stats
on those require self-hosting - see
[Advanced: self-hosting the stats backend](#advanced-self-hosting-the-stats-backend).

### Forge

1. Have a **Minecraft Forge 1.8.9** instance.
2. Drop `Cobblify-1.8.9-forge-<version>.jar` into your instance's `mods/` folder. That's it - stats work out of the box.
3. Launch the game. Press **Right Shift** (or run `/cobblify`) to open the settings (bind can be changed in minecraft settings).



### Lunar Client

1. Extract the `Cobblify-Lunar-<version>.zip` bundle into a fresh, empty folder.
2. Run the installer once (`Install BedwarsQOL (Lunar).command` on Mac, `.bat` on Windows). It copies the Weave loader + mod into place and creates a **Launch Lunar (Cobblify)** launcher next to it and on your Desktop.
3. From then on, always start the game with **Launch Lunar (Cobblify)** (fully quit Lunar first if it is already open). No Lunar settings need changing.
4. Remaining manual steps: log into Lunar, pick **1.8.9**, and turn **Waypoints OFF** inside your active Lunar settings profile. Press **Right Shift** (or run `/cobblify`) in-game to open the settings.

## Advanced: self-hosting the stats backend

Only for people running their own Worker - that is, using a blank public
Release jar or a from-source build instead of an official build. Hypixel stats
are served by a tiny **Cloudflare Worker you self-host** (free, private). Use
the installer.

1. Run the installer for your OS (clone/download this repo, or get it from **[Releases](../../releases/latest)**):
  - **Windows:** double-click `[installers/setup-windows.bat](installers/setup-windows.bat)`
  - **Mac:** double-click `[installers/setup-mac.command](installers/setup-mac.command)`
2. Follow the prompts. A browser opens to log in or sign up for Cloudflare (free). If Cloudflare asks you to register a `workers.dev` name, type anything (e.g. your Minecraft name). The script deploys the Worker, locks it with a private token, and prints two chat commands (the first is copied to your clipboard).
3. In Minecraft, paste both commands into chat (`/cobblify statsurl ...` and `/cobblify statstoken ...`), then enable **Hypixel Stats** in the mod GUI (Right Shift).

`/cobblify statsurl` and `/cobblify statstoken` point the mod at your own
Worker; on official builds they are unnecessary (leave them unset to use the
built-in backend). See `server/stats-worker/README.md` for Worker details.

## Features: Cobblify — Features



### HUD (draggable/resizable)

- **Potion** - active potion effects + timers
- **Armor** - your equipped armor
- **Inventory** - your stored items at a glance
- **Gen Timers** - diamond/emerald spawn countdowns
- **Keystrokes** - WASD + spacebar display

  **NOTE** - most HUD modules have an "In Game Only" option = show only during a BedWars game

### Combat

- **Hand Position** - move/resize your held item (X / Y / Z / Scale)
- **TNT Countdown** - fuse timer over nearby TNT (adjustable radius)
- **Disable Esc Menu** - stop Esc opening the pause menu mid-combat



### Visuals

- **Block Overlay** - highlight the block you look at (style / color / opacity / see-through)
- **Tab Numeric Ping** - show latency as "123ms" instead of signal bars
- **Hide Tab Header/Footer** - hide the server's tab header/footer text
- **Tab + Scoreboard size scaling** - in setting



### AntiSnipe (opponent intel — needs the stats backend)

- **Hypixel Stats** - opponents' BedWars stats on nametag + tab (level, rank, FKDR, WLR)
- **Party Report** - announce flagged/sweaty enemies to party chat once per game



### Other

- **Auto GG** - say "gg" in chat once each time a BedWars game ends
- Practice/Debug : spawn clientside practice dummies
- Custom settings GUI (Right Shift or /cobblify), GUI scale + color themes



## Showcase



### GUI

Cobblify demo

### HUD Editor

Cobblify demo

## Build from source (for developers)

Requires JDK 21 (Temurin recommended) for Gradle.

**Forge:**

```sh
./gradlew build
```

Output: `versions/1.8.9-forge/build/libs/Cobblify-1.8.9-forge-<version>.jar`
(the remapped production jar; ignore the `-dev.jar`, which is for the `runClient` dev environment).

**Lunar Client (Weave):**

```sh
cd lunar && ./gradlew build
```

Output: `lunar/build/libs/Cobblify-Lunar-<version>.jar`.

## Credits

- [Tabler Icons](https://tabler.io/icons) (MIT) — settings nav icons.
- [Inter](https://rsms.me/inter/) (SIL OFL) — UI font.



## License

MIT — see [LICENSE](./LICENSE).