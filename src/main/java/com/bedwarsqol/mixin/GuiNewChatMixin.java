package com.bedwarsqol.mixin;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.feature.ChatCopyAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.GuiUtilRenderComponents;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.MathHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Chat module hooks on the vanilla chat GUI, all config-gated and inert by default.
 *
 * <p><b>Unlimited Chat</b>: {@code setChatLine} trims both {@code chatLines} and
 * {@code drawnChatLines} at a hardcoded 100; the {@code @ModifyConstant} raises both trims to
 * 32,767 while the toggle is on (effectively unlimited, still memory-bounded).
 *
 * <p><b>Stack Spam Messages</b>: consecutive identical lines collapse into the original line, edited
 * in place with a gray {@code (xN)} counter, so chat never jumps. The last-printed non-blank line is
 * remembered at the end of {@code setChatLine} (its formatted text plus the exact {@link ChatLine}
 * objects it produced in both lists); when the next print matches it, the print is cancelled and the
 * remembered lines are swapped for re-wrapped ones carrying the counter, at the same indices. Any
 * different non-blank line simply overwrites the memory, which is what makes stacking consecutive-only.
 * With time-based stacking on, a repeat older than the configured window starts a fresh line instead.
 * With ignore-blanks on, whitespace-only lines are invisible to the stacker: they neither stack nor
 * break a chain. Lines carrying a server deletion id are never touched.
 *
 * <p><b>Copy Chat</b> ({@link ChatCopyAccess}): resolves the raw mouse position to the full original
 * message. The drawn index math mirrors vanilla {@code getChatComponent}; the drawn line is mapped
 * back to its source message by walking {@code chatLines} and counting each message's wrapped lines
 * with the same {@code splitText} call {@code setChatLine} uses, since {@code drawnChatLines} is
 * built from {@code chatLines} in order.
 */
@Mixin(GuiNewChat.class)
public abstract class GuiNewChatMixin implements ChatCopyAccess {

    @Shadow @Final private Minecraft mc;
    @Shadow @Final private List<ChatLine> chatLines;
    @Shadow @Final private List<ChatLine> drawnChatLines;
    @Shadow private int scrollPos;

    @Shadow public abstract boolean getChatOpen();
    @Shadow public abstract int getChatWidth();
    @Shadow public abstract float getChatScale();
    @Shadow public abstract int getLineCount();

    // ---- Unlimited Chat ----

    private static final int UNLIMITED_CAP = 32767;

    @ModifyConstant(method = "setChatLine", constant = @Constant(intValue = 100))
    private int bedwarsqol$historyCap(int vanillaCap) {
        ClientSettings cfg = BedwarsQol.config;
        return cfg != null && cfg.chatUnlimited ? UNLIMITED_CAP : vanillaCap;
    }

    // ---- Stack Spam Messages ----

    /** Formatted text of the last stackable line printed (null = no chain). */
    @Unique private String bedwarsqol$lastFormatted;
    /** The component that produced it, kept pristine so the counter can be re-appended per repeat. */
    @Unique private IChatComponent bedwarsqol$lastComponent;
    @Unique private long bedwarsqol$lastStackMs;
    @Unique private int bedwarsqol$stackCount;
    /** The exact list entries the last line occupies, replaced in place on each repeat. */
    @Unique private ChatLine bedwarsqol$lastChatLine;
    @Unique private final List<ChatLine> bedwarsqol$lastDrawn = new ArrayList<ChatLine>();

    @Inject(method = "printChatMessageWithOptionalDeletion", at = @At("HEAD"), cancellable = true)
    private void bedwarsqol$stackSpam(IChatComponent component, int id, CallbackInfo ci) {
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.chatStackSpam || id != 0 || component == null) return;
        if (bedwarsqol$lastFormatted == null || bedwarsqol$lastChatLine == null) return;

        String formatted = component.getFormattedText();
        if (!bedwarsqol$lastFormatted.equals(formatted)) return;
        if (cfg.chatStackIgnoreBlanks && bedwarsqol$isBlank(formatted)) return;

        long now = System.currentTimeMillis();
        if (cfg.chatStackTimeBased && now - bedwarsqol$lastStackMs > (long) (cfg.chatStackWindowSec * 1000f)) {
            return; // repeat too old: let it print as a fresh line (setChatLine re-captures it)
        }

        int idx = chatLines.indexOf(bedwarsqol$lastChatLine);
        if (idx < 0) return; // original line gone (trimmed/deleted): print normally

        // Replace the message entry in place with a counter-carrying copy.
        bedwarsqol$stackCount++;
        bedwarsqol$lastStackMs = now;
        IChatComponent stacked = bedwarsqol$lastComponent.createCopy();
        ChatComponentText counter = new ChatComponentText(" (x" + bedwarsqol$stackCount + ")");
        counter.getChatStyle().setColor(EnumChatFormatting.GRAY);
        stacked.appendSibling(counter);
        int updateCounter = mc.ingameGUI.getUpdateCounter();
        ChatLine replacement = new ChatLine(updateCounter, stacked, 0);
        chatLines.set(idx, replacement);
        bedwarsqol$lastChatLine = replacement;

        // Swap the drawn (wrapped) lines at the same position; the counter can change the wrap count.
        int insertAt = Integer.MAX_VALUE;
        for (ChatLine old : bedwarsqol$lastDrawn) {
            int i = drawnChatLines.indexOf(old);
            if (i >= 0) {
                drawnChatLines.remove(i);
                if (i < insertAt) insertAt = i;
            }
        }
        if (insertAt == Integer.MAX_VALUE) insertAt = 0;
        List<ChatLine> fresh = new ArrayList<ChatLine>();
        for (IChatComponent part : bedwarsqol$split(stacked)) {
            // Newest-first list: within one message the later wrapped slices sit at lower indices,
            // matching setChatLine's add(0, ...) loop.
            fresh.add(0, new ChatLine(updateCounter, part, 0));
        }
        drawnChatLines.addAll(insertAt, fresh);
        bedwarsqol$lastDrawn.clear();
        bedwarsqol$lastDrawn.addAll(fresh);

        ci.cancel();
    }

    @Inject(method = "setChatLine", at = @At("RETURN"))
    private void bedwarsqol$trackLastLine(IChatComponent component, int id, int updateCounter, boolean displayOnly, CallbackInfo ci) {
        if (displayOnly) return; // refreshChat re-wrap, not a new message
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.chatStackSpam) {
            bedwarsqol$resetStackState();
            return;
        }
        if (id != 0) {
            bedwarsqol$resetStackState(); // deletable server lines break the chain and are never stacked
            return;
        }
        String formatted = component.getFormattedText();
        if (cfg.chatStackIgnoreBlanks && bedwarsqol$isBlank(formatted)) return; // invisible to the stacker

        bedwarsqol$lastFormatted = formatted;
        bedwarsqol$lastComponent = component;
        bedwarsqol$lastStackMs = System.currentTimeMillis();
        bedwarsqol$stackCount = 1;
        bedwarsqol$lastChatLine = chatLines.isEmpty() ? null : chatLines.get(0);
        bedwarsqol$lastDrawn.clear();
        int wrapped = bedwarsqol$split(component).size();
        for (int i = 0; i < wrapped && i < drawnChatLines.size(); i++) {
            bedwarsqol$lastDrawn.add(drawnChatLines.get(i));
        }
    }

    @Unique
    private void bedwarsqol$resetStackState() {
        bedwarsqol$lastFormatted = null;
        bedwarsqol$lastComponent = null;
        bedwarsqol$lastChatLine = null;
        bedwarsqol$lastDrawn.clear();
        bedwarsqol$stackCount = 0;
    }

    /** The same wrap {@code setChatLine} performs, so drawn-line counts always agree with vanilla. */
    @Unique
    private List<IChatComponent> bedwarsqol$split(IChatComponent component) {
        int width = MathHelper.floor_float((float) getChatWidth() / getChatScale());
        return GuiUtilRenderComponents.splitText(component, width, mc.fontRendererObj, false, false);
    }

    @Unique
    private static boolean bedwarsqol$isBlank(String formatted) {
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(formatted);
        return plain == null || plain.trim().isEmpty();
    }

    // ---- Copy Chat ----

    @Override
    public IChatComponent bedwarsqol$fullComponentAt(int rawMouseX, int rawMouseY) {
        if (!getChatOpen()) return null;
        ScaledResolution sr = new ScaledResolution(mc);
        int scaleFactor = sr.getScaleFactor();
        float chatScale = getChatScale();
        int x = MathHelper.floor_float((rawMouseX / scaleFactor - 3) / chatScale);
        int y = MathHelper.floor_float((rawMouseY / scaleFactor - 27) / chatScale);
        if (x < 0 || y < 0) return null;
        int visible = Math.min(getLineCount(), drawnChatLines.size());
        if (x > MathHelper.floor_float((float) getChatWidth() / chatScale)
                || y >= mc.fontRendererObj.FONT_HEIGHT * visible + visible) {
            return null;
        }
        int drawnIdx = y / mc.fontRendererObj.FONT_HEIGHT + scrollPos;
        if (drawnIdx < 0 || drawnIdx >= drawnChatLines.size()) return null;

        // Map the drawn (wrapped) index back to its source message.
        int cursor = 0;
        for (ChatLine line : chatLines) {
            int wrapped = bedwarsqol$split(line.getChatComponent()).size();
            if (drawnIdx < cursor + wrapped) return line.getChatComponent();
            cursor += wrapped;
        }
        return drawnChatLines.get(drawnIdx).getChatComponent();
    }
}
