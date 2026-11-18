package com.bedwarsqol.rewind.watch;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Isolation layer 3 (PLAN section 5; Phase 5a), checked on the Ornithe sources:
 * <ul>
 *   <li>every {@code ModEvents.<X>.post(} (or {@code ::post}) goes through the gated helper
 *       {@code ModEvents.post(dispatcher, event)}, which skips posting while a recording plays;</li>
 *   <li>every mixin reference into {@code com.bedwarsqol.feature} or {@code com.bedwarsqol.stats} is on a reviewed
 *       list: either it is a direct hook that returns early under the gate (checked: the enclosing method reads
 *       {@code PlaybackGate.muted()} before the call), or it is one of the visual or inert hooks that stay active on
 *       purpose (PLAN section 5, "Still active during playback"), each with its reason;</li>
 *   <li>Rewind's camera sample in {@code EntityRendererRewindMixin} is skipped under the gate.</li>
 * </ul>
 * Comments and string literals are blanked before matching, so a javadoc link is not a call. Entries that no longer
 * match the source fail too, so the list stays the truth.
 */
public class PlaybackIsolationScanTest {

    /** Direct hooks into features that must return early while muted: mixin file -> feature/stats classes. */
    private static final Map<String, Set<String>> GATED = new TreeMap<String, Set<String>>();
    /** Hooks that stay active during playback on purpose: "mixin file -> class" -> why. */
    private static final Map<String, String> ACTIVE = new TreeMap<String, String>();

    static {
        gated("GuiIngameMixin", "feature.SessionStatsWatch", "feature.TeamUpgradesWatch");
        gated("GuiPlayerTabOverlayMixin", "stats.TabListLookup", "feature.Denicks");
        gated("GuiChatMixin", "feature.ChatHoverStats");
        gated("GuiScreenMixin", "feature.ChatHoverStats", "feature.OutgoingChat");

        String visual = "visual module, active while watching on purpose (PLAN section 5)";
        String chatStyle = "Cobblify's styling of the vanilla chat, active on purpose (PLAN section 5)";
        active("AbstractClientPlayerFovMixin", "feature.CameraOptions", visual + ": zoom and FOV");
        active("EntityRendererCameraMixin", "feature.CameraOptions", visual + ": camera options");
        active("EntityRendererLightmapMixin", "feature.Fullbright", visual + ": Fullbright");
        active("EntityRendererLightmapMixin", "feature.FullbrightRules", visual + ": Fullbright");
        active("EntityRendererMotionBlurMixin", "feature.MotionBlur", visual + ": Motion Blur");
        active("EntityRendererMotionBlurMixin", "feature.MotionBlurFrame", visual + ": Motion Blur");
        active("NetHandlerPlayClientMixin", "feature.MotionBlur", visual + ": Motion Blur camera cut");
        active("RenderEntityItemMixin", "feature.ItemPhysics", visual + ": Item Physics");
        active("RenderEntityItemMixin", "feature.ItemPhysicsRules", visual + ": Item Physics");
        active("EffectRendererParticlesMixin", "feature.ParticleOptions", visual + ": Particles");
        active("EntityLivingBaseParticlesMixin", "feature.ParticleOptions", visual + ": Particles");
        active("EntityPlayerParticlesMixin", "feature.ParticleOptions", visual + ": Particles");
        active("EntityPlayerParticlesMixin", "feature.ParticleRules", visual + ": Particles");
        active("RenderGlobalParticlesMixin", "feature.ParticleOptions", visual + ": Particles");
        String oldAnim = visual + ": 1.7 Animations";
        active("EntityRendererSneakCameraMixin", "feature.OldAnimations", oldAnim);
        active("GuiIngameHeartsMixin", "feature.OldAnimations", oldAnim);
        active("ItemRendererMixin", "feature.OldAnimations", oldAnim);
        active("LayerArmorBaseOldAnimMixin", "feature.OldAnimRules", oldAnim);
        active("LayerArmorBaseOldAnimMixin", "feature.OldAnimations", oldAnim);
        active("LayerCustomHeadOldAnimMixin", "feature.OldAnimations", oldAnim);
        active("LayerHeldItemOldAnimMixin", "feature.OldAnimations", oldAnim);
        active("MinecraftOldAnimMixin", "feature.OldAnimations", oldAnim);
        active("ModelBipedOldAnimMixin", "feature.OldAnimRules", oldAnim);
        active("ModelBipedOldAnimMixin", "feature.OldAnimations", oldAnim);
        active("ModelPlayerOldAnimMixin", "feature.OldAnimations", oldAnim);
        active("RenderPlayerOldAnimMixin", "feature.OldAnimations", oldAnim);
        active("GuiNewChatMixin", "feature.ChatCopyAccess", chatStyle + ": Copy Chat lookup");
        active("GuiNewChatMixin", "feature.ChatStack", chatStyle + ": Chat Stack");
        active("GuiNewChatMixin", "feature.ChatStackCore", chatStyle + ": Chat Stack");
        active("GuiChatMixin", "feature.ChatLengthLimit", "chat input length; no chat screen opens while watching");
        active("GuiChatMixin", "feature.ChatCopyAccess", "Copy Chat, a user click in the chat screen");
        active("GuiChatMixin", "feature.AlertCopyReason", "Copy Chat, a user click in the chat screen");
        active("C01PacketChatMessageMixin", "feature.ChatLengthLimit",
                "outbound chat length; the playback connection discards every outbound packet");
        active("EntityPlayerSPMixin", "feature.OutgoingChat",
                "pacing of the player's own sends; nothing sends chat while watching (no chat screen, inert camera)");
        active("EntityPlayerMixin", "feature.ClutchPractice", "integrated server only; playback has none");
        active("ItemInWorldManagerMixin", "feature.ClutchPractice", "integrated server only; playback has none");
        active("GuiPlayerTabOverlayMixin", "stats.HypixelContext",
                "context read only: false with no server data, which playback never has");
    }

    private static void gated(String mixin, String... classes) {
        GATED.put(mixin, new TreeSet<String>(Arrays.asList(classes)));
    }

    private static void active(String mixin, String cls, String why) {
        ACTIVE.put(mixin + " -> " + cls, why);
    }

    // ------------------------------------------------------------------ post sites

    @Test
    public void everyModEventsPostGoesThroughTheGatedHelper() throws IOException {
        File root = mainRoot();
        String modEvents = strip(read(new File(root, "com/bedwarsqol/ornithe/ModEvents.java")));
        List<String> dispatchers = dispatchers(modEvents);
        assertTrue("ModEvents declares its dispatchers: " + dispatchers, dispatchers.size() >= 10);

        String names = join(dispatchers, "|");
        Pattern raw = Pattern.compile("\\b(?:" + names + ")\\s*(?:\\.\\s*post\\s*\\(|::\\s*post\\b)");
        Pattern viaHelper = Pattern.compile("\\bModEvents\\s*\\.\\s*post\\s*\\(\\s*ModEvents\\s*\\.\\s*(" + names
                + ")\\b");
        List<String> violations = new ArrayList<String>();
        Map<String, Integer> helperUses = new TreeMap<String, Integer>();
        for (File f : javaFiles(root)) {
            String src = strip(read(f));
            Matcher m = raw.matcher(src);
            while (m.find()) {
                violations.add(rel(root, f) + ":" + line(src, m.start()) + " " + m.group().trim());
            }
            Matcher h = viaHelper.matcher(src);
            while (h.find()) {
                Integer n = helperUses.get(h.group(1));
                helperUses.put(h.group(1), n == null ? 1 : n + 1);
            }
        }
        assertEquals("ungated ModEvents posts", Collections.<String>emptyList(), violations);
        for (String d : dispatchers) {
            assertTrue(d + " is posted through the helper somewhere", helperUses.containsKey(d));
        }

        // The helper itself: skips while muted, otherwise posts unchanged.
        Matcher helper = Pattern.compile("static\\s+<E>\\s+boolean\\s+post\\s*\\(").matcher(modEvents);
        assertTrue("ModEvents.post(dispatcher, event) exists", helper.find());
        String body = block(modEvents, modEvents.indexOf('{', helper.end()));
        assertTrue("the helper reads the gate: " + body, body.contains("PlaybackGate.muted()"));
    }

    // ------------------------------------------------------------------ direct hooks

    @Test
    public void everyMixinCallIntoAFeatureIsGatedOrReviewedAsActive() throws IOException {
        File mixins = new File(mainRoot(), "com/bedwarsqol/mixin");
        Map<String, Set<String>> refs = new TreeMap<String, Set<String>>();
        List<String> ungated = new ArrayList<String>();
        for (File f : javaFiles(mixins)) {
            String name = f.getName().replace(".java", "");
            String src = strip(read(f));
            assertFalse(name + " uses a wildcard feature/stats import",
                    Pattern.compile("import\\s+com\\.bedwarsqol\\.(?:feature|stats)\\.\\*").matcher(src).find());
            Set<String> classes = featureRefs(src);
            if (classes.isEmpty()) {
                continue;
            }
            refs.put(name, classes);
            Set<String> gatedHere = GATED.get(name);
            for (String cls : classes) {
                boolean isGated = gatedHere != null && gatedHere.contains(cls);
                boolean isActive = ACTIVE.containsKey(name + " -> " + cls);
                if (isGated == isActive) {
                    fail(name + " -> " + cls + (isGated ? " is listed both gated and active"
                            : " is not reviewed: gate it (return early on PlaybackGate.muted()) or list it as"
                            + " active with a reason"));
                }
                if (isGated) {
                    ungated.addAll(usesWithoutGate(name, src, simple(cls)));
                }
            }
        }
        assertEquals("gated hooks that reach a feature before reading the gate",
                Collections.<String>emptyList(), ungated);

        // Stale entries: every listed pair still exists.
        for (Map.Entry<String, Set<String>> e : GATED.entrySet()) {
            Set<String> have = refs.get(e.getKey());
            for (String cls : e.getValue()) {
                assertTrue("stale gated entry " + e.getKey() + " -> " + cls, have != null && have.contains(cls));
            }
        }
        for (String pair : ACTIVE.keySet()) {
            String[] p = pair.split(" -> ");
            Set<String> have = refs.get(p[0]);
            assertTrue("stale active entry " + pair, have != null && have.contains(p[1]));
        }
    }

    @Test
    public void rewindsCameraSampleIsSkippedUnderTheGate() throws IOException {
        String src = strip(read(new File(mainRoot(), "com/bedwarsqol/mixin/EntityRendererRewindMixin.java")));
        List<String> bad = usesWithoutGate("EntityRendererRewindMixin", src, "Rewind.onRenderWorld");
        assertTrue("Rewind.onRenderWorld is called", src.contains("Rewind.onRenderWorld"));
        assertEquals(Collections.<String>emptyList(), bad);
    }

    /** The checker itself: an ungated call is found, a gated one is not, a javadoc mention is not a call. */
    @Test
    public void theGateCheckerSeesThroughCommentsAndFindsUngatedCalls() {
        String ungated = strip("class A {\n /** {@link Foo} */\n void a() { Foo.go(); }\n}");
        assertEquals(1, usesWithoutGate("A", ungated, "Foo").size());
        String gatedSrc = strip("class A {\n void a() { if (PlaybackGate.muted()) return; Foo.go(); }\n}");
        assertEquals(0, usesWithoutGate("A", gatedSrc, "Foo").size());
        String late = strip("class A {\n void a() { Foo.go(); if (PlaybackGate.muted()) return; }\n}");
        assertEquals("a gate read after the call does not count", 1, usesWithoutGate("A", late, "Foo").size());
        String otherMethod = strip("class A {\n void g() { if (PlaybackGate.muted()) return; }\n void a() { Foo.go(); }\n}");
        assertEquals("another method's gate does not count", 1, usesWithoutGate("A", otherMethod, "Foo").size());
        String qualified = strip("class A {\n void a() { com.bedwarsqol.feature.Foo.go(); }\n}");
        assertEquals("a qualified call is a use too", 1, usesWithoutGate("A", qualified, "Foo").size());
        assertEquals(new TreeSet<String>(Arrays.asList("feature.Foo", "stats.Bar")), featureRefs(strip(
                "import com.bedwarsqol.feature.Foo;\nclass A { void a() { com.bedwarsqol.stats.Bar.x(); } }\n"
                        + "// com.bedwarsqol.feature.Gone\n")));
    }

    // ------------------------------------------------------------------ helpers (also used by QueuedWorkScanTest)

    /** {@code ornithe/src/main/java}, from Gradle's working directory (the project) or the repo root. */
    static File mainRoot() {
        for (String p : new String[]{"src/main/java", "ornithe/src/main/java"}) {
            File f = new File(p);
            if (new File(f, "com/bedwarsqol/ornithe/ModEvents.java").isFile()) {
                return f;
            }
        }
        throw new AssertionError("ornithe/src/main/java not found from " + new File(".").getAbsolutePath());
    }

    static List<File> javaFiles(File dir) {
        List<File> out = new ArrayList<File>();
        collect(dir, out);
        Collections.sort(out);
        if (out.isEmpty()) {
            throw new AssertionError("no sources under " + dir);
        }
        return out;
    }

    private static void collect(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (File k : kids) {
            if (k.isDirectory()) {
                collect(k, out);
            } else if (k.getName().endsWith(".java")) {
                out.add(k);
            }
        }
    }

    static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    static String rel(File root, File f) {
        return root.toURI().relativize(f.toURI()).getPath();
    }

    /** Blanks comments, string and char literals with spaces (newlines kept), so offsets and lines still match. */
    static String strip(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            char next = i + 1 < n ? src.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && next == '*') {
                out.append("  ");
                i += 2;
                while (i < n && !(src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/')) {
                    out.append(src.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) {
                    out.append("  ");
                    i += 2;
                }
            } else if (c == '"' || c == '\'') {
                out.append(c);
                i++;
                while (i < n && src.charAt(i) != c) {
                    if (src.charAt(i) == '\\' && i + 1 < n) {
                        out.append(' ');
                        i++;
                    }
                    out.append(src.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) {
                    out.append(c);
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    static int line(String src, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < src.length(); i++) {
            if (src.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** The text of the brace block that opens at {@code open} (inclusive). */
    static String block(String src, int open) {
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(open, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced block at " + open);
    }

    /**
     * Offset of the '{' opening the method body (brace depth 2: class body, then member body) around
     * {@code offset}, or -1 when the offset is not inside one.
     */
    static int methodBodyStart(String src, int offset) {
        List<Integer> open = new ArrayList<Integer>();
        for (int i = 0; i < offset; i++) {
            char c = src.charAt(i);
            if (c == '{') {
                open.add(i);
            } else if (c == '}' && !open.isEmpty()) {
                open.remove(open.size() - 1);
            }
        }
        return open.size() >= 2 ? open.get(1) : -1;
    }

    /** Uses of {@code token} (outside imports) whose method has not read {@code PlaybackGate.muted()} before it. */
    static List<String> usesWithoutGate(String file, String src, String token) {
        List<String> out = new ArrayList<String>();
        Matcher m = Pattern.compile("(?<![\\w.])(?:com\\.bedwarsqol\\.\\w+\\.)?" + Pattern.quote(token) + "\\b")
                .matcher(src);
        while (m.find()) {
            int lineStart = src.lastIndexOf('\n', m.start()) + 1;
            if (src.substring(lineStart, m.start()).trim().startsWith("import")) {
                continue;
            }
            int body = methodBodyStart(src, m.start());
            if (body < 0 || !src.substring(body, m.start()).contains("PlaybackGate.muted()")) {
                out.add(file + ":" + line(src, m.start()) + " " + token);
            }
        }
        return out;
    }

    private static final Pattern FEATURE_REF =
            Pattern.compile("\\bcom\\.bedwarsqol\\.((?:feature|stats)\\.[A-Z]\\w*)");

    /** {@code feature.X} / {@code stats.X} named by an import or a qualified name in (stripped) source. */
    static Set<String> featureRefs(String src) {
        Set<String> out = new TreeSet<String>();
        Matcher m = FEATURE_REF.matcher(src);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static String simple(String cls) {
        return cls.substring(cls.indexOf('.') + 1);
    }

    private static List<String> dispatchers(String modEvents) {
        Set<String> out = new LinkedHashSet<String>();
        Matcher m = Pattern.compile("EventDispatcher\\s*<[^;=]*>\\s+([A-Z_]+)\\s*=").matcher(modEvents);
        while (m.find()) {
            out.add(m.group(1));
        }
        return new ArrayList<String>(out);
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (b.length() > 0) {
                b.append(sep);
            }
            b.append(p);
        }
        return b.toString();
    }
}
