package com.bedwarsqol.stats;

import java.util.Collections;
import java.util.List;

public final class BedwarsStats {

    public enum State { OK, NEVER_PLAYED, NICKED, ERROR }

    /**
     * Whether this account's rank is known to be elevated (can use /nick), known not to be, or
     * unknown. Used as the denick rank backstop. UNKNOWN forces a fresh lookup rather than a reject,
     * so a warm/old cache entry that predates rank-code persistence is never falsely rejected.
     */
    public enum RankProvenance { ELEVATED, NON_ELEVATED, UNKNOWN }

    /** The six raw Bedwars counters for one mode (or overall) plus derived ratios. */
    public static final class ModeStats {
        public static final ModeStats EMPTY = new ModeStats(0, 0, 0, 0, 0, 0);

        public final int finalKills;
        public final int finalDeaths;
        public final double fkdr;
        public final int wins;
        public final int losses;
        public final double wlr;
        public final int kills;
        public final int deaths;
        public final double kd;

        public ModeStats(int finalKills, int finalDeaths, int wins, int losses, int kills, int deaths) {
            this.finalKills = finalKills;
            this.finalDeaths = finalDeaths;
            this.fkdr = ratio(finalKills, finalDeaths);
            this.wins = wins;
            this.losses = losses;
            this.wlr = ratio(wins, losses);
            this.kills = kills;
            this.deaths = deaths;
            this.kd = ratio(kills, deaths);
        }

        public boolean hasGames() {
            return wins != 0 || losses != 0 || kills != 0 || deaths != 0
                    || finalKills != 0 || finalDeaths != 0;
        }
    }

    public final State state;
    public final String displayName;
    /** Real Bedwars star (level); 0 when not known. Present only on fallback-served lookups. */
    public final int bedwarsLevel;
    /** Pre-colored §-prefixed rank label, e.g. {@code §b[MVP§c+§b]}; empty for no rank. */
    public final String rankPrefix;
    /**
     * Raw forum rank code (e.g. {@code superstar}, {@code mvp_plus}), for elevation classification.
     * {@code ""} = a successfully-fetched rankless (Default) account; {@code null} = unknown (an old
     * cache entry from before this field, or a non-OK state) which forces a refetch, never a reject.
     */
    public final String rankCode;

    public final ModeStats overall;
    public final ModeStats solo;
    public final ModeStats doubles;
    public final ModeStats threes;
    public final ModeStats fours;

    // Flat overall fields kept so existing callers (and disk cache) keep working.
    public final int finalKills;
    public final int finalDeaths;
    public final double fkdr;
    public final int wins;
    public final int losses;
    public final double wlr;
    public final int kills;
    public final int deaths;
    public final double kd;

    /** Community-reported Urchin tags (in-memory only, never persisted); unmodifiable, never null. */
    public final List<UrchinTag> urchinTags;

    /** Seraph list tags (in-memory only, never persisted); unmodifiable, never null. */
    public final List<SeraphTag> seraphTags;

    /** Seraph statistics (in-memory only, never persisted); {@code -1} when absent. */
    public final int seraphThreat;
    public final int seraphEncounters;

    private BedwarsStats(State state, String displayName, int bedwarsLevel,
                         String rankPrefix, String rankCode, ModeStats overall, ModeStats solo,
                         ModeStats doubles, ModeStats threes, ModeStats fours, List<UrchinTag> urchinTags,
                         List<SeraphTag> seraphTags, int seraphThreat, int seraphEncounters) {
        this.urchinTags = urchinTags == null
                ? Collections.<UrchinTag>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<UrchinTag>(urchinTags));
        this.seraphTags = seraphTags == null
                ? Collections.<SeraphTag>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<SeraphTag>(seraphTags));
        this.seraphThreat = seraphThreat < 0 ? -1 : seraphThreat;
        this.seraphEncounters = seraphEncounters < 0 ? -1 : seraphEncounters;
        this.state = state;
        this.displayName = displayName;
        this.bedwarsLevel = Math.max(0, bedwarsLevel);
        this.rankPrefix = rankPrefix == null ? "" : rankPrefix;
        this.rankCode = rankCode;
        this.overall = overall == null ? ModeStats.EMPTY : overall;
        this.solo = solo == null ? ModeStats.EMPTY : solo;
        this.doubles = doubles == null ? ModeStats.EMPTY : doubles;
        this.threes = threes == null ? ModeStats.EMPTY : threes;
        this.fours = fours == null ? ModeStats.EMPTY : fours;

        this.finalKills = this.overall.finalKills;
        this.finalDeaths = this.overall.finalDeaths;
        this.fkdr = this.overall.fkdr;
        this.wins = this.overall.wins;
        this.losses = this.overall.losses;
        this.wlr = this.overall.wlr;
        this.kills = this.overall.kills;
        this.deaths = this.overall.deaths;
        this.kd = this.overall.kd;
    }

    public static BedwarsStats nicked() {
        return new BedwarsStats(State.NICKED, null, 0, "", null, null, null, null, null, null, null, null, -1, -1);
    }

    public static BedwarsStats error() {
        return new BedwarsStats(State.ERROR, null, 0, "", null, null, null, null, null, null, null, null, -1, -1);
    }

    public static BedwarsStats neverPlayed(String displayName) {
        return new BedwarsStats(State.NEVER_PLAYED, displayName, 0, "", null, null, null, null, null, null, null, null, -1, -1);
    }

    public static BedwarsStats ok(String displayName, String rankPrefix,
                                  String rankCode, ModeStats overall, ModeStats solo, ModeStats doubles,
                                  ModeStats threes, ModeStats fours) {
        return new BedwarsStats(State.OK, displayName, 0, rankPrefix, rankCode,
                overall, solo, doubles, threes, fours, null, null, -1, -1);
    }

    /**
     * Elevation of this account's rank for the denick backstop. Only an {@code OK} account with a
     * known rank code yields ELEVATED/NON_ELEVATED; a null code (old cache / non-OK) is UNKNOWN so
     * the caller refetches rather than falsely rejecting a warm elevated entry.
     */
    public RankProvenance rankProvenance() {
        if (state != State.OK) return RankProvenance.UNKNOWN;
        if (rankCode == null) return RankProvenance.UNKNOWN;
        if (rankCode.isEmpty()) return RankProvenance.NON_ELEVATED; // fetched, rankless (Default)
        return HypixelRanks.isElevated(rankCode)
                ? RankProvenance.ELEVATED : RankProvenance.NON_ELEVATED;
    }

    /** A copy carrying {@code tags} (Urchin resolution merge). Preserves all stats fields. */
    public BedwarsStats withUrchinTags(List<UrchinTag> tags) {
        return new BedwarsStats(state, displayName, bedwarsLevel, rankPrefix, rankCode,
                overall, solo, doubles, threes, fours, tags, seraphTags, seraphThreat, seraphEncounters);
    }

    /** A copy carrying Seraph {@code tags} + statistics (Seraph resolution merge). {@code threat} /
     *  {@code encounters} are {@code -1} when absent. Preserves all stats fields. */
    public BedwarsStats withSeraph(List<SeraphTag> tags, int threat, int encounters) {
        return new BedwarsStats(state, displayName, bedwarsLevel, rankPrefix, rankCode,
                overall, solo, doubles, threes, fours, urchinTags, tags, threat, encounters);
    }

    /**
     * A copy with the Bedwars star filled in - present only when the backend served the lookup from
     * the fallback source, whose embedded data includes the star for free. A no-op for non-OK
     * states or a non-positive/unchanged level.
     */
    public BedwarsStats withLevel(int level) {
        if (state != State.OK || level <= 0 || level == bedwarsLevel) return this;
        return new BedwarsStats(state, displayName, level, rankPrefix, rankCode,
                overall, solo, doubles, threes, fours, urchinTags, seraphTags, seraphThreat, seraphEncounters);
    }

    /** The highest-severity active Urchin tag at {@code nowMs}, or null when there is none. */
    public UrchinTag priorityUrchinTag(long nowMs) {
        return UrchinTag.priority(urchinTags, nowMs);
    }

    /** The highest-severity Seraph tag, or null when there is none. */
    public SeraphTag prioritySeraphTag() {
        return SeraphTag.priority(seraphTags);
    }

    /** The stats for {@code mode}, falling back to overall when that mode has no games. */
    public ModeStats statsFor(BedwarsMode mode) {
        ModeStats m;
        switch (mode) {
            case SOLO:    m = solo; break;
            case DOUBLES: m = doubles; break;
            case THREES:  m = threes; break;
            case FOURS:   m = fours; break;
            default:      m = overall; break;
        }
        return m != null && m.hasGames() ? m : overall;
    }

    /**
     * Threat ramp by FKDR alone: White &lt; 2 &rarr;
     * Yellow 2-5 &rarr; Orange (gold) 5-10 &rarr; Red 10+. Monotonic, so a weak player is never
     * flagged as a threat (the old ramp painted both {@code <1} and {@code >=10} red).
     */
    public static String fkdrColor(double fkdr) {
        switch (fkdrTier(fkdr)) {
            case 0:  return "§f";  // White  - low threat
            case 1:  return "§e";  // Yellow
            case 2:  return "§6";  // Orange (gold)
            default: return "§c";  // Red    - sweat
        }
    }

    /**
     * THE FKDR threshold table: tier 0 below 2, 1 below 5, 2 below 10, 3 at 10 and above. Every
     * surface that colours or classes an FKDR (nametag/tab/chat colour, overlay row tier) derives
     * from this one function, so the tiers can never drift apart again.
     */
    public static int fkdrTier(double fkdr) {
        if (fkdr < 2.0)  return 0;
        if (fkdr < 5.0)  return 1;
        if (fkdr < 10.0) return 2;
        return 3;
    }

    /** Skill ramp by WLR: White &lt; 1 &rarr; Green 1-2 &rarr; Yellow 2-3 &rarr; Gold 3-5 &rarr; Red 5+. */
    public static String wlrColor(double wlr) {
        if (wlr < 1.0) return "§f";
        if (wlr < 2.0) return "§a";
        if (wlr < 3.0) return "§e";
        if (wlr < 5.0) return "§6";
        return "§c";
    }

    /** Skill ramp by KD: White &lt; 1 &rarr; Green 1-2 &rarr; Yellow 2-3 &rarr; Gold 3+. */
    public static String kdColor(double kd) {
        if (kd < 1.0) return "§f";
        if (kd < 2.0) return "§a";
        if (kd < 3.0) return "§e";
        return "§6";
    }

    public String formatForNametag(BedwarsMode mode, boolean showRank) {
        return formatForNametag(mode, showRank, false);
    }

    /**
     * Nametag line: {@code FKDR x.xx}, star/rank prefix first. With {@code labelMode} (the user has
     * forced a mode with {@code /bw mode}) the mode the numbers actually came from is stamped in
     * front — {@code 4s FKDR x.xx}, or {@code All FKDR x.xx} when the player has no games in the
     * forced mode and {@link #statsFor} fell back to overall — so a fallback is never disguised.
     */
    public String formatForNametag(BedwarsMode mode, boolean showRank, boolean labelMode) {
        // A transient fetch failure renders nothing (like a still-loading player) — see specialLabel;
        // falling through would paint a real player as a misleading "FKDR 0.00" for the error TTL.
        if (state == State.ERROR) return null;
        String special = specialLabel();
        if (special != null) return special;
        ModeStats m = statsFor(mode);
        StringBuilder sb = new StringBuilder();
        appendPrefix(sb, showRank);
        sb.append(fkdrColor(m.fkdr));
        String label = labelFor(mode, labelMode);
        if (label != null) sb.append(label).append(' ');
        sb.append("FKDR ").append(fmt2(m.fkdr)).append("§r");
        return sb.toString();
    }

    public String formatForTab(BedwarsMode mode, boolean showRank) {
        return formatForTab(mode, showRank, false);
    }

    /** Tab-list cell; the same {@code labelMode} rule as {@link #formatForNametag(BedwarsMode, boolean, boolean)}. */
    public String formatForTab(BedwarsMode mode, boolean showRank, boolean labelMode) {
        if (state == State.ERROR) return null; // same render-nothing contract as formatForNametag
        String special = specialLabel();
        if (special != null) return special;
        ModeStats m = statsFor(mode);
        StringBuilder sb = new StringBuilder();
        appendPrefix(sb, showRank);
        sb.append("§7");
        String label = labelFor(mode, labelMode);
        if (label != null) sb.append(label).append(' ');
        sb.append("FKDR: ").append(fkdrColor(m.fkdr)).append(fmt2(m.fkdr))
                .append(" §7WLR: §f").append(fmt2(m.wlr));
        return sb.toString();
    }

    /**
     * The leading Chat Stats bracket for a chat line: {@code [x.xx]} coloured by threat, {@code [New]}
     * for a never-played account, {@code ""} for every other state (nicked / error render nothing in
     * chat). Under {@code labelMode} the bracket carries the mode its number came from —
     * {@code [4s x.xx]}, or {@code [All x.xx]} after a fallback to overall. Trailing space included so
     * it can be spliced straight in front of the rank tag.
     */
    public String chatBracket(BedwarsMode mode, boolean labelMode) {
        if (state == State.OK) {
            double v = statsFor(mode).fkdr;
            String label = labelFor(mode, labelMode);
            return "§7[" + (label == null ? "" : label + " ") + fkdrColor(v) + fmt2(v) + "§7]§r ";
        }
        if (state == State.NEVER_PLAYED) return "§7[New]§r ";
        return "";
    }

    /**
     * The placeholder a chat line wears while its stats are still fetching: width-matched to the
     * resolved {@link #chatBracket} so the line does not jump when the number lands. Under
     * {@code labelMode} it carries the forced mode's own label ({@code [4s -.--]}) — the outcome it
     * resolves to whenever the player has games there; the {@code All} fallback is width-identical in
     * the vanilla font for 2s/3s/4s and one label wider for Solo.
     */
    public static String pendingChatBracket(BedwarsMode mode, boolean labelMode) {
        String label = labelMode ? mode.shortLabel() : null;
        return "§7[" + (label == null ? "" : label + " ") + "-.--]§r ";
    }

    /**
     * The tag a labelled surface stamps on numbers rendered for {@code mode}: {@code null} when not
     * labelling (auto mode — nothing is stamped), else the mode's short label when its block has games
     * or {@code All} when {@link #statsFor} fell back to the overall block. Public for the surfaces that
     * compose their own line (the denick report, the Players page row) so they follow the same rule.
     */
    public String labelFor(BedwarsMode mode, boolean labelMode) {
        if (!labelMode) return null;
        String own = modeLabel(mode);
        return own != null ? own : BedwarsMode.UNKNOWN.shortLabel();
    }

    /**
     * Multi-line stat block for the chat hover card. Returns the lines to append under Hypixel's own
     * rank tooltip; empty when there is nothing useful to show (a transient fetch error), so the caller
     * can fall back to the vanilla card alone.
     */
    public java.util.List<String> formatForHoverCard(BedwarsMode mode, boolean showRank) {
        return formatForHoverCard(mode, showRank, false);
    }

    /**
     * Hover card with the same {@code labelMode} rule as the other surfaces: the header names the
     * mode the numbers came from whenever it is a specific one ({@code (4s)}, as today), and under
     * {@code labelMode} also names {@code (All)} after a fallback so a forced mode never shows
     * overall numbers unmarked.
     */
    public java.util.List<String> formatForHoverCard(BedwarsMode mode, boolean showRank, boolean labelMode) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        switch (state) {
            case NICKED:       out.add("§6§lBedWars §r§8(nicked / not found)"); return out;
            case NEVER_PLAYED: out.add("§6§lBedWars §r§8(never played)");       return out;
            case ERROR:        return out; // empty -> caller keeps the vanilla card and retries later
            default: break;
        }
        ModeStats m = statsFor(mode);
        StringBuilder header = new StringBuilder("§6§lBedWars");
        String modeTag = labelMode ? labelFor(mode, true) : modeLabel(mode);
        if (modeTag != null) header.append(" §r§7(").append(modeTag).append("§7)");
        if (bedwarsLevel > 0) header.append(" §r").append(starTag(bedwarsLevel));
        if (showRank && !rankPrefix.isEmpty()) header.append(" §r").append(rankPrefix);
        out.add(header.toString());
        out.add("§7FKDR: " + fkdrColor(m.fkdr) + fmt2(m.fkdr) + " §r§8| §7Finals: §f" + num(m.finalKills) + "§7/§f" + num(m.finalDeaths));
        out.add("§7WLR: §f" + fmt2(m.wlr) + " §8| §7W/L: §f" + num(m.wins) + "§7/§f" + num(m.losses));
        out.add("§7K/D: §f" + fmt2(m.kd) + " §8| §7Kills: §f" + num(m.kills));
        return out;
    }

    /**
     * Short label for the mode a stats display is actually showing — {@code Solo}/{@code 2s}/{@code 3s}/
     * {@code 4s} — or {@code null} for overall. Returns null when a specific mode was requested but the
     * player has no games there, because {@link #statsFor} then falls back to overall and labelling it
     * with the requested mode would misrepresent the numbers on screen.
     */
    public String modeLabel(BedwarsMode mode) {
        ModeStats own;
        switch (mode) {
            case SOLO:    own = solo;    break;
            case DOUBLES: own = doubles; break;
            case THREES:  own = threes;  break;
            case FOURS:   own = fours;   break;
            default:      return null; // overall: never labelled here (PlayerCard maps null → "Overall")
        }
        return own != null && own.hasGames() ? mode.shortLabel() : null;
    }

    /** The bracketed label for non-OK states (or null when stats should render). */
    private String specialLabel() {
        switch (state) {
            case NICKED:       return "§8[Nicked]";
            // A transient fetch failure is NOT an answer — render nothing (like a still-loading player)
            // and let the cache retry, so real players never show a misleading "[?]". Only genuinely
            // nicked players get a bracketed "can't find" tag.
            case ERROR:        return null;
            case NEVER_PLAYED: return "§8[New]";
            default:           return null;
        }
    }

    private void appendPrefix(StringBuilder sb, boolean showRank) {
        // Unconditional when known: the star only arrives on fallback-served lookups, and its
        // absence (bedwarsLevel 0) renders byte-identically to before the field existed.
        if (bedwarsLevel > 0) {
            sb.append(starTag(bedwarsLevel)).append("§r ");
        }
        if (showRank && !rankPrefix.isEmpty()) {
            sb.append(rankPrefix).append("§r ");
        }
    }

    /** {@code [<star>✫]} colored by prestige tier (simplified to one color per 100 levels). */
    public static String starTag(int star) {
        return starColor(star) + "[" + star + "✫]§r";
    }

    private static String starColor(int star) {
        switch (star / 100) {
            case 0:  return "§7"; // Stone
            case 1:  return "§f"; // Iron
            case 2:  return "§6"; // Gold
            case 3:  return "§b"; // Diamond
            case 4:  return "§2"; // Emerald
            case 5:  return "§3"; // Sapphire
            case 6:  return "§4"; // Ruby
            case 7:  return "§d"; // Crystal
            case 8:  return "§9"; // Opal
            case 9:  return "§5"; // Amethyst
            default: return "§c"; // Rainbow (1000+) — single stand-in color
        }
    }

    private static double ratio(int a, int b) {
        if (b == 0) return a;
        return (double) a / (double) b;
    }

    private static String fmt2(double d) {
        return String.format(java.util.Locale.US, "%.2f", d);
    }

    private static String num(int n) {
        return String.format(java.util.Locale.US, "%,d", n);
    }
}
