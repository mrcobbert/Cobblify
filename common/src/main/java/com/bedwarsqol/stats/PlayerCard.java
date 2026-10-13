package com.bedwarsqol.stats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * THE per-player presentation decision, computed once from the same policy classes every in-game
 * surface consults ({@link UrchinTag}, {@link BedwarsStats#fkdrTier}) and
 * exported to the launcher overlay verbatim. The launcher draws a card; it never re-derives a
 * tier, a cheater flag or a badge.
 *
 * <p>Rules, in the order they are applied:
 * <ol>
 *   <li><b>Identity.</b> A nick is revealed only when Nick Utils and Auto Denick are both on and
 *       the nick has a verified real name; then the card's stats and state are the <i>real</i>
 *       account's (kicking a name-keyed fetch when they are not cached yet). Otherwise stats are
 *       looked up by the displayed identity and the real name is never exported.</li>
 *   <li><b>Numbers.</b> {@link BedwarsStats#statsFor(BedwarsMode)} for the caller's display mode,
 *       the same resolver the chat bracket and hover card use; {@link #modeLabel} names the mode
 *       the numbers actually came from.</li>
 *   <li><b>Tags.</b> Urchin tags pass through {@code activeTags} (expiry, displayability) and are
 *       dropped entirely unless Urchin is on and the caller's eligibility gate
 *       ({@code badgeAllowed}) confirmed this (name, uuid) pair.</li>
 *   <li><b>Badge and cheater.</b> The badge is the single priority chip ({@link #priorityBadge});
 *       {@code cheater} is true only when the priority tag is a cheater type (confirmed, blatant or
 *       closet).</li>
 * </ol>
 *
 * <p>Pure and platform-neutral (Java 8, no Minecraft types); the mod supplies a {@link Source}
 * over {@code StatsCache} and tests supply a map.
 */
public final class PlayerCard {

    /** Label for numbers that come from the overall block. */
    public static final String OVERALL = "Overall";

    /** One rendered tag: badge glyph, §-colour, human label, and whether it is a positive signal. */
    public static final class Chip {
        public final String code;
        public final String color;
        public final String label;
        public final boolean positive;

        public Chip(String code, String color, String label, boolean positive) {
            this.code = code == null ? "" : code;
            this.color = color == null ? "§7" : color;
            this.label = label == null ? "" : label;
            this.positive = positive;
        }

        static Chip of(UrchinTag t) {
            return new Chip(t.displayIcon(), t.color(), t.displayName(), false);
        }
    }

    /** The three master toggles the card honours. */
    public static final class Toggles {
        public final boolean urchin;
        public final boolean nickUtils;
        public final boolean autoDenick;

        public Toggles(boolean urchin, boolean nickUtils, boolean autoDenick) {
            this.urchin = urchin;
            this.nickUtils = nickUtils;
            this.autoDenick = autoDenick;
        }

        public static final Toggles ALL_ON = new Toggles(true, true, true);
        public static final Toggles ALL_OFF = new Toggles(false, false, false);
    }

    /** Cache reads the card needs; the mod backs this with {@code StatsCache}. */
    public interface Source {
        BedwarsStats byUuid(UUID uuid);
        BedwarsStats byName(String name);
        /** Request a name-keyed fetch (used only for a verified real name that is not cached). */
        void fetchByName(String name);
    }

    public final String name;
    /** Null while loading. */
    public final BedwarsStats.State state;
    public final boolean nicked;
    /** Verified real account name when revealed, else null. */
    public final String realName;
    /** Plain rank label, e.g. {@code [MVP+]}; empty for none. */
    public final String rank;
    /** The same label with its §-colour codes, e.g. {@code §b[MVP§c+§b]}. */
    public final String rankCodes;
    /** {@link #OVERALL} or the short mode label the numbers came from (Solo/2s/3s/4s). */
    public final String modeLabel;
    public final double fkdr;
    public final double wlr;
    public final int finalKills;
    public final double kd;
    public final int fkdrTier;
    public final boolean cheater;
    /** Single priority chip, or null. */
    public final Chip badge;
    /** Every active, gated chip, in provider order. */
    public final List<Chip> chips;

    private PlayerCard(String name, BedwarsStats.State state, boolean nicked, String realName,
                       String rank, String rankCodes, String modeLabel, double fkdr, double wlr,
                       int finalKills, double kd, boolean cheater, Chip badge, List<Chip> chips) {
        this.name = name;
        this.state = state;
        this.nicked = nicked;
        this.realName = realName;
        this.rank = rank;
        this.rankCodes = rankCodes;
        this.modeLabel = modeLabel;
        this.fkdr = fkdr;
        this.wlr = wlr;
        this.finalKills = finalKills;
        this.kd = kd;
        this.fkdrTier = BedwarsStats.fkdrTier(fkdr);
        this.cheater = cheater;
        this.badge = badge;
        this.chips = Collections.unmodifiableList(new ArrayList<Chip>(chips));
    }

    /** The contract state string: OK|NICKED|NEVER_PLAYED|ERROR, or LOADING while unresolved. */
    public String stateName() {
        return state == null ? "LOADING" : state.name();
    }

    /**
     * Build the card. {@code urchinEligible} is the caller's {@code badgeAllowed} result for the
     * displayed (name, uuid); {@code verifiedRealName} is the
     * {@code Denicks} verified real name or null. Never throws; a missing source yields a loading card.
     */
    public static PlayerCard build(Source src, String name, UUID uuid, BedwarsMode mode, Toggles t,
                                   boolean urchinEligible, String verifiedRealName, long nowMs) {
        if (t == null) t = Toggles.ALL_OFF;
        if (mode == null) mode = BedwarsMode.UNKNOWN;
        boolean reveal = t.nickUtils && t.autoDenick && verifiedRealName != null
                && !verifiedRealName.isEmpty();

        BedwarsStats st = null;
        if (src != null) {
            if (reveal) {
                st = src.byName(verifiedRealName);
                if (st == null) src.fetchByName(verifiedRealName);
            } else {
                if (uuid != null) st = src.byUuid(uuid);
                if (st == null) st = src.byName(name);
            }
        }
        String realName = reveal ? verifiedRealName : null;
        if (st == null) {
            return new PlayerCard(name, null, reveal, realName, "", "", OVERALL, 0, 0, 0, 0,
                    false, null, Collections.<Chip>emptyList());
        }

        boolean nicked = reveal || st.state == BedwarsStats.State.NICKED;
        BedwarsStats.ModeStats m = st.statsFor(mode);
        String label = st.modeLabel(mode);
        if (label == null) label = OVERALL;

        List<UrchinTag> urchin = (t.urchin && urchinEligible)
                ? UrchinTag.activeTags(st.urchinTags, nowMs) : Collections.<UrchinTag>emptyList();

        List<Chip> chips = new ArrayList<Chip>(urchin.size());
        for (UrchinTag u : urchin) chips.add(Chip.of(u));

        UrchinTag pu = UrchinTag.priority(urchin, nowMs);
        boolean cheater = pu != null && pu.isCheaterType();

        return new PlayerCard(name, st.state, nicked, realName, stripSection(st.rankPrefix),
                st.rankPrefix, label, m.fkdr, m.wlr, m.finalKills, m.kd, cheater,
                priorityBadge(urchin, nowMs), chips);
    }

    /**
     * The single most-severe tag (highest {@code severity()}), or null. The overlay badge comes
     * from here. Callers pass an already gating-filtered list.
     */
    public static Chip priorityBadge(List<UrchinTag> urchin, long nowMs) {
        UrchinTag u = UrchinTag.priority(urchin, nowMs);
        return u == null ? null : Chip.of(u);
    }

    /** Strip §-codes from a rank label. */
    static String stripSection(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) { i++; continue; }
            b.append(c);
        }
        return b.toString();
    }
}
