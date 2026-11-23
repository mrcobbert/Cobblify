package com.bedwarsqol.mixin;

import com.bedwarsqol.rewind.export.RewindExport;
import net.minecraft.client.gui.GuiNewChat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rewind Export, Chat off (TASK P5, review I4): an export frame with Chat off draws no chat lines, so
 * {@code GuiNewChat.drawChat} is cancelled for it. The recorded chat input bar is skipped in {@code RecordedScreens}.
 * Returns at once when no export draws.
 */
@Mixin(GuiNewChat.class)
public abstract class GuiNewChatRewindExportMixin {

    @Inject(method = "drawChat", at = @At("HEAD"), cancellable = true, require = 1)
    private void bedwarsqol$rewindExportChat(int updateCounter, CallbackInfo ci) {
        if (RewindExport.hidesChatLines()) {
            ci.cancel();
        }
    }
}
