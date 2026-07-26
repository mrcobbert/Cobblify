package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.anticheat.CheaterDetector;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.stats.BedwarsStats;
import com.bedwarsqol.stats.EligibilitySnapshot;
import com.bedwarsqol.stats.GameSessionTracker;
import com.bedwarsqol.stats.HypixelContext;
import com.bedwarsqol.stats.StatsCache;
import com.bedwarsqol.stats.UrchinTag;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Urchin chat alert + anticheat fusion surface. Every ~1 s (client-thread) it sweeps the tab list,
 * drives Urchin fetches for current-session confirmed players, and — the first time a player with a
 * cheater-type tag (CCC / BC / CC) is seen each game — prints one private chat line (tooltip + click
 * to {@code /bw urchin <name>}), with an optional pling. Sniper/caution tags stay badge-only (no
 * ordinary chat). When ANY displayable tag AND live Cheater Detector flags coincide, the badge is
 * marked for red-bold fusion highlighting; fusion <em>chat</em> + double pling fire only for
 * cheater-type tags, once per player per game.
 */
public final class UrchinAlert {

    private static final int SCAN_INTERVAL_TICKS = 20; // ~1 s

    private int ticks;
    private int currentSession = Integer.MIN_VALUE;
    private final Set<String> alerted = new java.util.HashSet<String>();
    private final Set<String> fusionFired = new java.util.HashSet<String>();
    private int secondPlingTicks;

    /** Fusion-highlighted players this session (read by tab/nametag badge rendering, any thread). */
    private static final Set<String> FUSION_HIGHLIGHT = ConcurrentHashMap.newKeySet();

    public static boolean isFusionHighlighted(String name) {
        return name != null && FUSION_HIGHLIGHT.contains(name.toLowerCase(Locale.ROOT));
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        // Delayed second pling of a fusion double-pling (runs every tick, independent of the sweep).
        if (secondPlingTicks > 0 && --secondPlingTicks == 0) {
            Minecraft m = Minecraft.getMinecraft();
            if (m != null && m.thePlayer != null) m.thePlayer.playSound("note.pling", 1.0f, 2.0f);
        }

        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.urchinTags) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.getNetHandler() == null) return;
        if (!HypixelContext.isOnHypixel() || !HypixelContext.isInActiveBedwarsGame()) return;

        int session = GameSessionTracker.currentSessionId();
        if (session != currentSession) {
            currentSession = session;
            alerted.clear();
            fusionFired.clear();
            FUSION_HIGHLIGHT.clear();
        }

        if (++ticks < SCAN_INTERVAL_TICKS) return;
        ticks = 0;

        EligibilitySnapshot snap = EligibilitySnapshot.current();
        NetHandlerPlayClient net = mc.getNetHandler();
        long now = System.currentTimeMillis();
        for (NetworkPlayerInfo info : net.getPlayerInfoMap()) {
            if (info == null || info.getGameProfile() == null) continue;
            GameProfile profile = info.getGameProfile();
            String name = profile.getName();
            UUID uuid = profile.getId();
            if (name == null || uuid == null) continue;
            if (!snap.eligible(name, uuid)) continue;

            // Population: keep the Urchin fetch moving for every eligible tab player, even with both
            // badge surfaces off (so the chat alert alone still works). Carry the sibling provider's
            // eligibility too — a single-bit task leaves the other provider unresolved, and its own
            // sweep would fire a second full fetch for the same player seconds later.
            StatsCache.ensureFetched(uuid, StatsCache.PRIORITY_TAB, true,
                    snap.eligibleSeraph(name, uuid));

            BedwarsStats stats = StatsCache.getCached(uuid);
            if (stats == null) continue;
            UrchinTag tag = stats.priorityUrchinTag(now);
            if (tag == null) continue;

            String key = name.toLowerCase(Locale.ROOT);
            // Only cheater types get ordinary chat; do not mark alerted for sniper/caution so a later
            // priority upgrade can still announce once.
            if (cfg.urchinChatAlert && tag.isCheaterType() && alerted.add(key)) {
                announceAlert(mc, cfg, name, stats, tag, now);
            }
            maybeFusion(mc, cfg, name, tag, key);
        }
    }

    private void announceAlert(Minecraft mc, ClientSettings cfg, String name, BedwarsStats stats,
                               UrchinTag tag, long now) {
        String line = UrchinAlertFormat.formatOrdinary(TeamColors.nameColor(name), name, tag);
        if (line == null) return;
        ChatComponentText msg = new ChatComponentText(line);
        ChatComponentText hover = AlertCopyReason.attach(
                new ChatComponentText(tooltip(stats, now)), tag.reason);
        msg.getChatStyle()
                .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover))
                .setChatClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/bw urchin " + name));
        mc.thePlayer.addChatMessage(ModChat.mark(msg));
        if (cfg.urchinAlertSound && tag.isCheaterType()) {
            mc.thePlayer.playSound("note.pling", 1.0f, 1.0f);
        }
    }

    private void maybeFusion(Minecraft mc, ClientSettings cfg, String name, UrchinTag tag, String key) {
        if (!cfg.urchinAcFusion || !cfg.anticheat) return;
        // Highlight: ANY displayable Urchin tag + live AC flags (badge red-bold). Chat/pling: cheater
        // types only. The tag here already came from priorityUrchinTag (displayable only).
        if (!CheaterDetector.get().hasLiveFlags(name)) return;
        FUSION_HIGHLIGHT.add(key);
        if (!tag.isCheaterType()) return;
        if (!fusionFired.add(key)) return;
        String line = UrchinAlertFormat.formatFusion(TeamColors.nameColor(name), name, tag);
        if (line == null) return;
        ChatComponentText msg = new ChatComponentText(line);
        mc.thePlayer.addChatMessage(ModChat.mark(msg));
        // Distinct double pling (1.6f then 2.0f, 3 ticks apart) — ChatNotifications second-pling pattern.
        mc.thePlayer.playSound("note.pling", 1.0f, 1.6f);
        secondPlingTicks = 3;
    }

    /** The hover tooltip: one line per active tag (name + reason + added date) plus an appeal footer. */
    private static String tooltip(BedwarsStats stats, long now) {
        StringBuilder sb = new StringBuilder();
        List<UrchinTag> tags = UrchinTag.activeTags(stats.urchinTags, now);
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        for (UrchinTag t : tags) {
            sb.append(t.color()).append(t.displayName());
            if (!t.reason.isEmpty()) sb.append(" §7- §f").append(t.reason);
            if (t.addedOnMs > 0) sb.append(" §8(").append(fmt.format(new Date(t.addedOnMs))).append(')');
            sb.append('\n');
        }
        sb.append("§8Community report - appeal via the Urchin Discord");
        return sb.toString();
    }
}
