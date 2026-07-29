package com.bedwarsqol.stats;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.util.EnumChatFormatting;

public final class TabListLookup {

    private TabListLookup() {}

    public static String statsTextForRenderedName(String renderedText) {
        if (BedwarsQol.config == null) return null;
        if (!HypixelContext.isOnHypixel() || !HypixelContext.isInActiveBedwarsGame()) return null;
        if (renderedText == null || renderedText.isEmpty()) return null;

        String stripped = EnumChatFormatting.getTextWithoutFormattingCodes(renderedText);
        if (stripped == null || stripped.isEmpty()) return null;

        String name = lastToken(stripped);
        if (name == null || name.isEmpty()) return null;

        return statsTextForPlayerInfo(playerInfo(name));
    }

    /**
     * Composes the right-aligned tab overlay for a player: BedWars stats (when Hypixel Stats + Show
     * Tab are on). Missing data triggers a background fetch and renders progressively as it resolves.
     */
    public static String statsTextForPlayerInfo(NetworkPlayerInfo info) {
        if (BedwarsQol.config == null) return null;
        if (!HypixelContext.isOnHypixel() || !HypixelContext.isInActiveBedwarsGame()) return null;
        if (info == null || info.getGameProfile() == null) return null;
        java.util.UUID uuid = info.getGameProfile().getId();
        if (uuid == null) return null;

        ClientSettings cfg = BedwarsQol.config;
        // Fetch when Player Stats OR an Urchin/Seraph badge needs this player's identity.
        if (!UrchinTag.needsTabIdentity(cfg) && !SeraphTag.needsTabIdentity(cfg)) return null;

        String name = info.getGameProfile().getName();
        boolean urchinEligible = cfg.urchinTags
                && UrchinTag.badgeAllowed(EligibilitySnapshot.current(), name, uuid);
        boolean seraphEligible = cfg.seraphTags
                && SeraphTag.badgeAllowed(EligibilitySnapshot.current(), name, uuid);

        BedwarsStats stats = StatsCache.getCached(uuid);
        if (stats == null) {
            StatsCache.ensureFetched(uuid, StatsCache.PRIORITY_TAB, urchinEligible, seraphEligible);
            return null;
        }
        // A cache hit still needs to keep any eligible provider refresh moving.
        if (urchinEligible || seraphEligible) {
            StatsCache.ensureFetched(uuid, StatsCache.PRIORITY_TAB, urchinEligible, seraphEligible);
        }

        // Rendering stays per-module: Player Stats off + a provider on -> only the badge renders.
        String stat = "";
        if (cfg.playerStats && cfg.playerStatsTab) {
            String s = stats.formatForTab(BedwarsModeDetector.current(), cfg.playerStatsShowRank);
            if (s != null) stat = s;
        }
        String out = stat
                + urchinBadge(cfg, stats, urchinEligible)
                + seraphBadge(cfg, stats, seraphEligible);
        return out.isEmpty() ? null : out;
    }

    /** The Urchin priority-tag badge for the tab overlay, or "" when off / no active tag. */
    private static String urchinBadge(ClientSettings cfg, BedwarsStats stats, boolean eligible) {
        if (cfg == null || !cfg.urchinTags || !cfg.urchinBadgeTab || !eligible) return "";
        UrchinTag tag = stats.priorityUrchinTag(System.currentTimeMillis());
        if (tag == null) return "";
        return tag.badgeToken();
    }

    /** The Seraph priority-tag badge for the tab overlay, or "" when off / no active tag. */
    private static String seraphBadge(ClientSettings cfg, BedwarsStats stats, boolean eligible) {
        if (cfg == null || !cfg.seraphTags || !cfg.seraphBadgeTab || !eligible) return "";
        SeraphTag tag = stats.prioritySeraphTag();
        if (tag == null) return "";
        return tag.badgeToken();
    }

    /**
     * Resolves a rendered tab cell string to its {@link NetworkPlayerInfo}, or null if it isn't a known
     * player (e.g. a sidebar-score string or header/footer line). Unlike {@link #statsTextForRenderedName}
     * this is NOT gated on Hypixel, so callers can reserve layout space for any player on any server.
     */
    public static NetworkPlayerInfo playerInfoForRenderedName(String renderedText) {
        if (renderedText == null || renderedText.isEmpty()) return null;
        String stripped = EnumChatFormatting.getTextWithoutFormattingCodes(renderedText);
        if (stripped == null || stripped.isEmpty()) return null;
        String name = lastToken(stripped);
        if (name == null || name.isEmpty()) return null;
        return playerInfo(name);
    }

    private static String lastToken(String s) {
        int sp = s.lastIndexOf(' ');
        return sp < 0 ? s : s.substring(sp + 1);
    }

    private static NetworkPlayerInfo playerInfo(String name) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getNetHandler() == null) return null;
        return mc.getNetHandler().getPlayerInfo(name);
    }
}
