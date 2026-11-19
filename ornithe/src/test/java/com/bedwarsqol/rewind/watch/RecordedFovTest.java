package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RewindFormat;
import net.minecraft.client.renderer.EntityRenderer;
import org.junit.Test;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * C7 (PLAN "FovPart", Phase 12): in your view of a recording with FOV records, the world FOV is the recorded final
 * FOV (sprint, Speed, bow, Zoom: the recorder read it through {@code getFOVModifier(partial, true)}) at the frame's
 * playback time, interpolated between samples at most 50 ms apart, whatever the watching client's FOV setting. The
 * hand pass ({@code useFOVSetting} false), the free camera, another player's view and a v1 recording keep vanilla's.
 */
public class RecordedFovTest {

    private static final long MS = 1_000L;

    private static ExtTrack fov(long[] times, float[] degrees) {
        ExtCodec codec = new ExtCodec();
        ExtRecords.Builder b = new ExtRecords.Builder();
        for (int i = 0; i < times.length; i++) {
            b.addEncoded(0, times[i], RewindFormat.TAG_FOV, codec.buffer(), codec.fov(degrees[i]));
        }
        return ExtTrack.of(b.build());
    }

    private static float at(ExtTrack t, long us) {
        return ViewTrack.fovAt(t, 0, us, new ExtCodec.Fov());
    }

    @Test
    public void theRecordedFovAtPlaybackTimeIsInterpolatedBetweenSamplesUpTo50msApart() {
        ExtTrack t = fov(new long[]{100 * MS, 110 * MS, 500 * MS, 600 * MS, 640 * MS},
                new float[]{70f, 80f, 80f, 90f, 99f});
        assertTrue("nothing recorded yet", Float.isNaN(at(t, 99 * MS)));
        assertEquals(70f, at(t, 100 * MS), 1e-4f);
        assertEquals("10 ms apart: interpolated", 75f, at(t, 105 * MS), 1e-4f);
        assertEquals(80f, at(t, 110 * MS), 1e-4f);
        assertEquals("390 ms apart: held", 80f, at(t, 300 * MS), 1e-4f);
        assertEquals("100 ms apart: held, a step at the next sample", 80f, at(t, 599 * MS), 1e-4f);
        assertEquals(90f, at(t, 600 * MS), 1e-4f);
        assertEquals("40 ms apart: interpolated", 94.5f, at(t, 620 * MS), 1e-4f);
        assertEquals("after the last sample: held", 99f, at(t, 9_000 * MS), 1e-4f);
        assertTrue("a v1 recording", Float.isNaN(at(ExtTrack.EMPTY, 300 * MS)));
    }

    @Test
    public void theWorldFovIsTheRecordedOneWhateverTodaysFovSetting() {
        float recorded = 84.25f;
        for (float setting : new float[]{30f, 70f, 90f, 110f}) {
            for (float modifier : new float[]{0.85f, 1f, 1.15f, 1.3f}) {
                float vanilla = setting * modifier;
                assertEquals("setting " + setting + " x " + modifier, recorded,
                        FovPart.choose(vanilla, true, true, true, recorded), 0f);
            }
        }
    }

    @Test
    public void onlyTheWorldPassInYourViewOfAPlayingRecordingWithFovRecordsTakesIt() {
        float vanilla = 77f;
        assertEquals("the hand pass", vanilla, FovPart.choose(vanilla, false, true, true, 90f), 0f);
        assertEquals("nothing playing", vanilla, FovPart.choose(vanilla, true, false, true, 90f), 0f);
        assertEquals("free camera / another player's view", vanilla, FovPart.choose(vanilla, true, true, false, 90f),
                0f);
        assertEquals("no FOV recorded (v1, or before the first sample)", vanilla,
                FovPart.choose(vanilla, true, true, true, Float.NaN), 0f);
    }

    @Test
    public void thePartReadsTheRecordedFovOnlyInYourView() {
        ExtTrack t = fov(new long[]{100 * MS, 120 * MS}, new float[]{70f, 80f});
        FovPart part = new FovPart();
        part.update(t, 0, 110 * MS, true);
        assertEquals(75f, part.recorded(), 1e-4f);
        part.update(t, 0, 110 * MS, false);
        assertTrue("not your view", Float.isNaN(part.recorded()));
        part.update(ExtTrack.EMPTY, 0, 110 * MS, true);
        assertTrue("a v1 recording", Float.isNaN(part.recorded()));
    }

    /** The one new injector (Phase 12): {@code getFOVModifier} RETURN, require 1, through {@code FovPart.worldFov}. */
    @Test
    public void theMixinReplacesTheReturnOfGetFovModifierWithRequireOne() throws Exception {
        Method target = EntityRenderer.class.getDeclaredMethod("getFOVModifier", float.class, boolean.class);
        assertEquals(float.class, target.getReturnType());
        Class<?> mixin = Class.forName("com.bedwarsqol.mixin.EntityRendererRewindPlaybackMixin");
        List<Method> found = new ArrayList<Method>();
        for (Method m : mixin.getDeclaredMethods()) {
            Inject inject = m.getAnnotation(Inject.class);
            if (inject == null) continue;
            for (String t : inject.method()) {
                if (t.equals("getFOVModifier") || t.equals("getFOVModifier(FZ)F")) {
                    found.add(m);
                    assertEquals(1, inject.at().length);
                    At at = inject.at()[0];
                    assertEquals("RETURN", at.value());
                    assertTrue(inject.cancellable());
                    assertEquals(1, inject.require());
                    assertEquals(3, m.getParameterTypes().length);
                    assertEquals(float.class, m.getParameterTypes()[0]);
                    assertEquals(boolean.class, m.getParameterTypes()[1]);
                    assertEquals(CallbackInfoReturnable.class, m.getParameterTypes()[2]);
                }
            }
        }
        assertEquals("one injector on getFOVModifier", 1, found.size());
        String text = PlaybackIsolationScanTest.strip(PlaybackIsolationScanTest.read(new File(
                PlaybackIsolationScanTest.mainRoot(), "com/bedwarsqol/mixin/EntityRendererRewindPlaybackMixin.java")));
        Matcher decl = Pattern.compile("void\\s+" + Pattern.quote(found.get(0).getName()) + "\\s*\\(").matcher(text);
        assertTrue(decl.find());
        String b = PlaybackIsolationScanTest.block(text, text.indexOf('{', decl.end()));
        assertTrue(b, b.matches("(?s).*FovPart\\.worldFov\\s*\\(\\s*\\w+\\s*,\\s*useFOVSetting\\s*\\).*"));
        assertTrue(b, b.contains("setReturnValue("));
    }

    /** With nothing playing the injector hands vanilla's value back (live play unchanged). */
    @Test
    public void withNothingPlayingTheWorldFovIsVanillas() {
        assertEquals(70f, FovPart.worldFov(70f, true), 0f);
        assertEquals(70f, FovPart.worldFov(70f, false), 0f);
    }
}
