package com.bedwarsqol.gui;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Undo and redo over whole-state snapshots. Record the state just before each change; undo hands
 * back the previous state and keeps the current one for redo. A new change clears redo.
 */
public final class EditHistory<T> {

    private final Deque<T> undo = new ArrayDeque<T>();
    private final Deque<T> redo = new ArrayDeque<T>();
    private final int limit;

    public EditHistory(int limit) {
        this.limit = Math.max(1, limit);
    }

    /** Remembers {@code before}, the state just before a change. The oldest entry drops past the limit. */
    public void record(T before) {
        undo.push(before);
        while (undo.size() > limit) undo.removeLast();
        redo.clear();
    }

    /** The state to restore for an undo, or {@code null} when there is none. */
    public T undo(T current) {
        if (undo.isEmpty()) return null;
        redo.push(current);
        return undo.pop();
    }

    /** The state to restore for a redo, or {@code null} when there is none. */
    public T redo(T current) {
        if (redo.isEmpty()) return null;
        undo.push(current);
        return redo.pop();
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }
}
