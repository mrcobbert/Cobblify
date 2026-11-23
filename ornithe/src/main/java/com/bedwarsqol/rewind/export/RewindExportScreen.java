package com.bedwarsqol.rewind.export;

import com.bedwarsqol.rewind.watch.RewindPlayback;
import com.bedwarsqol.rewind.watch.RewindScreen;
import com.bedwarsqol.rewind.watch.ViewerKeys;

/**
 * The screen of an export (Rewind Export PLAN Solution 2; TASK P3, U5): the recorded screens under the bar
 * ({@link RewindPlayback#drawUnder}) and the ridden player's name, nothing else of the viewer. It is the current screen
 * while an export draws, so no key or click does anything but Esc, which cancels the export.
 */
public final class RewindExportScreen extends RewindScreen {

    @Override
    protected void drawRewind(int mouseX, int mouseY, float partialTicks) {
        RewindPlayback.drawUnder(width, height, mouseX, mouseY, partialTicks);
        RewindPlayback.drawRiddenName();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == ViewerKeys.KEY_ESCAPE && RewindExport.active()) {
            RewindExport.cancel("esc");
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        // an export takes no clicks (TASK U5)
    }
}
