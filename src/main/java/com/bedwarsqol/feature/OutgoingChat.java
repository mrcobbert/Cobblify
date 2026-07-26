package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.stats.GameSessionTracker;
import com.bedwarsqol.stats.HypixelContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Shared outgoing-chat gatekeeper for typed chat, IncSender, SweatReport, and AutoGG.
 *
 * <p>All managed sends share one Hypixel-safe pacing rule. Priority is MANUAL &gt; INC &gt; AUTO.
 * Optional automation pauses while the chat GUI is open and is cancelled on stale context or when
 * the player submits normal chat / INC. Held manual text is never silently dropped. Slash commands
 * bypass pacing but still update send clocks. Party epoch only invalidates party-targeted sends.
 */
public final class OutgoingChat {

    public static final long INC_COOLDOWN_MS = 2000L;

    private static final OutgoingChat INSTANCE = new OutgoingChat();

    public static OutgoingChat get() {
        return INSTANCE;
    }

    private final OutgoingChatCore core = new OutgoingChatCore();
    private final PartyEpoch partyEpoch = new PartyEpoch();
    private boolean passthrough;

    private final OutgoingChatCore.FeatureGate features = new OutgoingChatCore.FeatureGate() {
        public boolean enabled(OutgoingChatKind kind) {
            return featureEnabled(kind);
        }
    };

    private OutgoingChat() {}

    public boolean isPassthrough() {
        return passthrough;
    }

    public int partyEpoch() {
        return partyEpoch.current();
    }

    public OutgoingChatCore.SweatFlight sweatFlight() {
        return core.sweatFlight();
    }

    public boolean consumeSweatRetry() {
        return core.consumeSweatRetry();
    }

    public void resetSweatForNewGame() {
        core.resetSweatForNewGame();
    }

    public void markSweatDone() {
        core.markSweatDone();
    }

    public boolean canSubmitInc(long nowMs) {
        return core.canSubmitInc(nowMs, INC_COOLDOWN_MS);
    }

    // ---- feature submit APIs ------------------------------------------------

    /**
     * Intercept a player-originated {@code sendChatMessage}. Returns true when the original call
     * should be cancelled (held, sent via passthrough, or slash-command bypass).
     */
    public boolean interceptPlayerSend(String message) {
        if (message == null || message.isEmpty()) return false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return false;

        long now = System.currentTimeMillis();
        OutgoingChatCore.InterceptResult result =
                core.interceptPlayerSend(message, liveContext(), now);
        applyDecision(result.decision, now);
        return result.handled;
    }

    /**
     * Player submitted from the chat GUI (including client commands that never reach
     * {@code EntityPlayerSP}). Cancels optional automation and starts the quiet window.
     */
    public void onUserIntent(String message) {
        core.onUserIntent(System.currentTimeMillis());
    }

    public void cancelKind(OutgoingChatKind kind) {
        applyDecision(core.cancelKind(kind), System.currentTimeMillis());
    }

    public boolean submitInc(String text) {
        long now = System.currentTimeMillis();
        if (!core.submitInc(text, liveContext(), now, INC_COOLDOWN_MS)) return false;
        flush(now);
        return true;
    }

    public void submitSweat(String text) {
        long now = System.currentTimeMillis();
        core.submitSweat(text, liveContext(), now);
        flush(now);
    }

    public void submitAutoGg(String text) {
        long now = System.currentTimeMillis();
        core.submit(OutgoingChatKind.AUTOGG, text, liveContext(), now);
        flush(now);
    }

    // ---- tick / lifecycle ---------------------------------------------------

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        flush(System.currentTimeMillis());
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        applyDecision(core.invalidateAll(), System.currentTimeMillis());
        partyEpoch.bump();
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (event == null || event.message == null) return;
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(
                event.message.getUnformattedText());
        if (partyEpoch.observeChat(plain)) {
            // Leave/kick/disband — finalize any Sweat tied to the prior party.
            core.onPartyEpochAdvanced();
        }
    }

    private void flush(long nowMs) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;
        boolean chatOpen = mc.currentScreen instanceof GuiChat;
        // Drain until idle or waiting on pacing — at most a few steps (send + notices).
        for (int i = 0; i < 4; i++) {
            OutgoingChatCore.Decision d = core.tick(liveContext(), nowMs, chatOpen, features);
            if (d.send == null && d.notice == null) break;
            applyDecision(d, nowMs);
            if (d.send != null) break; // one wire send per flush burst
        }
    }

    private void applyDecision(OutgoingChatCore.Decision d, long nowMs) {
        if (d == null) return;
        if (d.notice != null) noticeHeldManualInvalid(d.notice, d.noticeWhy);
        if (d.send != null) dispatch(d.send, nowMs);
    }

    private void dispatch(OutgoingChatRequest request, long nowMs) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || request == null) return;
        passthrough = true;
        try {
            mc.thePlayer.sendChatMessage(request.text);
        } finally {
            passthrough = false;
        }
        core.acknowledgeDelivered(request, nowMs);
    }

    private OutgoingChatCore.LiveContext liveContext() {
        return new OutgoingChatCore.LiveContext(
                GameSessionTracker.currentSessionId(),
                currentServerKey(),
                HypixelContext.isInActiveBedwarsGame(),
                partyEpoch.current());
    }

    private static boolean featureEnabled(OutgoingChatKind kind) {
        if (kind == OutgoingChatKind.MANUAL) return true;
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null) return false;
        switch (kind) {
            case INC: return cfg.pcIncKey;
            case SWEAT: return cfg.playerStats && cfg.statsSweatReport;
            case AUTOGG: return cfg.autoGg;
            default: return false;
        }
    }

    static String currentServerKey() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.isSingleplayer()) return "";
        ServerData server = mc.getCurrentServerData();
        if (server == null || server.serverIP == null) return "";
        return server.serverIP.toLowerCase();
    }

    private static void noticeHeldManualInvalid(OutgoingChatRequest held, String why) {
        if (held == null || held.text == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;
        String preview = held.text.length() > 40 ? held.text.substring(0, 40) + "..." : held.text;
        mc.thePlayer.addChatMessage(ModChat.mark(new ChatComponentText(
                "§8[Chat] §7Held message not sent (" + why + "): §f" + preview)));
    }

    // ---- test hooks ---------------------------------------------------------

    OutgoingChatCore coreForTest() { return core; }
    PartyEpoch partyEpochForTest() { return partyEpoch; }
    void resetForTest() {
        core.reset();
        passthrough = false;
    }
}
