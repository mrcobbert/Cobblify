package com.bedwarsqol.rewind.watch;

import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.network.NetworkPlayerInfo;

/**
 * One piece of Rewind playback behaviour (PLAN "B-seam"): later phases add a class that implements this and register
 * it once ({@link RewindPlayback#register}), instead of editing the sink, the camera or the viewer. Every method has a
 * default that does nothing, so a part overrides only what it needs. All calls come on the client thread, only while a
 * recording plays; a part that throws is logged once (counts only) and keeps being called.
 *
 * <p><b>Lifecycle</b>, per session:
 * <ol>
 *   <li>{@link #restart()}: a fresh playback connection (opening, every backward seek), before the headers are fed.
 *       Forget everything world-bound.</li>
 *   <li>{@link #boundary()}: after every S01/S07 (a new world and a new camera), after the session's own boundary
 *       hook (the camera's id, the game type, the puppet).</li>
 *   <li>Per world tick k, in this order (PLAN "Event order"): the NET records handled before tick k (vanilla's
 *       handlers), then {@link #local} for each tick-scoped extension record stamped in tick k's interval (OUT, DIG,
 *       BLOCK, USE, INV, CONT, HUD, SETTINGS, IDENTITY), then {@link #tick} (after the puppet and the hand took tick
 *       k's end state), then the world tick. The same records reach the same ticks however the tick was reached
 *       (straight play, a forward catch-up, a rebuild), so a part that only reacts to these keeps seek
 *       equivalence.</li>
 *   <li>Per drawn frame: {@link #timed} for each time-scoped extension record (SOUND, TAP, VIEW, MOUSE, CHAT_INPUT,
 *       FOV, INPUT) whose stamp playback time has reached, never during a catch-up or seek (read state such as the
 *       recorded VIEW by time from {@code LoadedRecording.ext()} after a seek); then {@link #frame} after the camera
 *       took its pose; {@link #drawUnder} under the viewer bar and {@link #drawOver} over it.</li>
 *   <li>{@link #viewChanged()}: the view changed ({@link ViewState}: your view, free camera, ridden, F5, clean
 *       view), applied at once even while paused.</li>
 *   <li>{@link #exportStart()}: an export's start has landed, before its pre-roll.</li>
 *   <li>{@link #exit()}: the session ends (every path). Put back anything you changed outside the playback world.</li>
 * </ol>
 *
 * <p><b>Questions</b> the session asks every part in registration order; the first non-default answer wins:
 * {@link #cyclePlayer} (keys 3 / Shift+3), {@link #puppetSound} (the recorded player's {@code playSound}),
 * {@link #playerInfo} (the camera's and the puppet's tab entry: skin, arm model).
 *
 * <p>Isolation still holds: a part never sends (the playback connection discards writes anyway), never opens a screen
 * ({@code mc.currentScreen} stays Rewind's), never calls a live tracker, and does nothing when no recording plays.
 */
public interface PlaybackPart {

    /** A fresh playback connection (opening, backward seek), before its headers. */
    default void restart() {
    }

    /** After each S01/S07 and the session's boundary hook. */
    default void boundary() {
    }

    /**
     * A tick-scoped extension record ({@code ExtRecords.Scope.TICK}) in the local phase of the tick its stamp falls
     * in. {@code buf[off, off + len)} is the body ({@code ExtCodec}'s decoders read it), valid during the call only.
     */
    default void local(int tag, long timeUs, byte[] buf, int off, int len) {
    }

    /** World tick {@code tick} is about to run ({@code timeUs} = T[k]); its records and the puppet's sample are in. */
    default void tick(long tick, long timeUs) {
    }

    /** A time-scoped extension record ({@code ExtRecords.Scope.TIME}), once, when playback time reached its stamp. */
    default void timed(int tag, long timeUs, byte[] buf, int off, int len) {
    }

    /** Each drawn frame while a world plays, after the camera's pose: playback time and the partial tick drawn. */
    default void frame(long timeUs, float partial) {
    }

    /** The view changed ({@code RewindPlayback.session().view()}). */
    default void viewChanged() {
    }

    /** The viewer's underlay: drawn before the bar, in GUI pixels ({@code width} x {@code height}). */
    default void drawUnder(int width, int height, int mouseX, int mouseY, float partial) {
    }

    /** The viewer's overlay: drawn after the bar. */
    default void drawOver(int width, int height, int mouseX, int mouseY, float partial) {
    }

    /** Key 3 ({@code direction} 1) or Shift+3 (-1): ride the next or previous player. @return true if handled */
    default boolean cyclePlayer(int direction) {
        return false;
    }

    /**
     * The recorded player's puppet plays a sound ({@code Entity.playSound}; silent on a client by default).
     *
     * @return true if this part played or swallowed it (the default silence is skipped)
     */
    default boolean puppetSound(RewindSelfPuppet puppet, String name, float volume, float pitch) {
        return false;
    }

    /** The tab entry {@code player} (the camera or the puppet) is drawn with, or null for vanilla's own lookup. */
    default NetworkPlayerInfo playerInfo(AbstractClientPlayer player) {
        return null;
    }

    /**
     * An export starts (Rewind Export PLAN Solution 1 "Pre-roll"): after the session's seek to the pre-roll has landed
     * and before the pre-roll's first frame. Forget state that draws build from what was watched (a fade, a flash),
     * so the export's frames are the same whatever was watched before; the pre-roll rebuilds it.
     */
    default void exportStart() {
    }

    /** The session ends. */
    default void exit() {
    }
}
