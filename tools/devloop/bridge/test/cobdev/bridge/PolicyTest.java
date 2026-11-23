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

        // Rewind probes: the newest recording's counts, and clearing cobdev's own folder only
        allowed(Policy.probeName("rewind-latest"));
        allowed(Policy.probeName("rewind-clear"));
        refused(Policy.probeName("rewind-delete"));
        allowed(Policy.rewindClear("cobdev-rewind", "/Users/someone/run/cobdev-rewind", "/Users/someone"));
        refused(Policy.rewindClear(null, "/Users/someone/.cobblify/rewind", "/Users/someone"));
        refused(Policy.rewindClear("  ", "/Users/someone/.cobblify/rewind", "/Users/someone"));
        refused(Policy.rewindClear("/Users/someone/.cobblify/rewind", "/Users/someone/.cobblify/rewind", "/Users/someone"));
        for (String ok : new String[]{"20261117-031500-ab12.cbr", "20261117-031500-ab12.json",
                "20261117-031500-ab12.stats.json"}) {
            checks++;
            if (!Policy.rewindFile(ok)) fail("a recording file: " + ok);
        }
        for (String no : new String[]{"notes.txt", "20261117-031500-AB12.cbr", "20261117-031500-ab12.cbr.tmp",
                "../20261117-031500-ab12.cbr", "x20261117-031500-ab12.json", "20261117-0315-ab12.cbr", null}) {
            checks++;
            if (Policy.rewindFile(no)) fail("not a recording file: " + no);
        }
        // Rewind watch (step RWW): a recording playing through Rewind's socketless connection counts as local; a
        // real server never does, whatever the session says.
        checks++;
        if (!Policy.rewindPlayback(true, true, false, false)) fail("an active session without a network address");
        for (boolean[] no : new boolean[][]{{false, true, false, false}, {true, true, false, true},
                {true, false, false, false}, {true, true, true, false}}) {
            checks++;
            if (Policy.rewindPlayback(no[0], no[1], no[2], no[3])) fail("not Rewind playback: " + java.util.Arrays.toString(no));
        }
        allowed(Policy.local(true, false, true));
        refused(Policy.local(true, false, false));
        allowed(Policy.keyhold("on", true, false, true));
        refused(Policy.keyhold("on", true, false, false));
        checks++;
        if (!Policy.keepHolding(true, false, true) || Policy.keepHolding(true, false, false)) fail("holds while watching only");
        same("42", joinAll(Policy.modifiers("shift", true, false, true)));
        refusedCall(() -> Policy.modifiers("shift", true, false, false));
        for (String p : new String[]{"rewind-watch-swing", "rewind-watch-open", "rewind-watch-state", "rewind-watch-seek",
                "rewind-watch-play-to", "rewind-watch-snapshot", "rewind-watch-quit", "rewind-watch-stage",
                "rewind-watch-actions", "rewind-watch-window"}) {
            allowed(Policy.probeName(p));
        }
        for (String a : new String[]{"start", "status", "cleanup"}) {
            same(a, Policy.rewindActions(java.util.Arrays.asList(a)));
        }
        refusedCall(() -> Policy.rewindActions(java.util.Arrays.asList("dig")));
        refusedCall(() -> Policy.rewindActions(java.util.Collections.<String>emptyList()));
        refusedCall(() -> Policy.rewindActions(java.util.Arrays.asList("start", "now")));
        refused(Policy.probeName("rewind-watch-copy"));
        same("0,1234", join3(Policy.rewindSeek(java.util.Arrays.asList("1234"))));
        same("1,-30000000", join3(Policy.rewindSeek(java.util.Arrays.asList("by", "-30000"))));
        refusedCall(() -> Policy.rewindSeek(java.util.Arrays.asList("-1")));
        refusedCall(() -> Policy.rewindSeek(java.util.Arrays.asList("by", "x")));
        refusedCall(() -> Policy.rewindSeek(java.util.Arrays.asList("by", String.valueOf(Policy.REWIND_MAX_SEEK_MS + 1))));
        refusedCall(() -> Policy.rewindSeek(java.util.Collections.<String>emptyList()));
        checks++;
        if (Policy.rewindPlayTo(java.util.Arrays.asList("600")) != 600) fail("play-to 600");
        refusedCall(() -> Policy.rewindPlayTo(java.util.Arrays.asList("-5")));
        refusedCall(() -> Policy.rewindPlayTo(java.util.Arrays.asList("1", "2")));
        same("minecraft:stone_sword", Policy.rewindSwingItem(java.util.Arrays.asList("minecraft:stone_sword")));
        refusedCall(() -> Policy.rewindSwingItem(java.util.Arrays.asList("stone sword")));
        refusedCall(() -> Policy.rewindSwingItem(java.util.Collections.<String>emptyList()));
        allowed(Policy.rewindOwnFolder("rewind-watch-open", "cobdev-rewind", "/Users/someone/run/cobdev-rewind",
                "/Users/someone"));
        refused(Policy.rewindOwnFolder("rewind-watch-open", null, "/Users/someone/.cobblify/rewind", "/Users/someone"));
        refused(Policy.rewindOwnFolder("rewind-watch-stage", "/Users/someone/.cobblify/rewind",
                "/Users/someone/.cobblify/rewind", "/Users/someone"));
        allowed(Policy.rewindStageDir(java.util.Arrays.asList("/Users/Shared/cobblify-dev/cache/rewind-stage")));
        refused(Policy.rewindStageDir(java.util.Arrays.asList("/Users/agent/rewind-samples/rewind")));
        refused(Policy.rewindStageDir(java.util.Arrays.asList("cobblify-dev/cache/rewind-stage")));
        refused(Policy.rewindStageDir(java.util.Arrays.asList("/Users/Shared/cobblify-dev/cache/../../x/cobblify-dev/cache/rewind-stage")));
        refused(Policy.rewindStageDir(java.util.Collections.<String>emptyList()));
        checks++;
        if (!Policy.rewindStageFile("20261117-031500-ab12.cbr") || !Policy.rewindStageFile("20261117-031500-ab12.json")
                || Policy.rewindStageFile("20261117-031500-ab12.stats.json") || Policy.rewindStageFile("notes.txt")) {
            fail("staged files: the recording and its metadata only");
        }

        // Rewind Export Phase 2 (spike): its probes are known, its arguments bounded, its folder fixed.
        for (String n : Policy.REWIND_EXPORT_PROBES) allowed(Policy.probeName(n));
        refused(Policy.probeName("rewind-export-anything"));
        checks++;
        Object[] spike = Policy.exportSpike(java.util.Arrays.asList("own", "1920", "1080", "60", "60", "rule", "2500",
                "120", "0", "dev-own-1"));
        same("own,1920,1080,60,60,rule,2500,120,0,dev-own-1", spike[0] + "," + spike[1] + "," + spike[2] + ","
                + spike[3] + "," + spike[4] + "," + spike[5] + "," + spike[6] + "," + spike[7] + "," + spike[8] + ","
                + spike[9]);
        same("-", String.valueOf(Policy.exportSpike(java.util.Arrays.asList("own", "1920", "1080", "60", "60", "none",
                "0", "0", "0", "-"))[9])); // "-": frames are discarded (timing only)
        refusedCall(() -> Policy.exportSpike(java.util.Arrays.asList("own", "1921", "1080", "60", "60", "rule", "0",
                "0", "0", "a"))); // odd width
        refusedCall(() -> Policy.exportSpike(java.util.Arrays.asList("own", "1920", "1080", "50", "60", "rule", "0",
                "0", "0", "a"))); // fps not 30/60/120/240
        refusedCall(() -> Policy.exportSpike(java.util.Arrays.asList("own", "8000", "1080", "60", "60", "rule", "0",
                "0", "0", "a"))); // wider than 4K x 2
        refusedCall(() -> Policy.exportSpike(java.util.Arrays.asList("own", "1920", "1080", "60", "0", "rule", "0",
                "0", "0", "a"))); // no frames
        refusedCall(() -> Policy.exportSpike(java.util.Arrays.asList("own", "1920", "1080", "60", "60", "maybe", "0",
                "0", "0", "a")));
        refusedCall(() -> Policy.exportSpike(java.util.Arrays.asList("own", "1920", "1080", "60", "60", "rule", "0",
                "5000", "0", "a"))); // hold above the 2 s cap
        refusedCall(() -> Policy.exportSpike(java.util.Arrays.asList("own", "1920", "1080", "60", "60", "rule", "0",
                "0", "0", "../x")));
        refusedCall(() -> Policy.exportSpike(java.util.Arrays.asList("tv", "1920", "1080", "60", "60", "rule", "0",
                "0", "0", "a")));
        refusedCall(() -> Policy.exportSpike(java.util.Arrays.asList("own", "1920", "1080")));
        checks++;
        Object[] chatOff = Policy.exportSpike(java.util.Arrays.asList("own", "1920", "1080", "60", "60", "paced",
                "2500u250", "0", "0", "k11-x", "chat-off"));
        same("false,2500250,2500", chatOff[10] + "," + chatOff[11] + "," + chatOff[6]);
        refusedCall(() -> Policy.exportSpike(java.util.Arrays.asList("own", "1920", "1080", "60", "60", "rule", "0",
                "0", "0", "a", "chat-maybe")));

        checks++;
        if (Policy.rewindPlayTo(java.util.Arrays.asList("40", "1x")) != 40
                || !Policy.rewindPlayToRealSpeed(java.util.Arrays.asList("40", "1x"))
                || Policy.rewindPlayToRealSpeed(java.util.Arrays.asList("40"))) {
            fail("play-to TICK 1x");
        }
        refusedCall(() -> Policy.rewindPlayTo(java.util.Arrays.asList("40", "2x")));

        // Rewind Export Phase 5 (step RWE): exports only into the dev client's own export folder, bounded arguments.
        String dir = "/Users/Shared/cobblify-dev/exports/rwe";
        checks++;
        Object[] st = Policy.exportStart("{\"startUs\":1025000,\"endUs\":4035000,\"height\":1080,\"fps\":60,"
                + "\"quality\":\"high\",\"codec\":\"h264\",\"chat\":false,\"route\":\"own\",\"helper\":\"jar\"}", dir);
        same("1025000,4035000,0,1080,60,high,h264,false,own,jar", st[0] + "," + st[1] + "," + st[2] + "," + st[3] + ","
                + st[4] + "," + st[5] + "," + st[6] + "," + st[7] + "," + st[8] + "," + st[9]);
        Object[] small = Policy.exportStart("{\"startUs\":0,\"endUs\":1000000,\"width\":640,\"height\":360,"
                + "\"fps\":60,\"codec\":\"hevc\"}", dir);
        same("640x360 hevc", small[2] + "x" + small[3] + " " + small[6]);
        refusedCall(() -> Policy.exportStart("{\"startUs\":0,\"endUs\":1000000,\"height\":1080,\"fps\":60}", null));
        refusedCall(() -> Policy.exportStart("{\"startUs\":0,\"endUs\":1000000,\"height\":1080,\"fps\":60}",
                "/Users/p/Movies"));
        refusedCall(() -> Policy.exportStart("{\"startUs\":0,\"endUs\":1000000,\"height\":1080,\"fps\":60}",
                "/Users/Shared/cobblify-dev/exports/../../p"));
        refusedCall(() -> Policy.exportStart("{\"startUs\":0,\"endUs\":1000000,\"height\":1000,\"fps\":60}", dir));
        refusedCall(() -> Policy.exportStart("{\"startUs\":5,\"endUs\":5,\"height\":1080,\"fps\":60}", dir));
        refusedCall(() -> Policy.exportStart("{\"startUs\":0,\"endUs\":1000000,\"height\":1080,\"fps\":50}", dir));
        refusedCall(() -> Policy.exportStart("{\"startUs\":0,\"endUs\":1000000,\"height\":1080,\"fps\":60,"
                + "\"out\":\"x\"}", dir)); // unknown key
        refusedCall(() -> Policy.exportStart("{\"startUs\":0,\"endUs\":1000000,\"height\":1080,\"fps\":60,"
                + "\"helper\":\"/bin/sh\"}", dir));
        refusedCall(() -> Policy.exportStart("[1,2]", dir));
        same("cobdev test 123", Policy.exportChatText(java.util.Arrays.asList("type", "cobdev test 123")));
        same("null", String.valueOf(Policy.exportChatText(java.util.Arrays.asList("close"))));
        refusedCall(() -> Policy.exportChatText(java.util.Arrays.asList("type", "/op me")));
        refusedCall(() -> Policy.exportChatText(java.util.Arrays.asList("type", "A")));
        refusedCall(() -> Policy.exportChatText(java.util.Arrays.asList("cobdev test")));
        checks++;
        int[] box = Policy.exportRegion(java.util.Arrays.asList("rwe-on", "frame-00010", "rwe-off", "frame-00010", "0",
                "900", "600", "1079"));
        same("0,900,600,1079", box[0] + "," + box[1] + "," + box[2] + "," + box[3]);
        refusedCall(() -> Policy.exportRegion(java.util.Arrays.asList("rwe-on", "frame-00010", "../x", "frame-00010",
                "0", "0", "1", "1")));
        refusedCall(() -> Policy.exportRegion(java.util.Arrays.asList("rwe-on", "frame-00010", "rwe-off", "frame-00010",
                "5", "0", "1", "1")));
        same("k11-x,31,k11-ref-0,8", String.join(",", java.util.Arrays.asList(
                java.util.Arrays.stream(Policy.exportK11(java.util.Arrays.asList("k11-x", "31", "k11-ref-0", "8")))
                        .map(String::valueOf).toArray(String[]::new))));
        refusedCall(() -> Policy.exportK11(java.util.Arrays.asList("k11-x", "0", "k11-ref-0", "8")));
        refusedCall(() -> Policy.exportK11(java.util.Arrays.asList("k11-x", "31", "k11-ref-0", "99")));
        allowed(Policy.exportRun("dev-own-1"));
        refused(Policy.exportRun("../etc"));
        refused(Policy.exportRun("A"));
        refused(Policy.exportRun("-"));
        refused(Policy.exportRun(""));
        refused(Policy.exportRun(null));
        refused(Policy.exportRun("a234567890123456789012345678901234567890"));
        allowed(Policy.exportName("frame-00012"));
        allowed(Policy.exportName("window-k3"));
        refused(Policy.exportName("../../x"));
        refused(Policy.exportName("x.png"));
        same("/Users/Shared/cobblify-dev/exports/spike/dev-own-1", Policy.exportFolder("dev-own-1"));
        checks++;
        try {
            Policy.exportFolder("../x");
            fail("export folder outside spike/");
        } catch (Refused expected) {
            // refused
        }

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

    private static String join3(long[] p) {
        return p[0] + "," + p[1];
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
