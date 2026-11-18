package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RecordingMeta;
import com.bedwarsqol.rewind.RecordingStore;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Coordinator amendment 2, end to end on the real recordings in {@code COBBLIFY_REWIND_SAMPLES} (skipped when unset):
 * the gallery ({@link RecordingLibrary#list()} into {@link GalleryModel}) and Watch Last Game
 * ({@link RecordingLibrary#newest()}, {@link RecordingLibrary#newestAvailability()} after a
 * {@link RecordingLibrary#refresh()}) never show a Hypixel replay-viewer visit (serverType REPLAY), and nothing in the
 * folder is changed. Output is counts only: no id, name, map or chat.
 */
public class RealRecordingLibraryTest {

    @Test
    public void galleryAndWatchLastGameSkipReplayViewerVisits() throws Exception {
        String dir = System.getenv("COBBLIFY_REWIND_SAMPLES");
        Assume.assumeTrue("COBBLIFY_REWIND_SAMPLES unset", dir != null && !dir.trim().isEmpty());
        File folder = new File(dir.trim());
        Map<String, String> before = snapshot(folder);
        Assume.assumeTrue("no recordings", !before.isEmpty());

        RecordingStore store = new RecordingStore(folder);
        int closed = 0, replay = 0;
        boolean replayIsNewest = false;
        long newestStart = Long.MIN_VALUE;
        for (String id : store.listIds()) {
            RecordingMeta m = store.readMeta(id);
            if (m == null || !m.complete || !store.hasCbr(id)) continue;
            closed++;
            boolean isReplay = ModeLabels.isReplay(m);
            if (isReplay) replay++;
            if (m.startedAtMs > newestStart) {
                newestStart = m.startedAtMs;
                replayIsNewest = isReplay;
            }
        }

        RecordingLibrary lib = new RecordingLibrary(store);
        int listed = lib.list().size();
        for (RecordingMeta m : lib.list()) {
            assertFalse("a REPLAY recording is listed", ModeLabels.isReplay(m));
        }
        GalleryModel gallery = new GalleryModel(GalleryModel.Origin.TITLE, lib.list(), System.currentTimeMillis(),
                TimeZone.getDefault());
        RecordingLibrary.NewestGame newest = lib.newest();
        assertNotNull(newest);
        assertFalse("Watch Last Game's game is a REPLAY recording", ModeLabels.isReplay(newest.meta));
        int read = lib.refresh();
        WatchEntryRules.Newest availability = lib.newestAvailability();

        System.out.println("rewind library: closed=" + closed + " replay=" + replay + " replayIsNewest="
                + replayIsNewest + " listed=" + listed + " galleryRows=" + gallery.entries().size()
                + " quickChecked=" + read + " newest=" + availability);
        assertTrue("the folder has a REPLAY recording to skip", replay > 0);
        assertEquals("every closed recording but the REPLAY ones", closed - replay, listed);
        assertEquals(listed, gallery.entries().size());
        assertEquals("refresh reads every listed recording, never a REPLAY one", listed, read);
        assertEquals(WatchEntryRules.Newest.PASSED, availability);
        Map<String, String> after = snapshot(folder);
        // Compared without printing the maps: file names are recording ids.
        assertTrue("the sample folder is unchanged (" + before.size() + " files before, " + after.size() + " after)",
                before.equals(after));
    }

    /** name -> size and modified time of every file (no content read). */
    private static Map<String, String> snapshot(File folder) throws Exception {
        Map<String, String> out = new TreeMap<String, String>();
        File[] files = folder.listFiles();
        if (files == null) return out;
        for (File f : files) {
            BasicFileAttributes a = Files.readAttributes(f.toPath(), BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            out.put(f.getName(), a.size() + "@" + a.lastModifiedTime().toMillis());
        }
        return out;
    }
}
