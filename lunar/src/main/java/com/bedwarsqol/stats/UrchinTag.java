package com.bedwarsqol.stats;

import com.bedwarsqol.config.ClientSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * One community-reported Urchin blacklist tag plus THE single policy/format authority every surface
 * (tab badge, nametag badge, chat alert, hover, command) consults. Immutable;
 * relayed in-memory only (never persisted to disk).
 *
 * <p>Tags carry an optional {@link #expiresAtMs}; a tag whose expiry has passed is inactive and must
 * never reach any surface. Read exclusively through {@link #activeTags(List, long)} — no consumer
 * reads a raw tag list.
 */
public final class UrchinTag {

    public final String type;       // sanitized, lowercase Coral tag_type
    public final String reason;     // sanitized reason (may be empty)
    public final long addedOnMs;    // epoch ms the tag was added; 0 when unknown
    public final Long expiresAtMs;  // epoch ms the tag expires; null = never expires

    public UrchinTag(String type, String reason, long addedOnMs, Long expiresAtMs) {
        this.type = sanitize(type == null ? "" : type).toLowerCase(Locale.ROOT);
        this.reason = sanitize(reason == null ? "" : reason);
        this.addedOnMs = addedOnMs;
        this.expiresAtMs = expiresAtMs;
    }

    // ---- policy: mapping, colors, severity ----------------------------------

    /** Icon glyph shown in the bracketed badge, or null for a non-displayable (info/account) tag. */
    public String displayIcon() {
        switch (type) {
            case "confirmed_cheater": return "CCC";
            case "blatant_cheater":   return "BC";
            case "closet_cheater":    return "CC";
            case "sniper":            return "S";
            case "legit_sniper":      return "LS";
            case "possible_sniper":   return "PS";
            case "caution":           return "C";
            case "info":
            case "account":
                return null; // non-displayable
            default:
                return "?"; // unknown future type — generic badge, raw type shown in tooltips
        }
    }

    /** The §-color code for this tag's badge/label. */
    public String color() {
        switch (type) {
            case "confirmed_cheater": return "§5";
            case "blatant_cheater":   return "§6";
            case "closet_cheater":    return "§6";
            case "sniper":            return "§4";
            case "legit_sniper":      return "§c";
            case "possible_sniper":   return "§6";
            case "caution":           return "§6";
            default:                  return "§7"; // unknown fallback (and never used for info/account)
        }
    }

    /** Human label for the tag, used in tooltips/command output. Unknown types show their raw type. */
    public String displayName() {
        switch (type) {
            case "confirmed_cheater": return "Confirmed Cheater";
            case "blatant_cheater":   return "Blatant Cheater";
            case "closet_cheater":    return "Closet Cheater";
            case "sniper":            return "Sniper";
            case "legit_sniper":      return "Legit Sniper";
            case "possible_sniper":   return "Possible Sniper";
            case "caution":           return "Caution";
            default:                  return type.isEmpty() ? "Unknown" : type;
        }
    }

    /** Whether this tag can render on a surface (info/account never do). */
    public boolean isDisplayable() {
        return displayIcon() != null;
    }

    /** The bracketed badge token appended to a tab/nametag line, e.g. {@code " §8[§4S§8]"}. Empty
     *  for a non-displayable tag. */
    public String badgeToken() {
        String icon = displayIcon();
        if (icon == null) return "";
        return " §8[" + color() + icon + "§8]";
    }

    /** Cheater-type tags gate ordinary chat and its pling. */
    public boolean isCheaterType() {
        return type.equals("confirmed_cheater") || type.equals("blatant_cheater")
                || type.equals("closet_cheater");
    }

    /** Severity rank; higher wins the priority badge. CCC>BC>CC>S>LS>PS>C>unknown. */
    public int severity() {
        switch (type) {
            case "confirmed_cheater": return 7;
            case "blatant_cheater":   return 6;
            case "closet_cheater":    return 5;
            case "sniper":            return 4;
            case "legit_sniper":      return 3;
            case "possible_sniper":   return 2;
            case "caution":           return 1;
            default:                  return 0; // unknown
        }
    }

    public boolean isActive(long nowMs) {
        return expiresAtMs == null || expiresAtMs > nowMs;
    }

    /**
     * Render gate shared by every Urchin badge surface (tab + nametag): a badge may only render when
     * the current-session eligibility snapshot confirms the exact displayed (name, uuid) pair right
     * now. Fails closed on a null snapshot, a stale session, or an unconfirmed row - the same
     * predicate transport uses - so a cached tag can never paint on an unconfirmed identity.
     */
    public static boolean badgeAllowed(EligibilitySnapshot snap, String name, java.util.UUID uuid) {
        return snap != null && snap.eligible(name, uuid);
    }

    // ---- static helpers -----------------------------------------------------

    /**
     * The active, displayable subset of {@code tags} at {@code nowMs}: a tag with a non-null
     * {@code expiresAtMs <= nowMs} is inactive (equality = inactive; null never expires). EVERY read
     * goes through this — no surface reads the raw list. Returns an unmodifiable list.
     */
    public static List<UrchinTag> activeTags(List<UrchinTag> tags, long nowMs) {
        if (tags == null || tags.isEmpty()) return Collections.emptyList();
        List<UrchinTag> out = new ArrayList<UrchinTag>();
        for (UrchinTag t : tags) {
            if (t == null) continue;
            if (t.isActive(nowMs) && t.isDisplayable()) out.add(t);
        }
        return Collections.unmodifiableList(out);
    }

    /** The highest-severity active displayable tag, or null when there is none. */
    public static UrchinTag priority(List<UrchinTag> tags, long nowMs) {
        UrchinTag best = null;
        for (UrchinTag t : activeTags(tags, nowMs)) {
            if (best == null || t.severity() > best.severity()) best = t;
        }
        return best;
    }

    /** Strip §/control/format chars, collapse whitespace, cap at 120 chars. */
    public static String sanitize(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '§') { i++; continue; } // drop § and the code char after it
            if (isControlOrFormat(c)) { sb.append(' '); continue; } // -> space, collapsed below
            sb.append(c);
        }
        String s = sb.toString().replaceAll("\\s+", " ").trim();
        if (s.length() > 120) s = s.substring(0, 120);
        return s;
    }

    /**
     * C0 + DEL + C1 controls, zero-width/format chars, bidi overrides/isolates and BOM - the same
     * character policy the Worker applies in server/stats-worker/src/urchin.js sanitizeText. These
     * must never survive into chat/tooltips from untrusted tag reasons/types.
     */
    private static boolean isControlOrFormat(char c) {
        // Explicit BMP Cf ranges (current Unicode) because the Java 8 runtime's own Unicode
        // tables predate e.g. U+061C ARABIC LETTER MARK; getType() alone under-strips there.
        return c < ' '                                      // C0 controls
                || (c >= '\u007f' && c <= '\u009f')         // DEL + C1 controls
                || c == '\u00ad'                             // soft hyphen
                || (c >= '\u0600' && c <= '\u0605')         // Arabic number signs
                || c == '\u061c' || c == '\u06dd' || c == '\u070f'
                || c == '\u0890' || c == '\u0891' || c == '\u08e2'
                || c == '\u180e'                             // Mongolian vowel separator
                || (c >= '\u200b' && c <= '\u200f')         // zero-width + LRM/RLM
                || (c >= '\u202a' && c <= '\u202e')         // bidi overrides/embeddings
                || (c >= '\u2060' && c <= '\u206f')         // word joiner, invisibles, bidi isolates, deprecated controls
                || c == '\ufeff'                             // BOM
                || (c >= '\ufff9' && c <= '\ufffb')         // interlinear annotation
                || Character.getType(c) == Character.FORMAT; // any Cf the runtime does know
    }

    /** The tab overlay needs player identity when Player Stats OR the Urchin tab badge wants it. */
    public static boolean needsTabIdentity(ClientSettings cfg) {
        if (cfg == null) return false;
        return (cfg.playerStats && cfg.playerStatsTab)
                || (cfg.urchinTags && (cfg.urchinBadgeTab || cfg.urchinChatAlert));
    }

    /** The nametag overlay needs player identity when Player Stats OR the Urchin nametag badge wants it. */
    public static boolean needsNametagIdentity(ClientSettings cfg) {
        if (cfg == null) return false;
        return (cfg.playerStats && cfg.playerStatsNametag)
                || (cfg.urchinTags && cfg.urchinBadgeNametag);
    }
}
