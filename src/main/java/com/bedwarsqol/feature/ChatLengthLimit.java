package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.stats.HypixelContext;

/**
 * Single source of truth for how long an outgoing chat message may be.
 *
 * <p>1.8.9 clamps chat to 100 characters in two independent places, both purely client-side:
 * {@code GuiChat.initGui} caps the input field, and the {@code C01PacketChatMessage(String)}
 * constructor substrings anything longer. Neither limit comes from the server — the wire format is a
 * plain length-prefixed string, and every version since 1.11 has used 256. Hypixel's backend runs a
 * modern version, so it decodes with the 256 limit.
 *
 * <p>{@code mixin/GuiChatMixin} lifts the first clamp and {@code mixin/C01PacketChatMessageMixin}
 * lifts the second, both asking this class for the ceiling. Raising it is gated on Hypixel: a real
 * 1.8 server reads the packet with {@code readStringFromBuffer(100)} and would drop the connection.
 * We never go above {@link #EXTENDED} so the client stays at modern-vanilla parity.
 */
public final class ChatLengthLimit {

    /** The vanilla 1.8.9 cap, and the fallback whenever the extension does not apply. */
    public static final int VANILLA = 100;

    /** The modern-vanilla cap, and the hard ceiling we will ever ask for. */
    public static final int EXTENDED = 256;

    private ChatLengthLimit() {}

    /** True when the extended cap applies right now (setting on and connected to Hypixel). */
    public static boolean extended() {
        ClientSettings cfg = BedwarsQol.config;
        return cfg != null && cfg.chatLongMessages && HypixelContext.isOnHypixel();
    }

    /** Maximum characters an outgoing chat message may carry right now. */
    public static int limit() {
        return extended() ? EXTENDED : VANILLA;
    }

    /** Trims {@code message} to {@code max}, leaving shorter text (and null) untouched. */
    public static String clamp(String message, int max) {
        if (message == null || message.length() <= max) return message;
        return message.substring(0, max);
    }
}
