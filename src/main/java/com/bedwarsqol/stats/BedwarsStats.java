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

    private BedwarsStats(State state, String displayName,
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
        return new BedwarsStats(State.NICKED, null, "", null, null, null, null, null, null, null, null, -1, -1);
    }

    public static BedwarsStats error() {
        return new BedwarsStats(State.ERROR, null, "", null, null, null, null, null, null, null, null, -1, -1);
    }

    public static BedwarsStats neverPlayed(String displayName) {
        return new BedwarsStats(State.NEVER_PLAYED, displayName, "", null, null, null, null, null, null, null, null, -1, -1);
    }

    public static BedwarsStats ok(String displayName, String rankPrefix,
                                  String rankCode, ModeStats overall, ModeStats solo, ModeStats doubles,
                                  ModeStats threes, ModeStats fours) {
        return new BedwarsStats(State.OK, displayName, rankPrefix, rankCode,
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
        return new BedwarsStats(state, displayName, rankPrefix, rankCode,
                overall, solo, doubles, threes, fours, tags, seraphTags, seraphThreat, seraphEncounters);
    }

    /** A copy carrying Seraph {@code tags} + statistics (Seraph resolution merge). {@code threat} /
     *  {@code encounters} are {@code -1} when absent. Preserves all stats fields. */
    public BedwarsStats withSeraph(List<SeraphTag> tags, int threat, int encounters) {
        return new BedwarsStats(state, displayName, rankPrefix, rankCode,
                overall, solo, doubles, threes, fours, urchinTags, tags, threat, encounters);
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
        if (fkdr < 2.0)  return "§f";  // White  - low threat
        if (fkdr < 5.0)  return "§e";  // Yellow
        if (fkdr < 10.0) return "§6";  // Orange (gold)
        return "§c";                   // Red    - sweat
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
        // A transient fetch failure renders nothing (like a still-loading player) — see specialLabel;
        // falling through would paint a real player as a misleading "FKDR 0.00" for the error TTL.
        if (state == State.ERROR) return null;
        String special = specialLabel();
        if (special != null) return special;
        ModeStats m = statsFor(mode);
        StringBuilder sb = new StringBuilder();
        appendPrefix(sb, showRank);
        sb.append(fkdrColor(m.fkdr)).append("FKDR ").append(fmt2(m.fkdr)).append("§r");
        return sb.toString();
    }

    public String formatForTab(BedwarsMode mode, boolean showRank) {
        if (state == State.ERROR) return null; // same render-nothing contract as formatForNametag
        String special = specialLabel();
        if (special != null) return special;
        ModeStats m = statsFor(mode);
        StringBuilder sb = new StringBuilder();
        appendPrefix(sb, showRank);
        sb.append("§7FKDR: ").append(fkdrColor(m.fkdr)).append(fmt2(m.fkdr))
                .append(" §7WLR: §f").append(fmt2(m.wlr));
        return sb.toString();
    }

    /**
     * Multi-line stat block for the chat hover card. Returns the lines to append under Hypixel's own
     * rank tooltip; empty when there is nothing useful to show (a transient fetch error), so the caller
     * can fall back to the vanilla card alone.
     */
    public java.util.List<String> formatForHoverCard(BedwarsMode mode, boolean showRank) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        switch (state) {
            case NICKED:       out.add("§6§lBedWars §r§8(nicked / not found)"); return out;
            case NEVER_PLAYED: out.add("§6§lBedWars §r§8(never played)");       return out;
            case ERROR:        return out; // empty -> caller keeps the vanilla card and retries later
            default: break;
        }
        ModeStats m = statsFor(mode);
        StringBuilder header = new StringBuilder("§6§lBedWars");
        String modeTag = hoverModeTag(mode);
        if (modeTag != null) header.append(" §r§7(").append(modeTag).append("§7)");
        if (showRank && !rankPrefix.isEmpty()) header.append(" §r").append(rankPrefix);
        out.add(header.toString());
        out.add("§7FKDR: " + fkdrColor(m.fkdr) + fmt2(m.fkdr) + " §r§8| §7Finals: §f" + num(m.finalKills) + "§7/§f" + num(m.finalDeaths));
        out.add("§7WLR: §f" + fmt2(m.wlr) + " §8| §7W/L: §f" + num(m.wins) + "§7/§f" + num(m.losses));
        out.add("§7K/D: §f" + fmt2(m.kd) + " §8| §7Kills: §f" + num(m.kills));
        return out;
    }

    /**
     * Short label for the mode the hover card is actually showing — {@code Solo}/{@code 2s}/{@code 3s}/
     * {@code 4s} — or {@code null} for overall. Returns null when a specific mode was requested but the
     * player has no games there, because {@link #statsFor} then falls back to overall and labelling it
     * with the requested mode would misrepresent the numbers on screen.
     */
    private String hoverModeTag(BedwarsMode mode) {
        switch (mode) {
            case SOLO:    return solo    != null && solo.hasGames()    ? "Solo" : null;
            case DOUBLES: return doubles != null && doubles.hasGames() ? "2s"   : null;
            case THREES:  return threes  != null && threes.hasGames()  ? "3s"   : null;
            case FOURS:   return fours   != null && fours.hasGames()   ? "4s"   : null;
            default:      return null;
        }
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
        if (showRank && !rankPrefix.isEmpty()) {
            sb.append(rankPrefix).append("§r ");
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
