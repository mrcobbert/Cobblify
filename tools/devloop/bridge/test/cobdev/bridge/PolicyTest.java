package cobdev.bridge;

/** Run by {@code cobdev selftest} (plain javac + java, no game, no JUnit). Exits 1 on the first failure. */
public final class PolicyTest {

    private static int checks;

    public static void main(String[] args) {
        // Multiplayer server: every input, probe and command path refuses.
        refused(Policy.local(true, false));
        refused(Policy.singleplayerWorld(true, false, false));
        refused(Policy.singleplayerWorld(true, false, true));
        same(Policy.ON_SERVER, Policy.local(true, false));
        same(Policy.ON_SERVER, Policy.singleplayerWorld(true, false, true));
        // Title screen and singleplayer are fine for input; probes need the integrated server's world.
        allowed(Policy.local(false, false));
        allowed(Policy.local(true, true));
        refused(Policy.singleplayerWorld(false, false, false));
        refused(Policy.singleplayerWorld(true, true, false));
        allowed(Policy.singleplayerWorld(true, true, true));

        // field / invoke / has targets
        allowed(Policy.modClass("com.bedwarsqol.BedwarsQol"));
        allowed(Policy.modClass("com.bedwarsqol.config.ClientSettings$Inner"));
        refused(Policy.modClass("com.bedwarsqol.mixin.MixinGuiIngame"));
        refused(Policy.modClass("net.minecraft.client.Minecraft"));
        refused(Policy.modClass("com.bedwarsqolx.Evil"));
        refused(Policy.modClass("java.lang.Runtime"));
        refused(Policy.modClass("com.bedwarsqol.X;rm"));
        refused(Policy.modClass(null));
        refused(Policy.modObject(String.class));
        refused(Policy.modObject(FakeMod.class)); // cobdev.bridge.*, not com.bedwarsqol.*

        // key bindings
        allowed(Policy.keyBinding("Cobblify", "Zoom"));
        allowed(Policy.keyBinding("key.categories.multiplayer", "key.playerlist")); // Tab, for the tab list checks
        refused(Policy.keyBinding("key.categories.multiplayer", "key.chat"));
        refused(Policy.keyBinding("key.categories.movement", "key.forward"));
        refused(Policy.keyBinding("key.categories.movement", "key.playerlist"));
        refused(Policy.keyBinding(null, null));

        // commands
        allowed(Policy.command("/time set 6000"));
        refused(Policy.command("hello"));
        refused(Policy.command("/a\n/b"));
        refused(Policy.command(null));

        // keyhold: holding needs a local world, releasing is always allowed (review I2)
        refused(Policy.keyhold("on", true, false));
        allowed(Policy.keyhold("off", true, false));
        allowed(Policy.keyhold("off", false, false));
        allowed(Policy.keyhold("on", true, true));
        allowed(Policy.keyhold(null, false, false));
        checks++;
        if (Policy.keepHolding(true, false)) fail("a hold must end on a multiplayer server");
        checks++;
        if (!Policy.keepHolding(true, true) || !Policy.keepHolding(false, false)) fail("a local hold must continue");

        // block targets: relative to the feet, or absolute with "at" (review I3)
        same("4,5,6", join(Policy.blockTarget(java.util.Arrays.asList("3", "1", "-1"), 1, 4, 7)));
        same("10,4,-2", join(Policy.blockTarget(java.util.Arrays.asList("at", "10", "4", "-2", "minecraft:wool"), 1, 4, 7)));
        same("minecraft:wool", Policy.blockId(java.util.Arrays.asList("at", "10", "4", "-2", "minecraft:wool")));
        same("minecraft:stone", Policy.blockId(java.util.Arrays.asList("3", "0", "1", "minecraft:stone")));
        checks++;
        if (Policy.blockId(java.util.Arrays.asList("at", "1", "2", "3")) != null) fail("a read has no block id");
        refusedCall(() -> Policy.blockTarget(java.util.Arrays.asList("at", "40", "4", "7"), 1, 4, 7));
        refusedCall(() -> Policy.blockTarget(java.util.Arrays.asList("17", "0", "0"), 1, 4, 7));
        refusedCall(() -> Policy.blockTarget(java.util.Arrays.asList("1", "2"), 1, 4, 7));

        // probe reach and names
        allowed(Policy.near(3, 0, -16));
        refused(Policy.near(17, 0, 0));
        for (String p : new String[]{"fov", "hurt", "player", "block", "clutch-blast", "use-item", "open-shop", "hud-boxes"}) {
            allowed(Policy.probeName(p));
        }
        allowed(Policy.shopFixture(java.util.Arrays.asList("full")));
        allowed(Policy.shopFixture(java.util.Arrays.asList("unknown")));
        refused(Policy.shopFixture(java.util.Arrays.asList("../fabric.mod")));
        refused(Policy.shopFixture(java.util.Arrays.asList("Full")));
        refused(Policy.shopFixture(java.util.Arrays.asList("full", "x")));
        refused(Policy.shopFixture(java.util.Collections.<String>emptyList()));
        refused(Policy.probeName("setblock"));
        refused(Policy.probeName(null));

        System.out.println("PolicyTest: " + checks + " checks passed");
    }

    /** A class outside com.bedwarsqol: modObject only looks at the name. */
    private static final class FakeMod {
    }

    private static String join(int[] p) {
        return p[0] + "," + p[1] + "," + p[2];
    }

    private static void refusedCall(Runnable r) {
        checks++;
        try {
            r.run();
        } catch (Refused e) {
            return;
        }
        fail("expected a Refused");
    }

    private static void allowed(String result) {
        checks++;
        if (result != null) fail("expected allowed, got: " + result);
    }

    private static void refused(String result) {
        checks++;
        if (result == null) fail("expected a refusal");
    }

    private static void same(String expected, String actual) {
        checks++;
        if (!expected.equals(actual)) fail("expected " + expected + ", got " + actual);
    }

    private static void fail(String msg) {
        StackTraceElement at = new Throwable().getStackTrace()[2];
        System.err.println("PolicyTest FAILED at line " + at.getLineNumber() + ": " + msg);
        System.exit(1);
    }
}
