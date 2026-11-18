package com.bedwarsqol.feature;

import com.bedwarsqol.rewind.watch.PlaybackGate;
import net.minecraft.client.Minecraft;

/**
 * The only way feature code puts a callback on the client thread (PLAN "Isolation", layer 4; Rewind R2).
 *
 * <p>The callback carries the {@link PlaybackGate.Ticket} captured <i>where its work was submitted</i> (a chat line,
 * a command, the Netty handler that saw a packet), not when the callback is scheduled. When the client thread drains
 * it, it runs only if the gate is unmuted, not entering, and still in that ticket's epoch. So work submitted before a
 * Rewind recording started playing can never print, sound or open anything during playback or after it, whenever it
 * finishes; work submitted after playback runs as before. There is deliberately no overload without a ticket.
 * {@code QueuedWorkScanTest} fails on any other {@code addScheduledTask} in the feature, stats, command and hud
 * packages.
 */
public final class FeatureTasks {

    private FeatureTasks() {
    }

    /**
     * Runs {@code r} on the client thread if {@code ticket} is still current when it is drained (inline when called
     * on the client thread, as {@code addScheduledTask} does). Returns false, doing nothing, when there is no
     * Minecraft instance. A null ticket never runs.
     */
    public static boolean schedule(PlaybackGate.Ticket ticket, Runnable r) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return false;
        mc.addScheduledTask(guarded(PlaybackGate.global(), ticket, r));
        return true;
    }

    /** {@code r}, run only if {@code ticket} is current on {@code gate} at the moment the returned task runs. */
    public static Runnable guarded(final PlaybackGate gate, final PlaybackGate.Ticket ticket, final Runnable r) {
        return new Runnable() {
            @Override
            public void run() {
                if (gate.isCurrent(ticket)) r.run();
            }
        };
    }
}
