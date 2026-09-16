package com.bedwarsqol.mixin;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.feature.ChatCopyAccess;
import com.bedwarsqol.feature.ChatStack;
import com.bedwarsqol.feature.ChatStackCore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.GuiUtilRenderComponents;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.MathHelper;
import org.apache.logging.log4j.Logger;
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
 * in place with a gray {@code (xN)} counter, so chat never jumps. The last-printed stackable line is
 * remembered at the end of {@code setChatLine} as the live component object the chat stores plus its
 * {@code chatLines} entry. When the next print matches its <i>current</i> text (holders that
 * {@link com.bedwarsqol.feature.ChatNameTags} patched since count), the print is cancelled, a
 * {@link ChatStack.Counter} sibling is appended to that same component — never a copy, so the line
 * keeps its identity and keeps receiving back-patches — and its wrapped rows are swapped at the
 * position the {@code chatLines} order implies (not by remembered objects, which
 * {@code refreshChat()} invalidates). The cancelled print still writes vanilla's {@code [CHAT]} log
 * line. Any different non-blank line overwrites the memory, which is what makes stacking
 * consecutive-only. With time-based stacking on, a repeat older than the configured window starts a
 * fresh line instead. With ignore-blanks on, whitespace-only lines are invisible to the stacker:
 * they neither stack nor break a chain. Decorative lines (no letter or digit — Hypixel's separator
 * bars) are never a target and always break the chain. Lines carrying a server deletion id, and
 * foreign component implementations, are never touched. Copy Chat strips the counter.
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
    @Shadow public abstract void scroll(int amount);
    @Shadow @Final private static Logger logger;

    // ---- Unlimited Chat ----

    private static final int UNLIMITED_CAP = 32767;

    @ModifyConstant(method = "setChatLine", constant = @Constant(intValue = 100))
    private int bedwarsqol$historyCap(int vanillaCap) {
        ClientSettings cfg = BedwarsQol.config;
        return cfg != null && cfg.chatUnlimited ? UNLIMITED_CAP : vanillaCap;
    }

    // ---- Stack Spam Messages ----

    /** The live component of the last stackable line printed (null = no chain). It is the object
     *  stored in {@code chatLines}; the counter is appended to it in place, never to a copy. */
    @Unique private IChatComponent bedwarsqol$lastComponent;
    /** The {@code chatLines} entry wrapping it, replaced on each repeat (fresh fade timer). */
    @Unique private ChatLine bedwarsqol$lastChatLine;
    @Unique private long bedwarsqol$lastStackMs;
    @Unique private int bedwarsqol$stackCount;

    /**
     * Ordering is the safety guarantee: every call that can throw runs before the first mutation;
     * the one fallible call after it ({@code split} of the mutated component) is rolled back on
     * failure; the list writes come last and cannot throw. Any failure resets the chain and lets
     * vanilla print the line.
     */
    @Inject(method = "printChatMessageWithOptionalDeletion", at = @At("HEAD"), cancellable = true)
    private void bedwarsqol$stackSpam(IChatComponent component, int id, CallbackInfo ci) {
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.chatStackSpam || id != 0 || component == null) return;
        IChatComponent target = bedwarsqol$lastComponent;
        if (target == null || bedwarsqol$lastChatLine == null) return;
        try {
            // -- fallible reads, nothing mutated yet --
            String candidate = ChatStack.safeFormatted(component);
            if (candidate == null) return;
            if (cfg.chatStackIgnoreBlanks && ChatStackCore.blank(ChatStackCore.plain(candidate))) return;
            long now = System.currentTimeMillis();
            String base = ChatStack.baseText(target);
            if (!ChatStackCore.shouldStack(base, candidate, cfg.chatStackTimeBased,
                    ChatStackCore.windowMs(cfg.chatStackWindowSec), bedwarsqol$lastStackMs, now)) {
                return; // different line, or repeat too old: print fresh (setChatLine re-captures)
            }
            String logLine = ChatStack.safeUnformatted(component);
            if (logLine == null) return;
            int idx = chatLines.indexOf(bedwarsqol$lastChatLine);
            if (idx < 0) return; // original gone (F3+D, trimmed, foreign surgery): print normally
            // Rows are located by position, not by remembered objects: refreshChat() rebuilds
            // drawnChatLines with new ChatLine instances, but the concatenation order is stable.
            int[] rowsAbove = new int[idx];
            for (int i = 0; i < idx; i++) rowsAbove[i] = bedwarsqol$split(chatLines.get(i).getChatComponent()).size();
            int start = ChatStackCore.rowStart(rowsAbove, idx);
            int oldRows = bedwarsqol$split(target).size();
            // The two lists are capped independently (messages vs rows); a message can outlive its rows.
            if (!ChatStackCore.targetPresent(start, oldRows, drawnChatLines.size())) return;

            // -- the one mutation before the lists, with rollback --
            int count = bedwarsqol$stackCount + 1;
            ChatStack.Counter previous = ChatStack.setCounter(target, count);
            List<IChatComponent> parts;
            try {
                parts = bedwarsqol$split(target);
            } catch (Throwable t) {
                ChatStack.restoreCounter(target, previous);
                throw t;
            }

            // -- list writes: cannot throw --
            int updateCounter = mc.ingameGUI.getUpdateCounter();
            ChatLine replacement = new ChatLine(updateCounter, target, 0);
            chatLines.set(idx, replacement);
            List<ChatLine> fresh = new ArrayList<ChatLine>(parts.size());
            for (IChatComponent part : parts) {
                // Newest-first list: within one message the later wrapped slices sit at lower
                // indices, matching setChatLine's add(0, ...) loop.
                fresh.add(0, new ChatLine(updateCounter, part, 0));
            }
            ChatStackCore.replaceRows(drawnChatLines, start, oldRows, fresh);
            int delta = ChatStackCore.scrollDelta(getChatOpen(), scrollPos, start, oldRows, fresh.size());
            if (delta != 0) scroll(delta); // keep a scrolled-up reader's view when the swap is below it
            bedwarsqol$lastChatLine = replacement;
            bedwarsqol$stackCount = count;
            bedwarsqol$lastStackMs = now;
            logger.info("[CHAT] " + logLine); // the line vanilla would have logged for this receipt
            ci.cancel();
        } catch (Throwable t) {
            bedwarsqol$resetStackState();
        }
    }

    @Inject(method = "setChatLine", at = @At("RETURN"))
    private void bedwarsqol$trackLastLine(IChatComponent component, int id, int updateCounter, boolean displayOnly, CallbackInfo ci) {
        if (displayOnly) return; // refreshChat re-wrap, not a new message
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.chatStackSpam) {
            bedwarsqol$resetStackState();
            return;
        }
        try {
            if (id != 0 || !ChatStack.stackable(component)) {
                // Deletable server lines are never stacked; nor is a foreign component whose sibling
                // list we cannot mutate atomically. Either breaks the chain.
                bedwarsqol$resetStackState();
                return;
            }
            String formatted = ChatStack.safeFormatted(component);
            if (formatted == null) {
                bedwarsqol$resetStackState();
                return;
            }
            switch (ChatStackCore.captureAction(ChatStackCore.plain(formatted), cfg.chatStackIgnoreBlanks)) {
                case TRANSPARENT:
                    return; // invisible to the stacker: neither a target nor a chain-breaker
                case RESET:
                    bedwarsqol$resetStackState(); // decorative bar: prints as vanilla, breaks the chain
                    return;
                case CAPTURE:
                default:
                    break;
            }
            bedwarsqol$lastComponent = component;
            bedwarsqol$lastChatLine = chatLines.isEmpty() ? null : chatLines.get(0);
            bedwarsqol$lastStackMs = System.currentTimeMillis();
            bedwarsqol$stackCount = 1;
        } catch (Throwable t) {
            bedwarsqol$resetStackState();
        }
    }

    @Unique
    private void bedwarsqol$resetStackState() {
        bedwarsqol$lastComponent = null;
        bedwarsqol$lastChatLine = null;
        bedwarsqol$stackCount = 0;
    }

    /** The same wrap {@code setChatLine} performs, so drawn-line counts always agree with vanilla. */
    @Unique
    private List<IChatComponent> bedwarsqol$split(IChatComponent component) {
        int width = MathHelper.floor_float((float) getChatWidth() / getChatScale());
        return GuiUtilRenderComponents.splitText(component, width, mc.fontRendererObj, false, false);
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
            if (drawnIdx < cursor + wrapped) return ChatStack.withoutCounter(line.getChatComponent());
            cursor += wrapped;
        }
        return ChatStack.withoutCounter(drawnChatLines.get(drawnIdx).getChatComponent());
    }
}
