package com.bedwarsqol.rewind.export;

import com.bedwarsqol.rewind.export.audio.Mixer;
import com.bedwarsqol.rewind.export.audio.SoundEvent;
import com.bedwarsqol.rewind.export.audio.SoundLog;
import net.minecraft.client.audio.ISound;
import net.minecraft.util.ResourceLocation;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link ExportSoundTap} (Rewind Export PLAN Solution 3, Phase 4; TASK S2, S4, S5): while an export renders every
 * sound the replay plays is cancelled, and the ones the mix can use are logged with their resolved variant, time,
 * clamped volume and pitch, range and listener; while it is off the tap takes nothing.
 */
public class ExportSoundTapTest {

    private static final Mixer.Pcm PCM = new Mixer.Pcm(44_100, 1, new short[]{1, 2, 3});

    private final Map<String, ExportSoundTap.Variant> variants = new HashMap<>();
    private final Map<String, Mixer.Pcm> decoded = new HashMap<>();
    private final List<String> calls = new ArrayList<>();
    private boolean lookupThrows;

    private final ExportSoundTap.Lookups lookups = new ExportSoundTap.Lookups() {
        @Override
        public ExportSoundTap.Variant pick(ISound sound) {
            calls.add("pick " + sound.getSoundLocation());
            if (lookupThrows) throw new IllegalStateException("broken lookup");
            return variants.get(sound.getSoundLocation().toString());
        }

        @Override
        public Mixer.Pcm decode(String variant) {
            calls.add("decode " + variant);
            return decoded.get(variant);
        }
    };

    /** Time, pre-roll flag and listener chosen by the test. */
    private static final class Ctx implements ExportSoundTap.Context {
        long timeUs = 1_200_000L;
        boolean discard;

        @Override
        public boolean discard() {
            return discard;
        }

        @Override
        public long eventTimeUs() {
            return timeUs;
        }

        @Override
        public double listenerX() {
            return 10.5;
        }

        @Override
        public double listenerY() {
            return 64;
        }

        @Override
        public double listenerZ() {
            return -3;
        }

        @Override
        public float listenerYaw() {
            return 45f;
        }
    }

    private ExportSoundTap tap;
    private SoundLog log;
    private Ctx ctx;

    @Before
    public void setUp() {
        tap = new ExportSoundTap(lookups);
        log = new SoundLog(1_000_000L, 5_000_000L);
        ctx = new Ctx();
        variants.put("minecraft:dig.stone",
                new ExportSoundTap.Variant("minecraft:sounds/dig/stone2.ogg", 0.8f, 1.2f, false));
        variants.put("minecraft:music.game",
                new ExportSoundTap.Variant("minecraft:sounds/music/game/calm1.ogg", 1f, 1f, true));
        variants.put("minecraft:broken", new ExportSoundTap.Variant("minecraft:sounds/broken.ogg", 1f, 1f, false));
        decoded.put("minecraft:sounds/dig/stone2.ogg", PCM);
    }

    private static ISound sound(String name, boolean repeat, ISound.AttenuationType att, float volume, float pitch) {
        final ResourceLocation loc = new ResourceLocation(name);
        return new ISound() {
            @Override
            public ResourceLocation getSoundLocation() {
                return loc;
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
                return volume;
            }

            @Override
            public float getPitch() {
                return pitch;
            }

            @Override
            public float getXPosF() {
                return 12f;
            }

            @Override
            public float getYPosF() {
                return 65f;
            }

            @Override
            public float getZPosF() {
                return -7.5f;
            }

            @Override
            public ISound.AttenuationType getAttenuationType() {
                return att;
            }
        };
    }

    @Test
    public void aPositionedSoundIsLoggedOnceWithItsVariantAndCancelled() {
        tap.start(log, ctx);
        assertTrue(tap.take(sound("dig.stone", false, ISound.AttenuationType.LINEAR, 1.5f, 1.9f), 0));
        List<SoundEvent> e = log.events();
        assertEquals(1, e.size());
        SoundEvent s = e.get(0);
        assertEquals(1_200_000L, s.timeUs());
        assertEquals("minecraft:sounds/dig/stone2.ogg", s.sound());
        assertEquals(12.0, s.x(), 1e-6);
        assertEquals(65.0, s.y(), 1e-6);
        assertEquals(-7.5, s.z(), 1e-6);
        assertEquals(1.0f, s.volume(), 1e-6); // 1.5 x 0.8 = 1.2, clamped to 1
        assertEquals(2.0f, s.pitch(), 1e-6); // 1.9 x 1.2 = 2.28, clamped to 2
        assertEquals(24f, s.range(), 1e-6); // 16 x max(1, 1.5)
        assertEquals(10.5, s.listenerX(), 1e-9);
        assertEquals(64.0, s.listenerY(), 1e-9);
        assertEquals(-3.0, s.listenerZ(), 1e-9);
        assertEquals(45f, s.listenerYaw(), 1e-6);
        assertEquals(1, tap.logged());
    }

    @Test
    public void quietAndLowSoundsAreClampedTheOtherWay() {
        tap.start(log, ctx);
        tap.take(sound("dig.stone", false, ISound.AttenuationType.LINEAR, 0.5f, 0.3f), 0);
        SoundEvent s = log.events().get(0);
        assertEquals(0.4f, s.volume(), 1e-6); // 0.5 x 0.8
        assertEquals(0.5f, s.pitch(), 1e-6); // 0.3 x 1.2 = 0.36, clamped to 0.5
        assertEquals(16f, s.range(), 1e-6); // 16 x max(1, 0.5)
    }

    @Test
    public void aDelayedSoundStartsLaterByItsDelay() {
        tap.start(log, ctx);
        assertTrue(tap.take(sound("dig.stone", false, ISound.AttenuationType.LINEAR, 1f, 1f), 4));
        assertEquals(1_400_000L, log.events().get(0).timeUs());
    }

    @Test
    public void repeatingStreamingAndUnpositionedSoundsAreCancelledButNotLogged() {
        tap.start(log, ctx);
        assertTrue(tap.take(sound("dig.stone", true, ISound.AttenuationType.LINEAR, 1f, 1f), 0));
        assertTrue(tap.take(sound("music.game", false, ISound.AttenuationType.LINEAR, 1f, 1f), 0));
        assertTrue(tap.take(sound("dig.stone", false, ISound.AttenuationType.NONE, 1f, 1f), 0));
        assertTrue(log.events().isEmpty());
        assertEquals(3, tap.skipped());
        assertEquals(0, tap.logged());
    }

    @Test
    public void theTapTakesNothingWhileOff() {
        assertFalse(tap.on());
        assertFalse(tap.take(sound("dig.stone", false, ISound.AttenuationType.LINEAR, 1f, 1f), 0));
        assertTrue(calls.isEmpty());
        tap.start(log, ctx);
        assertTrue(tap.on());
        tap.stop();
        assertFalse(tap.on());
        assertFalse(tap.take(sound("dig.stone", false, ISound.AttenuationType.LINEAR, 1f, 1f), 0));
        assertTrue(calls.isEmpty());
        assertTrue(log.events().isEmpty());
    }

    @Test
    public void unknownSoundsAndDecodeFailuresAreCountedNotLogged() {
        tap.start(log, ctx);
        assertTrue(tap.take(sound("no.such.sound", false, ISound.AttenuationType.LINEAR, 1f, 1f), 0));
        assertTrue(tap.take(sound("broken", false, ISound.AttenuationType.LINEAR, 1f, 1f), 0));
        lookupThrows = true;
        assertTrue(tap.take(sound("dig.stone", false, ISound.AttenuationType.LINEAR, 1f, 1f), 0));
        assertTrue(log.events().isEmpty());
        assertEquals(2, tap.missing());
        assertEquals(1, tap.undecodable());
    }

    @Test
    public void duringThePrerollSoundsAreCancelledAndDiscarded() {
        ctx.discard = true;
        tap.start(log, ctx);
        assertTrue(tap.take(sound("dig.stone", false, ISound.AttenuationType.LINEAR, 1f, 1f), 0));
        assertTrue(log.events().isEmpty());
        assertEquals(1, tap.discarded());
        assertTrue(calls.isEmpty());
    }

    @Test
    public void theMixersSourceDecodesThroughTheSameLookups() {
        assertEquals(PCM, tap.source().pcm("minecraft:sounds/dig/stone2.ogg"));
    }
}
