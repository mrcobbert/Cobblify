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
        allowed(Policy.probeName("hud-boxes"));
        allowed(Policy.probeName("sheet-node"));

        // --mods: alt, shift and ctrl only; ctrl is Ctrl and Cmd (GuiScreen.isCtrlKeyDown reads Cmd on macOS)
        same("56", joinAll(Policy.modifiers("alt", true, true)));
        same("42", joinAll(Policy.modifiers("shift", false, false)));
        same("29,219", joinAll(Policy.modifiers("ctrl", true, true)));
        same("56,42,29,219", joinAll(Policy.modifiers("alt,shift,ctrl", true, true)));
        same("42,56", joinAll(Policy.modifiers(" shift , alt ,shift", true, true)));
        same("", joinAll(Policy.modifiers(null, true, true)));
        same("", joinAll(Policy.modifiers("", true, true)));
        refusedCall(() -> Policy.modifiers("meta", true, true));
        refusedCall(() -> Policy.modifiers("alt,cmd", true, true));
        refusedCall(() -> Policy.modifiers("alt,,shift", true, true));
        // ... and refused on a multiplayer server, like every other input
        refusedCall(() -> Policy.modifiers("alt", true, false));
        refusedCall(() -> Policy.modifiers(null, true, false));

        // --device: display pixels from the top-left, inside the display
        allowed(Policy.device(0, 0, 2560, 1440));
        allowed(Policy.device(2559, 1439, 2560, 1440));
        refused(Policy.device(2560, 0, 2560, 1440));
        refused(Policy.device(0, 1440, 2560, 1440));
        refused(Policy.device(-1, 5, 2560, 1440));
        refused(Policy.device(5, -1, 2560, 1440));

        // keyhold --raw: keyboard state for the Tab key, the five movement keys and Cobblify's bindings
        allowed(Policy.keyBinding("key.categories.multiplayer", "key.playerlist", true));
        for (String move : new String[]{"key.forward", "key.left", "key.back", "key.right", "key.jump"}) {
            allowed(Policy.keyBinding("key.categories.movement", move, true));
            refused(Policy.keyBinding("key.categories.movement", move, false)); // a binding hold stays refused
        }
        allowed(Policy.keyBinding("Cobblify", "Zoom", true));
        refused(Policy.keyBinding("key.categories.movement", "key.sneak", true));
        refused(Policy.keyBinding("key.categories.movement", "key.sprint", true));
        refused(Policy.keyBinding("key.categories.gameplay", "key.attack", true));
        refused(Policy.keyBinding("key.categories.multiplayer", "key.chat", true));
        refused(Policy.keyBinding("key.categories.misc", "key.forward", true)); // right name, wrong category
        refused(Policy.keyBinding(null, null, true));
        // only keyboard keys have a keyboard state (mouse bindings are negative codes)
        allowed(Policy.rawKeyCode("key.playerlist", 15));
        allowed(Policy.rawKeyCode("key.forward", 17));
        refused(Policy.rawKeyCode("key.forward", 0));
        refused(Policy.rawKeyCode("key.attack", -100));
        refused(Policy.rawKeyCode("key.forward", 256));
        // a raw hold follows the keyhold rule: never on a server, release always allowed
        refused(Policy.keyhold("on", true, false));
        allowed(Policy.keyhold("off", true, false));

        // overlay item probes (HUD parity B)
        for (String p : new String[]{"overlay-boxes", "chat-fixture", "chat-clear", "chat-open", "chat-at",
                "chat-click", "tab-header-footer", "place-item", "chat-heights", "chat-defaults"}) {
            allowed(Policy.probeName(p));
        }
        allowed(Policy.probeName("hud-boxes")); // the editor cycle's probe, beside B's once both are merged
        allowed(Policy.chatClick(true));
        refused(Policy.chatClick(false)); // any other screen, a subclass, or none
        for (String id : new String[]{"scoreboard", "chat", "tablist"}) allowed(Policy.placeItem(id));
        refused(Policy.placeItem("potion"));
        refused(Policy.placeItem(null));
        allowed(Policy.chatFixture("overlays"));
        refused(Policy.chatFixture("../../etc/passwd"));
        refused(Policy.chatFixture(null));
        checks++;
        if (Policy.chatHeights(java.util.Arrays.asList("restore")) != null) fail("restore has no heights");
        same("1.0,0.5", Policy.chatHeights(java.util.Arrays.asList("1", "0.5"))[0] + ","
                + Policy.chatHeights(java.util.Arrays.asList("1", "0.5"))[1]);
        refusedCall(() -> Policy.chatHeights(java.util.Arrays.asList("1.5", "0.5")));
        refusedCall(() -> Policy.chatHeights(java.util.Arrays.asList("NaN", "0.5")));
        refusedCall(() -> Policy.chatHeights(java.util.Arrays.asList("1")));
        checks++;
        if (Policy.tabHeaderFooter(java.util.Arrays.asList("clear")) != null) fail("clear has no text");
        same("Cobdev Header|Cobdev Footer", String.join("|",
                Policy.tabHeaderFooter(java.util.Arrays.asList("Cobdev Header", "Cobdev Footer"))));
        refusedCall(() -> Policy.tabHeaderFooter(java.util.Arrays.asList("only one")));
        same("12,34,0", join(Policy.chatPoint(java.util.Arrays.asList("12", "34"), false)));
        same("12,34,0", join(Policy.chatPoint(java.util.Arrays.asList("12", "34", "0"), true)));
        refusedCall(() -> Policy.chatPoint(java.util.Arrays.asList("12", "34", "0"), false));
        refusedCall(() -> Policy.chatPoint(java.util.Arrays.asList("12", "34", "1"), true)); // Copy Chat: clipboard
        refusedCall(() -> Policy.chatPoint(java.util.Arrays.asList("12", "34", "2"), true));
        refusedCall(() -> Policy.chatPoint(java.util.Arrays.asList("12", "x"), false));

        // Motion Blur probes (cycle 2026-11-17-motion-blur, step MB)
        for (String p : new String[]{"mb-record", "mb-turn", "mb-pose", "mb-shot"}) allowed(Policy.probeName(p));
        refused(Policy.probeName("mb-coverage"));
        // mb-record: the last frame now, or a frame log (watch, then log)
        same("now", Policy.mbRecordMode(java.util.Collections.<String>emptyList()));
        same("watch", Policy.mbRecordMode(java.util.Arrays.asList("watch")));
        same("log", Policy.mbRecordMode(java.util.Arrays.asList("log")));
        refusedCall(() -> Policy.mbRecordMode(java.util.Arrays.asList("dump")));
        refusedCall(() -> Policy.mbRecordMode(java.util.Arrays.asList("watch", "log")));
        // mb-turn FROM TO DEG/S: finite angles, a positive rate up to 3600 deg/s, at most 10 s long
        same("-15.0,15.0,200.0", joinD(Policy.mbTurn(java.util.Arrays.asList("-15", "15", "200"))));
        same("15.0,-15.0,200.0", joinD(Policy.mbTurn(java.util.Arrays.asList("15", "-15", "200"))));
        refusedCall(() -> Policy.mbTurn(java.util.Arrays.asList("-15", "15")));
        refusedCall(() -> Policy.mbTurn(java.util.Arrays.asList("-15", "15", "0")));
        refusedCall(() -> Policy.mbTurn(java.util.Arrays.asList("-15", "15", "-200")));
        refusedCall(() -> Policy.mbTurn(java.util.Arrays.asList("-15", "15", "3601")));
        refusedCall(() -> Policy.mbTurn(java.util.Arrays.asList("NaN", "15", "200")));
        refusedCall(() -> Policy.mbTurn(java.util.Arrays.asList("-15", "Infinity", "200")));
        refusedCall(() -> Policy.mbTurn(java.util.Arrays.asList("-15", "x", "200")));
        refusedCall(() -> Policy.mbTurn(java.util.Arrays.asList("0", "3000", "200"))); // 15 s: longer than 10 s
        refusedCall(() -> Policy.mbTurn(java.util.Arrays.asList("-4000", "-3999", "200"))); // |yaw| over 3600
        // the yaw at a time since the start: from absolute time, held at TO once reached
        sameD(-15.0, Policy.turnYaw(-15, 15, 200, 0));
        sameD(0.0, Policy.turnYaw(-15, 15, 200, 0.075));
        sameD(15.0, Policy.turnYaw(-15, 15, 200, 0.15));
        sameD(15.0, Policy.turnYaw(-15, 15, 200, 2.0));
        sameD(5.0, Policy.turnYaw(15, -15, 200, 0.05)); // the other way round
        sameD(-15.0, Policy.turnYaw(-15, 15, 200, -1.0)); // never before the start
        // mb-pose YAW PITCH: finite, pitch within vanilla's -90..90
        same("-15.0,0.0", joinF(Policy.mbPose(java.util.Arrays.asList("-15", "0"))));
        same("0.83749,-90.0", joinF(Policy.mbPose(java.util.Arrays.asList("0.83749", "-90"))));
        refusedCall(() -> Policy.mbPose(java.util.Arrays.asList("0", "91")));
        refusedCall(() -> Policy.mbPose(java.util.Arrays.asList("NaN", "0")));
        refusedCall(() -> Policy.mbPose(java.util.Arrays.asList("4000", "0")));
        refusedCall(() -> Policy.mbPose(java.util.Arrays.asList("0")));
        // mb-shot [stopTurn]
        checks++;
        if (Policy.mbShotStop(java.util.Collections.<String>emptyList())) fail("mb-shot alone does not stop a turn");
        checks++;
        if (!Policy.mbShotStop(java.util.Arrays.asList("stopTurn"))) fail("mb-shot stopTurn stops the turn");
        refusedCall(() -> Policy.mbShotStop(java.util.Arrays.asList("stop")));
        refusedCall(() -> Policy.mbShotStop(java.util.Arrays.asList("stopTurn", "x")));

        System.out.println("PolicyTest: " + checks + " checks passed");
    }

    /** A class outside com.bedwarsqol: modObject only looks at the name. */
    private static final class FakeMod {
    }

    private static String join(int[] p) {
        return p[0] + "," + p[1] + "," + p[2];
    }

    private static String joinD(double[] p) {
        return p[0] + "," + p[1] + "," + p[2];
    }

    private static String joinF(float[] p) {
        return p[0] + "," + p[1];
    }

    private static void sameD(double expected, double actual) {
        checks++;
        if (Math.abs(expected - actual) > 1e-9) fail("expected " + expected + ", got " + actual);
    }

    private static String joinAll(int[] p) {
        StringBuilder sb = new StringBuilder();
        for (int v : p) sb.append(sb.length() == 0 ? "" : ",").append(v);
        return sb.toString();
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
