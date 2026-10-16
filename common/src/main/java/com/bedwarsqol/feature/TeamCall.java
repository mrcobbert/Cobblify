package com.bedwarsqol.feature;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The team calls Chat Notifications answers with a sound: "inc" (or "incoming"), "bed" and "bg", each as
 * a word in any case. Letters on either side disqualify, so "since", "beds", "bedwars" and "bgs" never
 * match, while "BED", "bed!!" and "Bed?" do. Each call has its own sound so the player can tell which
 * call it was.
 *
 * <p>Platform-neutral: the trees decide who counts (typed teammate or party chat during an active Bed
 * Wars game) and play the notes; {@link TeamCallAlerts} debounces and times them.
 */
public enum TeamCall {
    /** A rising double pling, three ticks apart: the original Inc Alert sound. */
    INC("inc(?:oming)?", new Note(0, "note.pling", 1.6f), new Note(3, "note.pling", 2.0f)),
    /** A quick triple pling, two ticks apart. */
    BED("bed", new Note(0, "note.pling", 1.2f), new Note(2, "note.pling", 1.2f), new Note(4, "note.pling", 1.2f)),
    /** One low bass note. */
    BG("bg", new Note(0, "note.bass", 1.0f));

    /** One sound of a call, played {@code tick} client ticks after the chat line arrives. */
    public static final class Note {
        public final int tick;
        public final String sound;
        public final float pitch;

        Note(int tick, String sound, float pitch) {
            this.tick = tick;
            this.sound = sound;
            this.pitch = pitch;
        }
    }

    private final Pattern word;
    private final List<Note> notes;

    TeamCall(String word, Note... notes) {
        this.word = Pattern.compile("(?i)(?<![a-z])" + word + "(?![a-z])");
        this.notes = Collections.unmodifiableList(Arrays.asList(notes));
    }

    /** This call's sounds, in tick order. */
    public List<Note> notes() {
        return notes;
    }

    /**
     * The one call a chat body plays: the first of Inc, Bed and BG that is enabled and in the body, or
     * null. A disabled call never shadows a lower one, so "bed inc" plays Bed when Inc is off.
     */
    public static TeamCall match(String body, boolean inc, boolean bed, boolean bg) {
        if (body == null) return null;
        if (inc && INC.word.matcher(body).find()) return INC;
        if (bed && BED.word.matcher(body).find()) return BED;
        if (bg && BG.word.matcher(body).find()) return BG;
        return null;
    }
}
