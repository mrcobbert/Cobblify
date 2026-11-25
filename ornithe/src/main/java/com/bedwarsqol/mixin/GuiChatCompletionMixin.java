package com.bedwarsqol.mixin;

import com.bedwarsqol.ornithe.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Tab completion for client commands, as Forge's {@code GuiChat} patch did it: the completions are
 * computed when the request goes to the server and put in front of the server's answer when it
 * arrives.
 */
@Mixin(GuiChat.class)
public abstract class GuiChatCompletionMixin extends GuiScreen {

    @Unique private String[] bedwarsqol$latestCompletions;

    @Inject(method = "sendAutocompleteRequest", at = @At("HEAD"), require = 1)
    private void bedwarsqol$completeClientCommands(String leftOfCursor, String full, CallbackInfo ci) {
        bedwarsqol$latestCompletions = null;
        Minecraft mc = this.mc;
        if (leftOfCursor == null || leftOfCursor.isEmpty() || mc == null || mc.thePlayer == null) return;
        BlockPos pos = mc.thePlayer.getPosition();
        List<String> options = ClientCommands.complete(mc.thePlayer, leftOfCursor, pos);
        if (!options.isEmpty()) bedwarsqol$latestCompletions = options.toArray(new String[0]);
    }

    @ModifyVariable(method = "onAutocompleteResponse", at = @At("HEAD"), argsOnly = true, require = 1)
    private String[] bedwarsqol$prependClientCompletions(String[] results) {
        String[] ours = bedwarsqol$latestCompletions;
        if (ours == null || ours.length == 0) return results;
        String[] server = results == null ? new String[0] : results;
        String[] merged = new String[ours.length + server.length];
        System.arraycopy(ours, 0, merged, 0, ours.length);
        System.arraycopy(server, 0, merged, ours.length, server.length);
        return merged;
    }
}
