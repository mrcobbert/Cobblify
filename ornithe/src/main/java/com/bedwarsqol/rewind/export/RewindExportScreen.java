package com.bedwarsqol.rewind.export;

import com.bedwarsqol.rewind.watch.RewindPlayback;
import com.bedwarsqol.rewind.watch.RewindScreen;
import com.bedwarsqol.rewind.watch.ViewerKeys;

/**
 * The screen of an export frame (Rewind Export PLAN Solution 2): the recorded screens under the bar
 * ({@link RewindPlayback#drawUnder}) and nothing of the viewer's chrome. Esc cancels the export. Phase 2 (spike)
 * version: the ridden player's name and the preview come with Phase 5.
 */
public final class RewindExportScreen extends RewindScreen {

    @Override
    protected void drawRewind(int mouseX, int mouseY, float partialTicks) {
        RewindPlayback.drawUnder(width, height, mouseX, mouseY, partialTicks);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == ViewerKeys.KEY_ESCAPE && RewindExport.active()) {
            RewindExport.cancel("esc");
        }
    }
}
