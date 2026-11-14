package com.bedwarsqol.feature;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads one chat line or title into a Team Upgrades event (research-hypixel.md §3): a purchase
 * broadcast, a trap set off, or a trap removed from the queue.
 *
 * <p>Only Hypixel's own broadcast counts, never a line a player typed (A5, same rule as
 * {@code GeneratorUpgradeParse}): a line with a sender is refused, a line containing {@code ": "} is
 * refused (player chat always has one; the one exception is Castle's {@code <Bed> Bed: } prefix on
 * the Reveal line), and a purchase must have its item in gold, which players cannot type. The item
 * must also be a catalogue upgrade tier or trap, which keeps the item shop's
 * {@code You purchased Wool} out.
 */
public final class TeamUpgradeChat {

    private TeamUpgradeChat() {}

    public enum Kind { PURCHASE, TRAP_FIRED, TRAP_REMOVED }

    /** One event. {@code item} is the catalogue name (traps under their current name); null for a fired head. */
    public static final class Event {
        public final Kind kind;
        public final String item;

        public Event(Kind kind, String item) {
            this.kind = kind;
            this.item = item;
        }

        @Override
        public String toString() {
            return kind + "(" + item + ")";
        }
    }

    private static final char SECTION = '§';

    private static final Pattern PURCHASE = Pattern.compile("^([A-Za-z0-9_]{1,16}) purchased (.+)$");
    private static final Pattern SET_OFF = Pattern.compile("^(.+?) was set off!$");
    private static final Pattern REVEAL =
            Pattern.compile("^([A-Za-z]+ Bed: )?(?:Reveal|Alarm) trap set off by \\S+ from .+ team!$");
    private static final Pattern REMOVED = Pattern.compile("^Removed (.+) from the queue!$");
    private static final Pattern SUBTITLE = Pattern.compile("^Your (?:[A-Za-z]+ Bed )?(.+) has been set off!$");

    /**
     * The event in one chat line, or null. {@code plain} is the unformatted text, {@code formatted}
     * Minecraft's {@code getFormattedText()} (§ codes, possibly §r between runs), {@code sender} the
     * typed-chat player name the chat parser found, or null for a server line.
     */
    public static Event classify(String plain, String formatted, String sender) {
        if (sender != null || plain == null) return null;
        String text = TeamUpgradeCatalog.strip(plain).trim();
        if (text.isEmpty()) return null;

        Matcher reveal = REVEAL.matcher(text);
        if (reveal.matches()) {
            // Castle names the bed with "<Bed> Bed: "; nothing after that prefix may hold another ": "
            String prefix = reveal.group(1);
            String rest = prefix == null ? text : text.substring(prefix.length());
            return rest.contains(": ") ? null : new Event(Kind.TRAP_FIRED, TeamUpgradeCatalog.REVEAL_TRAP);
        }
        if (text.contains(": ")) return null;

        Matcher m = PURCHASE.matcher(text);
        if (m.matches()) {
            String item = m.group(2);
            String canonical = catalogueItem(item);
            if (canonical == null || !goldItem(formatted, item)) return null;
            return new Event(Kind.PURCHASE, canonical);
        }
        m = SET_OFF.matcher(text);
        if (m.matches()) {
            String trap = TeamUpgradeCatalog.trapName(m.group(1));
            return trap == null ? null : new Event(Kind.TRAP_FIRED, trap);
        }
        m = REMOVED.matcher(text);
        if (m.matches()) {
            String trap = TeamUpgradeCatalog.trapName(m.group(1));
            return trap == null ? null : new Event(Kind.TRAP_REMOVED, trap);
        }
        return null;
    }

    /**
     * The event in a title or subtitle, or null: {@code TRAP TRIGGERED!} and {@code ALARM!!!} fire the
     * queue head; {@code Your <Trap> has been set off!} fires that trap (an unknown name is kept as
     * written; Castle's {@code <Bed> Bed } is dropped). Players cannot send titles.
     */
    public static Event classifyTitle(String text) {
        if (text == null) return null;
        String t = TeamUpgradeCatalog.strip(text).trim();
        if (t.equals("TRAP TRIGGERED!") || t.equals("ALARM!!!")) return new Event(Kind.TRAP_FIRED, null);
        Matcher m = SUBTITLE.matcher(t);
        if (m.matches()) {
            String name = m.group(1).trim();
            String trap = TeamUpgradeCatalog.trapName(name);
            return new Event(Kind.TRAP_FIRED, trap != null ? trap : name);
        }
        return null;
    }

    /** Catalogue name of a purchased item (traps canonical), or null when it is no team upgrade or trap. */
    private static String catalogueItem(String item) {
        String trap = TeamUpgradeCatalog.trapName(item);
        if (trap != null) return trap;
        return TeamUpgradeCatalog.upgradeTier(item) != null ? item : null;
    }

    /**
     * True when {@code formatted} ends with {@code item} (trailing codes allowed) and the run of §
     * codes right before it contains §6 (gold), e.g. {@code §6Heal Pool} or {@code §r§6Heal Pool§r}.
     */
    static boolean goldItem(String formatted, String item) {
        if (formatted == null) return false;
        int end = formatted.length();
        while (true) {
            while (end > 0 && Character.isWhitespace(formatted.charAt(end - 1))) end--;
            if (end >= 2 && formatted.charAt(end - 2) == SECTION) end -= 2;
            else break;
        }
        if (!formatted.substring(0, end).endsWith(item)) return false;
        int i = end - item.length();
        boolean gold = false;
        while (i >= 2 && formatted.charAt(i - 2) == SECTION) {
            if (formatted.charAt(i - 1) == '6') gold = true;
            i -= 2;
        }
        return gold;
    }
}
