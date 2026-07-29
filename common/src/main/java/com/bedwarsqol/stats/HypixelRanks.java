package com.bedwarsqol.stats;

/**
 * Maps a forum {@code rank-badge} code (e.g. {@code mvp_plus}) to a colored §-prefixed
 * label like {@code §b[MVP§c+§b]}. The forum page doesn't expose each player's custom
 * "+" color, so we use Hypixel's defaults (red "+" for MVP+, gold "+" for VIP+).
 */
public final class HypixelRanks {

    private HypixelRanks() {}

    /**
     * True if the raw forum rank code is an <b>elevated</b> rank able to use Hypixel's /nick feature:
     * MVP++ ({@code superstar}), YOUTUBE, or staff. Used as the denick rank backstop (a genuine denick's
     * resolved real account must be elevated). Null/unknown → false (fail closed). Classify on the raw
     * code, never the colored prefix (an unknown/failed code collapses to the same empty prefix as
     * Default and must not be read as a rank).
     */
    public static boolean isElevated(String code) {
        if (code == null) return false;
        switch (code.toLowerCase()) {
            case "superstar":   // MVP++
            case "youtuber":
            case "youtube":
            case "mojang":
            case "admin":
            case "owner":
            case "staff":
            case "game_master":
            case "moderator":
            case "helper":
                return true;
            default:
                return false;
        }
    }

    public static String prefix(String code) {
        if (code == null) return "";
        switch (code.toLowerCase()) {
            case "vip":         return "§a[VIP]";
            case "vip_plus":    return "§a[VIP§6+§a]";
            case "mvp":         return "§b[MVP]";
            case "mvp_plus":    return "§b[MVP§c+§b]";
            case "superstar":   return "§6[MVP§c++§6]"; // MVP++
            case "youtuber":
            case "youtube":     return "§c[§fYOUTUBE§c]";
            case "mojang":      return "§6[MOJANG]";
            case "admin":       return "§c[ADMIN]";
            case "owner":       return "§c[OWNER]";
            case "staff":       return "§c[STAFF]";
            case "game_master": return "§2[GM]";
            case "moderator":   return "§2[MOD]";
            case "helper":      return "§9[HELPER]";
            default:            return ""; // default/none/unknown → no prefix
        }
    }
}
