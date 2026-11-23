package com.bedwarsqol.rewind.export;

import com.bedwarsqol.rewind.export.audio.Mixer;
import com.bedwarsqol.rewind.export.audio.SoundEvent;
import com.bedwarsqol.rewind.export.audio.SoundLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.SoundEventAccessorComposite;
import net.minecraft.client.audio.SoundPoolEntry;
import net.minecraft.util.ResourceLocation;

/**
 * The export's sound tap (Rewind Export PLAN Solution 3; TASK S2, S4, S5). {@code SoundHandlerRewindMixin} asks it
 * first about every {@code SoundHandler.playSound} / {@code playDelayedSound}.
 *
 * <ul>
 *   <li>Off (the default; only the export job turns it on): {@link #take} returns false at once and vanilla, or the
 *       catch-up mute, runs as before.</li>
 *   <li>On: every sound is taken (the hook cancels it, so nothing reaches the speakers). During the seek and the
 *       pre-roll ({@link Context#discard()}) it is only cancelled. Otherwise a positioned (linear attenuation),
 *       non-repeating, non-streaming sound is logged: its variant picked through the public {@code SoundHandler}
 *       lookups, the time from {@link Context#eventTimeUs()} (plus {@code delay x 50 ms}), volume
 *       {@code clamp(sound x entry, 0, 1)} and pitch {@code clamp(sound x entry, 0.5, 2)} (the game's volume sliders
 *       are ignored, PLAN D5), range {@code 16 x max(1, sound volume)} and the listener at that moment. A sound whose
 *       variant is unknown or whose file cannot be decoded is counted and left out.</li>
 * </ul>
 *
 * Nothing here throws into the hook.
 */
public final class ExportSoundTap {

    /** Resolves a sound to a file variant and decodes files. */
    public interface Lookups {
        /** The variant played for {@code sound} (a fresh pick, as {@code SoundManager} would make), or null. */
        Variant pick(ISound sound);

        /** The decoded file, or null when it cannot be found or decoded. */
        Mixer.Pcm decode(String variant);
    }

    /** A picked sound file with its own volume and pitch factors. */
    public static final class Variant {
        final String key;
        final float volume;
        final float pitch;
        final boolean streaming;

        public Variant(String key, float volume, float pitch, boolean streaming) {
            this.key = key;
            this.volume = volume;
            this.pitch = pitch;
            this.streaming = streaming;
        }
    }

    /** The running export's view of time and of the listener. */
    public interface Context {
        /** Before frame 0 (seek, pre-roll): cancel, do not log. */
        boolean discard();

        /** When a sound played now happened on the recording's clock ({@code PlaybackSession.eventTimeUs()}). */
        long eventTimeUs();

        double listenerX();

        double listenerY();

        double listenerZ();

        /** Degrees, Minecraft's yaw (0 faces +z). */
        float listenerYaw();
    }

    private static final ExportSoundTap GAME = new ExportSoundTap(new GameLookups(new OggPcm(OggPcm.DEFAULT_CAPACITY)));

    private final Lookups lookups;
    private volatile SoundLog log;
    private volatile Context ctx;
    private int logged;
    private int skipped;
    private int discarded;
    private int missing;
    private int undecodable;

    public ExportSoundTap(Lookups lookups) {
        this.lookups = lookups;
    }

    /** The game's tap, the one the mixin asks. */
    public static ExportSoundTap game() {
        return GAME;
    }

    /** The mixin's entry: true when the sound must be cancelled. */
    public static boolean hook(ISound sound, int delayTicks) {
        return GAME.take(sound, delayTicks);
    }

    /** Turns the tap on: sounds go to {@code log}; counts start from zero. */
    public synchronized void start(SoundLog log, Context ctx) {
        logged = skipped = discarded = missing = undecodable = 0;
        this.ctx = ctx;
        this.log = log;
    }

    /** Turns the tap off; sounds play as normal again. */
    public synchronized void stop() {
        this.log = null;
        this.ctx = null;
    }

    public boolean on() {
        return log != null;
    }

    /**
     * Asked by the sound hooks. Returns false (not taken) when the tap is off; otherwise true (the hook cancels),
     * after logging the sound when the mix can use it.
     */
    public boolean take(ISound sound, int delayTicks) {
        if (log == null) {
            return false;
        }
        synchronized (this) {
            SoundLog l = log;
            Context c = ctx;
            if (l == null || c == null) {
                return false;
            }
            try {
                record(l, c, sound, delayTicks);
            } catch (RuntimeException e) {
                missing++;
            }
            return true;
        }
    }

    private void record(SoundLog l, Context c, ISound sound, int delayTicks) {
        if (c.discard()) {
            discarded++;
            return;
        }
        if (sound == null || sound.canRepeat() || sound.getAttenuationType() != ISound.AttenuationType.LINEAR) {
            skipped++;
            return;
        }
        Variant v = lookups.pick(sound);
        if (v == null) {
            missing++;
            return;
        }
        if (v.streaming) {
            skipped++;
            return;
        }
        if (lookups.decode(v.key) == null) {
            undecodable++;
            return;
        }
        float sv = sound.getVolume();
        float volume = clamp(sv * v.volume, 0f, 1f);
        float pitch = clamp(sound.getPitch() * v.pitch, 0.5f, 2f);
        float range = 16f * Math.max(1f, sv);
        SoundEvent e = new SoundEvent(c.eventTimeUs(), v.key, sound.getXPosF(), sound.getYPosF(), sound.getZPosF(),
                volume, pitch, range, c.listenerX(), c.listenerY(), c.listenerZ(), c.listenerYaw());
        if (l.add(e, delayTicks)) {
            logged++;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    /** The mixer's source: the same decoder and cache the tap checked the files with. */
    public Mixer.Source source() {
        return lookups::decode;
    }

    /** Sounds logged for the mix. */
    public synchronized int logged() {
        return logged;
    }

    /** Sounds cancelled but not for the mix: repeating, streaming or unpositioned. */
    public synchronized int skipped() {
        return skipped;
    }

    /** Sounds cancelled during the seek or the pre-roll. */
    public synchronized int discarded() {
        return discarded;
    }

    /** Sounds whose variant could not be picked (unknown, or the lookup failed). */
    public synchronized int missing() {
        return missing;
    }

    /** Sounds whose file could not be found or decoded. */
    public synchronized int undecodable() {
        return undecodable;
    }

    /** The game's lookups: {@code SoundHandler.getSound} -> {@code cloneEntry}, files from the resource manager. */
    static final class GameLookups implements Lookups {
        private final OggPcm ogg;

        GameLookups(OggPcm ogg) {
            this.ogg = ogg;
        }

        @Override
        public Variant pick(ISound sound) {
            ResourceLocation name = sound.getSoundLocation();
            SoundEventAccessorComposite composite = name == null ? null
                    : Minecraft.getMinecraft().getSoundHandler().getSound(name);
            SoundPoolEntry entry = composite == null ? null : composite.cloneEntry();
            if (entry == null || entry.getSoundPoolEntryLocation() == null) {
                return null;
            }
            return new Variant(entry.getSoundPoolEntryLocation().toString(), (float) entry.getVolume(),
                    (float) entry.getPitch(), entry.isStreamingSound());
        }

        @Override
        public Mixer.Pcm decode(final String variant) {
            return ogg.decode(variant, () -> Minecraft.getMinecraft().getResourceManager()
                    .getResource(new ResourceLocation(variant)).getInputStream());
        }
    }
}
