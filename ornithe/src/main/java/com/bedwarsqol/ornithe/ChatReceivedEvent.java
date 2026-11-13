package com.bedwarsqol.ornithe;

import com.bedwarsqol.event.CancelableEvent;
import net.minecraft.util.IChatComponent;

/**
 * A chat packet from the server, posted on the client thread before vanilla prints it (Forge's
 * {@code ClientChatReceivedEvent}). {@code type} 2 is the action bar. Listeners may edit
 * {@code message} in place; canceling stops vanilla from printing it.
 */
public final class ChatReceivedEvent extends CancelableEvent {

    public final IChatComponent message;
    public final byte type;

    public ChatReceivedEvent(byte type, IChatComponent message) {
        this.type = type;
        this.message = message;
    }
}
