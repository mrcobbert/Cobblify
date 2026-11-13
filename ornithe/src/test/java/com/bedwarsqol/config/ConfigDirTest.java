package com.bedwarsqol.config;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * The config-dir seam that replaced Forge's {@code Loader.getConfigDir()} (and the test-scope Loader
 * stub): the {@code cobblify.test.configDir} override wins, and outside a running game, where Fabric
 * has no game directory, it refuses to guess.
 */
public class ConfigDirTest {

    private String saved;

    @Before
    public void save() {
        saved = System.getProperty(ConfigDir.OVERRIDE_PROPERTY);
    }

    @After
    public void restore() {
        if (saved == null) System.clearProperty(ConfigDir.OVERRIDE_PROPERTY);
        else System.setProperty(ConfigDir.OVERRIDE_PROPERTY, saved);
    }

    @Test
    public void overridePropertyWins() {
        File dir = new File(System.getProperty("java.io.tmpdir"), "cobblify-config-dir-test");
        System.setProperty(ConfigDir.OVERRIDE_PROPERTY, dir.getPath());
        assertEquals(dir, ConfigDir.get());
    }

    @Test
    public void withoutOverrideOutsideTheGameItRefusesToGuess() {
        System.clearProperty(ConfigDir.OVERRIDE_PROPERTY);
        try {
            File dir = ConfigDir.get();
            fail("expected no config dir outside a running game, got " + dir);
        } catch (IllegalStateException expected) {
            // the seam must not fall back to a directory next to the build
        }
    }
}
