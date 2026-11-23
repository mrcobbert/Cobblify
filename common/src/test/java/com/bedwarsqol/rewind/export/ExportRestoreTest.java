package com.bedwarsqol.rewind.export;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** {@link ExportRestore} (TASK P7, S4): every saved value is put back once, also when a step throws. */
public class ExportRestoreTest {

    @Test
    public void everyStepRunsOnceInReverseOrder() {
        List<String> ran = new ArrayList<String>();
        ExportRestore r = new ExportRestore();
        r.add("vsync", () -> ran.add("vsync"));
        r.add("limit", () -> ran.add("limit"));
        r.add("blur", () -> ran.add("blur"));
        assertTrue(r.restore().isEmpty());
        assertEquals(Arrays.asList("blur", "limit", "vsync"), ran);
        assertTrue(r.restored());
        r.restore();
        assertEquals("a second restore does nothing", 3, ran.size());
    }

    @Test
    public void aThrowingStepDoesNotStopTheOthers() {
        List<String> ran = new ArrayList<String>();
        ExportRestore r = new ExportRestore();
        r.add("a", () -> ran.add("a"));
        r.add("b", () -> {
            throw new IllegalStateException("b");
        });
        r.add("c", () -> {
            ran.add("c");
            throw new Error("c");
        });
        r.add("d", () -> ran.add("d"));
        assertEquals(Arrays.asList("c", "b"), r.restore());
        assertEquals(Arrays.asList("d", "c", "a"), ran);
        r.restore();
        assertEquals(3, ran.size());
    }

    @Test
    public void aStepAddedAfterTheRestoreRunsAtOnce() {
        List<String> ran = new ArrayList<String>();
        ExportRestore r = new ExportRestore();
        r.restore();
        r.add("late", () -> ran.add("late"));
        assertEquals(Arrays.asList("late"), ran);
    }
}
