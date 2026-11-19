package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RewindFormat;
import net.minecraft.client.audio.ISound;
import net.minecraft.init.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Your own sounds (PLAN Phase 11, "Tick scheduler", round 2 I2; TASK C3, B7). The recorder stores each positioned,
 * non-repeating sound your own tick, click, dig, place or use played (SOUND: name, position, volume, pitch) with the
 * time it was played. Playback plays each one once, in the first drawn frame whose playback time has reached its own
 * stamp (not at the start of its tick), as vanilla's {@code PositionedSoundRecord} at the recorded position, volume and
 * pitch; never during a catch-up or seek; and never next to a derived copy (the derived footsteps and the puppet's
 * own tick sounds are off for a recording with SOUND records, {@link StepSounds#storedSounds}).
 *
 * <p>The scheduler is the session's {@link TickClock} over the recording's v2 {@link TickTable}; frames are driven
 * with a fake wall clock as {@code PlaybackSession} drives them (advance, then dispatch; a seek sets P and skips).
 */
public class LocalSoundPlaybackTest {

    private static final long MS = 1_000L;
    private static final long EO = 40 * MS;
    private static final String DIG = "minecraft:dig.stone";
    private static final String STEP = "minecraft:step.wood";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @BeforeClass
    public static void bootstrap() {
        PrintStream out = System.out;
        Bootstrap.register();
        System.setOut(out);
    }

    private final List<ISound> played = new ArrayList<ISound>();
    private boolean muted;
    private final SoundPart part = new SoundPart(() -> muted, played::add);

    private static byte[] sound(String name, float x, float y, float z, float volume, float pitch) {
        ExtCodec c = new ExtCodec();
        int n = c.sound(name, x, y, z, volume, pitch);
        return ExtRecords.body(c.buffer(), n);
    }

    private static String describe(ISound s) {
        return s.getSoundLocation() + " " + s.getXPosF() + " " + s.getYPosF() + " " + s.getZPosF() + " "
                + s.getVolume() + " " + s.getPitch();
    }

    private List<String> heard() {
        List<String> out = new ArrayList<String>();
        for (ISound s : played) {
            out.add(describe(s));
        }
        return out;
    }

    // ------------------------------------------------------------------ the part

    @Test
    public void aStoredSoundPlaysAsVanillasPositionedSoundAtItsRecordedPositionVolumeAndPitch() {
        byte[] b = sound(DIG, 10.5f, 64.5f, -2.5f, 0.25f, 0.4f);
        part.timed(RewindFormat.TAG_SOUND, 140 * MS, b, 0, b.length);
        assertEquals(1, played.size());
        ISound s = played.get(0);
        assertEquals(DIG, s.getSoundLocation().toString());
        assertEquals(10.5f, s.getXPosF(), 0f);
        assertEquals(64.5f, s.getYPosF(), 0f);
        assertEquals(-2.5f, s.getZPosF(), 0f);
        assertEquals(0.25f, s.getVolume(), 0f);
        assertEquals(0.4f, s.getPitch(), 0f);
        assertEquals("distance-attenuated, as the recorder stored only those", ISound.AttenuationType.LINEAR,
                s.getAttenuationType());
        assertFalse(s.canRepeat());
        assertEquals(0, s.getRepeatDelay());
    }

    @Test
    public void otherTimedRecordsAndLocalRecordsPlayNothing() {
        ExtCodec c = new ExtCodec();
        byte[] view = ExtRecords.body(c.buffer(), c.view(1, 0, RewindFormat.SCREEN_NONE));
        part.timed(RewindFormat.TAG_VIEW, 0, view, 0, view.length);
        byte[] s = sound(DIG, 0f, 64f, 0f, 1f, 1f);
        part.local(RewindFormat.TAG_SOUND, 0, s, 0, s.length); // SOUND never comes through the tick feed
        assertEquals(Collections.<ISound>emptyList(), played);
    }

    @Test
    public void nothingPlaysWhileSoundsAreMuted() {
        muted = true; // a catch-up or rebuild (the session's mute; the sound mixin cancels too)
        byte[] s = sound(DIG, 0f, 64f, 0f, 1f, 1f);
        part.timed(RewindFormat.TAG_SOUND, 0, s, 0, s.length);
        assertEquals(Collections.<ISound>emptyList(), played);
    }

    // ------------------------------------------------------------------ through the scheduler

    /** S01 at 40; heads every 50 ms from 100 to 400; SOUND records at 140 (inside the tick at 100) and 141.5. */
    private LoadedRecording recording(ExtRecords.Builder more) throws Exception {
        TickFeederTest.Rec rec = new TickFeederTest.Rec()
                .segment(1, TickFeederTest.standardHeader0(), TickFeederTest.standardHeader1())
                .at(EO, RecordingFixture.joinGame(0))
                .at(450 * MS, TickFeederTest.p(0x03, 1));
        long[] heads = new long[7];
        int[] inc = new int[7];
        for (int i = 0; i < heads.length; i++) {
            heads[i] = 100 + 50 * i;
        }
        inc[0] = 1;
        ExtRecords.Builder b = TickFeederTest.heads(heads, inc, null);
        if (more != null) {
            ExtRecords m = more.build();
            for (int i = 0; i < m.size(); i++) {
                b.add(m.segmentIndex(i), m.timeUs(i), m.tag(i), m.buffer(), m.offset(i), m.length(i));
            }
        }
        return rec.load(tmp.newFolder(), EO, 500 * MS).withExt(b.build());
    }

    private static ExtRecords.Builder twoSounds() {
        return new ExtRecords.Builder()
                .add(0, 140 * MS, RewindFormat.TAG_SOUND, sound(DIG, 10.5f, 64.5f, -2.5f, 0.25f, 0.5f))
                .add(0, 141_500L, RewindFormat.TAG_SOUND, sound(STEP, 10f, 64f, -3f, 0.15f, 1f));
    }

    /** The session's part of a frame: P advances by the wall time, then every reached SOUND is dispatched. */
    private final class Frames implements PacketSink {
        final LoadedRecording r;
        final TickClock clock;
        long nanos = 5_000_000_000L;
        final List<String> dispatchedAt = new ArrayList<String>();

        Frames(LoadedRecording r) {
            this.r = r;
            clock = new TickClock(r.timeline().ticks());
        }

        /** A seek or catch-up landed at {@code us} (PlaybackSession.finishCatchUp). */
        void land(long us) {
            clock.set(us, nanos);
            clock.skipTimed(r.ext());
        }

        /** One drawn frame {@code ms} of wall time after the last, at 1x. */
        void frame(double ms) {
            nanos += (long) (ms * 1_000_000L);
            clock.advance(nanos, 1f, false);
            clock.dispatchTimed(r.ext(), this);
        }

        @Override
        public void timed(int tag, long timeUs, byte[] buf, int off, int len) {
            dispatchedAt.add((timeUs / 100) / 10.0 + " at P=" + (clock.playbackUs() / 100) / 10.0);
            part.timed(tag, timeUs, buf, off, len);
        }

        @Override
        public boolean apply(int packetId, byte[] buf, int off, int len) {
            return true;
        }

        @Override
        public void boundary(int packetId, byte[] buf, int off, int len) {
        }

        @Override
        public boolean sessionHook(int packetId, byte[] buf, int off, int len) {
            return true;
        }

        @Override
        public void sample(long tick, long timeUs) {
        }
    }

    /**
     * A sound stamped at 140 inside the tick that started at 100 plays in the first frame with P >= 140, not in the
     * frame that ran the tick at 100, and not in a frame before its stamp; each plays once.
     */
    @Test
    public void eachSoundPlaysOnceInTheFirstFrameAtOrAfterItsOwnStamp() throws Exception {
        Frames f = new Frames(recording(twoSounds()));
        f.land(100 * MS); // the frame at P = 100 runs the tick at 100
        f.frame(0);
        assertTrue("not at the start of its tick", played.isEmpty());
        f.frame(20); // P = 120
        f.frame(19); // P = 139: a frame just before the stamp
        assertTrue("not before its stamp", played.isEmpty());
        f.frame(1.2); // P = 140.2
        assertEquals(Collections.singletonList(DIG + " 10.5 64.5 -2.5 0.25 0.5"), heard());
        f.frame(1); // P = 141.2
        assertEquals("the second one waits for its own stamp", 1, played.size());
        f.frame(0.5); // P = 141.7
        assertEquals(Arrays.asList(DIG + " 10.5 64.5 -2.5 0.25 0.5", STEP + " 10.0 64.0 -3.0 0.15 1.0"), heard());
        assertEquals(Arrays.asList("140.0 at P=140.2", "141.5 at P=141.7"), f.dispatchedAt);
        for (int i = 0; i < 20; i++) {
            f.frame(16);
        }
        assertEquals("once each", 2, played.size());
    }

    /** One long frame that passes both stamps plays both, in stamp order, once. */
    @Test
    public void aLongFramePlaysEveryReachedSoundInStampOrder() throws Exception {
        Frames f = new Frames(recording(twoSounds()));
        f.land(100 * MS);
        f.frame(60); // P = 160
        assertEquals(Arrays.asList(DIG + " 10.5 64.5 -2.5 0.25 0.5", STEP + " 10.0 64.0 -3.0 0.15 1.0"), heard());
    }

    /**
     * A seek or catch-up past a sound plays nothing; one stamped exactly at the landing time plays once after it; a
     * backward seek to before it plays it once more, at its stamp.
     */
    @Test
    public void noneDuringACatchUpOrSeek() throws Exception {
        Frames f = new Frames(recording(twoSounds()));
        f.land(100 * MS);
        f.land(200 * MS); // a forward seek over both stamps: the catch-up dispatches nothing
        f.frame(16);
        f.frame(16);
        assertEquals(Collections.<ISound>emptyList(), played);

        // landing exactly on a stamp (a seek lands at a tick's time): the record belongs to the frame that runs that
        // tick, which comes after the landing, so it plays there, once (review code-2 O10; it used to count as passed)
        f.land(141_500L);
        f.frame(1);
        assertEquals(Collections.singletonList(STEP + " 10.0 64.0 -3.0 0.15 1.0"), heard());
        f.frame(16);
        assertEquals(1, played.size());
        played.clear();

        f.land(100 * MS); // backward seek to before both
        f.frame(39); // P = 139
        assertTrue(played.isEmpty());
        f.frame(5); // P = 144
        assertEquals("each once, after the seek, at its own stamp", 2, played.size());
    }

    // ------------------------------------------------------------------ never both (B7)

    /**
     * A recording with SOUND records plays its stored footsteps and eat ticks only: the derived step rule is off and
     * the puppet's own tick sounds are swallowed (PuppetSounds), so each sound is heard once. A recording without them
     * (v1) has nothing for this part and derives its footsteps.
     */
    @Test
    public void neverBothDerivedAndStoredSounds() throws Exception {
        ExtRecords.Builder b = new ExtRecords.Builder()
                .add(0, 140 * MS, RewindFormat.TAG_SOUND, sound(STEP, 10f, 64f, -3f, 0.15f, 1f));
        LoadedRecording v2 = recording(b);
        LoadedRecording v1 = recording(null);

        StepSounds derived = new StepSounds();
        derived.forRecording(v2.ext());
        assertFalse("derived footsteps are off with stored sounds", derived.enabled());
        assertFalse("the puppet's own tick sound is not played next to the stored one",
                PuppetSounds.audible(false, StepSounds.storedSounds(v2.ext()), false));
        Frames f = new Frames(v2);
        f.land(100 * MS);
        f.frame(50);
        assertEquals("the stored step plays, once", Collections.singletonList(STEP + " 10.0 64.0 -3.0 0.15 1.0"),
                heard());

        played.clear();
        derived.forRecording(v1.ext());
        assertTrue("v1 derives its footsteps", derived.enabled());
        assertTrue(PuppetSounds.audible(false, StepSounds.storedSounds(v1.ext()), false));
        Frames g = new Frames(v1);
        g.land(100 * MS);
        g.frame(300);
        assertEquals("v1 holds nothing for this part", Collections.<ISound>emptyList(), played);
    }

    @Test
    public void registerAddsThePartOnce() {
        SoundPart.register();
        SoundPart.register();
        assertEquals(1, Collections.frequency(RewindPlayback.parts(), SoundPart.instance()));
    }
}
