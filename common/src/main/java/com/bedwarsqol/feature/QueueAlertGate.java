package com.bedwarsqol.feature;

import com.bedwarsqol.config.ClientSettings;

/**
 * Which pregame-queue alerts the settings ask for. The queue has no toggle of its own: tag lines follow
 * Tag Utils' Chat Alert for whichever sources are on, and nick lines follow Nick Utils' Nick Notify -
 * the same switches that cover the lobby and the game. Pure, so both trees share it.
 */
public final class QueueAlertGate {

    private QueueAlertGate() {}

    /** Queue tag lines: Tag Utils' Chat Alert, with at least one source on. */
    public static boolean wantsTags(ClientSettings cfg) {
        return cfg != null && cfg.tagChatAlert && (cfg.urchinOn() || cfg.seraphOn());
    }

    /** Queue nick lines: Nick Utils' Nick Notify. */
    public static boolean wantsNicks(ClientSettings cfg) {
        return cfg != null && cfg.nickUtils && cfg.nickNotify;
    }
}
