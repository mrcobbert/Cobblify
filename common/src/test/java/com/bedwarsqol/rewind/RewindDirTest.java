package com.bedwarsqol.rewind;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The Rewind folder, its {@code -Dcobblify.rewindDir} override, and the command that opens it on each OS. */
public class RewindDirTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private String saved;

    @Before
    public void save() {
        saved = System.getProperty(RewindDir.OVERRIDE_PROPERTY);
    }

    @After
    public void restore() {
        if (saved == null) System.clearProperty(RewindDir.OVERRIDE_PROPERTY);
        else System.setProperty(RewindDir.OVERRIDE_PROPERTY, saved);
    }

    @Test
    public void theOverridePropertyIsCobblifyRewindDir() {
        assertEquals("cobblify.rewindDir", RewindDir.OVERRIDE_PROPERTY);
    }

    @Test
    public void defaultsToRewindInTheCobblifyDataFolder() {
        System.clearProperty(RewindDir.OVERRIDE_PROPERTY);
        File expected = new File(new File(System.getProperty("user.home"), ".cobblify"), "rewind");
        assertEquals(expected, RewindDir.get());
        assertEquals(new File(new File("/home/x", ".cobblify"), "rewind"), RewindDir.resolve(null, "/home/x"));
        assertEquals("a blank override is no override",
                new File(new File("/home/x", ".cobblify"), "rewind"), RewindDir.resolve("  ", "/home/x"));
    }

    @Test
    public void thePropertyOverridesTheFolder() throws Exception {
        File dir = tmp.newFolder("staged");
        System.setProperty(RewindDir.OVERRIDE_PROPERTY, dir.getAbsolutePath());
        assertEquals(dir.getAbsoluteFile(), RewindDir.get());
        assertEquals(new File("/tmp/elsewhere"), RewindDir.resolve("/tmp/elsewhere", "/home/x"));
    }

    @Test
    public void openCommandPerOs() {
        File dir = new File("/x/.cobblify/rewind");
        String path = dir.getAbsolutePath();
        assertArrayEquals(new String[]{"open", path}, RewindDir.openCommand("Mac OS X", dir));
        assertArrayEquals(new String[]{"open", path}, RewindDir.openCommand("macOS", dir));
        assertArrayEquals(new String[]{"explorer.exe", path}, RewindDir.openCommand("Windows 11", dir));
        assertArrayEquals(new String[]{"explorer.exe", path}, RewindDir.openCommand("Windows 10", dir));
        assertArrayEquals(new String[]{"xdg-open", path}, RewindDir.openCommand("Linux", dir));
        assertArrayEquals(new String[]{"xdg-open", path}, RewindDir.openCommand("FreeBSD", dir));
        assertArrayEquals(new String[]{"xdg-open", path}, RewindDir.openCommand(null, dir));
    }

    @Test
    public void openRunsOnADaemonThreadCreatesTheFolderAndSwallowsAFailedLaunch() throws Exception {
        File dir = new File(tmp.getRoot(), "not-yet/rewind");
        assertFalse(dir.exists());
        final Throwable[] escaped = new Throwable[1];
        Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable e) {
                escaped[0] = e;
            }
        });
        try {
            // A command that cannot start: the launch fails on the opener thread and nothing escapes it.
            Thread t = RewindDir.open(dir, new String[]{new File(tmp.getRoot(), "no-such-program").getAbsolutePath()});
            assertTrue("the opener is a daemon thread", t.isDaemon());
            assertFalse("never the caller's thread", t == Thread.currentThread());
            t.join(10000);
            assertFalse(t.isAlive());
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before);
        }
        assertEquals("a failed launch is swallowed", null, escaped[0]);
        assertTrue("the folder is created so there is something to open", dir.isDirectory());
    }
}
