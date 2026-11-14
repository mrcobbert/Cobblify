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
            case "hud-boxes":
                return null;
            default:
                return "unknown probe " + name + " (fov, hurt, player, block, clutch-blast, use-item, hud-boxes)";
        }
    }
}
