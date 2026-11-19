package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RewindFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.util.ResourceLocation;

/**
 * Your own sounds in playback (PLAN Phase 11, "Tick scheduler", round 2 I2; TASK C3, B7): each stored SOUND (a
 * positioned, non-repeating sound your own tick, click, dig, place or use played live) plays once, in the first drawn
 * frame whose playback time has reached its own stamp, at its recorded position, volume and pitch.
 *
 * <p>SOUND is time-scoped: the session's {@link TickClock} hands it to {@link #timed} when playback time reaches its
 * stamp (not at the start of its tick) and never in a catch-up or seek (the dispatch cursor is moved past what a seek
 * skips). The sound is vanilla's {@code PositionedSoundRecord(name, volume, pitch, x, y, z)} (linear attenuation, no
 * repeat: what {@code WorldClient.playSound} and the dig hit sound build) into {@code SoundHandler.playSound}, where
 * the session's catch-up mute ({@code SoundHandlerRewindMixin}) still applies; the part checks the mute too.
 *
 * <p>Never both: a recording with SOUND records turns the derived footsteps and the puppet's own tick sounds off
 * ({@link StepSounds#storedSounds}, Phase 8), so each sound is heard once, from here. Register once with
 * {@link #register()} (Phase 16). Client thread; never sends.
 */
public final class SoundPart implements PlaybackPart {

    /** What the part reads from the session. */
    interface Source {
        /** A catch-up or rebuild is running. */
        boolean muted();
    }

    /** Where the sound goes ({@code SoundHandler.playSound}). */
    interface Speaker {
        void play(ISound sound);
    }

    private static final Speaker CLIENT = sound -> {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.getSoundHandler() != null) {
            mc.getSoundHandler().playSound(sound);
        }
    };

    private static final SoundPart INSTANCE = new SoundPart(RewindPlayback::soundsMuted, CLIENT);

    private final Source source;
    private final Speaker speaker;
    private final ExtCodec.Sound record = new ExtCodec.Sound();

    SoundPart(Source source, Speaker speaker) {
        this.source = source;
        this.speaker = speaker;
    }

    /** Registers the part with playback (once; repeated calls do nothing). Client thread. */
    public static void register() {
        RewindPlayback.register(INSTANCE);
    }

    /** The registered instance. */
    static SoundPart instance() {
        return INSTANCE;
    }

    @Override
    public void timed(int tag, long timeUs, byte[] buf, int off, int len) {
        if (tag != RewindFormat.TAG_SOUND || source.muted()) {
            return;
        }
        record.read(buf, off, len);
        speaker.play(new PositionedSoundRecord(new ResourceLocation(record.name), record.volume, record.pitch,
                record.x, record.y, record.z));
    }
}
