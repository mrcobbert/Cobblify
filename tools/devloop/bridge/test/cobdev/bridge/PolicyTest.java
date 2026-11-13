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
        allowed(Policy.keyBinding("Cobblify"));
        refused(Policy.keyBinding("key.categories.movement"));
        refused(Policy.keyBinding(null));

        // commands
        allowed(Policy.command("/time set 6000"));
        refused(Policy.command("hello"));
        refused(Policy.command("/a\n/b"));
        refused(Policy.command(null));

        // probe reach and names
        allowed(Policy.near(3, 0, -16));
        refused(Policy.near(17, 0, 0));
        for (String p : new String[]{"fov", "hurt", "player", "block", "clutch-blast", "use-item"}) {
            allowed(Policy.probeName(p));
        }
        refused(Policy.probeName("setblock"));
        refused(Policy.probeName(null));

        System.out.println("PolicyTest: " + checks + " checks passed");
    }

    /** A class outside com.bedwarsqol: modObject only looks at the name. */
    private static final class FakeMod {
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
