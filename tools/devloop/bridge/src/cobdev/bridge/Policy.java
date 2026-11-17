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
        Refused.check(local(inWorld, singleplayer));
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
        return "off".equals(state) ? null : local(inWorld, singleplayer);
    }

    /** Checked on every tick of a hold: once the client is on a multiplayer server, every hold ends. */
    static boolean keepHolding(boolean inWorld, boolean singleplayer) {
        return local(inWorld, singleplayer) == null;
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
                return OVERLAY_PROBES.contains(name) || REWIND_PROBES.contains(name) ? null
                        : "unknown probe " + name + " (fov, hurt, player, block, clutch-blast, use-item, open-shop, hud-boxes, "
                        + "sheet-node, " + String.join(", ", OVERLAY_PROBES) + ", " + String.join(", ", REWIND_PROBES) + ")";
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
        if (overrideProperty == null || overrideProperty.trim().isEmpty()) {
            return "rewind-clear needs the dev client's own folder (-D" + REWIND_DIR_PROPERTY + ", set by cobdev stage)";
        }
        if (resolvedDir == null) return "rewind-clear: no Rewind folder";
        String real = new java.io.File(new java.io.File(userHome == null ? "" : userHome, ".cobblify"), "rewind")
                .getAbsolutePath();
        if (new java.io.File(resolvedDir).getAbsolutePath().equals(real)) {
            return "rewind-clear never touches the player's own Rewind folder";
        }
        return null;
    }

    /** True for a file name {@code rewind-clear} may delete. */
    static boolean rewindFile(String name) {
        return name != null && REWIND_FILE.matcher(name).matches();
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
