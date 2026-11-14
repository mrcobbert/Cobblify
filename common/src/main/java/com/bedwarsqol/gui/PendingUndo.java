package com.bedwarsqol.gui;

/**
 * An undo step made outside the editor, offered to the next Edit HUD session: Reset Positions from the settings menu
 * can be undone in Edit HUD if the layout has not changed since (D7). Taken once, whether or not it applies.
 */
public final class PendingUndo<T> {

    private T before;
    private T after;

    /** Offers the state {@code before} a change that left {@code after}. */
    public void offer(T before, T after) {
        this.before = before;
        this.after = after;
    }

    /** The offered before-state if {@code current} still equals the after-state, else null; clears the offer. */
    public T take(T current) {
        T b = before, a = after;
        before = null;
        after = null;
        return b != null && a != null && a.equals(current) ? b : null;
    }
}
