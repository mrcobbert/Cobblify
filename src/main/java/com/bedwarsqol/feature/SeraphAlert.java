package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.stats.BedwarsStats;
import com.bedwarsqol.stats.EligibilitySnapshot;
import com.bedwarsqol.stats.GameSessionTracker;
import com.bedwarsqol.stats.HypixelContext;
import com.bedwarsqol.stats.SeraphTag;
import com.bedwarsqol.stats.StatsCache;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
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

/**
 * Seraph chat alert surface, the sibling of {@link UrchinAlert}. Every ~1 s (client-thread) it sweeps
 * the tab list, drives Seraph fetches for current-session confirmed players, and — the first time a
 * player with a <b>negative</b> Seraph tag (blacklist / bot / annoy) is seen each game — prints one
 * private chat line with a hover tooltip, plus an optional pling for blacklist tags.
 *
 * <p>Differences from {@link UrchinAlert} (deliberate): a <b>safelist</b> tag is a positive signal and
 * never alerts; there is no anticheat fusion and no click-to-lookup (Seraph's API is UUID-only, so
 * there is no {@code /bw seraph <name>} command to suggest).
 */
public final class SeraphAlert {

    private static final int SCAN_INTERVAL_TICKS = 20; // ~1 s

    private int ticks;
    private int currentSession = Integer.MIN_VALUE;
    private final Set<String> alerted = new java.util.HashSet<String>();

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.seraphTags) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.getNetHandler() == null) return;
        if (!HypixelContext.isOnHypixel() || !HypixelContext.isInActiveBedwarsGame()) return;

        int session = GameSessionTracker.currentSessionId();
        if (session != currentSession) {
            currentSession = session;
            alerted.clear();
        }

        if (++ticks < SCAN_INTERVAL_TICKS) return;
        ticks = 0;

        EligibilitySnapshot snap = EligibilitySnapshot.current();
        NetHandlerPlayClient net = mc.getNetHandler();
        for (NetworkPlayerInfo info : net.getPlayerInfoMap()) {
            if (info == null || info.getGameProfile() == null) continue;
            GameProfile profile = info.getGameProfile();
            String name = profile.getName();
            UUID uuid = profile.getId();
            if (name == null || uuid == null) continue;
            if (!snap.eligibleSeraph(name, uuid)) continue;

            // Keep the Seraph fetch moving for every eligible tab player, even with the badge off (so
            // the chat alert alone still works). Carry the sibling provider's eligibility too — a
            // single-bit task leaves the other provider unresolved, and its own sweep would fire a
            // second full fetch for the same player seconds later.
            StatsCache.ensureFetched(uuid, StatsCache.PRIORITY_TAB,
                    snap.eligible(name, uuid), true);

            BedwarsStats stats = StatsCache.getCached(uuid);
            if (stats == null) continue;
            SeraphTag tag = stats.prioritySeraphTag();
            // Only negative tags alert; a safelisted (positive) player is not a warning.
            if (tag == null || tag.kind.equals("safelist")) continue;

            String key = name.toLowerCase(Locale.ROOT);
            if (cfg.seraphChatAlert && alerted.add(key)) {
                announceAlert(mc, cfg, name, stats, tag);
            }
        }
    }

    private void announceAlert(Minecraft mc, ClientSettings cfg, String name, BedwarsStats stats, SeraphTag tag) {
        String line = SeraphAlertFormat.formatOrdinary(TeamColors.nameColor(name), name, tag);
        if (line == null) return;
        ChatComponentText msg = new ChatComponentText(line);
        ChatComponentText hover = AlertCopyReason.attach(
                new ChatComponentText(tooltip(stats)), tag.reason);
        msg.getChatStyle().setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                hover));
        mc.thePlayer.addChatMessage(ModChat.mark(msg));
        if (cfg.seraphAlertSound && tag.isCheaterType()) {
            mc.thePlayer.playSound("note.pling", 1.0f, 1.0f);
        }
    }

    /** The hover tooltip: one line per displayable Seraph tag (name + reason + added date). */
    private static String tooltip(BedwarsStats stats) {
        StringBuilder sb = new StringBuilder();
        List<SeraphTag> tags = SeraphTag.activeTags(stats.seraphTags);
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        for (SeraphTag t : tags) {
            sb.append(t.color()).append(t.displayName());
            if (!t.reason.isEmpty()) sb.append(" §7- §f").append(t.reason);
            if (t.addedOnMs > 0) sb.append(" §8(").append(fmt.format(new Date(t.addedOnMs))).append(')');
            sb.append('\n');
        }
        sb.append("§8Community report from api.seraph.si");
        return sb.toString();
    }
}
