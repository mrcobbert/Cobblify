package com.bedwarsqol.rewind.export;

import java.util.ArrayList;
import java.util.List;

/**
 * What an export changes for its length, put back in one place (TASK P7, S4; Rewind Export PLAN Solution 2): each
 * saved value registers how to put it back; {@link #restore()} runs every step once, newest first, and a step that
 * throws does not stop the others. A step added after the restore runs at once. Game thread.
 */
public final class ExportRestore {

    /** Puts one saved value back. */
    public interface Undo {
        void run() throws Exception;
    }

    private final List<String> names = new ArrayList<String>();
    private final List<Undo> undos = new ArrayList<Undo>();
    private boolean restored;

    /** Registers how to put a value back (call right after changing it). */
    public void add(String name, Undo undo) {
        if (restored) {
            run(name, undo, new ArrayList<String>());
            return;
        }
        names.add(name);
        undos.add(undo);
    }

    /** Puts everything back once; the names of the steps that threw (empty when all went well). */
    public List<String> restore() {
        List<String> failed = new ArrayList<String>();
        if (restored) {
            return failed;
        }
        restored = true;
        for (int i = undos.size() - 1; i >= 0; i--) {
            run(names.get(i), undos.get(i), failed);
        }
        undos.clear();
        names.clear();
        return failed;
    }

    public boolean restored() {
        return restored;
    }

    private static void run(String name, Undo undo, List<String> failed) {
        try {
            undo.run();
        } catch (Throwable t) {
            failed.add(name);
        }
    }
}
