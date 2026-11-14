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
        if (KEY_CATEGORY.equals(category)) return null;
        if ("key.categories.multiplayer".equals(category) && PLAYER_LIST_KEY.equals(description)) return null;
        return "only key bindings Cobblify registers (category " + KEY_CATEGORY + ") and the Tab key can be held";
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
                return null;
            default:
                return "unknown probe " + name + " (fov, hurt, player, block, clutch-blast, use-item, open-shop)";
        }
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
