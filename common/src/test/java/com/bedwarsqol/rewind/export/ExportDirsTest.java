package com.bedwarsqol.rewind.export;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;

/**
 * {@link ExportDirs} (TASK F1; PLAN Solution 5): {@code Cobblify} inside the helper's videos folder, else
 * {@code ~/Movies} on the Mac and {@code ~/Videos} elsewhere; {@code -Dcobblify.exportDir} wins (the dev client's own
 * export folder, used as is).
 */
public class ExportDirsTest {

    @Test
    public void theHelpersVideosFolderComesFirst() {
        assertEquals(new File("/Users/p/Movies", "Cobblify"),
                ExportDirs.resolve(null, "/Users/p/Movies", "/Users/p", "Mac OS X"));
        assertEquals(new File("D:\\Vids", "Cobblify"), ExportDirs.resolve("", "D:\\Vids", "C:\\Users\\p", "Windows 11"));
    }

    @Test
    public void withoutItTheHomeFolderByOs() {
        assertEquals(new File(new File("/Users/p", "Movies"), "Cobblify"), ExportDirs.resolve(null, null, "/Users/p", "Mac OS X"));
        assertEquals(new File(new File("C:\\Users\\p", "Videos"), "Cobblify"),
                ExportDirs.resolve(null, "", "C:\\Users\\p", "Windows 10"));
        assertEquals("a relative videos folder is not trusted", new File(new File("/home/p", "Videos"), "Cobblify"),
                ExportDirs.resolve(null, "rel/dir", "/home/p", "Linux"));
    }

    @Test
    public void theOverrideWins() {
        assertEquals(new File("/Users/Shared/cobblify-dev/exports/rwe"),
                ExportDirs.resolve("/Users/Shared/cobblify-dev/exports/rwe", "/Users/p/Movies", "/Users/p", "Mac OS X"));
    }
}
