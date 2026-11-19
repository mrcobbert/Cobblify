package com.bedwarsqol.rewind;

import com.bedwarsqol.rewind.watch.PlaybackGate;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.util.ResourceLocation;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A6 and A12: a positioned, non-repeating, distance-attenuated sound played inside a local scope (the player's own
 * tick or click) is stored with name, position, volume and pitch; outside a scope (a server packet's sound, ambient),
 * unpositioned (music, GUI clicks) or repeating it is not; replaying a known name allocates nothing.
 */
public class LocalSoundCaptureTest {

    private RecordingCaptureSink sink;

    @Before
    public void setUp() {
        sink = new RecordingCaptureSink();
        ActionCapture.bind(sink, null, null);
        ActionCapture.startSegment(1);
    }

    @After
    public void tearDown() {
        ActionCapture.unbind();
        if (PlaybackGate.global().isMuted()) {
            PlaybackGate.global().exit();
        }
    }

    /** A sound with every field chosen by the test. */
    static ISound sound(final ResourceLocation name, final boolean repeat, final ISound.AttenuationType att,
                        final float x, final float y, final float z) {
        return new ISound() {
            @Override
            public ResourceLocation getSoundLocation() {
                return name;
            }

            @Override
            public boolean canRepeat() {
                return repeat;
            }

            @Override
            public int getRepeatDelay() {
                return 0;
            }

            @Override
            public float getVolume() {
                return 0.15f;
            }

            @Override
            public float getPitch() {
                return 1.25f;
            }

            @Override
            public float getXPosF() {
                return x;
            }

            @Override
            public float getYPosF() {
                return y;
            }

            @Override
            public float getZPosF() {
                return z;
            }

            @Override
            public ISound.AttenuationType getAttenuationType() {
                return att;
            }
        };
    }

    @Test
    public void aPositionedSoundInsideALocalScopeIsStored() {
        ActionCapture.enterLocal();
        ActionCapture.onSound(new PositionedSoundRecord(new ResourceLocation("step.stone"), 0.15f, 1f, 1.5f, 64f,
                -2.5f));
        ActionCapture.exitLocal();
        List<RecordingCaptureSink.Rec> s = sink.ofTag(RewindFormat.TAG_SOUND);
        assertEquals(1, s.size());
        ExtCodec.Sound d = new ExtCodec.Sound();
        d.read(s.get(0).body, 0, s.get(0).body.length);
        assertEquals("minecraft:step.stone", d.name);
        assertEquals(1.5f, d.x, 0f);
        assertEquals(64f, d.y, 0f);
        assertEquals(-2.5f, d.z, 0f);
        assertEquals(0.15f, d.volume, 0f);
        assertEquals(1f, d.pitch, 0f);
    }

    @Test
    public void outsideAScopeUnpositionedOrRepeatingIsNotStored() {
        ResourceLocation dig = new ResourceLocation("dig.stone");
        ActionCapture.onSound(sound(dig, false, ISound.AttenuationType.LINEAR, 1, 2, 3)); // no scope
        ActionCapture.enterLocal();
        ActionCapture.onSound(PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 1f)); // GUI
        ActionCapture.onSound(sound(dig, false, ISound.AttenuationType.NONE, 1, 2, 3)); // music-like
        ActionCapture.onSound(sound(dig, true, ISound.AttenuationType.LINEAR, 1, 2, 3)); // repeating
        ActionCapture.onSound(null);
        sink.recording = false;
        ActionCapture.onSound(sound(dig, false, ISound.AttenuationType.LINEAR, 1, 2, 3)); // segment not recorded
        sink.recording = true;
        PlaybackGate.global().enter();
        ActionCapture.onSound(sound(dig, false, ISound.AttenuationType.LINEAR, 1, 2, 3)); // a recording plays
        PlaybackGate.global().exit();
        ActionCapture.exitLocal();
        assertEquals(0, sink.appends);
        ActionCapture.enterLocal();
        ActionCapture.onSound(sound(dig, false, ISound.AttenuationType.LINEAR, 1, 2, 3));
        ActionCapture.exitLocal();
        assertEquals(1, sink.appends);
    }

    @Test
    public void aSoundFromAnotherThreadIsNotStored() throws Exception {
        ActionCapture.enterLocal();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                ActionCapture.onSound(sound(new ResourceLocation("dig.stone"), false, ISound.AttenuationType.LINEAR,
                        0, 0, 0));
            }
        });
        t.start();
        t.join();
        ActionCapture.exitLocal();
        assertEquals(0, sink.appends);
    }

    static volatile Object keepAlive;

    @Test(timeout = 120_000)
    public void aKnownSoundNameAllocatesNothing() {
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long self = Thread.currentThread().getId();
        long before = mx.getThreadAllocatedBytes(self);
        for (int i = 0; i < 100_000; i++) {
            keepAlive = new byte[1 + (i & 7)];
        }
        assertTrue("allocation meter works", mx.getThreadAllocatedBytes(self) - before >= 100_000 * 16L);

        sink.keep = false;
        ActionCapture.bind(sink, null, null); // a timed test runs on its own thread: make it the client thread
        ActionCapture.startSegment(1);
        ISound[] sounds = {
                sound(new ResourceLocation("step.stone"), false, ISound.AttenuationType.LINEAR, 1, 2, 3),
                sound(new ResourceLocation("dig.wool"), false, ISound.AttenuationType.LINEAR, 4, 5, 6),
                sound(new ResourceLocation("random.eat"), false, ISound.AttenuationType.LINEAR, 7, 8, 9)};
        ActionCapture.enterLocal();
        for (int i = 0; i < 300_000; i++) { // first-seen names, codec growth, JIT warm-up
            ActionCapture.onSound(sounds[i % 3]);
        }
        long best = Long.MAX_VALUE;
        for (int round = 0; round < 3; round++) {
            long a = mx.getThreadAllocatedBytes(self);
            for (int i = 0; i < 100_000; i++) {
                ActionCapture.onSound(sounds[i % 3]);
            }
            best = Math.min(best, mx.getThreadAllocatedBytes(self) - a);
        }
        ActionCapture.exitLocal();
        assertEquals(600_000, sink.appends);
        assertTrue("100k known sounds allocate " + best + " B", best < 50_000);
    }
}
