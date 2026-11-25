package com.bedwarsqol.event;

/**
 * Base for events a listener may cancel. A canceled event still reaches listeners registered with
 * {@code receiveCanceled}; the code that posted it decides what canceling means (for chat: the line
 * is not printed).
 */
public abstract class CancelableEvent {

    private boolean canceled;

    public boolean isCanceled() {
        return canceled;
    }

    public void setCanceled(boolean canceled) {
        this.canceled = canceled;
    }
}
