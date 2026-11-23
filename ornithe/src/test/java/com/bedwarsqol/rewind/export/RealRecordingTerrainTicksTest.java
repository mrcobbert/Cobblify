package com.bedwarsqol.rewind.export;

import com.bedwarsqol.rewind.watch.LoadedRecording;
import com.bedwarsqol.rewind.watch.Timeline;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertTrue;

/**
 * Rewind Export PLAN Phase 2, terrain stop rule (Q1 answer a): how often real games have ticks whose records hold chunk
 * data (0x21/0x26) or block changes (0x22/0x23), the ticks after which the best-effort settle rule holds a frame
 * ({@link RewindExport#terrainTicks}). Reads the folder named by {@code COBBLIFY_REWIND_SAMPLES} (skipped when unset).
 * Recordings are player data: it prints one aggregate line (counts per game second over all files: min, median, max),
 * nothing per file.
 */
public class RealRecordingTerrainTicksTest {

    @Test
    public void terrainTicksPerGameSecond() {
        File[] files = RealRecordingExportDryRunTest.samples();
        List<Double> chunkRate = new ArrayList<Double>();
        List<Double> blockRate = new ArrayList<Double>();
        List<Double> anyRate = new ArrayList<Double>();
        double seconds = 0;
        for (File cbr : files) {
            String name = cbr.getName();
            File json = new File(cbr.getParentFile(), name.substring(0, name.length() - 4) + ".json");
            LoadedRecording r = LoadedRecording.load(cbr, json.isFile() ? json : null);
            if (r.problem() != null) {
                continue;
            }
            try {
                Timeline tl = r.timeline();
                long start = tl.startTick();
                long end = tl.endTick();
                BitSet chunk = new BitSet();
                BitSet block = new BitSet();
                RewindExport.terrainTicks(r, tl, chunk, block);
                int c = chunk.get((int) start + 1, (int) end + 1).cardinality();
                int b = block.get((int) start + 1, (int) end + 1).cardinality();
                BitSet any = (BitSet) chunk.clone();
                any.or(block);
                int a = any.get((int) start + 1, (int) end + 1).cardinality();
                double s = (end - start) / 20.0;
                assertTrue(s > 0);
                seconds += s;
                chunkRate.add(c / s);
                blockRate.add(b / s);
                anyRate.add(a / s);
            } finally {
                r.close();
            }
        }
        assertTrue("no playable sample", !anyRate.isEmpty());
        System.out.println(String.format(Locale.ROOT,
                "terrain ticks per game second over %d games (%.0f s): chunk %s, block %s, either %s",
                anyRate.size(), seconds, spread(chunkRate), spread(blockRate), spread(anyRate)));
    }

    private static String spread(List<Double> xs) {
        List<Double> s = new ArrayList<Double>(xs);
        Collections.sort(s);
        return String.format(Locale.ROOT, "min %.2f median %.2f max %.2f", s.get(0), s.get(s.size() / 2),
                s.get(s.size() - 1));
    }
}
