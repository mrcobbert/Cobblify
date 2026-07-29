package com.bedwarsqol.stats;

import com.bedwarsqol.config.ClientSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * One Seraph list membership (blacklist / safelist / bot / annoy) plus the single policy/format
 * authority every surface (tab badge, nametag badge, chat alert, hover, command) consults for the
 * Seraph provider. Immutable; relayed in-memory only (never persisted to disk).
 *
 * <p>Unlike {@link UrchinTag}, Seraph tags do not expire. The {@code blacklist} kind is a cheater
 * accusation (its {@link #verified} flag raises confidence and colour); {@code safelist} is a
 * trusted/legit signal (the false-flag counter-signal, never a cheater type); {@code bot} and
 * {@code annoy} are low-severity nuisance markers. Read exclusively through
 * {@link #activeTags(List)} — no consumer reads a raw tag list.
 */
public final class SeraphTag {

    public final String kind;      // "blacklist" | "safelist" | "bot" | "annoy"
    public final String subtype;   // blacklist report_type (e.g. "cheater"/"sniper"); may be empty
    public final String reason;    // sanitized reason/tooltip (may be empty)
    public final long addedOnMs;   // epoch ms the entry was added; 0 when unknown
    public final boolean verified; // blacklist only: manually verified report

    public SeraphTag(String kind, String subtype, String reason, long addedOnMs, boolean verified) {
        this.kind = UrchinTag.sanitize(kind == null ? "" : kind).toLowerCase(Locale.ROOT);
        this.subtype = UrchinTag.sanitize(subtype == null ? "" : subtype).toLowerCase(Locale.ROOT);
        this.reason = UrchinTag.sanitize(reason == null ? "" : reason);
        this.addedOnMs = addedOnMs;
        this.verified = verified;
    }

    // ---- policy: mapping, colors, severity ----------------------------------

    /** Icon glyph shown in the bracketed badge, or null for an unknown/non-displayable kind. */
    public String displayIcon() {
        switch (kind) {
            case "blacklist": return "BL";
            case "safelist":  return "SL";
            case "bot":       return "BOT";
            case "annoy":     return "AL";
            default:          return null; // unknown future kind — not rendered
        }
    }

    /** The section-color code for this tag's badge/label. */
    public String color() {
        switch (kind) {
            case "blacklist": return verified ? "§4" : "§c";
            case "safelist":  return "§a";
            case "bot":       return "§e";
            case "annoy":     return "§7";
            default:          return "§7";
        }
    }

    /** Human label for the tag, used in tooltips/command output. */
    public String displayName() {
        switch (kind) {
            case "blacklist": return verified ? "Blacklisted (verified)" : "Blacklisted";
            case "safelist":  return "Safelisted";
            case "bot":       return "Bot";
            case "annoy":     return "Annoylist";
            default:          return kind.isEmpty() ? "Unknown" : kind;
        }
    }

    /** Whether this tag can render on a surface (unknown kinds never do). */
    public boolean isDisplayable() {
        return displayIcon() != null;
    }

    /** The bracketed badge token appended to a tab/nametag line, e.g. {@code " [BL]"}. Empty for a
     *  non-displayable tag. */
    public String badgeToken() {
        String icon = displayIcon();
        if (icon == null) return "";
        return " §8[" + color() + icon + "§8]";
    }

    /** Only a blacklist accusation gates the alert sound. */
    public boolean isCheaterType() {
        return kind.equals("blacklist");
    }

    /** Severity rank; higher wins the priority badge. verified BL > BL > bot > annoy > safelist. */
    public int severity() {
        switch (kind) {
            case "blacklist": return verified ? 5 : 4;
            case "bot":       return 3;
            case "annoy":     return 2;
            case "safelist":  return 1; // positive signal — lowest so a real accusation always wins the badge
            default:          return 0;
        }
    }

    /**
     * Render gate shared by every Seraph badge surface (tab + nametag): a badge may only render when
     * the current-session eligibility snapshot confirms the exact displayed (name, uuid) pair for the
     * Seraph provider right now. Fails closed on a null snapshot, a stale session, or an unconfirmed
     * row, so a cached tag can never paint on an unconfirmed identity.
     */
    public static boolean badgeAllowed(EligibilitySnapshot snap, String name, java.util.UUID uuid) {
        return snap != null && snap.eligibleSeraph(name, uuid);
    }

    // ---- static helpers -----------------------------------------------------

    /**
     * The displayable subset of {@code tags} (Seraph tags never expire). EVERY read goes through
     * this — no surface reads the raw list. Returns an unmodifiable list.
     */
    public static List<SeraphTag> activeTags(List<SeraphTag> tags) {
        if (tags == null || tags.isEmpty()) return Collections.emptyList();
        List<SeraphTag> out = new ArrayList<SeraphTag>();
        for (SeraphTag t : tags) {
            if (t == null) continue;
            if (t.isDisplayable()) out.add(t);
        }
        return Collections.unmodifiableList(out);
    }

    /** The highest-severity displayable tag, or null when there is none. */
    public static SeraphTag priority(List<SeraphTag> tags) {
        SeraphTag best = null;
        for (SeraphTag t : activeTags(tags)) {
            if (best == null || t.severity() > best.severity()) best = t;
        }
        return best;
    }

    /**
     * Sanitize untrusted tag text. Delegates to {@link UrchinTag#sanitize(String)} so the Seraph and
     * Urchin providers apply the byte-for-byte same character policy (section signs, C0/C1 controls,
     * zero-width/format/bidi chars, BOM stripped; whitespace collapsed; capped at 120 chars) — the
     * same policy the Worker enforces server-side.
     */
    public static String sanitize(String raw) {
        return UrchinTag.sanitize(raw);
    }

    /** The tab overlay needs player identity when the Seraph tab badge/alert wants it. */
    public static boolean needsTabIdentity(ClientSettings cfg) {
        if (cfg == null) return false;
        return cfg.seraphTags && (cfg.seraphBadgeTab || cfg.seraphChatAlert);
    }

    /** The nametag overlay needs player identity when the Seraph nametag badge wants it. */
    public static boolean needsNametagIdentity(ClientSettings cfg) {
        if (cfg == null) return false;
        return cfg.seraphTags && cfg.seraphBadgeNametag;
    }
}
