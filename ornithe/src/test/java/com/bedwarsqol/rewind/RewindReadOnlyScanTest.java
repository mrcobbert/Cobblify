package com.bedwarsqol.rewind;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Source scans for the recorder's format v2 capture (A12, A15), on the Ornithe sources with comments blanked (so
 * documentation may name what code may not):
 * <ul>
 *   <li>no recorder class or recorder mixin sends or writes a packet ({@code sendPacket}, {@code addToSendQueue},
 *       {@code writeAndFlush}, a channel or context {@code write});</li>
 *   <li>the capture classes (run on the client thread per tick, frame, packet and draw) do no file I/O and no
 *       logging;</li>
 *   <li>no recorder class calls a HUD data method (it is handed what the painters drew);</li>
 *   <li>the recorder mixins only read: nothing cancellable, no return value set, no redirect, argument, variable or
 *       constant modification, no overwrite, and every injector is {@code require = 1};</li>
 *   <li>{@code mixins.bedwarsqol.json} lists exactly the mixin classes on disk, once each;</li>
 *   <li>the wiring calls every capture entry point that only it calls (Phase 6).</li>
 * </ul>
 * Each scanner is also run on a planted line, so a scan that silently matches nothing fails.
 */
public class RewindReadOnlyScanTest {

    /** The recorder's mixins (format v1 camera sample, format v2 capture). */
    static final List<String> RECORDER_MIXINS = Collections.unmodifiableList(Arrays.asList(
            "EntityRendererRewindMixin", "NetHandlerPlayClientRewindMixin", "MinecraftRewindRecordMixin",
            "EntityPlayerSPRewindMixin", "PlayerControllerMPRewindAccessor", "PlayerControllerMPRewindRecordMixin",
            "WorldRewindMixin", "SoundHandlerRewindRecordMixin", "PacketThreadUtilRunMixin",
            "EntityRendererFovInvoker", "GuiChatRewindAccessor"));

    /** The capture classes: client-thread code on per-tick, per-frame, per-packet and per-draw paths. */
    static final List<String> CAPTURE_CLASSES = Collections.unmodifiableList(Arrays.asList(
            "ActionCapture", "StateCapture", "ViewCapture", "HudCapture", "SettingsSnapshot"));

    private static final Pattern SEND = Pattern.compile(
            "\\b(sendPacket|addToSendQueue|writeAndFlush)\\s*\\(|\\b(ctx|channel|ch|pipeline\\(\\))\\s*\\.\\s*write\\s*\\(");

    private static final Pattern IO_OR_LOG = Pattern.compile("\\bjava\\.io\\.File\\b|\\bjava\\.nio\\.file\\b"
            + "|\\bFiles\\s*\\.|\\bFileOutputStream\\b|\\bFileWriter\\b|\\bRandomAccessFile\\b|\\bFileChannel\\b"
            + "|\\bDiagLog\\b|\\bSystem\\s*\\.\\s*(out|err)\\b|\\bprintStackTrace\\s*\\(|\\bLogger\\b|\\bLogManager\\b"
            + "|\\.\\s*diag\\s*\\(");

    private static final Pattern WRITES_GAME = Pattern.compile("\\bcancellable\\b|\\.\\s*cancel\\s*\\(\\s*\\)"
            + "|\\bsetReturnValue\\b|@Redirect\\b|@ModifyArgs?\\b|@ModifyVariable\\b|@ModifyConstant\\b|@Overwrite\\b"
            + "|@WrapOperation\\b|@ModifyReturnValue\\b");

    private static final Pattern INJECT = Pattern.compile("@Inject\\s*\\(((?:[^()]|\\([^()]*\\))*)\\)");

    // ------------------------------------------------------------------ helpers

    private static File mixinDir() {
        return new File(HudCaptureTest.mainRoot(), "com/bedwarsqol/mixin");
    }

    private static File rewindDir() {
        return new File(HudCaptureTest.mainRoot(), "com/bedwarsqol/rewind");
    }

    /** The recorder classes: {@code com.bedwarsqol.rewind} itself, not its {@code watch} (playback) package. */
    private static List<File> recorderClasses() {
        List<File> out = new ArrayList<File>();
        File[] kids = rewindDir().listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (k.isFile() && k.getName().endsWith(".java")) {
                    out.add(k);
                }
            }
        }
        Collections.sort(out);
        assertTrue("recorder classes found: " + out, out.size() >= 8);
        return out;
    }

    private static File mixin(String name) {
        File f = new File(mixinDir(), name + ".java");
        assertTrue("recorder mixin on disk: " + name, f.isFile());
        return f;
    }

    /** {@code name:line text} for each match of {@code p} in {@code code} (comments already blanked). */
    static List<String> hits(String name, String code, Pattern p) {
        List<String> out = new ArrayList<String>();
        Matcher m = p.matcher(code);
        while (m.find()) {
            int line = 1;
            for (int i = 0; i < m.start(); i++) {
                if (code.charAt(i) == '\n') {
                    line++;
                }
            }
            out.add(name + ":" + line + " " + m.group());
        }
        return out;
    }

    private static String code(File f) throws IOException {
        return HudCaptureTest.strip(HudCaptureTest.read(f));
    }

    // ------------------------------------------------------------------ the scanners find planted lines

    @Test
    public void everyScannerFindsAPlantedLine() {
        String planted = "class P { void a() { net.addToSendQueue(p); ctx.write(m); /* sendPacket( */ } }\n";
        assertEquals(2, hits("p", HudCaptureTest.strip(planted), SEND).size());
        String io = "import java.io.File;\nclass P { void a() { DiagLog.write(\"x\"); System.err.println(); } }\n";
        assertEquals(3, hits("p", HudCaptureTest.strip(io), IO_OR_LOG).size());
        String mx = "@Inject(method = \"a\", at = @At(\"HEAD\"), cancellable = true)\nvoid a(CallbackInfo ci) "
                + "{ ci.cancel(); }\n@Redirect(method = \"b\")\n";
        assertEquals(3, hits("p", HudCaptureTest.strip(mx), WRITES_GAME).size());
        assertEquals(1, injectorsWithoutRequire1(HudCaptureTest.strip(
                "@Inject(method = \"a\", at = @At(\"HEAD\"))\n@Inject(method = \"b\", at = @At(\"HEAD\"), require = 1)"))
                .size());
    }

    // ------------------------------------------------------------------ A15: nothing sends

    @Test
    public void noRecorderClassOrRecorderMixinSendsOrWritesAPacket() throws IOException {
        List<String> found = new ArrayList<String>();
        for (File f : recorderClasses()) {
            found.addAll(hits(f.getName(), code(f), SEND));
        }
        for (String m : RECORDER_MIXINS) {
            found.addAll(hits(m, code(mixin(m)), SEND));
        }
        assertTrue("recorder code sends or writes: " + found, found.isEmpty());
    }

    // ------------------------------------------------------------------ A12: no I/O or logging in capture

    @Test
    public void captureClassesDoNoFileIoAndNoLogging() throws IOException {
        List<String> found = new ArrayList<String>();
        for (String c : CAPTURE_CLASSES) {
            File f = new File(rewindDir(), c + ".java");
            assertTrue(c + " on disk", f.isFile());
            found.addAll(hits(c, code(f), IO_OR_LOG));
        }
        for (String m : RECORDER_MIXINS) {
            found.addAll(hits(m, code(mixin(m)), IO_OR_LOG));
        }
        assertTrue("file I/O or logging on a capture path: " + found, found.isEmpty());
    }

    @Test
    public void noRecorderClassCallsAHudDataMethod() throws IOException {
        List<String> found = new ArrayList<String>();
        for (File f : recorderClasses()) {
            found.addAll(HudCaptureTest.hudDataCalls(f.getName(), code(f)));
        }
        assertTrue("recorder code names a HUD data method: " + found, found.isEmpty());
    }

    // ------------------------------------------------------------------ A15: recorder mixins only read

    private static List<String> injectorsWithoutRequire1(String code) {
        List<String> out = new ArrayList<String>();
        Matcher m = INJECT.matcher(code);
        while (m.find()) {
            if (!m.group(1).replaceAll("\\s+", "").contains("require=1")) {
                out.add(m.group());
            }
        }
        return out;
    }

    @Test
    public void recorderMixinsOnlyReadAndEveryInjectorIsRequired() throws IOException {
        List<String> found = new ArrayList<String>();
        int injectors = 0;
        for (String m : RECORDER_MIXINS) {
            String code = code(mixin(m));
            found.addAll(hits(m, code, WRITES_GAME));
            for (String bad : injectorsWithoutRequire1(code)) {
                found.add(m + " without require = 1: " + bad);
            }
            injectors += hits(m, code, INJECT).size();
        }
        assertTrue("injectors found: " + injectors, injectors >= 15);
        assertTrue("a recorder mixin changes the game: " + found, found.isEmpty());
    }

    /**
     * Review code-1 O6 item 2, code-2 recurrence: Mixin builds a {@code CallbackInfo(Returnable)} per call only for a
     * handler whose bytecode loads it ({@code CallbackInjector.Callback.usesCallbackInfo}). No recorder handler may,
     * so {@code World.setBlockState} on the integrated server's worlds (and every other hooked call) allocates
     * nothing for the recorder. Checked on the compiled handlers, as Mixin checks them.
     */
    @Test
    public void recorderInjectorsNeverLoadTheirCallbackInfo() throws IOException {
        List<String> found = new ArrayList<String>();
        int handlers = 0;
        for (String m : RECORDER_MIXINS) {
            java.io.InputStream in = RewindReadOnlyScanTest.class.getClassLoader()
                    .getResourceAsStream("com/bedwarsqol/mixin/" + m + ".class");
            assertTrue("compiled " + m, in != null);
            org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
            try {
                new org.objectweb.asm.ClassReader(in).accept(cn, 0);
            } finally {
                in.close();
            }
            for (org.objectweb.asm.tree.MethodNode mn : cn.methods) {
                org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(mn.desc);
                if (args.length == 0 || !args[args.length - 1].getInternalName()
                        .startsWith("org/spongepowered/asm/mixin/injection/callback/CallbackInfo")) {
                    continue;
                }
                handlers++;
                int slot = (mn.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0 ? 0 : 1;
                for (int i = 0; i < args.length - 1; i++) {
                    slot += args[i].getSize();
                }
                for (org.objectweb.asm.tree.AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (insn.getOpcode() == org.objectweb.asm.Opcodes.ALOAD
                            && ((org.objectweb.asm.tree.VarInsnNode) insn).var == slot) {
                        found.add(m + "." + mn.name);
                        break;
                    }
                }
            }
        }
        assertTrue("handlers found: " + handlers, handlers >= 10);
        assertTrue("a recorder handler loads its CallbackInfo (Mixin allocates one per call): " + found,
                found.isEmpty());
    }

    // ------------------------------------------------------------------ A15: every mixin registered

    @Test
    public void theMixinListEqualsTheMixinClassesOnDisk() throws IOException {
        File json = new File(HudCaptureTest.mainRoot().getParentFile(), "resources/mixins.bedwarsqol.json");
        JsonObject o = new JsonParser().parse(HudCaptureTest.read(json)).getAsJsonObject();
        assertEquals("com.bedwarsqol.mixin", o.get("package").getAsString());
        List<String> listed = new ArrayList<String>();
        for (String side : new String[]{"mixins", "client", "server"}) {
            JsonArray a = o.getAsJsonArray(side);
            if (a != null) {
                for (JsonElement e : a) {
                    listed.add(e.getAsString());
                }
            }
        }
        Set<String> unique = new TreeSet<String>(listed);
        assertEquals("no mixin listed twice", listed.size(), unique.size());

        Set<String> onDisk = new TreeSet<String>();
        List<File> files = HudCaptureTest.javaFiles(mixinDir());
        for (File f : files) {
            String rel = mixinDir().toURI().relativize(f.toURI()).getPath();
            String name = rel.substring(0, rel.length() - ".java".length()).replace('/', '.');
            if (code(f).contains("@Mixin(")) {
                onDisk.add(name);
            }
        }
        assertEquals("mixins.bedwarsqol.json lists exactly the @Mixin classes on disk", onDisk, unique);
        for (String m : RECORDER_MIXINS) {
            assertTrue(m + " registered", unique.contains(m));
        }
    }

    // ------------------------------------------------------------------ Phase 6: capture is wired

    @Test
    public void theWiringCallsEveryCaptureEntryPoint() throws IOException {
        String rewind = code(new File(rewindDir(), "Rewind.java"));
        String headMixin = code(mixin("MinecraftRewindRecordMixin"));
        String[] inRewind = {
                "new HandledRing(", "ActionCapture.bind(", "ActionCapture.unbind(", "ActionCapture.startSegment(",
                "ActionCapture.onTickEnd(", "ActionCapture.onRunTickHead(", "StateCapture.install(",
                "StateCapture.resetSegment(", "StateCapture.tick(", "ViewCapture.install(",
                "ViewCapture.resetSegment(", "ViewCapture.frame(", ".setSink(", ".tickHead(", ".setTapReceiver(",
                "ViewCapture::mousePressed", ".clientTick.add(", ".clientFrame.add("};
        List<String> missing = new ArrayList<String>();
        for (String call : inRewind) {
            if (!rewind.replaceAll("\\s+", "").contains(call.replaceAll("\\s+", ""))) {
                missing.add(call);
            }
        }
        if (!headMixin.contains("Rewind.onRunTickHead(")) {
            missing.add("MinecraftRewindRecordMixin -> Rewind.onRunTickHead(");
        }
        assertTrue("capture entry points the wiring does not call: " + missing, missing.isEmpty());
    }
}
