package com.bedwarsqol.feature;

/** Identifies which feature (or the player) originated a managed outgoing chat line. */
public enum OutgoingChatKind {
    MANUAL(OutgoingChatPriority.MANUAL),
    INC(OutgoingChatPriority.INC),
    SWEAT(OutgoingChatPriority.AUTO),
    AUTOGG(OutgoingChatPriority.AUTO);

    public final OutgoingChatPriority priority;

    OutgoingChatKind(OutgoingChatPriority priority) {
        this.priority = priority;
    }

    public boolean isOptionalAutomation() {
        return priority == OutgoingChatPriority.AUTO;
    }
}
