package com.bedwarsqol.feature;

import com.bedwarsqol.config.ClientSettings;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The settings the queue alerts follow since their own toggles were folded into Tag Utils and Nick Utils. */
public class QueueAlertGateTest {

    @Test
    public void tagLinesFollowTagUtilsChatAlertForTheSourcesThatAreOn() {
        ClientSettings cfg = new ClientSettings();
        assertTrue(QueueAlertGate.wantsTags(cfg));
        cfg.tagChatAlert = false;
        assertFalse("Chat Alert off silences the queue too", QueueAlertGate.wantsTags(cfg));
        cfg.tagChatAlert = true;
        cfg.urchinTags = false;
        assertTrue("Seraph alone still counts", QueueAlertGate.wantsTags(cfg));
        cfg.seraphTags = false;
        assertFalse("no source on, nothing to ask", QueueAlertGate.wantsTags(cfg));
        cfg.urchinTags = true;
        cfg.tagUtils = false;
        assertFalse("Tag Utils off silences it", QueueAlertGate.wantsTags(cfg));
        assertFalse(QueueAlertGate.wantsTags(null));
    }

    @Test
    public void nickLinesFollowNickUtilsNickNotify() {
        ClientSettings cfg = new ClientSettings();
        assertTrue(QueueAlertGate.wantsNicks(cfg));
        cfg.nickNotify = false;
        assertFalse(QueueAlertGate.wantsNicks(cfg));
        cfg.nickNotify = true;
        cfg.nickUtils = false;
        assertFalse(QueueAlertGate.wantsNicks(cfg));
        assertFalse(QueueAlertGate.wantsNicks(null));
    }
}
