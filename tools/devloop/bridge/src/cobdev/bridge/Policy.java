package cobdev.bridge;

/**
 * What the dev loop may touch. Pure rules with no Minecraft types, so {@code cobdev selftest} can
 * check every refusal path without a game. Each check returns {@code null} when allowed, otherwise
 * the message the control API sends back with HTTP 409.
 */
final class Policy {

    static final String MOD_PREFIX = "com.bedwarsqol.";
    static final String MIXIN_PREFIX = "com.bedwarsqol.mixin.";
    /** Key bindings Cobblify registers use this category (CONTRACT: KeyBinding("Zoom", KEY_C, "Cobblify")). */
    static final String KEY_CATEGORY = "Cobblify";
    static final int PROBE_REACH = 16;

    static final String ON_SERVER = "you are on a multiplayer server; agent input is off there"
            + " (screenshots and hot-swap still work)";

    private Policy() {
    }

    /** Input, screens, field/invoke and keyhold: anything but a multiplayer server. */
    static String local(boolean inWorld, boolean singleplayer) {
        return inWorld && !singleplayer ? ON_SERVER : null;
    }

    /**
     * {@link #local(boolean, boolean)}, except that a Rewind recording playing through its socketless connection
     * ({@link #rewindPlayback}) counts as local: its world is not singleplayer, but there is no server behind it.
     */
    static String local(boolean inWorld, boolean singleplayer, boolean rewindPlayback) {
        return rewindPlayback ? null : local(inWorld, singleplayer);
    }

    /**
     * A Rewind recording is playing in the client: the session is active, the client has a world that is not
     * singleplayer, and the connection's remote address is not a network address (Rewind's playback channel is a
     * Netty embedded channel; every real server connection has an internet address).
     */
    static boolean rewindPlayback(boolean sessionActive, boolean inWorld, boolean singleplayer,
                                  boolean networkAddress) {
        return sessionActive && inWorld && !singleplayer && !networkAddress;
    }

    /** Commands and probes act on a world, so they need the integrated (singleplayer) server. */
    static String singleplayerWorld(boolean inWorld, boolean singleplayer, boolean integratedServer) {
        if (inWorld && !singleplayer) return ON_SERVER;
        if (!inWorld) return "this needs the singleplayer world first (cobdev world)";
        if (!integratedServer) return "this only runs in singleplayer (no integrated server is running)";
        return null;
    }

    /** {@code field}, {@code invoke} and {@code has} reach Cobblify classes only, never its mixins. */
    static String modClass(String className) {
        if (className == null || !className.matches("[A-Za-z0-9_$.]+")) return "bad class name: " + className;
        if (!className.startsWith(MOD_PREFIX)) return "only com.bedwarsqol.* classes are reachable: " + className;
        if (className.startsWith(MIXIN_PREFIX)) return "mixin classes are not reachable: " + className;
        return null;
    }

    /** Every object a field path walks through (and every invoke target) must be Cobblify's own. */
    static String modObject(Class<?> type) {
        String err = modClass(type.getName());
        return err == null ? null : "the path leaves Cobblify's classes at " + type.getName();
    }

    /** The vanilla Tab key: holding it in a local world shows the tab list for the Tab List Size checks. */
    static final String PLAYER_LIST_KEY = "key.playerlist";

    static String keyBinding(String category, String description) {
        return keyBinding(category, description, false);
    }

    /** The movement keys whose keyboard state a raw hold may set (Edit HUD's live Keystrokes preview). */
    static final java.util.List<String> RAW_MOVEMENT_KEYS = java.util.Arrays.asList(
            "key.forward", "key.left", "key.back", "key.right", "key.jump");

    /**
     * {@code raw} holds the key's keyboard state (what {@code Keyboard.isKeyDown} and
     * {@code GameSettings.isKeyDown} read) and never the binding, so a screen sees exactly what a real
     * press and release produce. It also allows the five movement keys: inside a screen their state
     * moves nothing, because a screen leaves the bindings unpressed.
     */
    static String keyBinding(String category, String description, boolean raw) {
        if (KEY_CATEGORY.equals(category)) return null;
        if ("key.categories.multiplayer".equals(category) && PLAYER_LIST_KEY.equals(description)) return null;
        if (raw && "key.categories.movement".equals(category) && RAW_MOVEMENT_KEYS.contains(description)) return null;
        return raw
                ? "raw holds are for Cobblify's key bindings, the Tab key and " + RAW_MOVEMENT_KEYS
                : "only key bindings Cobblify registers (category " + KEY_CATEGORY + ") and the Tab key can be held";
    }

    /** A raw hold writes the keyboard state, so the binding must be on a keyboard key (mouse codes are negative). */
    static String rawKeyCode(String name, int code) {
        if (code > 0 && code < 256) return null;
        return name + " is not bound to a keyboard key (code " + code + "); a raw hold needs one";
    }

    /**
     * {@code --mods}: the LWJGL key codes held around one injected event. Only alt (LMENU), shift (LSHIFT)
     * and ctrl, which holds both LCONTROL and LMETA because {@code GuiScreen.isCtrlKeyDown} reads Cmd on
     * macOS. Refused on a multiplayer server like every other input.
     */
    static int[] modifiers(String mods, boolean inWorld, boolean singleplayer) {
        return modifiers(mods, inWorld, singleplayer, false);
    }

    /** {@link #modifiers(String, boolean, boolean)}, also allowed while a Rewind recording plays. */
    static int[] modifiers(String mods, boolean inWorld, boolean singleplayer, boolean rewindPlayback) {
        Refused.check(local(inWorld, singleplayer, rewindPlayback));
        java.util.LinkedHashSet<Integer> codes = new java.util.LinkedHashSet<>();
        if (mods == null || mods.trim().isEmpty()) return new int[0];
        for (String raw : mods.split(",", -1)) {
            String m = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (m.equals("alt")) {
                codes.add(56);
            } else if (m.equals("shift")) {
                codes.add(42);
            } else if (m.equals("ctrl")) {
                codes.add(29);
                codes.add(219);
            } else {
                throw new Refused("--mods takes alt, shift and ctrl (comma-separated), not '" + raw + "'");
            }
        }
        int[] out = new int[codes.size()];
        int i = 0;
        for (int c : codes) out[i++] = c;
        return out;
    }

    /** {@code --device}: display pixels from the top-left, inside the display. */
    static String device(int x, int y, int displayWidth, int displayHeight) {
        if (x >= 0 && y >= 0 && x < displayWidth && y < displayHeight) return null;
        return "(" + x + "," + y + ") is outside the " + displayWidth + "x" + displayHeight + " display";
    }

    /** Holding a key needs a local world; releasing ("off") is always allowed, wherever the client is. */
    static String keyhold(String state, boolean inWorld, boolean singleplayer) {
        return keyhold(state, inWorld, singleplayer, false);
    }

    /** {@link #keyhold(String, boolean, boolean)}; a Rewind recording playing counts as local. */
    static String keyhold(String state, boolean inWorld, boolean singleplayer, boolean rewindPlayback) {
        return "off".equals(state) ? null : local(inWorld, singleplayer, rewindPlayback);
    }

    /** Checked on every tick of a hold: once the client is on a multiplayer server, every hold ends. */
    static boolean keepHolding(boolean inWorld, boolean singleplayer) {
        return keepHolding(inWorld, singleplayer, false);
    }

    /** {@link #keepHolding(boolean, boolean)}; a Rewind recording playing keeps holds (the free camera flies). */
    static boolean keepHolding(boolean inWorld, boolean singleplayer, boolean rewindPlayback) {
        return local(inWorld, singleplayer, rewindPlayback) == null;
    }

    /**
     * {@code block DX DY DZ [ID]} (relative to the player's feet) or {@code block at X Y Z [ID]}
     * (absolute, so a check after a blast reads the block it placed before, wherever the player went).
     * Either way the target must be within {@link #PROBE_REACH} of the feet.
     */
    static int[] blockTarget(java.util.List<String> args, int feetX, int feetY, int feetZ) {
        boolean at = !args.isEmpty() && "at".equals(args.get(0));
        int base = at ? 1 : 0;
        int n = args.size() - base;
        if (n != 3 && n != 4) throw new Refused("probe block DX DY DZ [BLOCK_ID] or block at X Y Z [BLOCK_ID]");
        int[] p = new int[3];
        try {
            for (int i = 0; i < 3; i++) p[i] = Integer.parseInt(args.get(base + i));
        } catch (NumberFormatException e) {
            throw new Refused("block coordinates are integers");
        }
        if (!at) {
            p[0] += feetX;
            p[1] += feetY;
            p[2] += feetZ;
        }
        Refused.check(near(p[0] - feetX, p[1] - feetY, p[2] - feetZ));
        return p;
    }

    /** The block id to place, or null for a read. */
    static String blockId(java.util.List<String> args) {
        int base = !args.isEmpty() && "at".equals(args.get(0)) ? 1 : 0;
        return args.size() - base == 4 ? args.get(args.size() - 1) : null;
    }

    static String command(String command) {
        if (command == null || !command.startsWith("/")) return "a command starts with /";
        if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) return "one command per call";
        if (command.length() > 100) return "commands are limited to 100 characters (vanilla chat limit)";
        return null;
    }

    static String near(int dx, int dy, int dz) {
        if (Math.abs(dx) > PROBE_REACH || Math.abs(dy) > PROBE_REACH || Math.abs(dz) > PROBE_REACH) {
            return "probes reach at most " + PROBE_REACH + " blocks from the player";
        }
        return null;
    }

    /** Probe names the bridge answers; anything else is refused before touching the game. */
    static String probeName(String name) {
        switch (name == null ? "" : name) {
            case "fov":
            case "hurt":
            case "player":
            case "block":
            case "clutch-blast":
            case "use-item":
            case "open-shop":
            case "hud-boxes":
            case "sheet-node":
                return null;
            default:
                return OVERLAY_PROBES.contains(name) || MOTION_BLUR_PROBES.contains(name)
                        || REWIND_PROBES.contains(name) || REWIND_WATCH_PROBES.contains(name) ? null
                        : "unknown probe " + name + " (fov, hurt, player, block, clutch-blast, use-item, open-shop, hud-boxes, "
                        + "sheet-node, " + String.join(", ", OVERLAY_PROBES) + ", "
                        + String.join(", ", MOTION_BLUR_PROBES) + ", " + String.join(", ", REWIND_PROBES) + ", "
                        + String.join(", ", REWIND_WATCH_PROBES) + ")";
        }
    }

    // ------------------------------------------------------------------ Rewind (cycle 2026-11-17-rewind-record)

    /**
     * Rewind's probes: {@code rewind-latest} (RewindCheck's counts for the newest closed recording) and
     * {@code rewind-clear} (deletes the closed recordings of cobdev's own folder). Neither needs a world (the step
     * reads the recording after leaving it), but both refuse on a multiplayer server like every other probe.
     */
    static final java.util.List<String> REWIND_PROBES = java.util.Arrays.asList("rewind-latest", "rewind-clear");

    /** The system property cobdev's launch sets to the dev client's own Rewind folder. */
    static final String REWIND_DIR_PROPERTY = "cobblify.rewindDir";

    /** Recording file names, exactly as Rewind's pruning matches them; nothing else is ever deleted. */
    private static final java.util.regex.Pattern REWIND_FILE =
            java.util.regex.Pattern.compile("^\\d{8}-\\d{6}-[0-9a-f]{4}\\.(cbr|json|stats\\.json)$");

    /**
     * {@code rewind-clear} only runs in a game cobdev launched with its own folder ({@code -Dcobblify.rewindDir}),
     * so it can never reach the player's real {@code ~/.cobblify/rewind}.
     */
    static String rewindClear(String overrideProperty, String resolvedDir, String userHome) {
        return rewindOwnFolder("rewind-clear", overrideProperty, resolvedDir, userHome);
    }

    /**
     * {@code probe} (rewind-clear, rewind-watch-open, rewind-watch-stage) only runs in a game cobdev launched with its
     * own folder, never the player's {@code ~/.cobblify/rewind}.
     */
    static String rewindOwnFolder(String probe, String overrideProperty, String resolvedDir, String userHome) {
        if (overrideProperty == null || overrideProperty.trim().isEmpty()) {
            return probe + " needs the dev client's own folder (-D" + REWIND_DIR_PROPERTY + ", set by cobdev stage)";
        }
        if (resolvedDir == null) return probe + ": no Rewind folder";
        String real = new java.io.File(new java.io.File(userHome == null ? "" : userHome, ".cobblify"), "rewind")
                .getAbsolutePath();
        if (new java.io.File(resolvedDir).getAbsolutePath().equals(real)) {
            return probe + " never touches the player's own Rewind folder";
        }
        return null;
    }

    /** True for a file name {@code rewind-clear} may delete. */
    static boolean rewindFile(String name) {
        return name != null && REWIND_FILE.matcher(name).matches();
    }

    // ------------------------------------------------------------------ Rewind watch (cycle 2026-11-17-rewind-watch)

    /**
     * Step RWW's probes. {@code rewind-watch-swing ITEM} (singleplayer: hotbar slot 2 holds ITEM and the player swings
     * once, before RW records); {@code rewind-watch-open} (the newest closed recording of cobdev's own folder);
     * {@code rewind-watch-state}; {@code rewind-watch-seek TICK} or {@code rewind-watch-seek by MS};
     * {@code rewind-watch-play-to TICK} (plays at 4x and pauses exactly at TICK); {@code rewind-watch-snapshot} (hashes
     * and counts of the seek-equivalence contract's seven groups); {@code rewind-watch-quit} (Quit to Title);
     * {@code rewind-watch-stage DIR} (copies recording files from cobdev's staging folder into its own Rewind folder,
     * for the timings on a real game). Counts and hashes only: no id, name, chat, map or position leaves the game.
     */
    static final java.util.List<String> REWIND_WATCH_PROBES = java.util.Arrays.asList("rewind-watch-swing",
            "rewind-watch-open", "rewind-watch-state", "rewind-watch-seek", "rewind-watch-play-to",
            "rewind-watch-snapshot", "rewind-watch-quit", "rewind-watch-stage");
    /** The longest seek {@code rewind-watch-seek by MS} takes, either way: a day. */
    static final long REWIND_MAX_SEEK_MS = 24L * 3600 * 1000;
    /** The staging folder's tail under cobdev's shared folder (cobdev writes it, the game reads it). */
    static final String REWIND_STAGE_TAIL = "/cobblify-dev/cache/rewind-stage";

    /** {@code rewind-watch-seek TICK} -> {0, tick}; {@code rewind-watch-seek by MS} -> {1, microseconds}. */
    static long[] rewindSeek(java.util.List<String> args) {
        try {
            if (args.size() == 1) {
                long tick = Long.parseLong(args.get(0));
                if (tick < 0) throw new Refused("rewind-watch-seek TICK is 0 or more");
                return new long[]{0, tick};
            }
            if (args.size() == 2 && "by".equals(args.get(0))) {
                long ms = Long.parseLong(args.get(1));
                if (Math.abs(ms) > REWIND_MAX_SEEK_MS) throw new Refused("rewind-watch-seek by MS is at most a day");
                return new long[]{1, ms * 1000L};
            }
        } catch (NumberFormatException e) {
            throw new Refused("rewind-watch-seek takes whole numbers");
        }
        throw new Refused("probe rewind-watch-seek TICK or rewind-watch-seek by MS");
    }

    /** {@code rewind-watch-play-to TICK}: the tick, 0 or more. */
    static long rewindPlayTo(java.util.List<String> args) {
        if (args.size() != 1) throw new Refused("probe rewind-watch-play-to TICK");
        try {
            long t = Long.parseLong(args.get(0));
            if (t >= 0) return t;
        } catch (NumberFormatException ignored) {
            // refused below
        }
        throw new Refused("rewind-watch-play-to TICK is a whole number, 0 or more");
    }

    /** {@code rewind-watch-swing ITEM}: a namespaced item id such as minecraft:stone_sword. */
    static String rewindSwingItem(java.util.List<String> args) {
        if (args.size() != 1 || args.get(0) == null || !args.get(0).matches("[a-z0-9_]+:[a-z0-9_]+")) {
            throw new Refused("probe rewind-watch-swing ITEM (e.g. minecraft:stone_sword)");
        }
        return args.get(0);
    }

    /** {@code rewind-watch-stage DIR}: an absolute path that is cobdev's staging folder, nothing else. */
    static String rewindStageDir(java.util.List<String> args) {
        if (args.size() != 1 || args.get(0) == null) return "probe rewind-watch-stage DIR";
        String d = args.get(0);
        if (!d.startsWith("/") || d.contains("/../") || d.contains("/./") || !d.endsWith(REWIND_STAGE_TAIL)) {
            return "rewind-watch-stage only reads cobdev's staging folder (*" + REWIND_STAGE_TAIL + ")";
        }
        return null;
    }

    /** Recording files {@code rewind-watch-stage} copies: a recording and its metadata, not its stats. */
    static boolean rewindStageFile(String name) {
        return rewindFile(name) && !name.endsWith(".stats.json");
    }

    // ------------------------------------------------------------------ Motion Blur (cycle 2026-11-17)

    /** The Motion Blur probes (step MB): the record, a scripted turn, an exact pose, a frame with its record. */
    static final java.util.List<String> MOTION_BLUR_PROBES = java.util.Arrays.asList("mb-record", "mb-turn", "mb-pose",
            "mb-shot");
    /** The fastest scripted turn, deg/s, and the longest one, s: a turn always ends on its own. */
    static final double MB_MAX_RATE = 3600;
    static final double MB_MAX_TURN_SECONDS = 10;
    /** Yaw is unbounded in Minecraft; the probes keep it to a few turns either way. */
    static final double MB_MAX_YAW = 3600;

    /** {@code mb-record} (the last frame now), {@code mb-record watch} (start a frame log), {@code mb-record log}. */
    static String mbRecordMode(java.util.List<String> args) {
        if (args.isEmpty()) return "now";
        if (args.size() == 1 && ("watch".equals(args.get(0)) || "log".equals(args.get(0)))) return args.get(0);
        throw new Refused("probe mb-record [watch|log]");
    }

    /** {@code mb-turn FROM TO DEG/S}: {from, to, rate}. */
    static double[] mbTurn(java.util.List<String> args) {
        if (args.size() != 3) throw new Refused("probe mb-turn FROM_YAW TO_YAW DEG_PER_S");
        double[] t = numbers(args, "mb-turn");
        if (Math.abs(t[0]) > MB_MAX_YAW || Math.abs(t[1]) > MB_MAX_YAW) {
            throw new Refused("mb-turn yaws are within " + (int) MB_MAX_YAW + " degrees of 0");
        }
        if (!(t[2] > 0 && t[2] <= MB_MAX_RATE)) throw new Refused("mb-turn rate is above 0 and at most " + (int) MB_MAX_RATE + " deg/s");
        if (Math.abs(t[1] - t[0]) / t[2] > MB_MAX_TURN_SECONDS) {
            throw new Refused("mb-turn lasts at most " + (int) MB_MAX_TURN_SECONDS + " s");
        }
        return t;
    }

    /**
     * The scripted turn's yaw {@code seconds} after it started: from FROM toward TO at {@code rate} deg/s, held at TO once
     * reached. A function of the time alone, so every frame shows where the turn is, whatever the frame rate.
     */
    static double turnYaw(double from, double to, double rate, double seconds) {
        double travelled = rate * Math.max(0, seconds);
        double span = Math.abs(to - from);
        if (travelled >= span) return to;
        return from + Math.signum(to - from) * travelled;
    }

    /** {@code mb-pose YAW PITCH}: {yaw, pitch} as the floats the entity stores; pitch within vanilla's -90..90. */
    static float[] mbPose(java.util.List<String> args) {
        if (args.size() != 2) throw new Refused("probe mb-pose YAW PITCH");
        double[] p = numbers(args, "mb-pose");
        if (Math.abs(p[0]) > MB_MAX_YAW) throw new Refused("mb-pose yaw is within " + (int) MB_MAX_YAW + " degrees of 0");
        if (Math.abs(p[1]) > 90) throw new Refused("mb-pose pitch is from -90 to 90");
        return new float[]{Float.parseFloat(args.get(0)), Float.parseFloat(args.get(1))};
    }

    /** {@code mb-shot} or {@code mb-shot stopTurn}: whether to stop the scripted turn first. */
    static boolean mbShotStop(java.util.List<String> args) {
        if (args.isEmpty()) return false;
        if (args.size() == 1 && "stopTurn".equals(args.get(0))) return true;
        throw new Refused("probe mb-shot [stopTurn]");
    }

    private static double[] numbers(java.util.List<String> args, String probe) {
        double[] out = new double[args.size()];
        for (int i = 0; i < out.length; i++) {
            try {
                out[i] = Double.parseDouble(args.get(i));
            } catch (NumberFormatException e) {
                throw new Refused(probe + " takes numbers: " + args.get(i));
            }
            if (Double.isNaN(out[i]) || Double.isInfinite(out[i])) throw new Refused(probe + " takes finite numbers");
        }
        return out;
    }

    // ------------------------------------------------------------------ overlay items (HUD parity B)

    /** The scoreboard, chat and tab list probes (Cobblify's overlay items). */
    static final java.util.List<String> OVERLAY_PROBES = java.util.Arrays.asList("overlay-boxes", "chat-fixture",
            "chat-clear", "chat-open", "chat-at", "chat-click", "tab-header-footer", "place-item", "chat-heights",
            "chat-defaults");
    /** The overlay items {@code place-item} may move. */
    static final java.util.List<String> OVERLAY_ITEMS = java.util.Arrays.asList("scoreboard", "chat", "tablist");
    /** The chat fixtures the bridge ships (bridge/resources/chat-&lt;name&gt;.json). */
    static final java.util.List<String> CHAT_FIXTURES = java.util.Arrays.asList("overlays");

    /**
     * {@code chat-click} injects a real click into vanilla's chat screen (links, Copy Chat); every other
     * input goes only to Cobblify screens, so it is allowed only while the open screen is exactly
     * vanilla's {@code GuiChat} (not a subclass, not any other screen).
     */
    static String chatClick(boolean screenIsVanillaChat) {
        return screenIsVanillaChat ? null : "chat-click needs vanilla's chat screen open (probe chat-open first)";
    }

    static String placeItem(String id) {
        return OVERLAY_ITEMS.contains(id) ? null : "place-item takes " + String.join(", ", OVERLAY_ITEMS) + ": " + id;
    }

    static String chatFixture(String name) {
        return CHAT_FIXTURES.contains(name) ? null : "chat-fixture takes " + String.join(", ", CHAT_FIXTURES) + ": " + name;
    }

    /** {@code chat-heights FOCUSED UNFOCUSED} (vanilla's sliders, 0 to 1) or {@code chat-heights restore}: null = restore. */
    static float[] chatHeights(java.util.List<String> args) {
        if (args.size() == 1 && "restore".equals(args.get(0))) return null;
        if (args.size() != 2) throw new Refused("probe chat-heights FOCUSED UNFOCUSED (0 to 1) or chat-heights restore");
        float[] out = new float[2];
        for (int i = 0; i < 2; i++) {
            try {
                out[i] = Float.parseFloat(args.get(i));
            } catch (NumberFormatException e) {
                throw new Refused("chat heights are numbers from 0 to 1");
            }
            if (!(out[i] >= 0f && out[i] <= 1f)) throw new Refused("chat heights are numbers from 0 to 1");
        }
        return out;
    }

    /** {@code tab-header-footer HEADER FOOTER} or {@code tab-header-footer clear}: null = clear. */
    static String[] tabHeaderFooter(java.util.List<String> args) {
        if (args.size() == 1 && "clear".equals(args.get(0))) return null;
        if (args.size() != 2) throw new Refused("probe tab-header-footer HEADER FOOTER or tab-header-footer clear");
        for (String a : args) {
            if (a.length() > 64) throw new Refused("tab header and footer are limited to 64 characters");
        }
        return new String[]{args.get(0), args.get(1)};
    }

    /**
     * {@code chat-at GX GY} and {@code chat-click GX GY [BUTTON]}: GUI coordinates. A click is button 0 (left)
     * only: a right-click is Copy Chat, which would overwrite the user's clipboard ({@code chat-at} reports
     * what it would copy instead).
     */
    static int[] chatPoint(java.util.List<String> args, boolean withButton) {
        int n = args.size();
        if (n != 2 && !(withButton && n == 3)) {
            throw new Refused(withButton ? "probe chat-click GX GY [BUTTON]" : "probe chat-at GX GY");
        }
        int[] out = new int[3];
        try {
            for (int i = 0; i < n; i++) out[i] = Integer.parseInt(args.get(i));
        } catch (NumberFormatException e) {
            throw new Refused("chat coordinates are integers");
        }
        if (out[2] != 0) {
            throw new Refused("chat-click is a left click (button 0): a right-click would copy to the clipboard;"
                    + " probe chat-at reports what Copy Chat would copy");
        }
        return out;
    }

    /**
     * {@code open-shop} fixture names: lower-case letters, digits and dashes only, so a name can only pick
     * one of the bridge's own {@code team-upgrades-<name>.json} resources.
     */
    static String shopFixture(java.util.List<String> args) {
        if (args == null || args.size() != 1) return "probe open-shop FIXTURE (full, unknown)";
        String name = args.get(0);
        if (name == null || !name.matches("[a-z0-9-]{1,32}")) return "bad open-shop fixture name " + name;
        return null;
    }
}
