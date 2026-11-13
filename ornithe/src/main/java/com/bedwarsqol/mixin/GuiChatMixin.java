package com.bedwarsqol.mixin;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.feature.AlertCopyReason;
import com.bedwarsqol.feature.ChatCopyAccess;
import com.bedwarsqol.feature.ChatHoverStats;
import com.bedwarsqol.feature.ChatLengthLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Draws the chat-hover BedWars stats card on messages Hypixel does NOT decorate with its own hover
 * card — in-game and pre-game queue chat, party/guild chat, whispers. Vanilla only calls
 * {@code handleComponentHover} (where {@link GuiScreenMixin} hooks the lobby rank card) when the
 * hovered component already carries a hover event, so those un-decorated lines never reach that path.
 *
 * <p>Here we recompute the card at the end of the chat screen's render for whatever chat component is
 * under the cursor and draw it ourselves. Components that DO carry a hover event are skipped — those
 * go through {@code handleComponentHover}/{@link GuiScreenMixin}, so this avoids a double tooltip.
 */
// Extends GuiScreen so the inherited, protected drawHoveringText resolves at compile time and binds
// correctly at runtime (a @Shadow can't see it — it lives on GuiChat's superclass, not GuiChat).
@Mixin(GuiChat.class)
public abstract class GuiChatMixin extends GuiScreen {

    @Shadow protected GuiTextField inputField;
    @Shadow private String defaultInputFieldText;

    /**
     * Lifts the typing/pasting half of the 1.8.9 chat length cap. Vanilla {@code initGui} ends with
     * {@code inputField.setMaxStringLength(100)}, and {@code GuiTextField.writeText} clamps to it — so
     * without this a pasted line (a copied alert plus its reason, say) is silently cut at 100.
     *
     * <p>The default text is re-applied when it was long enough to have been clipped by vanilla's own
     * {@code setText} call, which ran while the field was still capped at 100.
     */
    @Inject(method = "initGui", at = @At("RETURN"))
    private void bedwarsqol$extendInput(CallbackInfo ci) {
        int limit = ChatLengthLimit.limit();
        if (limit <= ChatLengthLimit.VANILLA || this.inputField == null) return;
        this.inputField.setMaxStringLength(limit);
        if (this.defaultInputFieldText != null
                && this.defaultInputFieldText.length() > ChatLengthLimit.VANILLA) {
            this.inputField.setText(ChatLengthLimit.clamp(this.defaultInputFieldText, limit));
        }
    }

    @Inject(method = "drawScreen", at = @At("RETURN"))
    private void bedwarsqol$chatHoverStats(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.ingameGUI == null) return;
        IChatComponent comp = mc.ingameGUI.getChatGUI().getChatComponent(Mouse.getX(), Mouse.getY());
        if (comp == null) return;
        // Components carrying their own hover event are handled by the handleComponentHover ->
        // GuiScreenMixin path (which merges in Hypixel's rank card); skip them here to avoid drawing
        // the stats card twice.
        ChatStyle style = comp.getChatStyle();
        if (style != null && style.getChatHoverEvent() != null) return;
        List<String> lines = ChatHoverStats.buildTooltip(comp);
        if (lines == null || lines.isEmpty()) return;
        this.drawHoveringText(lines, mouseX, mouseY);
    }

    /**
     * Copy Chat: right-click a chat line to copy the full original message (color codes stripped) to
     * the clipboard. Right-click does nothing in the vanilla chat screen, so nothing is displaced;
     * the button-press click confirms the copy.
     */
    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void bedwarsqol$copyChat(int mouseX, int mouseY, int mouseButton, CallbackInfo ci) {
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.chatCopy || mouseButton != 1) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.ingameGUI == null) return;
        Object chat = mc.ingameGUI.getChatGUI();
        if (!(chat instanceof ChatCopyAccess)) return;
        IChatComponent component =
                ((ChatCopyAccess) chat).bedwarsqol$fullComponentAt(Mouse.getX(), Mouse.getY());
        if (component == null) return;
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(component.getFormattedText());
        if (plain == null || plain.trim().isEmpty()) return;
        GuiScreen.setClipboardString(AlertCopyReason.appendToPlain(component, plain.trim()));
        mc.getSoundHandler().playSound(
                PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 1.0F));
    }
}
