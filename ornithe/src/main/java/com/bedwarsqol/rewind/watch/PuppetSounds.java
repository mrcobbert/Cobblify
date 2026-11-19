package com.bedwarsqol.rewind.watch;

/**
 * The recorded player's own sounds (PLAN Phase 8, B2, B7): the answer to the puppet's {@code playSound} question
 * ({@link PlaybackPart#puppetSound}, asked from {@link RewindSelfPuppet#playSound}).
 *
 * <p>The puppet is an other-player entity, and an other player's {@code playSound} is silent on a client
 * ({@code Entity.playSound} ends in an empty {@code RenderGlobal} call), so the hurt and death sounds S19 makes and the
 * eat and drink ticks of its own item use would be lost. Live, the same calls went through
 * {@code EntityPlayerSP.playSound}, which is {@code worldObj.playSound(posX, posY, posZ, name, volume, pitch, false)}
 * (javap @0-20); that is what this plays, at the puppet.
 * <ul>
 *   <li>A recording without SOUND records (format v1): every puppet sound plays.</li>
 *   <li>A recording with SOUND records: the sounds your own tick made are in the file (Phase 11 plays them), so a
 *       puppet sound plays only while a recorded packet is being fed ({@link TickFeeder#recordIndex()} is a NET
 *       record: hurt, death, the S19 item-use finish), never both.</li>
 *   <li>Never during a catch-up or seek ({@code PlaybackSession.soundsMuted}; the sound mixin mutes them too).</li>
 * </ul>
 * The question is always answered (true), so vanilla's silent default never runs as a second copy. Header packets
 * are fed only while the world is rebuilt, when everything is muted.
 *
 * <p>Register once with {@link #register()} (Phase 16 calls it from {@code BedwarsQol}). Client thread.
 */
public final class PuppetSounds implements PlaybackPart {

    /** Plays a sound at the puppet. */
    interface Speaker {
        void play(RewindSelfPuppet puppet, String name, float volume, float pitch);
    }

    /** What the rule reads from the session. */
    interface Source {
        /** A catch-up or rebuild is running. */
        boolean muted();

        /** The recording playing, or null. */
        LoadedRecording recording();

        /** The session's feeder, or null. */
        TickFeeder feeder();
    }

    private static final Source SESSION = new Source() {
        @Override
        public boolean muted() {
            return RewindPlayback.soundsMuted();
        }

        @Override
        public LoadedRecording recording() {
            return RewindPlayback.session().recording();
        }

        @Override
        public TickFeeder feeder() {
            return RewindPlayback.session().feeder();
        }
    };

    /** {@code EntityPlayerSP.playSound}: at the entity's position, not delayed. */
    private static final Speaker WORLD = (puppet, name, volume, pitch) -> {
        if (puppet != null && puppet.worldObj != null) {
            puppet.worldObj.playSound(puppet.posX, puppet.posY, puppet.posZ, name, volume, pitch, false);
        }
    };

    private static final PuppetSounds INSTANCE = new PuppetSounds(SESSION, WORLD);

    private final Source source;
    private final Speaker speaker;

    PuppetSounds(Source source, Speaker speaker) {
        this.source = source;
        this.speaker = speaker;
    }

    /** Registers the part with playback (once; repeated calls do nothing). Client thread. */
    public static void register() {
        RewindPlayback.register(INSTANCE);
    }

    /** The registered instance. */
    static PuppetSounds instance() {
        return INSTANCE;
    }

    @Override
    public boolean puppetSound(RewindSelfPuppet puppet, String name, float volume, float pitch) {
        LoadedRecording r = source.recording();
        if (audible(source.muted(), StepSounds.storedSounds(r == null ? null : r.ext()), feeding(source.feeder()))) {
            speaker.play(puppet, name, volume, pitch);
        }
        return true;
    }

    /** The rule: not muted, and either the recording stores no sounds or a recorded packet is being fed. */
    static boolean audible(boolean muted, boolean storedSounds, boolean feeding) {
        return !muted && (!storedSounds || feeding);
    }

    /** A recorded NET packet is being handed to vanilla right now. */
    static boolean feeding(TickFeeder f) {
        return f != null && f.recordIndex() != TickFeeder.HEADER;
    }
}
