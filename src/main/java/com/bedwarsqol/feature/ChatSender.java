package com.bedwarsqol.feature;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls the sender's username out of a Hypixel chat component, format-aware. Shared by
 * {@link ChatHoverStats} (hover card) and {@link ChatNameTags} (inline tags) so the parse is defined
 * once. Three shapes are recognised, in order: the one colon-less server broadcast guaranteed to name
 * a real player (the rank-gated hub {@code "<name> joined the lobby!"}); {@code "<sender>: <message>"}
 * chat in any channel; and a lone name token (the lobby rank-card name). Returns null when no
 * plausible sender is present, so non-player lines are left untouched.
 *
 * <p>The pregame queue's join/leave broadcasts — {@code "<name> has joined (n/m)!"},
 * {@code "<name> has quit!"} and {@code "<name> disconnected."} — are deliberately <i>not</i>
 * recognised: since ~Aug 2024 Hypixel anonymizes the names in them (junk like {@code vj3x1s4w18}), so
 * those shapes can never be trusted to name a real player and must never drive lookups or tags, no
 * matter what context the caller believes it is in.
 *
 * <p>{@code /party list} roster lines are the one colon shape whose names sit <i>after</i> the colon,
 * and they get their own accessor ({@link #rosterMembers}) rather than a sender — see
 * {@link #isRosterLabel}.
 */
public final class ChatSender {

    private ChatSender() {}

    /** A single Minecraft username token: 3-16 of [A-Za-z0-9_]. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    /** Bracketed rank/guild tags dropped before locating the sender, e.g. [MVP+], [Officer], [TAG]. */
    private static final Pattern BRACKET_TAG = Pattern.compile("\\[[^\\]]*\\]");
    /** "[rank] <name> joined the lobby!" (optionally wrapped in >>> … <<<); group 1 = name. */
    private static final Pattern JOINED_LOBBY =
            Pattern.compile("^(?:>+\\s*)?(?:\\[[^\\]]*\\]\\s*)+([A-Za-z0-9_]{3,16}) joined the lobby!");

    /** The sender username named by this chat component, or null. */
    public static String extractName(IChatComponent component) {
        String raw = plainText(component);
        if (raw == null) return null;

        String shaped = extractFromServerLine(raw);
        if (shaped != null) return shaped;

        int colon = raw.indexOf(':');
        if (colon > 0) return senderFromHead(raw.substring(0, colon));

        // No colon and no known server shape: trust only a lone name token (the rank-card name
        // component), so prose like "Bob has joined" can't drive a bogus lookup on "joined".
        List<String> tokens = nameTokens(BRACKET_TAG.matcher(raw).replaceAll(" "));
        return tokens.size() == 1 ? tokens.get(0) : null;
    }

    /**
     * The sender of a typed chat message only ({@code "<sender>: <message>"}, any channel), or null.
     * Unlike {@link #extractName} this ignores the colon-less server broadcasts and the lone-name
     * shape: in the anonymized pregame queue those carry obfuscated names, while a message a player
     * actually typed always names its real sender (Hypixel never anonymizes typed chat).
     */
    public static String typedChatName(IChatComponent component) {
        String raw = plainText(component);
        if (raw == null) return null;
        int colon = raw.indexOf(':');
        return colon > 0 ? senderFromHead(raw.substring(0, colon)) : null;
    }

    /** The component's text stripped of formatting codes and trimmed; null when effectively empty. */
    private static String plainText(IChatComponent component) {
        if (component == null) return null;
        String unformatted = component.getUnformattedText();
        if (unformatted == null) return null;
        String raw = EnumChatFormatting.getTextWithoutFormattingCodes(unformatted);
        if (raw == null) return null;
        raw = raw.trim();
        return raw.isEmpty() ? null : raw;
    }

    /**
     * The name from the one colon-less broadcast that reliably carries a real player name: the
     * rank-gated hub lobby join. Null for anything else — including the pregame queue's join/leave
     * broadcasts, whose names Hypixel anonymizes (see class javadoc).
     */
    private static String extractFromServerLine(String raw) {
        Matcher m = JOINED_LOBBY.matcher(raw);
        return m.find() ? m.group(1) : null;
    }

    /**
     * The sender from a chat line's pre-colon head. A rank/level/guild bracket or a channel prefix
     * ("From", "Party >", "Guild >", …) means the trailing token is the name. A bare head with
     * neither is trusted only when it is a single token actually in our tab list, so system labels
     * ("Command Failed:", "Cooldown:") aren't mistaken for players.
     */
    private static String senderFromHead(String head) {
        String lower = head.trim().toLowerCase();
        boolean channel = lower.startsWith("to ") || lower.startsWith("from ")
                || lower.startsWith("party ") || lower.startsWith("guild ")
                || lower.startsWith("officer ") || lower.startsWith("friend ")
                || lower.startsWith("co-op ") || lower.startsWith("shout ");
        boolean hadBracket = head.indexOf('[') >= 0;
        List<String> tokens = nameTokens(BRACKET_TAG.matcher(head).replaceAll(" "));
        if (tokens.isEmpty()) return null;
        if (isRosterLabel(head, tokens)) return null; // names are after the colon; see rosterMembers
        String last = tokens.get(tokens.size() - 1);
        if (hadBracket || channel) return last;
        if (tokens.size() != 1) return null;
        return uuidInTab(last) != null ? last : null;
    }

    /**
     * True when a pre-colon head is a {@code /party list} roster <i>label</i> ("Party Leader",
     * "Party Moderators", "Party Members") rather than a channel prefix. Those lines carry their names
     * after the colon, so the channel branch's trailing-token rule returned the label word itself — and
     * because the accounts {@code Leader}, {@code Moderators} and {@code Members} all exist, the line
     * silently showed a stranger's FKDR and burned a lookup fetching it.
     *
     * <p>Party <i>chat</i> always carries the {@code >} marker ("Party > [VIP] Name: msg"), which is
     * what keeps a real player named Leader taggable. Brackets are already stripped out of
     * {@code tokens}, so our own prepended {@code [x.xx]} tag can't hide the label from this check when
     * the hover path re-parses an already-tagged line.
     */
    private static boolean isRosterLabel(String head, List<String> tokens) {
        if (head.indexOf('>') >= 0) return false;
        if (tokens.size() != 2 || !tokens.get(0).equalsIgnoreCase("Party")) return false;
        String label = tokens.get(1);
        return label.equalsIgnoreCase("Leader") || label.equalsIgnoreCase("Moderators")
                || label.equalsIgnoreCase("Members");
    }

    /** Cap on names taken off one roster line: a bound on the lookups a single line can fire. */
    private static final int MAX_ROSTER = 20;

    /**
     * The party members named by a {@code /party list} roster line ("Party Leader: [VIP] A ●",
     * "Party Members: [MVP+] B ● C ●"), in line order; empty for every other line. Rank brackets are
     * dropped and the {@code ●} status dots separate the names, so what is left is the member list.
     *
     * <p>These names are real: a party roster is your own party's state, which Hypixel does not
     * anonymize the way it does the pregame lobby's broadcasts and nametags.
     */
    public static List<String> rosterMembers(IChatComponent component) {
        String raw = plainText(component);
        if (raw == null) return Collections.emptyList();
        int colon = raw.indexOf(':');
        if (colon <= 0) return Collections.emptyList();
        String head = raw.substring(0, colon);
        if (!isRosterLabel(head, nameTokens(BRACKET_TAG.matcher(head).replaceAll(" ")))) {
            return Collections.emptyList();
        }
        List<String> names = nameTokens(BRACKET_TAG.matcher(raw.substring(colon + 1)).replaceAll(" "));
        return names.size() > MAX_ROSTER ? names.subList(0, MAX_ROSTER) : names;
    }

    /** Every {@link #NAME} token in a string, in order. */
    private static List<String> nameTokens(String s) {
        List<String> out = new ArrayList<String>();
        Matcher m = NAME.matcher(s);
        while (m.find()) out.add(m.group());
        return out;
    }

    /** UUID for an exact (case-insensitive) tab-list name, or null when the player isn't in your tab. */
    public static UUID uuidInTab(String name) {
        if (name == null) return null;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return null;
        NetHandlerPlayClient net = mc.getNetHandler();
        if (net == null) return null;
        for (NetworkPlayerInfo info : net.getPlayerInfoMap()) {
            if (info == null || info.getGameProfile() == null) continue;
            GameProfile gp = info.getGameProfile();
            if (gp.getId() == null || gp.getName() == null) continue;
            if (gp.getName().equalsIgnoreCase(name)) return gp.getId();
        }
        return null;
    }
}
