package com.bedwarsqol.feature;

/**
 * Outgoing chat priority for the shared gatekeeper. Lower ordinal = higher priority.
 * Manual typed chat beats IncSender; both beat optional automation (SweatReport / AutoGG).
 */
public enum OutgoingChatPriority {
    MANUAL,
    INC,
    AUTO;

    public boolean beats(OutgoingChatPriority other) {
        return other != null && this.ordinal() < other.ordinal();
    }
}
